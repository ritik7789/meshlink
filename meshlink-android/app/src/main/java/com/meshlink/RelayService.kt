package com.meshlink

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.meshlink.db.AppDatabase
import com.meshlink.db.CustodyEntity
import com.meshlink.db.MessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that owns the mesh: BLE discovery, link management and
 * routing.
 *
 * Routing is TTL-bounded flooding. A node forwards any envelope it has not seen
 * to every neighbour except the one it arrived from, so a message reaches nodes
 * several relays away without anyone maintaining a route table. Membership is
 * learned from periodic presence announcements, which are flooded the same way —
 * that is what makes every node visible to every other node rather than only to
 * its immediate BLE neighbours.
 */
class RelayService : Service(), GattServerListener, GattClientListener {

    companion object {
        const val TAG = "MeshLinkRelay"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "MeshLinkRelayChannel"
        const val MESSAGE_CHANNEL_ID = "MeshLinkMessages"

        val MESHLINK_SERVICE_UUID: ParcelUuid =
            ParcelUuid(UUID.fromString("0000FE22-0000-1000-8000-00805F9B34FB"))

        const val ACTION_ROSTER_UPDATED = "com.meshlink.ACTION_ROSTER_UPDATED"

        /** Tells the UI whether the radio the whole mesh depends on is usable. */
        const val ACTION_BLUETOOTH_STATE = "com.meshlink.ACTION_BLUETOOTH_STATE"
        const val EXTRA_BLUETOOTH_ENABLED = "extra_bluetooth_enabled"
        const val ACTION_MESSAGE_RECEIVED = "com.meshlink.ACTION_MESSAGE_RECEIVED"

        /** Emitted once an outbound message has been persisted, so the chat view
         *  can refresh from the single copy the service wrote. */
        const val ACTION_MESSAGE_SENT = "com.meshlink.ACTION_MESSAGE_SENT"

        const val ACTION_SEND_MESSAGE = "SEND_MESSAGE"
        const val ACTION_BROADCAST_MESSAGE = "BROADCAST_MESSAGE"
        const val ACTION_SYNC_STATE = "SYNC_STATE"
        const val ACTION_ANNOUNCE_PRESENCE = "ANNOUNCE_PRESENCE"

        const val EXTRA_BEACON_ID = "extra_beacon_id"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_MESSAGE_DATA = "extra_message_data"
        const val EXTRA_SENDER_BEACON = "extra_sender_beacon"
        const val EXTRA_IS_BROADCAST = "extra_is_broadcast"

        /** Roster snapshot, sent as parallel arrays so it survives an Intent. */
        const val EXTRA_ROSTER_IDS = "extra_roster_ids"
        const val EXTRA_ROSTER_HOPS = "extra_roster_hops"
        const val EXTRA_ROSTER_NAMES = "extra_roster_names"
        const val EXTRA_LOCAL_ID = "extra_local_id"

        const val PREFS_NAME = "MeshLinkPrefs"
        const val PREF_USERNAME = "username"

        private const val PRESENCE_VERSION = 1

        /** Must be comfortably below [PeerManager.ROSTER_ENTRY_TTL_MS]. */
        private const val PRESENCE_INTERVAL_MS = 20_000L
        private const val SCAN_CYCLE_MS = 15_000L
        private const val MAINTENANCE_INTERVAL_MS = 30_000L

        /** Coalesces the announcement bursts several links handshaking together would cause. */
        private const val PRESENCE_MIN_GAP_MS = 2_000L

        /** Bounds how much undecryptable traffic one sender can make us hold. */
        private const val MAX_DEFERRED_PER_SENDER = 20

        /** How long an unacknowledged message keeps being retried before it is abandoned. */
        private const val MAX_DELIVERY_AGE_MS = 24L * 60 * 60 * 1000

        /** First retry gap; each further attempt doubles it up to [RETRY_MAX_GAP_MS]. */
        private const val RETRY_BASE_GAP_MS = 20_000L
        private const val RETRY_MAX_GAP_MS = 10L * 60 * 1000

        /** Prefixes for the persisted per-node keys. */
        private const val PREF_NODE_KEY_PREFIX = "node_xk_"
        private const val PREF_NODE_IDENTITY_PREFIX = "node_ik_"

        /** How many messages this node will carry for other people at once. */
        private const val MAX_CUSTODY_ENTRIES = 100

        /** How long a carried message is held before it is given up on. */
        private const val CUSTODY_RETENTION_MS = 24L * 60 * 60 * 1000
        private const val DEDUP_MAX_AGE_SECS = 300u
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private var isAdvertising = false
    private var scanCycleCount = 0

    /** Whether discovery and the GATT server are currently up. */
    private var meshRunning = false

    private lateinit var gattServer: GattServer
    private lateinit var gattClient: GattClient
    private lateinit var peerManager: PeerManager
    private lateinit var dedupCache: uniffi.meshlink_core.DedupCache
    private lateinit var staticKeys: uniffi.meshlink_core.StaticKeyPair

    private lateinit var identityKey: uniffi.meshlink_core.IdentityKeyPair
    private var identityPublicKey: ByteArray = ByteArray(0)

    /**
     * Which neighbours have already been handed each undelivered message.
     *
     * When the recipient is unreachable the sender gives a copy to every node it
     * meets, in the hope one of them is still around when the recipient returns.
     * This stops that turning into re-handing the same message to the same
     * neighbour on every tick.
     */
    private val offeredToCarriers = ConcurrentHashMap<String, MutableSet<Int>>()

    /**
     * Messages from a sender whose static key has not arrived yet, held until
     * its presence announcement turns up. Dedup means the sender will not send
     * the same id again, so without this the very first message from a node is
     * lost whenever it outruns that node's gossip.
     */
    private val awaitingSenderKey =
        ConcurrentHashMap<Int, MutableList<uniffi.meshlink_core.MessageEnvelope>>()

    /** Guards against overlapping retries re-sending the same queued rows. */
    private val retryInProgress = AtomicBoolean(false)

    /**
     * Per-message retry schedule, backing off as attempts accumulate.
     *
     * A recipient that never acknowledges — an out-of-date build, or a node that
     * simply cannot answer — would otherwise be re-sent to on every twenty-second
     * tick for a day. Backing off keeps a hopeless delivery from monopolising the
     * radio while still retrying promptly when a peer has just reappeared.
     * In-memory only: a restart retries soon, which is the safe direction to err.
     */
    private val nextRetryAt = ConcurrentHashMap<String, Long>()
    private val retryAttempts = ConcurrentHashMap<String, Int>()
    private var lastPresenceAt = 0L
    private var presenceAnnouncePending = false

    /**
     * This node's mesh address. Derived from the persisted Ed25519 identity, so
     * it is the same after every restart — previously it was randomised in
     * `onCreate`, which silently orphaned conversation history and roster
     * entries each time the service was recreated.
     */
    private var localBeaconId: Int = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIFICATION_ID, createNotification())

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser

