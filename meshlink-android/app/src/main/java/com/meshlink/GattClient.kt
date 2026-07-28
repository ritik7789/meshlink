package com.meshlink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

interface GattClientListener {
    fun onPeerHandshakeComplete(device: BluetoothDevice, remoteBeaconId: Int, sharedSecret: ByteArray)
    fun onPeerDisconnected(device: BluetoothDevice)
    fun onMessageReceived(device: BluetoothDevice, data: ByteArray)
    fun onHandshakeFailed(device: BluetoothDevice, reason: String)
}

class GattClient(
    private val context: Context,
    private val listener: GattClientListener,
    private val identityKey: uniffi.meshlink_core.IdentityKeyPair
) {
    var localBeaconId: Int = 0
    private val clientEphemeralKeys = ConcurrentHashMap<String, uniffi.meshlink_core.EphemeralKeyPair>()
    private val sharedSecrets = ConcurrentHashMap<String, ByteArray>()
    private val writeQueues = ConcurrentHashMap<String, ConcurrentLinkedQueue<ByteArray>>()
    private val writeInProgress = ConcurrentHashMap<String, Boolean>()
    companion object {
        private const val TAG = "GattClient"
        private const val MAX_CONNECTIONS = 5 // FR-2.1.5
    }

    private val connections = ConcurrentHashMap<String, BluetoothGatt>()
    private val pendingConnections = ConcurrentHashMap<String, BluetoothGatt>()
    private val connectionHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    fun connectToPeer(device: BluetoothDevice) {
        if (connections.size >= MAX_CONNECTIONS && !connections.containsKey(device.address)) {
            Log.w(TAG, "Max connections reached, dropping connect to ${device.address}")
            return
        }
        if (connections.containsKey(device.address) || pendingConnections.containsKey(device.address)) {
            return
        }
        
        Log.i(TAG, "Connecting to ${device.address}")
        val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (gatt != null) {
            pendingConnections[device.address] = gatt
            
            // 10 second connection timeout
            connectionHandler.postDelayed({
                if (pendingConnections.containsKey(device.address)) {
                    Log.e(TAG, "Connection timeout to ${device.address}, aborting.")
                    pendingConnections.remove(device.address)?.let {
                        it.disconnect()
                        it.close()
                    }
                    // Trigger disconnect callback to start exponential backoff
                    listener.onPeerDisconnected(device)
                }
            }, 10000)
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
    }

    @SuppressLint("MissingPermission")
    fun disconnectAll() {
        connections.values.forEach { 
            it.disconnect()
            it.close()
        }
        connections.clear()
    }

    @SuppressLint("MissingPermission")
    fun sendMessage(deviceAddress: String, data: ByteArray) {
        val secret = sharedSecrets[deviceAddress] ?: return

        val nonce = ByteArray(12)
        SecureRandom().nextBytes(nonce)
        val encrypted = uniffi.meshlink_core.encryptTransport(secret, nonce, data)
        val combined = nonce + encrypted
        
        val mtu = 500
        val chunks = combined.toList().chunked(mtu)
        val totalChunks = chunks.size
        
        val queue = writeQueues.getOrPut(deviceAddress) { ConcurrentLinkedQueue() }
        chunks.forEachIndexed { index, chunk ->
            val payload = ByteArray(2 + chunk.size)
            payload[0] = index.toByte()
            payload[1] = totalChunks.toByte()
            System.arraycopy(chunk.toByteArray(), 0, payload, 2, chunk.size)
            queue.add(payload)
        }
        
        if (writeInProgress[deviceAddress] != true) {
            processNextWrite(deviceAddress)
        }
    }

    @SuppressLint("MissingPermission")
    fun sendRawEnvelope(deviceAddress: String, envelopeBytes: ByteArray) {
        val secret = sharedSecrets[deviceAddress] ?: return

        val nonce = ByteArray(12)
        SecureRandom().nextBytes(nonce)
        val encrypted = uniffi.meshlink_core.encryptTransport(secret, nonce, envelopeBytes)
        val combined = nonce + encrypted
        
        val mtu = 500
        val chunks = combined.toList().chunked(mtu)
        val totalChunks = chunks.size
        
        val queue = writeQueues.getOrPut(deviceAddress) { ConcurrentLinkedQueue() }
        chunks.forEachIndexed { index, chunk ->
            val payload = ByteArray(2 + chunk.size)
            payload[0] = index.toByte()
            payload[1] = totalChunks.toByte()
            System.arraycopy(chunk.toByteArray(), 0, payload, 2, chunk.size)
            queue.add(payload)
        }
        
        if (writeInProgress[deviceAddress] != true) {
            processNextWrite(deviceAddress)
        }
    }

    @SuppressLint("MissingPermission")
    private fun processNextWrite(deviceAddress: String) {
        val queue = writeQueues[deviceAddress]
        if (queue == null || queue.isEmpty()) {
            writeInProgress[deviceAddress] = false
            return
        }
        writeInProgress[deviceAddress] = true
        val chunk = queue.poll()
        if (chunk != null) {
            val gatt = connections[deviceAddress] ?: return
            val service = gatt.getService(RelayService.MESHLINK_SERVICE_UUID.uuid) ?: return
            val char = service.getCharacteristic(GattServer.MESSAGE_CHAR_UUID) ?: return
            char.value = chunk
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(char)
        } else {
            writeInProgress[deviceAddress] = false
        }
    }

    private val reconnectAttempts = ConcurrentHashMap<String, Int>()
    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val MAX_RECONNECT_ATTEMPTS = 5

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val device = gatt.device
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Connected to ${device.address}")
                    pendingConnections.remove(device.address)
                    connections[device.address] = gatt
                    reconnectAttempts.remove(device.address) // Reset retry counter on success
                    val requested = gatt.requestMtu(512)
                    if (!requested) {
                        gatt.discoverServices()
                    }
                } else {
                    Log.w(TAG, "Connection failed with status $status to ${device.address}")
                    gatt.close()
                    pendingConnections.remove(device.address)
                    listener.onPeerDisconnected(device)
                    scheduleReconnect(device)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from ${device.address} (status=$status)")
                connections.remove(device.address)
                pendingConnections.remove(device.address)
                writeQueues.remove(device.address)
                writeInProgress.remove(device.address)
                gatt.close()
                listener.onPeerDisconnected(device)
                
                // Auto-reconnect with exponential backoff
                scheduleReconnect(device)
            }
        }
        
        @SuppressLint("MissingPermission")
        private fun scheduleReconnect(device: BluetoothDevice) {
            val attempts = reconnectAttempts.getOrDefault(device.address, 0)
            if (attempts < MAX_RECONNECT_ATTEMPTS) {
                val delayMs = (Math.min(2000L * (1L shl attempts), 60000L)) // 2s, 4s, 8s, 16s, 32s, max 60s
                reconnectAttempts[device.address] = attempts + 1
                Log.i(TAG, "Scheduling reconnect #${attempts + 1} to ${device.address} in ${delayMs}ms")
                reconnectHandler.postDelayed({
                    if (!connections.containsKey(device.address) && !pendingConnections.containsKey(device.address)) {
                        connectToPeer(device)
                    }
                }, delayMs)
            } else {
                Log.w(TAG, "Max reconnect attempts reached for ${device.address}")
                reconnectAttempts.remove(device.address)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "MTU changed to $mtu, discovering services...")
                gatt.discoverServices()
            } else {
                gatt.discoverServices() // Try anyway
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(RelayService.MESHLINK_SERVICE_UUID.uuid)
                if (service != null) {
                    val versionChar = service.getCharacteristic(GattServer.VERSION_CHAR_UUID)
                    if (versionChar != null) {
                        gatt.readCharacteristic(versionChar)
                    } else {
                        listener.onHandshakeFailed(gatt.device, "VERSION_CHAR not found")
                        disconnect(gatt.device.address)
                    }
                } else {
                    listener.onHandshakeFailed(gatt.device, "MeshLink Service not found")
                    disconnect(gatt.device.address)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (characteristic.uuid) {
                    GattServer.VERSION_CHAR_UUID -> {
                        val version = characteristic.value?.firstOrNull()
                        if (version == GattServer.PROTOCOL_VERSION) {
                            val beaconChar = gatt.getService(RelayService.MESHLINK_SERVICE_UUID.uuid)
                                ?.getCharacteristic(GattServer.BEACON_CHAR_UUID)
                            if (beaconChar != null) {
                                val ephemeralKey = uniffi.meshlink_core.EphemeralKeyPair.generate()
                                clientEphemeralKeys[gatt.device.address] = ephemeralKey
                                val payload = uniffi.meshlink_core.createHandshakePayload(localBeaconId.toUInt(), identityKey, ephemeralKey)
                                beaconChar.value = uniffi.meshlink_core.serializeHandshake(payload)
                                beaconChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                gatt.writeCharacteristic(beaconChar)
                            }
                        } else {
                            listener.onHandshakeFailed(gatt.device, "Incompatible protocol version")
                            disconnect(gatt.device.address)
                        }
                    }
                    GattServer.BEACON_CHAR_UUID -> {
                        val value = characteristic.value
                        if (value != null && value.isNotEmpty()) {
                            try {
                                val serverPayload = uniffi.meshlink_core.deserializeHandshake(value)
                                if (uniffi.meshlink_core.verifyHandshakePayload(serverPayload)) {
                                    val ephemeralKey = clientEphemeralKeys[gatt.device.address]
                                    if (ephemeralKey != null) {
                                        val sharedSecret = ephemeralKey.computeSharedSecret(serverPayload.ephemeralPubKey)
                                        sharedSecrets[gatt.device.address] = sharedSecret
                                        listener.onPeerHandshakeComplete(gatt.device, serverPayload.beaconId.toInt(), sharedSecret)
                                    }
                                } else {
                                    listener.onHandshakeFailed(gatt.device, "Server handshake verification failed")
                                }
                            } catch(e: Exception) {
                                listener.onHandshakeFailed(gatt.device, "Invalid handshake payload")
                            }
                        }
                    }
                }
            }
        }

                @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == GattServer.BEACON_CHAR_UUID) {
                val beaconChar = gatt.getService(RelayService.MESHLINK_SERVICE_UUID.uuid)
                    ?.getCharacteristic(GattServer.BEACON_CHAR_UUID)
                if (beaconChar != null) {
                    gatt.readCharacteristic(beaconChar)
                }
            } else if (characteristic.uuid == GattServer.MESSAGE_CHAR_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    processNextWrite(gatt.device.address)
                } else {
                    writeInProgress[gatt.device.address] = false
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == GattServer.MESSAGE_CHAR_UUID) {
                val data = characteristic.value ?: return
                listener.onMessageReceived(gatt.device, data)
            }
        }
    }
}
