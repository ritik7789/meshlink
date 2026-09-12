package com.meshlink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

interface GattServerListener {
    fun onPeerConnected(device: BluetoothDevice)
    fun onPeerDisconnected(device: BluetoothDevice)
    fun onBeaconIdReceived(device: BluetoothDevice, beaconId: Int, sharedSecret: ByteArray)
    fun onMessageReceived(device: BluetoothDevice, data: ByteArray)

    /** One voice packet, already opened. Called on a Bluetooth thread. */
    fun onAudioReceived(device: BluetoothDevice, packet: ByteArray)
}

/**
 * Peripheral side of the link. Besides accepting inbound connections it can now
 * push data back out over indications, so a single BLE link carries traffic in
 * both directions.
 *
 * That matters for relaying: previously every transmission went through
 * [GattClient], so if a peer connected to us and our reciprocal outbound connect
 * failed, we could receive from that peer but never send to it, and the relay
 * chain died silently there.
 */
class GattServer(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val listener: GattServerListener,
    private val identityKey: uniffi.meshlink_core.IdentityKeyPair
) {
    companion object {
        private const val TAG = "GattServer"
        val VERSION_CHAR_UUID: UUID = UUID.fromString("00001001-0000-1000-8000-00805F9B34FB")
        val BEACON_CHAR_UUID: UUID = UUID.fromString("00001002-0000-1000-8000-00805F9B34FB")
        val MESSAGE_CHAR_UUID: UUID = UUID.fromString("00001003-0000-1000-8000-00805F9B34FB")

        /**
         * Voice packets, kept off [MESSAGE_CHAR_UUID] on purpose.
         *
         * The message characteristic indicates, which means one packet in
         * flight at a time and a retry for anything unconfirmed. That is right
         * for a message and ruinous for a call: a single retransmission stalls
         * the whole stream behind it, and by the time a late voice packet
         * arrives the moment it belonged to has passed. This one notifies
         * without confirmation and is written without response - lossy by
         * design, because for audio a missing frame costs less than a late one.
         */
        val AUDIO_CHAR_UUID: UUID = UUID.fromString("00001004-0000-1000-8000-00805F9B34FB")

        /** Standard Client Characteristic Configuration descriptor. */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        const val PROTOCOL_VERSION: Byte = 0x01

        /** BLE's mandatory default until an MTU exchange raises it. */
        private const val DEFAULT_MTU = 23
    }

    private var gattServer: BluetoothGattServer? = null
    private var messageCharacteristic: BluetoothGattCharacteristic? = null
    private var audioCharacteristic: BluetoothGattCharacteristic? = null

    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val subscribedDevices = ConcurrentHashMap<String, Boolean>()
    private val audioSubscribers = ConcurrentHashMap<String, Boolean>()
    private val sharedSecrets = ConcurrentHashMap<String, ByteArray>()
    private val serverPayloads = ConcurrentHashMap<String, ByteArray>()
    private val mtus = ConcurrentHashMap<String, Int>()

    private val reassembler = LinkCodec.Reassembler()
    private val notifyQueues = ConcurrentHashMap<String, ConcurrentLinkedQueue<ByteArray>>()
    private val notifyInProgress = ConcurrentHashMap<String, Boolean>()

    var localBeaconId: Int = 0

    @SuppressLint("MissingPermission")
    fun start() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        setupServices()
        Log.i(TAG, "GATT Server started")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        gattServer?.close()
        gattServer = null
        connectedDevices.clear()
        subscribedDevices.clear()
        audioSubscribers.clear()
        sharedSecrets.clear()
        notifyQueues.clear()
        notifyInProgress.clear()
        Log.i(TAG, "GATT Server stopped")
    }

    /** True when this peer can be reached over the server's indication path. */
    fun canSendTo(address: String): Boolean =
        connectedDevices.containsKey(address) &&
            subscribedDevices[address] == true &&
            sharedSecrets.containsKey(address)

    /**
     * Sends one envelope to a connected peer over indications. Returns false if
     * the peer is not reachable this way, so the caller can fall back to the
     * client path.
     */
    fun sendToDevice(address: String, envelopeBytes: ByteArray): Boolean {
        val secret = sharedSecrets[address] ?: return false
        if (!canSendTo(address)) return false

        val chunks = LinkCodec.frame(secret, envelopeBytes, mtus[address] ?: DEFAULT_MTU)
        if (chunks.isEmpty()) return false

        val queue = notifyQueues.getOrPut(address) { ConcurrentLinkedQueue() }
        // See GattClient.sendMessage: the append must be atomic or two messages'
        // chunks interleave on the wire.
        synchronized(queue) { queue.addAll(chunks) }
        pumpNotifications(address)
        return true
    }

    /** True when this peer has subscribed for voice packets over notifications. */
    fun canSendAudioTo(address: String): Boolean =
        connectedDevices.containsKey(address) &&
            audioSubscribers[address] == true &&
            sharedSecrets.containsKey(address)

    /**
     * Pushes one voice packet, dropping it rather than queueing on congestion.
     *
     * Deliberately bypasses the indication queue [sendToDevice] uses. There is
     * no retry and no ordering guarantee beyond what the link itself provides,
     * because a queue here would fill with packets whose moment had passed and
     * then deliver them all late. When the stack says it is busy, the right
     * answer for audio is to throw the packet away and send the next one.
     */
    @SuppressLint("MissingPermission")
    fun sendAudio(address: String, packet: ByteArray): Boolean {
        val characteristic = audioCharacteristic ?: return false
        val device = connectedDevices[address] ?: return false
        val secret = sharedSecrets[address] ?: return false
        if (audioSubscribers[address] != true) return false

        val sealed = LinkCodec.sealPacket(secret, packet, mtus[address] ?: DEFAULT_MTU)
        if (sealed.isEmpty()) return false

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gattServer?.notifyCharacteristicChanged(device, characteristic, false, sealed) ==
                    android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = sealed
                @Suppress("DEPRECATION")
                gattServer?.notifyCharacteristicChanged(device, characteristic, false) == true
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Sends the next queued chunk if no indication is already outstanding.
     *
     * Only one indication may be in flight per connection, and this runs both on
     * sending threads and on the confirmation callback, so the slot claim and
     * the dequeue happen together under one lock.
     */
    @SuppressLint("MissingPermission")
    private fun pumpNotifications(address: String) {
        val queue = notifyQueues[address] ?: return
        val characteristic = messageCharacteristic
        val device = connectedDevices[address]
        if (characteristic == null || device == null) {
            synchronized(queue) {
                queue.clear()
                notifyInProgress[address] = false
            }
            return
        }

        val chunk = synchronized(queue) {
            if (notifyInProgress[address] == true) return
            val next = queue.poll()
            notifyInProgress[address] = next != null
            next
        } ?: return

        // As in GattClient: a stack that rejects a frame by throwing must not be
        // able to kill the service.
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gattServer?.notifyCharacteristicChanged(device, characteristic, true, chunk) ==
                    android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = chunk
                @Suppress("DEPRECATION")
                gattServer?.notifyCharacteristicChanged(device, characteristic, true) == true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Indication to $address threw: ${e.message}")
            false
        }

        if (!ok) {
            Log.w(TAG, "Indication to $address failed; dropping queued chunks")
            completeNotification(address, keepQueue = false)
        }
    }

    /** Releases the slot claimed by [pumpNotifications] and continues the queue. */
    private fun completeNotification(address: String, keepQueue: Boolean) {
        val queue = notifyQueues[address] ?: return
        synchronized(queue) {
            if (!keepQueue) queue.clear()
            notifyInProgress[address] = false
        }
        if (keepQueue) pumpNotifications(address)
    }

    @SuppressLint("MissingPermission")
    private fun setupServices() {
        val service = BluetoothGattService(
            RelayService.MESHLINK_SERVICE_UUID.uuid,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val versionChar = BluetoothGattCharacteristic(
            VERSION_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val beaconChar = BluetoothGattCharacteristic(
            BEACON_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val messageChar = BluetoothGattCharacteristic(
            MESSAGE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        // Without this descriptor the INDICATE property is inert: a central has
        // no way to subscribe, so the server-to-client direction never works.
        messageChar.addDescriptor(
            BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        messageCharacteristic = messageChar

        service.addCharacteristic(versionChar)
        service.addCharacteristic(beaconChar)
        service.addCharacteristic(messageChar)

        // Voice needs its own unreliable channel rather than sharing the
        // message characteristic, which retries and reassembles. Nothing is
        // added when calling is disabled, so a build without the feature does
        // not advertise a channel it will never serve.
        if (CallFeature.isEnabled) {
            val audioChar = BluetoothGattCharacteristic(
                AUDIO_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            audioChar.addDescriptor(
                BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or
                        BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
            audioCharacteristic = audioChar
            service.addCharacteristic(audioChar)
        }

        gattServer?.addService(service)
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Peer connected to server: ${device.address}")
                connectedDevices[device.address] = device
                mtus.putIfAbsent(device.address, DEFAULT_MTU)
                listener.onPeerConnected(device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Peer disconnected from server: ${device.address}")
                connectedDevices.remove(device.address)
                subscribedDevices.remove(device.address)
                sharedSecrets.remove(device.address)
                serverPayloads.remove(device.address)
                notifyQueues.remove(device.address)
                notifyInProgress.remove(device.address)
                reassembler.forget(device.address)
                listener.onPeerDisconnected(device)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mtus[device.address] = mtu
            Log.d(TAG, "Server MTU for ${device.address} is $mtu")
        }

        @SuppressLint("MissingPermission")
        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Indication to ${device.address} failed with status $status")
            }
            completeNotification(device.address, keepQueue = status == BluetoothGatt.GATT_SUCCESS)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val enabled = value != null && value.isNotEmpty() && value[0].toInt() != 0
                // Two characteristics notify now, so the subscription has to be
                // recorded against the right one: a peer that has subscribed for
                // audio has not thereby said it can receive messages.
                when (descriptor.characteristic?.uuid) {
                    AUDIO_CHAR_UUID -> audioSubscribers[device.address] = enabled
                    else -> subscribedDevices[device.address] = enabled
                }
                Log.d(TAG, "${device.address} ${if (enabled) "subscribed to" else "unsubscribed from"} ${descriptor.characteristic?.uuid}")
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor
        ) {
            val value = if (subscribedDevices[device.address] == true) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            when (characteristic.uuid) {
                VERSION_CHAR_UUID -> {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf(PROTOCOL_VERSION))
                }
                BEACON_CHAR_UUID -> {
                    val payloadBytes = serverPayloads[device.address] ?: ByteArray(0)
                    val valueToSend = if (offset < payloadBytes.size) {
                        payloadBytes.copyOfRange(offset, payloadBytes.size)
                    } else {
                        ByteArray(0)
                    }
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, valueToSend)
                }
                else -> {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (value == null) {
                if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                return
            }

            when (characteristic.uuid) {
                BEACON_CHAR_UUID -> {
                    try {
                        val clientPayload = uniffi.meshlink_core.deserializeHandshake(value)
                        if (uniffi.meshlink_core.verifyHandshakePayload(clientPayload)) {
                            val ephemeralKey = uniffi.meshlink_core.EphemeralKeyPair.generate()
                            val serverPayloadObj = uniffi.meshlink_core.createHandshakePayload(
                                localBeaconId.toUInt(), identityKey, ephemeralKey
                            )
                            serverPayloads[device.address] = uniffi.meshlink_core.serializeHandshake(serverPayloadObj)

                            val sharedSecret = ephemeralKey.computeSharedSecret(clientPayload.ephemeralPubKey)
                            sharedSecrets[device.address] = sharedSecret
                            // A new link secret invalidates anything half-received.
                            reassembler.forget(device.address)

                            listener.onBeaconIdReceived(device, clientPayload.beaconId.toInt(), sharedSecret)
                        } else {
                            Log.w(TAG, "Failed to verify client handshake payload")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling client handshake: ${e.message}")
                    }
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
                AUDIO_CHAR_UUID -> {
                    LinkCodec.openPacket(sharedSecrets[device.address], value)
                        ?.let { listener.onAudioReceived(device, it) }
                    // Written without response, so there is nothing to answer.
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
                MESSAGE_CHAR_UUID -> {
                    val envelope = reassembler.accept(device.address, value, sharedSecrets[device.address])
                    if (envelope != null) {
                        listener.onMessageReceived(device, envelope)
                    }
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
                else -> {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                    }
                }
            }
        }
    }
}
