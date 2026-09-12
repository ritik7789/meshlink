package com.meshlink

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
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
import com.meshlink.db.AppDatabase
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
        private const val DEDUP_MAX_AGE_SECS = 300u
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private var scanCycleCount = 0

    private lateinit var gattServer: GattServer
    private lateinit var gattClient: GattClient
    private lateinit var peerManager: PeerManager
    private lateinit var dedupCache: uniffi.meshlink_core.DedupCache
    private lateinit var staticKeys: uniffi.meshlink_core.StaticKeyPair

    private var identityPublicKey: ByteArray = ByteArray(0)

    /**
     * Messages from a sender whose static key has not arrived yet, held until
     * its presence announcement turns up. Dedup means the sender will not send
     * the same id again, so without this the very first message from a node is
     * lost whenever it outruns that node's gossip.
     */
    private val awaitingSenderKey =
        ConcurrentHashMap<Int, MutableList<uniffi.meshlink_core.MessageEnvelope>>()

    /** Guards against overlapping flushes re-sending the same queued rows. */
    private val flushInProgress = AtomicBoolean(false)
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

        val identityKey = KeyManager(this).getIdentityKey()
        identityPublicKey = identityKey.publicKey()
        localBeaconId = uniffi.meshlink_core.beaconIdFromPublicKey(identityPublicKey).toInt()
        staticKeys = uniffi.meshlink_core.StaticKeyPair.fromIdentitySeed(identityKey.toBytes())

        gattServer = GattServer(this, bluetoothManager, this, identityKey)
        gattServer.localBeaconId = localBeaconId

        gattClient = GattClient(this, this, identityKey)
        gattClient.localBeaconId = localBeaconId
        Log.i(TAG, "Local Beacon ID: $localBeaconId")

        startBleMesh()
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
            ACTION_SYNC_STATE -> publishRoster()
            ACTION_ANNOUNCE_PRESENCE -> announcePresence()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        bleAdvertiser?.stopAdvertising(advertiseCallback)
        stopScanning()
        gattServer.stop()
        gattClient.disconnectAll()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BLE bring-up
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startBleMesh() {
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is disabled.")
            return
        }
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
            flushPendingMessages()
            handler.postDelayed(this, PRESENCE_INTERVAL_MS)
        }
    }

    private val maintenanceRunnable = object : Runnable {
        override fun run() {
            dedupCache.cleanupExpired(DEDUP_MAX_AGE_SECS)
            peerManager.cleanupExpired()
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
    private fun stopScanning() {
        if (isScanning) {
            bleScanner?.stopScan(scanCallback)
            isScanning = false
            Log.d(TAG, "Stopped BLE Scanning for restart.")
        }
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
     * Floods an envelope to every connected neighbour except [excludeAddress],
     * which is the link it arrived on. Returns how many neighbours accepted it.
     */
    private fun floodEnvelope(
        envelope: uniffi.meshlink_core.MessageEnvelope,
        excludeAddress: String?
    ): Int {
        val bytes = uniffi.meshlink_core.serializeEnvelope(envelope)
        var delivered = 0
        peerManager.getConnectedPeers().forEach { peer ->
            if (peer.address == excludeAddress) return@forEach
            if (sendToNeighbor(peer.address, bytes)) delivered++
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

        val envelope = uniffi.meshlink_core.createEnvelope(
            senderId = localBeaconId.toUInt(),
            recipientId = uniffi.meshlink_core.broadcastRecipient(),
            payload = payload,
            priority = uniffi.meshlink_core.Priority.BROADCAST,
            payloadType = uniffi.meshlink_core.PayloadType.PRESENCE
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
     * Sends a direct message to any node in the mesh, neighbour or not.
     *
     * The payload is sealed for the recipient's static key, so relays along the
     * way forward ciphertext they cannot read. If that key is not known yet the
     * message is stored as pending and retried once presence gossip supplies it.
     */
    fun sendMessageToNode(beaconId: Int, message: String) {
        val node = peerManager.getNode(beaconId)
        val recipientKey = node?.staticKey
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
        return uniffi.meshlink_core.createEnvelopeWithId(
            messageId = messageId,
            senderId = localBeaconId.toUInt(),
            recipientId = recipientBeaconId.toUInt(),
            payload = sealed,
            priority = uniffi.meshlink_core.Priority.DIRECT,
            payloadType = uniffi.meshlink_core.PayloadType.TEXT
        )
    }

    /**
     * Broadcasts to the whole mesh. Broadcast has no single recipient and so no
     * key to seal against: the payload travels as plaintext inside the envelope
     * and is protected only hop by hop.
     */
    fun broadcastMessage(message: String) {
        val envelope = uniffi.meshlink_core.createEnvelope(
            senderId = localBeaconId.toUInt(),
            recipientId = uniffi.meshlink_core.broadcastRecipient(),
            payload = message.toByteArray(),
            priority = uniffi.meshlink_core.Priority.BROADCAST,
            payloadType = uniffi.meshlink_core.PayloadType.TEXT
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
     * Re-attempts anything that had no path when it was composed. A message
     * queued before its recipient's key was known is sealed now, reusing its
     * original id so the stored row and the delivered copy stay the same message.
     */
    private fun flushPendingMessages() {
        if (peerManager.getConnectedPeers().isEmpty()) return
        if (!flushInProgress.compareAndSet(false, true)) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
            val dao = AppDatabase.getDatabase(this@RelayService).messageDao()
            dao.getPendingMessages().forEach { pending ->
                val envelope = if (pending.envelopeData.isNotEmpty()) {
                    runCatching { uniffi.meshlink_core.deserializeEnvelope(pending.envelopeData) }.getOrNull()
                } else if (pending.isBroadcast) {
                    uniffi.meshlink_core.createEnvelopeWithId(
                        messageId = pending.messageId,
                        senderId = localBeaconId.toUInt(),
                        recipientId = uniffi.meshlink_core.broadcastRecipient(),
                        payload = pending.plaintext.toByteArray(),
                        priority = uniffi.meshlink_core.Priority.BROADCAST,
                        payloadType = uniffi.meshlink_core.PayloadType.TEXT
                    )
                } else {
                    val recipient = rowToBeaconId(pending.recipientId)
                    peerManager.getNode(recipient)?.staticKey?.let { key ->
                        buildSealedEnvelope(pending.messageId, recipient, key, pending.plaintext)
                    }
                }

                if (envelope == null) return@forEach
                rememberOwnMessage(envelope.messageId)
                if (floodEnvelope(envelope, excludeAddress = null) > 0) {
                    dao.updateStatus(pending.messageId, "SENT")
                    Log.d(TAG, "Flushed pending message ${pending.messageId}")
                }
            }
            } finally {
                flushInProgress.set(false)
            }
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

        val action = uniffi.meshlink_core.processIncoming(envelope, localBeaconId.toUInt(), dedupCache)
        if (action == uniffi.meshlink_core.ProcessAction.DROP) {
            Log.d(TAG, "Dropped message from ${device.address}")
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
            }
        }
    }

    private fun deliverLocally(envelope: uniffi.meshlink_core.MessageEnvelope) {
        if (envelope.payloadType == uniffi.meshlink_core.PayloadType.PRESENCE) {
            handlePresence(envelope)
            return
        }

        val senderId = envelope.senderId.toInt()
        val isBroadcast = envelope.priority == uniffi.meshlink_core.Priority.BROADCAST

        if (!isBroadcast && peerManager.getNode(senderId)?.staticKey == null) {
            deferUntilSenderKnown(senderId, envelope)
            return
        }

        val plaintext = openPayload(envelope) ?: return

        Log.i(TAG, "Delivering message from $senderId (${uniffi.meshlink_core.envelopeHops(envelope)} hop(s))")

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
        }

        showMessageNotification(senderId, plaintext, isBroadcast)

        val intent = Intent(ACTION_MESSAGE_RECEIVED).apply {
            putExtra(EXTRA_MESSAGE_DATA, plaintext)
            putExtra(EXTRA_SENDER_BEACON, senderId)
            putExtra(EXTRA_IS_BROADCAST, isBroadcast)
            setPackage(packageName)
        }
        sendBroadcast(intent)
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
        val senderKey = peerManager.getNode(senderId)?.staticKey ?: return null
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
        }

        val name = json.optString("name").takeIf { it.isNotBlank() }
        val hops = uniffi.meshlink_core.envelopeHops(envelope).toInt()

        peerManager.recordNode(beaconId, identityKey, staticKey, name, hops)
        if (name != null) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString("peer_name_$beaconId", name).apply()
        }
        Log.d(TAG, "Presence: node $beaconId at $hops hop(s)${name?.let { " ($it)" } ?: ""}")

        publishRoster()
        // This node's key may be exactly what queued or held messages needed.
        drainDeferred(beaconId)
        flushPendingMessages()
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
        flushPendingMessages()
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
            .getString("peer_name_$beaconId", null)
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

    private fun createNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MeshLink Active")
            .setContentText("Relaying messages for the mesh network")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

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
