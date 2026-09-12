package com.meshlink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

interface GattClientListener {
    fun onPeerHandshakeComplete(device: BluetoothDevice, remoteBeaconId: Int, sharedSecret: ByteArray)
    fun onPeerDisconnected(device: BluetoothDevice)
    fun onMessageReceived(device: BluetoothDevice, data: ByteArray)
    fun onHandshakeFailed(device: BluetoothDevice, reason: String)
}

/**
 * Central side of the link: discovers peers, performs the X25519 handshake,
 * writes outbound envelopes and receives indications pushed by the peer's
 * [GattServer].
 */
class GattClient(
    private val context: Context,
    private val listener: GattClientListener,
    private val identityKey: uniffi.meshlink_core.IdentityKeyPair
) {
    companion object {
        private const val TAG = "GattClient"

        /** Android's own GATT client limit is low; staying under it avoids churn. */
        private const val MAX_CONNECTIONS = 5
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val SUBSCRIBE_TIMEOUT_MS = 4_000L
        private const val DEFAULT_MTU = 23
    }

    var localBeaconId: Int = 0

    private val connections = ConcurrentHashMap<String, BluetoothGatt>()
    private val pendingConnections = ConcurrentHashMap<String, BluetoothGatt>()
    private val clientEphemeralKeys = ConcurrentHashMap<String, uniffi.meshlink_core.EphemeralKeyPair>()
    private val sharedSecrets = ConcurrentHashMap<String, ByteArray>()
    private val mtus = ConcurrentHashMap<String, Int>()

    private val writeQueues = ConcurrentHashMap<String, ConcurrentLinkedQueue<ByteArray>>()
    private val writeInProgress = ConcurrentHashMap<String, Boolean>()
    private val reassembler = LinkCodec.Reassembler()

    private val reconnectAttempts = ConcurrentHashMap<String, Int>()
    private val connectionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    fun connectToPeer(device: BluetoothDevice) {
        if (connections.containsKey(device.address) || pendingConnections.containsKey(device.address)) {
            return
        }
        if (connections.size >= MAX_CONNECTIONS) {
            Log.w(TAG, "Max connections reached, dropping connect to ${device.address}")
            return
        }

        Log.i(TAG, "Connecting to ${device.address}")
        val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (gatt != null) {
            pendingConnections[device.address] = gatt

            connectionHandler.postDelayed({
                if (pendingConnections.containsKey(device.address)) {
                    Log.e(TAG, "Connection timeout to ${device.address}, aborting.")
                    pendingConnections.remove(device.address)?.let {
                        it.disconnect()
                        it.close()
                    }
                    listener.onPeerDisconnected(device)
                }
            }, CONNECT_TIMEOUT_MS)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect(deviceAddress: String) {
        pendingConnections.remove(deviceAddress)?.let {
            it.disconnect()
            it.close()
        }
        connections.remove(deviceAddress)?.let {
            it.disconnect()
            it.close()
        }
        forgetLinkState(deviceAddress)
    }

    @SuppressLint("MissingPermission")
    fun disconnectAll() {
        connections.values.forEach {
            it.disconnect()
            it.close()
        }
        connections.clear()
        pendingConnections.clear()
        sharedSecrets.clear()
        clientEphemeralKeys.clear()
        writeQueues.clear()
        writeInProgress.clear()
    }

    /**
     * Clears everything tied to a link that no longer exists. Leaving the shared
     * secret behind would let [sendMessage] happily encrypt into a dead
     * connection, so the message would vanish with no error anywhere.
     */
    private fun forgetLinkState(address: String) {
        sharedSecrets.remove(address)
        clientEphemeralKeys.remove(address)
        writeQueues.remove(address)
        writeInProgress.remove(address)
        mtus.remove(address)
        reassembler.forget(address)
    }

    /** True when we hold a live, handshaken outbound link to this peer. */
    fun canSendTo(address: String): Boolean =
        connections.containsKey(address) && sharedSecrets.containsKey(address)

    /**
     * Queues one envelope for delivery over this link. Returns false when the
     * link is not usable, so the caller can try the server's indication path
     * instead of assuming the message went out.
     */
    @SuppressLint("MissingPermission")
    fun sendMessage(deviceAddress: String, data: ByteArray): Boolean {
        val secret = sharedSecrets[deviceAddress] ?: return false
        if (!connections.containsKey(deviceAddress)) return false

        val chunks = LinkCodec.frame(secret, data, mtus[deviceAddress] ?: DEFAULT_MTU)
        if (chunks.isEmpty()) return false

        val queue = writeQueues.getOrPut(deviceAddress) { ConcurrentLinkedQueue() }
        // Flooding is driven from several threads at once, and an interleaved
        // enqueue would splice two messages' chunks together on the wire, which
        // the far side can only discard. Keeping the append atomic keeps each
        // message's chunks contiguous.
        synchronized(queue) { queue.addAll(chunks) }
        pumpWrites(deviceAddress)
        return true
    }

    /**
     * Issues the next queued chunk if no write is already outstanding.
     *
     * GATT allows one write at a time per connection, and this is reached both
     * from sending threads and from the completion callback. Claiming the slot
     * and taking the chunk under one lock is what stops two threads issuing
     * overlapping writes, which the stack answers by dropping one of them.
     */
    @SuppressLint("MissingPermission")
    private fun pumpWrites(deviceAddress: String) {
        val queue = writeQueues[deviceAddress] ?: return
        val gatt = connections[deviceAddress]
        val characteristic = gatt
            ?.getService(RelayService.MESHLINK_SERVICE_UUID.uuid)
            ?.getCharacteristic(GattServer.MESSAGE_CHAR_UUID)
        if (gatt == null || characteristic == null) {
            // The link went away mid-transfer; drop the rest rather than
            // retrying forever against a service that is no longer there.
            synchronized(queue) {
                queue.clear()
                writeInProgress[deviceAddress] = false
            }
            return
        }

        val chunk = synchronized(queue) {
            if (writeInProgress[deviceAddress] == true) return
            val next = queue.poll()
            writeInProgress[deviceAddress] = next != null
            next
        } ?: return

        // OEM stacks reject malformed or oversized frames by throwing rather than
        // returning a status, and this runs on the main looper, so an uncaught
        // throw would take the whole service down mid-flood.
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    characteristic.value = chunk
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    gatt.writeCharacteristic(characteristic)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Write to $deviceAddress threw: ${e.message}")
            false
        }

        if (!ok) {
            Log.w(TAG, "Write to $deviceAddress rejected; dropping queued chunks")
            synchronized(queue) {
                queue.clear()
                writeInProgress[deviceAddress] = false
            }
        }
    }

    /** Releases the write slot claimed by [pumpWrites] and continues the queue. */
    private fun completeWrite(deviceAddress: String, keepQueue: Boolean) {
        val queue = writeQueues[deviceAddress] ?: return
        synchronized(queue) {
            if (!keepQueue) queue.clear()
            writeInProgress[deviceAddress] = false
        }
        if (keepQueue) pumpWrites(deviceAddress)
    }

    /**
     * Subscribes to the peer's message characteristic so it can push envelopes
     * back to us over this same link, then reports the handshake as complete.
     */
    @SuppressLint("MissingPermission")
    private fun enableIndications(gatt: BluetoothGatt): Boolean {
        val characteristic = gatt
            .getService(RelayService.MESHLINK_SERVICE_UUID.uuid)
            ?.getCharacteristic(GattServer.MESSAGE_CHAR_UUID) ?: return false
        val cccd = characteristic.getDescriptor(GattServer.CCCD_UUID) ?: return false

        gatt.setCharacteristicNotification(characteristic, true)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) ==
                android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
        }
    }

    /**
     * Reports a completed handshake exactly once per link. Removing the pending
     * beacon id is what makes repeat calls — the subscribe callback and its
     * timeout fallback — harmless.
     */
    @Synchronized
    private fun announceHandshake(gatt: BluetoothGatt) {
        val secret = sharedSecrets[gatt.device.address] ?: return
        val beaconId = remoteBeaconIds.remove(gatt.device.address) ?: return
        listener.onPeerHandshakeComplete(gatt.device, beaconId, secret)
    }

    private val remoteBeaconIds = ConcurrentHashMap<String, Int>()

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val device = gatt.device
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Connected to ${device.address}")
                    pendingConnections.remove(device.address)
                    connections[device.address] = gatt
                    mtus.putIfAbsent(device.address, DEFAULT_MTU)
                    reconnectAttempts.remove(device.address)
                    if (!gatt.requestMtu(512)) {
                        gatt.discoverServices()
                    }
                } else {
                    Log.w(TAG, "Connection failed with status $status to ${device.address}")
                    gatt.close()
                    pendingConnections.remove(device.address)
                    forgetLinkState(device.address)
                    listener.onPeerDisconnected(device)
                    scheduleReconnect(device)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from ${device.address} (status=$status)")
                connections.remove(device.address)
                pendingConnections.remove(device.address)
                forgetLinkState(device.address)
                remoteBeaconIds.remove(device.address)
                gatt.close()
                listener.onPeerDisconnected(device)
                scheduleReconnect(device)
            }
        }

        @SuppressLint("MissingPermission")
        private fun scheduleReconnect(device: BluetoothDevice) {
            val attempts = reconnectAttempts.getOrDefault(device.address, 0)
            if (attempts >= MAX_RECONNECT_ATTEMPTS) {
                // Give up on the timer; the scan loop will retry on next sighting.
                Log.w(TAG, "Max reconnect attempts reached for ${device.address}")
                reconnectAttempts.remove(device.address)
                return
            }
            val delayMs = minOf(2000L * (1L shl attempts), 60_000L)
            reconnectAttempts[device.address] = attempts + 1
            Log.i(TAG, "Scheduling reconnect #${attempts + 1} to ${device.address} in ${delayMs}ms")
            reconnectHandler.postDelayed({
                if (!connections.containsKey(device.address) && !pendingConnections.containsKey(device.address)) {
                    connectToPeer(device)
                }
            }, delayMs)
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mtus[gatt.device.address] = mtu
                Log.d(TAG, "Client MTU for ${gatt.device.address} is $mtu")
            }
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onHandshakeFailed(gatt.device, "Service discovery failed ($status)")
                disconnect(gatt.device.address)
                return
            }
            val service = gatt.getService(RelayService.MESHLINK_SERVICE_UUID.uuid)
            if (service == null) {
                listener.onHandshakeFailed(gatt.device, "MeshLink Service not found")
                disconnect(gatt.device.address)
                return
            }
            val versionChar = service.getCharacteristic(GattServer.VERSION_CHAR_UUID)
            if (versionChar == null) {
                listener.onHandshakeFailed(gatt.device, "VERSION_CHAR not found")
                disconnect(gatt.device.address)
                return
            }
            gatt.readCharacteristic(versionChar)
        }

        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            when (characteristic.uuid) {
                GattServer.VERSION_CHAR_UUID -> {
                    val version = characteristic.value?.firstOrNull()
                    if (version != GattServer.PROTOCOL_VERSION) {
                        listener.onHandshakeFailed(gatt.device, "Incompatible protocol version")
                        disconnect(gatt.device.address)
                        return
                    }
                    val beaconChar = gatt.getService(RelayService.MESHLINK_SERVICE_UUID.uuid)
                        ?.getCharacteristic(GattServer.BEACON_CHAR_UUID) ?: return
                    val ephemeralKey = uniffi.meshlink_core.EphemeralKeyPair.generate()
                    clientEphemeralKeys[gatt.device.address] = ephemeralKey
                    val payload = uniffi.meshlink_core.createHandshakePayload(
                        localBeaconId.toUInt(), identityKey, ephemeralKey
                    )
                    beaconChar.value = uniffi.meshlink_core.serializeHandshake(payload)
                    beaconChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    gatt.writeCharacteristic(beaconChar)
                }
                GattServer.BEACON_CHAR_UUID -> {
                    val value = characteristic.value
                    if (value == null || value.isEmpty()) return
                    try {
                        val serverPayload = uniffi.meshlink_core.deserializeHandshake(value)
                        if (!uniffi.meshlink_core.verifyHandshakePayload(serverPayload)) {
                            listener.onHandshakeFailed(gatt.device, "Server handshake verification failed")
                            return
                        }
                        val ephemeralKey = clientEphemeralKeys.remove(gatt.device.address) ?: return
                        val sharedSecret = ephemeralKey.computeSharedSecret(serverPayload.ephemeralPubKey)
                        sharedSecrets[gatt.device.address] = sharedSecret
                        reassembler.forget(gatt.device.address)
                        remoteBeaconIds[gatt.device.address] = serverPayload.beaconId.toInt()

                        // Subscribing before announcing the handshake means the
                        // reverse direction is already live by the time the
                        // service starts flooding traffic over this link.
                        if (enableIndications(gatt)) {
                            // Not every stack delivers onDescriptorWrite. Without a
                            // fallback the handshake would never be reported and the
                            // peer would stay invisible to the mesh.
                            connectionHandler.postDelayed(
                                { announceHandshake(gatt) }, SUBSCRIBE_TIMEOUT_MS
                            )
                        } else {
                            Log.w(TAG, "Could not subscribe to ${gatt.device.address}; inbound relies on its client link")
                            announceHandshake(gatt)
                        }
                    } catch (e: Exception) {
                        listener.onHandshakeFailed(gatt.device, "Invalid handshake payload")
                    }
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            if (descriptor.uuid == GattServer.CCCD_UUID) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Indication subscribe failed for ${gatt.device.address} ($status)")
                }
                announceHandshake(gatt)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            when (characteristic.uuid) {
                GattServer.BEACON_CHAR_UUID -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val beaconChar = gatt.getService(RelayService.MESHLINK_SERVICE_UUID.uuid)
                            ?.getCharacteristic(GattServer.BEACON_CHAR_UUID)
                        if (beaconChar != null) gatt.readCharacteristic(beaconChar)
                    } else {
                        listener.onHandshakeFailed(gatt.device, "Handshake write failed ($status)")
                    }
                }
                GattServer.MESSAGE_CHAR_UUID -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "Chunk write to ${gatt.device.address} failed ($status)")
                    }
                    completeWrite(gatt.device.address, keepQueue = status == BluetoothGatt.GATT_SUCCESS)
                }
            }
        }

        /** API 33+ delivers the value directly instead of via `characteristic.value`. */
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) {
            handleIndication(gatt, characteristic, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            handleIndication(gatt, characteristic, characteristic.value ?: return)
        }

        private fun handleIndication(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (characteristic.uuid != GattServer.MESSAGE_CHAR_UUID) return
            // Indications arrive chunked and link-encrypted exactly like writes,
            // so they need the same reassembly rather than being handed straight
            // to the listener as a raw fragment.
            val envelope = reassembler.accept(
                gatt.device.address, value, sharedSecrets[gatt.device.address]
            )
            if (envelope != null) {
                listener.onMessageReceived(gatt.device, envelope)
            }
        }
    }
}