        peerManager = PeerManager()
        dedupCache = uniffi.meshlink_core.DedupCache()

        identityKey = KeyManager(this).getIdentityKey()
        identityPublicKey = identityKey.publicKey()
        localBeaconId = uniffi.meshlink_core.beaconIdFromPublicKey(identityPublicKey).toInt()
        staticKeys = uniffi.meshlink_core.StaticKeyPair.fromIdentitySeed(identityKey.toBytes())

        gattServer = GattServer(this, bluetoothManager, this, identityKey)
        gattServer.localBeaconId = localBeaconId

        gattClient = GattClient(this, this, identityKey)
        gattClient.localBeaconId = localBeaconId
        Log.i(TAG, "Local Beacon ID: $localBeaconId")

        restoreKnownNodeKeys()

        // The mesh is entirely dependent on the radio, and the user can toggle it
        // at any moment. Watching for that is what lets the service recover on its
        // own instead of sitting dead until it is restarted.
        ContextCompat.registerReceiver(
            this,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )

        startBleMesh()
        publishBluetoothState()
    }

    /**
     * Brings the mesh up or tears it down as the adapter is switched on and off.
     *
     * Previously `startBleMesh` simply logged and returned when Bluetooth was
     * disabled, and nothing ever retried — so enabling Bluetooth afterwards left
     * the app permanently inert until the service was recreated.
     */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "Bluetooth turned on; starting mesh")
                    // The adapter hands out new scanner/advertiser instances
                    // across a power cycle, so the old ones must not be reused.
                    bleScanner = bluetoothAdapter?.bluetoothLeScanner
                    bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
                    startBleMesh()
                    publishBluetoothState()
                }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    Log.w(TAG, "Bluetooth turned off; tearing down mesh")
                    stopBleMesh()
                    publishBluetoothState()
                }
            }
        }
    }

    /**
     * Reloads the encryption keys of nodes seen in previous runs.
     *
     * Without this, a message composed for a peer that is not on the mesh right
     * now could not be sealed after a restart, and would sit in the queue
     * forever even once that peer returned.
     */
    private fun restoreKnownNodeKeys() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        var restored = 0
        prefs.all.forEach { (key, value) ->
            if (value !is String) return@forEach
            val isStatic = key.startsWith(PREF_NODE_KEY_PREFIX)
            val isIdentity = key.startsWith(PREF_NODE_IDENTITY_PREFIX)
            if (!isStatic && !isIdentity) return@forEach

            val prefix = if (isStatic) PREF_NODE_KEY_PREFIX else PREF_NODE_IDENTITY_PREFIX
            val row = key.removePrefix(prefix).toLongOrNull() ?: return@forEach
            val decoded = runCatching { Base64.decode(value, Base64.NO_WRAP) }.getOrNull() ?: return@forEach

            if (isStatic) {
                peerManager.rememberStaticKey(rowToBeaconId(row), decoded)
                restored++
            } else {
                peerManager.rememberIdentityKey(rowToBeaconId(row), decoded)
            }
        }
        Log.i(TAG, "Restored encryption keys for $restored known node(s)")
    }

    private fun rememberNodeKey(beaconId: Int, staticKey: ByteArray, identityKey: ByteArray?) {
        peerManager.rememberStaticKey(beaconId, staticKey)
        val editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
        editor.putString(
            PREF_NODE_KEY_PREFIX + beaconIdToRow(beaconId),
            Base64.encodeToString(staticKey, Base64.NO_WRAP)
        )
        if (identityKey != null) {
            peerManager.rememberIdentityKey(beaconId, identityKey)
            editor.putString(
                PREF_NODE_IDENTITY_PREFIX + beaconIdToRow(beaconId),
                Base64.encodeToString(identityKey, Base64.NO_WRAP)
            )
        }
        editor.apply()
    }

    private fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    private fun publishBluetoothState() {
        val enabled = isBluetoothEnabled()
        updateNotification(enabled)
        sendBroadcast(
            Intent(ACTION_BLUETOOTH_STATE).apply {
                putExtra(EXTRA_BLUETOOTH_ENABLED, enabled)
                setPackage(packageName)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SEND_MESSAGE -> {
                val beaconId = intent.getIntExtra(EXTRA_BEACON_ID, 0)
                val message = intent.getStringExtra(EXTRA_MESSAGE)
                if (beaconId != 0 && !message.isNullOrBlank()) {
                    sendMessageToNode(beaconId, message)
                }
            }
            ACTION_BROADCAST_MESSAGE -> {
                intent.getStringExtra(EXTRA_MESSAGE)?.takeIf { it.isNotBlank() }?.let {
                    broadcastMessage(it)
                }
            }
            ACTION_SYNC_STATE -> {
                publishRoster()
                publishBluetoothState()
            }
            ACTION_ANNOUNCE_PRESENCE -> announcePresence()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        handler.removeCallbacksAndMessages(null)
        stopAdvertising()
        stopScanning()
        runCatching { gattServer.stop() }
        runCatching { gattClient.disconnectAll() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BLE bring-up
    // ─────────────────────────────────────────────────────────────────────────

    /** Idempotent: safe to call again whenever the radio comes back on. */
    @SuppressLint("MissingPermission")
    private fun startBleMesh() {
        if (!isBluetoothEnabled()) {
            Log.w(TAG, "Bluetooth is disabled; mesh stays down until it is enabled.")
            return
        }
        if (meshRunning) return
        meshRunning = true

        gattServer.start()
        Log.i(TAG, "GATT Server started in startBleMesh()")

        if (bleAdvertiser != null) {
            startAdvertising()
        } else {
            Log.w(TAG, "BLE Advertiser unavailable on this device. Skipping advertising.")
        }
        startScanning()

        handler.postDelayed(scanRestartRunnable, SCAN_CYCLE_MS)
        handler.postDelayed(presenceRunnable, PRESENCE_INTERVAL_MS)
        handler.postDelayed(maintenanceRunnable, MAINTENANCE_INTERVAL_MS)
    }

    /**
     * Releases every radio resource and forgets all mesh state.
     *
     * The roster is cleared rather than left to age out, because with the radio
     * off nothing is reachable and showing stale peers as online would be a lie.
     */
    @SuppressLint("MissingPermission")
    private fun stopBleMesh() {
        if (!meshRunning) return
        meshRunning = false

        handler.removeCallbacks(scanRestartRunnable)
        handler.removeCallbacks(presenceRunnable)
        handler.removeCallbacks(maintenanceRunnable)

        stopScanning()
        stopAdvertising()
        runCatching { gattClient.disconnectAll() }
            .onFailure { Log.d(TAG, "GATT client already released: ${it.message}") }
        runCatching { gattServer.stop() }
            .onFailure { Log.d(TAG, "GATT server already released: ${it.message}") }

        peerManager.reset()
        publishRoster()
    }

    private val scanRestartRunnable = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            if (bluetoothAdapter?.isEnabled != true) {
                handler.postDelayed(this, SCAN_CYCLE_MS)
                return
            }
            // Android deprioritises scanning once GATT links are up, so the scan
            // is cycled to keep rediscovering peers that drift in and out.
            stopScanning()
            handler.postDelayed({
                startScanning()
                handler.postDelayed(this, SCAN_CYCLE_MS)
            }, 1_000)
        }
    }

    /** Re-announces presence and re-tries anything still queued. */
    private val presenceRunnable = object : Runnable {
        override fun run() {
            announcePresence()
            retryUndeliveredMessages()
            handler.postDelayed(this, PRESENCE_INTERVAL_MS)
        }
    }

    private val maintenanceRunnable = object : Runnable {
        override fun run() {
            dedupCache.cleanupExpired(DEDUP_MAX_AGE_SECS)
            peerManager.cleanupExpired()
            CoroutineScope(Dispatchers.IO).launch {
                AppDatabase.getDatabase(this@RelayService).custodyDao()
                    .releaseExpired(System.currentTimeMillis() - CUSTODY_RETENTION_MS)
            }
            // Entries aging out changes who the UI should show as reachable.
            publishRoster()
            handler.postDelayed(this, MAINTENANCE_INTERVAL_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val serviceData = ByteArray(5)
        serviceData[0] = GattServer.PROTOCOL_VERSION
        serviceData[1] = (localBeaconId shr 24).toByte()
        serviceData[2] = (localBeaconId shr 16).toByte()
        serviceData[3] = (localBeaconId shr 8).toByte()
        serviceData[4] = localBeaconId.toByte()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(MESHLINK_SERVICE_UUID)
            .build()

        // Custom data goes in the scan response to stay inside the 31-byte
        // advertisement limit that strict OEM stacks enforce.
        val scanResponseData = AdvertiseData.Builder()
            .addServiceData(MESHLINK_SERVICE_UUID, serviceData)
            .build()

        bleAdvertiser?.startAdvertising(settings, advertiseData, scanResponseData, advertiseCallback)
        isAdvertising = true
        Log.i(TAG, "Started BLE Advertising.")
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        // No hardware filter: some OEM stacks ignore UUID filters while GATT
        // connections are active, so filtering happens in the callback.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner?.startScan(null, settings, scanCallback)
        isScanning = true
        scanCycleCount++
        Log.i(TAG, "Started BLE Scanning (cycle #$scanCycleCount).")
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        if (!isAdvertising) return
        // The adapter may already be off by the time this runs, in which case the
        // stack throws rather than returning an error. Teardown must never throw:
        // the radio is going away regardless, and the bookkeeping below still has
        // to happen.
        runCatching { bleAdvertiser?.stopAdvertising(advertiseCallback) }
            .onFailure { Log.d(TAG, "Advertiser already released: ${it.message}") }
        isAdvertising = false
        Log.d(TAG, "Stopped BLE Advertising.")
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        if (!isScanning) return
        // Same as stopAdvertising: once the adapter reports off, stopScan raises
        // IllegalStateException instead of failing quietly.
        runCatching { bleScanner?.stopScan(scanCallback) }
            .onFailure { Log.d(TAG, "Scanner already released: ${it.message}") }
        isScanning = false
        Log.d(TAG, "Stopped BLE Scanning.")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "Advertise success")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertise failed with error: $errorCode")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            val device = result?.device ?: return

            val serviceUuids = result.scanRecord?.serviceUuids
            if (serviceUuids == null || !serviceUuids.contains(MESHLINK_SERVICE_UUID)) return

            val serviceData = result.scanRecord?.serviceData?.get(MESHLINK_SERVICE_UUID)
            var peerBeaconId = 0
            if (serviceData != null && serviceData.size >= 5) {
                peerBeaconId = ((serviceData[1].toInt() and 0xFF) shl 24) or
                    ((serviceData[2].toInt() and 0xFF) shl 16) or
                    ((serviceData[3].toInt() and 0xFF) shl 8) or
                    (serviceData[4].toInt() and 0xFF)
            }
            if (peerBeaconId == localBeaconId) return

            val peer = peerManager.getPeer(device.address)
            if (peer == null || !peer.isConnected) {
                Log.i(TAG, "Scan found peer ${device.address} beacon=$peerBeaconId, connecting...")
                gattClient.connectToPeer(device)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Transmission
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Delivers one envelope to a single neighbour over whichever GATT direction
     * is available.
     *
     * A BLE link is symmetric but the GATT roles are not, and either side may be
     * the one that managed to connect. Trying the outbound client link first and
     * the server's indication path second means a relay can forward over a link
     * the *peer* established, instead of dropping the message because it has no
     * outbound connection of its own.
     */
    private fun sendToNeighbor(address: String, envelopeBytes: ByteArray): Boolean {
        if (gattClient.canSendTo(address) && gattClient.sendMessage(address, envelopeBytes)) {
            return true
        }
        if (gattServer.sendToDevice(address, envelopeBytes)) {
            return true
        }
        // Neither direction is usable. Opening our own link gives the next
        // attempt a path rather than leaving this neighbour permanently mute.
        ensureOutboundLink(address)
        return false
    }

    @SuppressLint("MissingPermission")
    private fun ensureOutboundLink(address: String) {
        val adapter = bluetoothAdapter ?: return
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return
        }
        gattClient.connectToPeer(device)
    }

    /**
     * Floods an envelope to every neighbouring node except the one it arrived
     * from. Returns how many distinct nodes accepted it.
     *
     * Neighbours are grouped by node id rather than BLE address: Android rotates
     * resolvable private addresses, so one physical peer routinely shows up
     * under two addresses at once (its advertisement and its GATT connection).
     * Sending to each would duplicate every message on the air, and excluding
     * only the arriving address would bounce a relay straight back to its
     * source over that peer's other address.
     */
    private fun floodEnvelope(
        envelope: uniffi.meshlink_core.MessageEnvelope,
        excludeAddress: String?
    ): Int {
        val bytes = uniffi.meshlink_core.serializeEnvelope(envelope)
        val excludedNode = excludeAddress?.let { peerManager.getPeer(it)?.beaconId }

        var delivered = 0
        peerManager.getConnectedPeers()
            .groupBy { it.beaconId }
            .forEach { (beaconId, links) ->
                if (beaconId == excludedNode) return@forEach
                // Try each address this node is reachable at; one success is
                // delivery to that node.
                if (links.any { sendToNeighbor(it.address, bytes) }) delivered++
            }
        return delivered
    }

    /**
     * Announces this node to the whole mesh: its address, its Ed25519 identity
     * key, the static X25519 key others need in order to encrypt for it, and its
     * display name. Flooded like any broadcast, so nodes several hops away learn
     * about each other.
     *
     * Bursts are coalesced: several links completing at once would otherwise
     * each trigger a flood. A call inside the quiet window is deferred rather
     * than dropped, so a neighbour arriving moments after the last announcement
     * still gets one promptly instead of waiting out the full interval.
     */
    private fun announcePresence() {
        val now = System.currentTimeMillis()
        val sinceLast = now - lastPresenceAt
        if (sinceLast < PRESENCE_MIN_GAP_MS) {
            if (!presenceAnnouncePending) {
                presenceAnnouncePending = true
                handler.postDelayed(
                    { presenceAnnouncePending = false; announcePresence() },
                    PRESENCE_MIN_GAP_MS - sinceLast
                )
            }
            return
        }
        lastPresenceAt = now

        val payload = JSONObject().apply {
            put("v", PRESENCE_VERSION)
            put("id", localBeaconId)
            put("ik", Base64.encodeToString(identityPublicKey, Base64.NO_WRAP))
            put("xk", Base64.encodeToString(staticKeys.publicKey(), Base64.NO_WRAP))
            displayNameOfSelf()?.let { put("name", it) }
        }.toString().toByteArray()

        val envelope = signed(
            uniffi.meshlink_core.createEnvelope(
                senderId = localBeaconId.toUInt(),
                recipientId = uniffi.meshlink_core.broadcastRecipient(),
                payload = payload,
                priority = uniffi.meshlink_core.Priority.BROADCAST,
                payloadType = uniffi.meshlink_core.PayloadType.PRESENCE
            )
        )
        rememberOwnMessage(envelope.messageId)
        floodEnvelope(envelope, excludeAddress = null)
    }

    private fun displayNameOfSelf(): String? =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_USERNAME, null)
            ?.takeIf { it.isNotBlank() }

    /**
     * Records an id we originated so a copy that loops back through the mesh is
     * recognised as ours. Without this a broadcast returning over a second path
     * is delivered to its own sender and flooded again.
     */
    private fun rememberOwnMessage(messageId: String) {
        dedupCache.recordMessage(messageId)
    }

    /**
     * Signs an envelope this node originated.
     *
     * Applied at creation rather than at send time, because relaying must pass a
     * message on untouched — re-signing someone else's traffic would destroy the
     * very attribution that makes carrying it safe.
     */
    private fun signed(
        envelope: uniffi.meshlink_core.MessageEnvelope
    ): uniffi.meshlink_core.MessageEnvelope =
        uniffi.meshlink_core.signEnvelope(envelope, identityKey)

    /**
     * Rejects traffic that claims to be from a node whose key we hold but whose
     * signature does not match. A sender we have never heard of is let through:
     * its presence announcement carries the key that will authenticate it from
     * then on, and refusing first contact outright would make the mesh unjoinable.
     */
    private fun isAuthentic(envelope: uniffi.meshlink_core.MessageEnvelope): Boolean {
        if (envelope.payloadType == uniffi.meshlink_core.PayloadType.PRESENCE) {
            // Verified in handlePresence against the key carried in its own payload.
            return true
        }
        val senderKey = peerManager.identityKeyFor(envelope.senderId.toInt()) ?: return true
        return uniffi.meshlink_core.verifyEnvelope(envelope, senderKey)
    }

    /**
     * Sends a direct message to any node in the mesh, neighbour or not.
     *
     * The payload is sealed for the recipient's static key, so relays along the
     * way forward ciphertext they cannot read. If that key is not known yet the
     * message is stored as pending and retried once presence gossip supplies it.
     */
    fun sendMessageToNode(beaconId: Int, message: String) {
        val recipientKey = peerManager.staticKeyFor(beaconId)
        val messageId = UUID.randomUUID().toString()

        val envelope = if (recipientKey != null) {
            buildSealedEnvelope(messageId, beaconId, recipientKey, message)
        } else {
            null
        }

        val delivered = envelope?.let {
            rememberOwnMessage(it.messageId)
            floodEnvelope(it, excludeAddress = null) > 0
        } ?: false

        if (!delivered) {
            Log.d(TAG, "No path to node $beaconId yet; message queued for relay.")
        }

        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.getDatabase(this@RelayService).messageDao().insertMessage(
                MessageEntity(
                    messageId = messageId,
                    senderId = beaconIdToRow(localBeaconId),
                    recipientId = beaconIdToRow(beaconId),
                    plaintext = message,
                    envelopeData = envelope?.let { uniffi.meshlink_core.serializeEnvelope(it) } ?: ByteArray(0),
                    timestamp = System.currentTimeMillis(),
                    direction = "OUTBOUND",
                    status = if (delivered) "SENT" else "PENDING_RELAY",
                    isBroadcast = false
                )
            )
            notifyMessageStored(beaconId)
        }
    }

    private fun buildSealedEnvelope(
        messageId: String,
        recipientBeaconId: Int,
        recipientStaticKey: ByteArray,
        message: String
    ): uniffi.meshlink_core.MessageEnvelope? {
        val sealed = staticKeys.seal(recipientStaticKey, message.toByteArray())
        if (sealed.isEmpty()) {
            Log.e(TAG, "Failed to seal message for node $recipientBeaconId")
            return null
        }
        return signed(
            uniffi.meshlink_core.createEnvelopeWithId(
                messageId = messageId,
                senderId = localBeaconId.toUInt(),
                recipientId = recipientBeaconId.toUInt(),
                payload = sealed,
                priority = uniffi.meshlink_core.Priority.DIRECT,
                payloadType = uniffi.meshlink_core.PayloadType.TEXT
            )
        )
    }

    /**
     * Broadcasts to the whole mesh. Broadcast has no single recipient and so no
     * key to seal against: the payload travels as plaintext inside the envelope
     * and is protected only hop by hop.
     */
    fun broadcastMessage(message: String) {
        val envelope = signed(
            uniffi.meshlink_core.createEnvelope(
                senderId = localBeaconId.toUInt(),
                recipientId = uniffi.meshlink_core.broadcastRecipient(),
                payload = message.toByteArray(),
                priority = uniffi.meshlink_core.Priority.BROADCAST,
                payloadType = uniffi.meshlink_core.PayloadType.TEXT
            )
        )
        rememberOwnMessage(envelope.messageId)
        val delivered = floodEnvelope(envelope, excludeAddress = null) > 0

        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.getDatabase(this@RelayService).messageDao().insertMessage(
                MessageEntity(
                    messageId = envelope.messageId,
                    senderId = beaconIdToRow(localBeaconId),
                    recipientId = 0L,
                    plaintext = message,
                    envelopeData = uniffi.meshlink_core.serializeEnvelope(envelope),
                    timestamp = System.currentTimeMillis(),
                    direction = "OUTBOUND",
                    status = if (delivered) "SENT" else "PENDING_RELAY",
                    isBroadcast = true
                )
            )
            notifyMessageStored(0)
        }
    }

    private fun notifyMessageStored(peerBeaconId: Int) {
        val intent = Intent(ACTION_MESSAGE_SENT).apply {
            putExtra(EXTRA_BEACON_ID, peerBeaconId)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    /**
     * Re-sends every message the recipient has not acknowledged yet.
     *
     * Handing a message to a neighbour is not delivery: with flooding, that
     * neighbour may have no path to the recipient at all, which is why a message
     * sent while the recipient was offline used to be marked sent and then
     * silently lost. A message is only finished once its recipient says so, so
     * this runs on every presence tick and whenever a node reappears.
     */
    private fun retryUndeliveredMessages() {
        if (peerManager.getConnectedPeers().isEmpty()) return
        if (!retryInProgress.compareAndSet(false, true)) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getDatabase(this@RelayService).messageDao()
                val now = System.currentTimeMillis()

                dao.getUnacknowledgedMessages().forEach { pending ->
                    if (now - pending.timestamp > MAX_DELIVERY_AGE_MS) return@forEach

                    val recipient = rowToBeaconId(pending.recipientId)
                    if (!peerManager.isReachable(recipient)) {
                        // The recipient is away. Leave a copy with each node we
                        // meet so that one of them can deliver it later, even if
                        // this device is gone by the time the recipient returns.
                        handToCarriers(pending, recipient)
                        return@forEach
                    }
                    if (now < (nextRetryAt[pending.messageId] ?: 0L)) return@forEach

                    val envelope = envelopeFor(pending, recipient) ?: return@forEach
                    rememberOwnMessage(envelope.messageId)
                    scheduleNextRetry(pending.messageId, now)
                    if (floodEnvelope(envelope, excludeAddress = null) > 0 &&
                        pending.status == "PENDING_RELAY"
                    ) {
                        // Handed to the mesh, but not yet acknowledged: the status
                        // only reaches DELIVERED when the recipient says so.
                        dao.updateStatus(pending.messageId, "SENT")
                        notifyMessageStored(recipient)
                    }
                    Log.d(TAG, "Re-sent unacknowledged message ${pending.messageId} to $recipient")
                }

                // Broadcasts have no single recipient to acknowledge them, so one
                // successful hand-off to the mesh is all they can be held to.
                dao.getPendingMessages().filter { it.isBroadcast }.forEach { pending ->
                    val envelope = signed(
                        uniffi.meshlink_core.createEnvelopeWithId(
                            messageId = pending.messageId,
                            senderId = localBeaconId.toUInt(),
                            recipientId = uniffi.meshlink_core.broadcastRecipient(),
                            payload = pending.plaintext.toByteArray(),
                            priority = uniffi.meshlink_core.Priority.BROADCAST,
                            payloadType = uniffi.meshlink_core.PayloadType.TEXT
                        )
                    )
                    rememberOwnMessage(envelope.messageId)
                    if (floodEnvelope(envelope, excludeAddress = null) > 0) {
                        dao.updateStatus(pending.messageId, "SENT")
                        notifyMessageStored(0)
                    }
                }
            } finally {
                retryInProgress.set(false)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Custody: carrying messages for nodes that are not here right now
    //
    // Flooding only works while sender and recipient are on the mesh together.
    // When a relay holds on to a message it could not deliver, the two never have
    // to be present at the same moment: the relay carries it across the gap.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Decides whether to carry a message this node has just relayed.
     *
     * Deliberately selective. A message is only worth storing when it is
     * addressed to a specific node this device has actually heard of, that node
     * is not reachable now, and the sender signed it — otherwise anyone in radio
     * range could fill this store with traffic attributed to someone else.
     */
    private fun considerCustody(envelope: uniffi.meshlink_core.MessageEnvelope) {
        val recipient = envelope.recipientId.toInt()
        if (recipient == uniffi.meshlink_core.broadcastRecipient().toInt()) return
        if (recipient == localBeaconId) return
        if (envelope.payloadType == uniffi.meshlink_core.PayloadType.ACK ||
            envelope.payloadType == uniffi.meshlink_core.PayloadType.PRESENCE
        ) return
        if (!peerManager.isKnown(recipient)) return
        if (peerManager.isReachable(recipient)) return

        val senderKey = peerManager.identityKeyFor(envelope.senderId.toInt()) ?: return
        if (!uniffi.meshlink_core.verifyEnvelope(envelope, senderKey)) return

        val bytes = uniffi.meshlink_core.serializeEnvelope(envelope)
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.getDatabase(this@RelayService).custodyDao()
            dao.insert(
                CustodyEntity(
                    messageId = envelope.messageId,
                    recipientId = beaconIdToRow(recipient),
                    envelopeData = bytes,
                    receivedAt = System.currentTimeMillis(),
                    isSos = envelope.priority == uniffi.meshlink_core.Priority.SOS
                )
            )
            val excess = dao.count() - MAX_CUSTODY_ENTRIES
            if (excess > 0) dao.evictLeastValuable(excess)
            Log.i(TAG, "Carrying ${envelope.messageId} for $recipient until it returns")
        }
    }

    /** Stops carrying a message once its recipient has acknowledged it. */
    private fun releaseCustody(messageId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.getDatabase(this@RelayService).custodyDao()
            if (dao.contains(messageId)) {
                dao.release(messageId)
                Log.i(TAG, "Released custody of $messageId; it has been delivered")
            }
        }
    }

    /** Hands over everything being carried for a node that has just reappeared. */
    private fun offerCustodyTo(beaconId: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            val carried = AppDatabase.getDatabase(this@RelayService).custodyDao()
                .forRecipient(beaconIdToRow(beaconId))
            if (carried.isEmpty()) return@launch

            carried.forEach { entry ->
                val envelope = runCatching {
                    uniffi.meshlink_core.deserializeEnvelope(entry.envelopeData)
                }.getOrNull() ?: return@forEach
                floodEnvelope(envelope, excludeAddress = null)
            }
            Log.i(TAG, "Offered ${carried.size} carried message(s) to $beaconId")
        }
    }

    /**
     * Gives an undelivered message to any neighbour that has not already been
     * offered it, so those neighbours can carry it to a recipient this device may
     * never be present for.
     *
     * Offered once per neighbour rather than on every tick: the point is to seed
     * copies across the nodes we meet, not to keep re-sending to the same ones.
     */
    private fun handToCarriers(pending: MessageEntity, recipient: Int) {
        val neighbours = peerManager.getConnectedPeers().map { it.beaconId }.distinct()
        if (neighbours.isEmpty()) return

        val alreadyOffered = offeredToCarriers.getOrPut(pending.messageId) {
            ConcurrentHashMap.newKeySet()
        }
        val fresh = neighbours.filter { alreadyOffered.add(it) }
        if (fresh.isEmpty()) return

        val envelope = envelopeFor(pending, recipient) ?: return
        rememberOwnMessage(envelope.messageId)
        floodEnvelope(envelope, excludeAddress = null)
        Log.d(TAG, "Handed ${pending.messageId} to ${fresh.size} new carrier(s) for $recipient")
    }

    /** Backs the next attempt off exponentially, capped at [RETRY_MAX_GAP_MS]. */
    private fun scheduleNextRetry(messageId: String, now: Long) {
        val attempt = (retryAttempts[messageId] ?: 0) + 1
        retryAttempts[messageId] = attempt
        val gap = (RETRY_BASE_GAP_MS shl minOf(attempt - 1, 5)).coerceAtMost(RETRY_MAX_GAP_MS)
        nextRetryAt[messageId] = now + gap
    }

    /**
     * Rebuilds the envelope for a queued message, reusing the stored copy when
     * there is one and sealing it now when the recipient's key only became known
     * after it was composed.
     */
    private fun envelopeFor(
        pending: MessageEntity,
        recipient: Int
    ): uniffi.meshlink_core.MessageEnvelope? {
        if (pending.envelopeData.isNotEmpty()) {
            return runCatching {
                uniffi.meshlink_core.deserializeEnvelope(pending.envelopeData)
            }.getOrNull()
        }
        val key = peerManager.staticKeyFor(recipient) ?: return null
        return buildSealedEnvelope(pending.messageId, recipient, key, pending.plaintext)
    }

    /**
     * Tells [toBeaconId] that one of its messages arrived.
     *
     * Sealing the acknowledgement against the sender's key means a relay cannot
     * forge one, so a message is only ever marked delivered on the word of the
     * node that actually received it.
     */
    private fun acknowledge(toBeaconId: Int, acknowledgedMessageId: String) {
        val key = peerManager.staticKeyFor(toBeaconId) ?: return
        val sealed = staticKeys.seal(key, acknowledgedMessageId.toByteArray())
        if (sealed.isEmpty()) return

        // The acknowledged id travels in the clear so relays carrying that
        // message learn it was delivered and can stop carrying it.
        val envelope = signed(
            uniffi.meshlink_core.createAckEnvelope(
                senderId = localBeaconId.toUInt(),
                recipientId = toBeaconId.toUInt(),
                payload = sealed,
                ackFor = acknowledgedMessageId
            )
        )
        rememberOwnMessage(envelope.messageId)
        floodEnvelope(envelope, excludeAddress = null)
    }

    /** Marks one of our messages delivered on the recipient's acknowledgement. */
    private fun handleAcknowledgement(envelope: uniffi.meshlink_core.MessageEnvelope) {
        val acknowledgedId = openPayload(envelope) ?: return
        val senderId = envelope.senderId.toInt()
        // Settled: stop tracking it so the schedule cannot grow without bound.
        nextRetryAt.remove(acknowledgedId)
        retryAttempts.remove(acknowledgedId)
        offeredToCarriers.remove(acknowledgedId)
        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.getDatabase(this@RelayService).messageDao()
                .updateStatus(acknowledgedId, "DELIVERED")
            notifyMessageStored(senderId)
            Log.i(TAG, "Message $acknowledgedId acknowledged by $senderId")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reception
    // ─────────────────────────────────────────────────────────────────────────

    override fun onMessageReceived(device: BluetoothDevice, data: ByteArray) {
        val envelope = try {
            uniffi.meshlink_core.deserializeEnvelope(data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize envelope from ${device.address}: ${e.message}")
            return
        }

        if (!isAuthentic(envelope)) {
            Log.w(TAG, "Rejected envelope with a bad signature from ${envelope.senderId}")
            return
        }

        // Snoop acknowledgements in passing, whatever else happens to this
        // envelope: if we are carrying the message it settles, we can stop.
        envelope.ackFor?.let { releaseCustody(it) }

        val action = uniffi.meshlink_core.processIncoming(envelope, localBeaconId.toUInt(), dedupCache)
        if (action == uniffi.meshlink_core.ProcessAction.DROP) {
            Log.d(TAG, "Dropped message from ${device.address}")
            return
        }
        if (action == uniffi.meshlink_core.ProcessAction.ACKNOWLEDGE_ONLY) {
            // Already have it; the sender is retrying because our acknowledgement
            // never made it back. Answer again instead of leaving it stuck.
            Log.d(TAG, "Re-acknowledging ${envelope.messageId}")
            acknowledge(envelope.senderId.toInt(), envelope.messageId)
            return
        }

        val isForUs = action == uniffi.meshlink_core.ProcessAction.DELIVER_LOCAL ||
            action == uniffi.meshlink_core.ProcessAction.DELIVER_AND_RELAY
        val shouldRelay = action == uniffi.meshlink_core.ProcessAction.RELAY ||
            action == uniffi.meshlink_core.ProcessAction.DELIVER_AND_RELAY

        if (isForUs) {
            deliverLocally(envelope)
        }

        if (shouldRelay) {
            // TTL is decremented only now, immediately before forwarding, so the
            // hop count a receiver derives from it stays accurate.
            val forwarded = uniffi.meshlink_core.decrementTtl(envelope)
            if (forwarded != null) {
                val count = floodEnvelope(forwarded, excludeAddress = device.address)
                Log.i(TAG, "Relayed ${envelope.messageId} from ${device.address} to $count neighbour(s)")
                considerCustody(forwarded)
            }
        }
    }

    private fun deliverLocally(envelope: uniffi.meshlink_core.MessageEnvelope) {
        if (envelope.payloadType == uniffi.meshlink_core.PayloadType.PRESENCE) {
            handlePresence(envelope)
            return
        }
        if (envelope.payloadType == uniffi.meshlink_core.PayloadType.ACK) {
            handleAcknowledgement(envelope)
            return
        }

        val senderId = envelope.senderId.toInt()
        val isBroadcast = envelope.priority == uniffi.meshlink_core.Priority.BROADCAST

        if (!isBroadcast && peerManager.staticKeyFor(senderId) == null) {
            deferUntilSenderKnown(senderId, envelope)
            return
        }

        val plaintext = openPayload(envelope) ?: return

        Log.i(TAG, "Delivering message from $senderId (${uniffi.meshlink_core.envelopeHops(envelope)} hop(s))")

        showMessageNotification(senderId, plaintext, isBroadcast)

        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.getDatabase(this@RelayService).messageDao().insertMessage(
                MessageEntity(
                    messageId = envelope.messageId,
                    senderId = beaconIdToRow(senderId),
                    recipientId = if (isBroadcast) 0L else beaconIdToRow(localBeaconId),
                    plaintext = plaintext,
                    envelopeData = uniffi.meshlink_core.serializeEnvelope(envelope),
                    timestamp = System.currentTimeMillis(),
                    direction = "INBOUND",
                    status = "RECEIVED",
                    isBroadcast = isBroadcast
                )
            )

            // Announce only once the row is committed. The activities respond by
            // re-querying the database, so notifying them from the calling thread
            // while the insert was still in flight let them read the table just
            // before the new row landed: the message silently failed to appear
            // until some later event triggered another refresh.
            val intent = Intent(ACTION_MESSAGE_RECEIVED).apply {
                putExtra(EXTRA_MESSAGE_DATA, plaintext)
                putExtra(EXTRA_SENDER_BEACON, senderId)
                putExtra(EXTRA_IS_BROADCAST, isBroadcast)
                setPackage(packageName)
            }
            sendBroadcast(intent)

            // Acknowledge only once the message is safely stored, so a delivery
            // receipt never outruns the copy it is vouching for.
            if (!isBroadcast) acknowledge(senderId, envelope.messageId)
        }
    }

    /**
     * Recovers the message text. Broadcasts carry plaintext because they have no
     * single recipient to seal against; direct messages are opened with the
     * sender's static key, which also proves the sender is who it claims to be.
     */
    private fun openPayload(envelope: uniffi.meshlink_core.MessageEnvelope): String? {
        if (envelope.priority == uniffi.meshlink_core.Priority.BROADCAST) {
            return String(envelope.encryptedPayload)
        }
        val senderId = envelope.senderId.toInt()
        val senderKey = peerManager.staticKeyFor(senderId) ?: return null
        val opened = staticKeys.open(senderKey, envelope.encryptedPayload)
        if (opened == null) {
            Log.w(TAG, "Message from $senderId failed to open; discarding")
            return null
        }
        return String(opened)
    }

    private fun deferUntilSenderKnown(
        senderId: Int,
        envelope: uniffi.meshlink_core.MessageEnvelope
    ) {
        val held = awaitingSenderKey.getOrPut(senderId) { mutableListOf() }
        synchronized(held) {
            if (held.size >= MAX_DEFERRED_PER_SENDER) held.removeAt(0)
            held.add(envelope)
        }
        Log.d(TAG, "Holding message from $senderId until its presence announcement arrives")
    }

    /** Re-runs delivery for messages that were waiting on this sender's key. */
    private fun drainDeferred(senderId: Int) {
        val held = awaitingSenderKey.remove(senderId) ?: return
        val snapshot = synchronized(held) { held.toList() }
        snapshot.forEach { deliverLocally(it) }
    }

    /**
     * Records a node learned from gossip. The hop count comes from how much TTL
     * the announcement has left, so the roster shows how far away each node is.
     */
    private fun handlePresence(envelope: uniffi.meshlink_core.MessageEnvelope) {
        val json = try {
            JSONObject(String(envelope.encryptedPayload))
        } catch (e: Exception) {
            Log.w(TAG, "Malformed presence payload: ${e.message}")
            return
        }
        if (json.optInt("v") != PRESENCE_VERSION) return

        val beaconId = json.optInt("id")
        if (beaconId == 0 || beaconId == localBeaconId) return

        val identityKey = json.optString("ik").takeIf { it.isNotEmpty() }
            ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
        val staticKey = json.optString("xk").takeIf { it.isNotEmpty() }
            ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }

        // The announcement is unsigned, so treat the claimed id as authoritative
        // only when it actually matches the identity key it ships with.
        if (identityKey != null) {
            val derived = uniffi.meshlink_core.beaconIdFromPublicKey(identityKey).toInt()
            if (derived != beaconId) {
                Log.w(TAG, "Presence for $beaconId does not match its identity key; ignoring")
                return
            }
            // Anyone can echo a public key; only its owner can sign with it. This
            // is what stops a node inventing peers to make others carry traffic.
            if (!uniffi.meshlink_core.verifyEnvelope(envelope, identityKey)) {
                Log.w(TAG, "Presence for $beaconId is not signed by its identity key; ignoring")
                return
            }
        }

        val name = json.optString("name").takeIf { it.isNotBlank() }
        val hops = uniffi.meshlink_core.envelopeHops(envelope).toInt()

        peerManager.recordNode(beaconId, identityKey, staticKey, name, hops)
        if (staticKey != null) rememberNodeKey(beaconId, staticKey, identityKey)
        if (name != null) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(peerNameKey(beaconId), name).apply()
        }
        Log.d(TAG, "Presence: node $beaconId at $hops hop(s)${name?.let { " ($it)" } ?: ""}")

        publishRoster()
        // This node's key may be exactly what queued or held messages needed.
        drainDeferred(beaconId)
        offerCustodyTo(beaconId)
        retryUndeliveredMessages()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Link lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onPeerConnected(device: BluetoothDevice) {
        // Nothing to record until the handshake reveals the peer's beacon id.
    }

    override fun onPeerDisconnected(device: BluetoothDevice) {
        peerManager.removePeer(device.address)
        publishRoster()
    }

    override fun onBeaconIdReceived(device: BluetoothDevice, beaconId: Int, sharedSecret: ByteArray) {
        onNeighborReady(device, beaconId, sharedSecret, "server")
    }

    override fun onPeerHandshakeComplete(device: BluetoothDevice, remoteBeaconId: Int, sharedSecret: ByteArray) {
        onNeighborReady(device, remoteBeaconId, sharedSecret, "client")
    }

    /**
     * A link became usable.
     *
     * The peer is registered unconditionally. Previously this bookkeeping sat
     * behind a five-minute handshake throttle, so a neighbour that reconnected
     * inside that window was never marked connected again — it disappeared from
     * the UI and stopped being used as a relay until the throttle expired.
     */
    private fun onNeighborReady(
        device: BluetoothDevice,
        beaconId: Int,
        sharedSecret: ByteArray,
        side: String
    ) {
        peerManager.addPeer(device.address, beaconId, sharedSecret)
        peerManager.recordHandshake(beaconId)
        Log.i(TAG, "Handshake complete ($side) with ${device.address}, beacon $beaconId")

        publishRoster()
        // Introduce ourselves immediately so the new neighbour — and everything
        // behind it — learns our keys without waiting for the next tick.
        announcePresence()
        offerCustodyTo(beaconId)
        retryUndeliveredMessages()
    }

    override fun onHandshakeFailed(device: BluetoothDevice, reason: String) {
        Log.w(TAG, "Handshake failed with ${device.address}: $reason")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI notifications
    // ─────────────────────────────────────────────────────────────────────────

    /** Pushes the current reachable set to any listening activity. */
    private fun publishRoster() {
        val nodes = peerManager.getReachableNodes()
        val intent = Intent(ACTION_ROSTER_UPDATED).apply {
            putExtra(EXTRA_LOCAL_ID, localBeaconId)
            putExtra(EXTRA_ROSTER_IDS, nodes.map { it.beaconId }.toIntArray())
            putExtra(EXTRA_ROSTER_HOPS, nodes.map { it.hops }.toIntArray())
            putExtra(EXTRA_ROSTER_NAMES, nodes.map { displayNameFor(it.beaconId) }.toTypedArray())
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun displayNameFor(beaconId: Int): String {
        val stored = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(peerNameKey(beaconId), null)
        return stored?.takeIf { it.isNotBlank() } ?: defaultNodeName(beaconId)
    }

    private fun createNotificationChannels() {
        val relayChannel = NotificationChannel(
            CHANNEL_ID, "MeshLink Background Relay", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Maintains the off-grid mesh network" }

        val messageChannel = NotificationChannel(
            MESSAGE_CHANNEL_ID, "MeshLink Messages", NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Notifications for incoming messages" }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(relayChannel)
        manager.createNotificationChannel(messageChannel)
    }

    private fun createNotification(bluetoothEnabled: Boolean = true): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(if (bluetoothEnabled) "MeshLink Active" else "MeshLink Paused")
            .setContentText(
                if (bluetoothEnabled) "Relaying messages for the mesh network"
                else "Bluetooth is off — turn it on to rejoin the mesh"
            )
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

    private fun updateNotification(bluetoothEnabled: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, createNotification(bluetoothEnabled))
    }

    private fun showMessageNotification(senderId: Int, message: String, isBroadcast: Boolean) {
        val senderName = displayNameFor(senderId)
        val title = if (isBroadcast) "Broadcast from $senderName" else "Message from $senderName"

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, MESSAGE_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        getSystemService(NotificationManager::class.java).notify(senderId, notification)
    }
}
