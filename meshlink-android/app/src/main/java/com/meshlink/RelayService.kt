package com.meshlink

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import uniffi.meshlink_core.createTestEnvelope
import uniffi.meshlink_core.MessageEnvelope
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RelayService : Service(), GattServerListener, GattClientListener {

    companion object {
        const val TAG = "MeshLinkRelay"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "MeshLinkRelayChannel"

        val MESHLINK_SERVICE_UUID = ParcelUuid(UUID.fromString("0000FE22-0000-1000-8000-00805F9B34FB"))
        
        const val ACTION_PEER_CONNECTED = "com.meshlink.ACTION_PEER_CONNECTED"
        const val ACTION_PEER_DISCONNECTED = "com.meshlink.ACTION_PEER_DISCONNECTED"
        const val ACTION_MESSAGE_RECEIVED = "com.meshlink.ACTION_MESSAGE_RECEIVED"
        
        const val EXTRA_PEER_ADDRESS = "extra_peer_address"
        const val EXTRA_BEACON_ID = "extra_beacon_id"
        const val EXTRA_MESSAGE_DATA = "extra_message_data"
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isAdvertising = false
    private var isScanning = false
    private var scanCycleCount = 0

    private val scanRestartRunnable = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            if (bluetoothAdapter?.isEnabled != true) return
            // Stop and restart scanning to force BLE stack to rediscover peers
            stopScanning()
            handler.postDelayed({
                startScanning()
                // Schedule next cycle
                handler.postDelayed(this, 15_000)
            }, 1_000) // 1 second pause before restarting
        }
    }
    
    private lateinit var gattServer: GattServer
    private lateinit var gattClient: GattClient
    private lateinit var peerManager: PeerManager
    private lateinit var dedupCache: uniffi.meshlink_core.DedupCache
    private var localBeaconId: Int = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser

        peerManager = PeerManager()
        dedupCache = uniffi.meshlink_core.DedupCache()
        val identityKey = KeyManager(this).getIdentityKey()
        localBeaconId = Random.nextInt(1, Int.MAX_VALUE)
        
        gattServer = GattServer(this, bluetoothManager, this, identityKey)
        gattServer.localBeaconId = localBeaconId
        
        gattClient = GattClient(this, this, identityKey)
        gattClient.localBeaconId = localBeaconId
        Log.i(TAG, "Local Beacon ID: $localBeaconId")

        startBleMesh()
    }

    @SuppressLint("MissingPermission")
    private fun startBleMesh() {
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is disabled.")
            return
        }
        // Start the GATT server so incoming connections can discover our service
        gattServer.start()
        Log.i(TAG, "GATT Server started in startBleMesh()")

        // Attempt to start advertising if the advertiser instance is available.
        if (bleAdvertiser != null) {
            startAdvertising()
        } else {
            Log.w(TAG, "BLE Advertiser unavailable on this device. Skipping advertising.")
        }
        // All devices can scan regardless of advertising capability
        startScanning()
        // Schedule periodic scan restarts to work around Android BLE stack
        // deprioritizing scanning after GATT connections are established
        handler.postDelayed(scanRestartRunnable, 15_000)
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

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(MESHLINK_SERVICE_UUID)
            .addServiceData(MESHLINK_SERVICE_UUID, serviceData)
            .build()

        bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
        isAdvertising = true
        Log.i(TAG, "Started BLE Advertising.")
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        // No hardware-level filter — some devices (Realme, Oppo) ignore UUID filters
        // when GATT connections are active. We filter manually in the callback.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
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
            result?.device?.let { device ->
                // Manual UUID filter since we scan without hardware filters
                val serviceUuids = result.scanRecord?.serviceUuids
                if (serviceUuids == null || !serviceUuids.contains(MESHLINK_SERVICE_UUID)) {
                    return@let // Not a MeshLink device
                }
                
                val serviceData = result.scanRecord?.serviceData?.get(MESHLINK_SERVICE_UUID)
                var peerBeaconId = 0
                if (serviceData != null && serviceData.size >= 5) {
                    peerBeaconId = ((serviceData[1].toInt() and 0xFF) shl 24) or
                                   ((serviceData[2].toInt() and 0xFF) shl 16) or
                                   ((serviceData[3].toInt() and 0xFF) shl 8) or
                                   (serviceData[4].toInt() and 0xFF)
                }
                
                // Skip if it's our own advertisement
                if (peerBeaconId == localBeaconId) return@let
                
                val peer = peerManager.getPeer(device.address)
                if (peer == null || !peer.isConnected) {
                    Log.i(TAG, "Scan found peer ${device.address} beacon=$peerBeaconId, connecting...")
                    gattClient.connectToPeer(device)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "SEND_MESSAGE") {
            val address = intent.getStringExtra("address") ?: return START_STICKY
            val message = intent.getStringExtra("message") ?: return START_STICKY
            sendMessageToPeer(address, message)
        } else if (intent?.action == "BROADCAST_MESSAGE") {
            val message = intent.getStringExtra("message") ?: return START_STICKY
            broadcastMessage(message)
        } else if (intent?.action == "SYNC_STATE") {
            peerManager.getConnectedPeers().forEach { peer ->
                broadcastPeerConnected(peer.address, peer.beaconId)
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        bleAdvertiser?.stopAdvertising(advertiseCallback)
        stopScanning()
        gattServer.stop()
        gattClient.disconnectAll()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    fun sendMessageToPeer(address: String, message: String) {
        val peerId = peerManager.getPeer(address)?.beaconId ?: return
        val envelope = uniffi.meshlink_core.createEnvelope(
            senderId = localBeaconId.toUInt(),
            recipientId = peerId.toUInt(),
            payload = message,
            priority = uniffi.meshlink_core.Priority.DIRECT,
            payloadType = uniffi.meshlink_core.PayloadType.TEXT
        )
        val serialized = uniffi.meshlink_core.serializeEnvelope(envelope)
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val db = com.meshlink.db.AppDatabase.getDatabase(this@RelayService)
            db.messageDao().insertMessage(com.meshlink.db.MessageEntity(
                messageId = envelope.messageId,
                envelopeData = serialized,
                timestamp = System.currentTimeMillis()
            ))
        }

        gattClient.sendMessage(address, serialized)
        Log.d(TAG, "Sent message to $address")
    }

    fun broadcastMessage(message: String) {
        val envelope = uniffi.meshlink_core.createEnvelope(
            senderId = localBeaconId.toUInt(),
            recipientId = 0U, // 0 for broadcast recipient
            payload = message,
            priority = uniffi.meshlink_core.Priority.BROADCAST,
            payloadType = uniffi.meshlink_core.PayloadType.TEXT
        )
        val outBytes = uniffi.meshlink_core.serializeEnvelope(envelope)
        
        peerManager.getConnectedPeers().forEach { peer ->
            gattClient.sendMessage(peer.address, outBytes)
        }
    }

    // --- GattServerListener ---
    override fun onPeerConnected(device: BluetoothDevice) {
        // Awaits beacon ID
    }

    override fun onPeerDisconnected(device: BluetoothDevice) {
        peerManager.removePeer(device.address)
        broadcastPeerDisconnected(device.address)
    }

    override fun onBeaconIdReceived(device: BluetoothDevice, beaconId: Int, sharedSecret: ByteArray) {
        if (peerManager.shouldHandshake(beaconId)) {
            peerManager.recordHandshake(beaconId)
            peerManager.addPeer(device.address, beaconId, sharedSecret)
            Log.i(TAG, "Handshake complete (server side) with ${device.address}, beacon $beaconId")
            broadcastPeerConnected(device.address, beaconId)
        }
        
        // Ensure symmetric connection so we can send messages back
        gattClient.connectToPeer(device)
    }

    override fun onMessageReceived(device: BluetoothDevice, data: ByteArray) {
        val envelope = try {
            uniffi.meshlink_core.deserializeEnvelope(data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize envelope from ${device.address}: ${e.message}")
            return
        }

        val action = uniffi.meshlink_core.processIncoming(envelope, localBeaconId.toUInt(), dedupCache)
        when (action) {
            uniffi.meshlink_core.ProcessAction.DELIVER_LOCAL -> {
                Log.i(TAG, "Delivering local message from ${device.address}")
                val intent = Intent(ACTION_MESSAGE_RECEIVED).apply {
                    putExtra(EXTRA_PEER_ADDRESS, device.address)
                    putExtra(EXTRA_MESSAGE_DATA, envelope.encryptedPayload)
                    putExtra("extra_sender_beacon", envelope.senderId.toInt())
                }
                intent.setPackage(packageName)
                sendBroadcast(intent)
                
                // If it's a broadcast, we also need to relay it to others
                if (envelope.priority == uniffi.meshlink_core.Priority.BROADCAST) {
                    val updatedEnvelope = uniffi.meshlink_core.decrementTtl(envelope)
                    if (updatedEnvelope != null) {
                        val serialized = uniffi.meshlink_core.serializeEnvelope(updatedEnvelope)
                        peerManager.getConnectedPeers().forEach { peer ->
                            if (peer.address != device.address) {
                                gattClient.sendMessage(peer.address, serialized)
                            }
                        }
                    }
                }
            }
            uniffi.meshlink_core.ProcessAction.RELAY -> {
                Log.i(TAG, "Relaying message from ${device.address}")
                val updatedEnvelope = uniffi.meshlink_core.decrementTtl(envelope)
                if (updatedEnvelope != null) {
                    val serialized = uniffi.meshlink_core.serializeEnvelope(updatedEnvelope)
                    peerManager.getConnectedPeers().forEach { peer ->
                        if (peer.address != device.address) {
                            gattClient.sendMessage(peer.address, serialized)
                        }
                    }
                }
            }
            uniffi.meshlink_core.ProcessAction.DROP -> {
                Log.d(TAG, "Dropped message from ${device.address}")
            }
        }
    }

    // --- GattClientListener ---
    override fun onPeerHandshakeComplete(device: BluetoothDevice, remoteBeaconId: Int, sharedSecret: ByteArray) {
        if (peerManager.shouldHandshake(remoteBeaconId)) {
            peerManager.recordHandshake(remoteBeaconId)
            peerManager.addPeer(device.address, remoteBeaconId, sharedSecret)
            
            Log.i(TAG, "Handshake complete (client side) with ${device.address}, beacon $remoteBeaconId")
            broadcastPeerConnected(device.address, remoteBeaconId)
        }
        
        // Always send our beacon ID back so the other side knows who we are
        
    }

    override fun onHandshakeFailed(device: BluetoothDevice, reason: String) {
        Log.w(TAG, "Handshake failed with ${device.address}: $reason")
    }

    private fun broadcastPeerConnected(address: String, beaconId: Int) {
        val intent = Intent(ACTION_PEER_CONNECTED).apply {
            putExtra(EXTRA_PEER_ADDRESS, address)
            putExtra(EXTRA_BEACON_ID, beaconId)
        }
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun broadcastPeerDisconnected(address: String) {
        val intent = Intent(ACTION_PEER_DISCONNECTED).apply {
            putExtra(EXTRA_PEER_ADDRESS, address)
        }
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MeshLink Background Relay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Maintains the off-grid mesh network"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MeshLink Active")
            .setContentText("Relaying messages for the mesh network")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }
}
