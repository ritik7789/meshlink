package com.meshlink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.UUID

interface GattServerListener {
    fun onPeerConnected(device: BluetoothDevice)
    fun onPeerDisconnected(device: BluetoothDevice)
    fun onBeaconIdReceived(device: BluetoothDevice, beaconId: Int, sharedSecret: ByteArray)
    fun onMessageReceived(device: BluetoothDevice, data: ByteArray)
}

class GattServer(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val listener: GattServerListener,
    private val identityKey: uniffi.meshlink_core.IdentityKeyPair
) {
    companion object {
        private const val TAG = "GattServer"
        val VERSION_CHAR_UUID = UUID.fromString("00001001-0000-1000-8000-00805F9B34FB")
        val BEACON_CHAR_UUID = UUID.fromString("00001002-0000-1000-8000-00805F9B34FB")
        val MESSAGE_CHAR_UUID = UUID.fromString("00001003-0000-1000-8000-00805F9B34FB")
        val PROTOCOL_VERSION: Byte = 0x01
    }

    private var gattServer: BluetoothGattServer? = null
    private val connectedDevices = mutableSetOf<BluetoothDevice>()
    private val messageBuffer = mutableMapOf<String, ByteArray>()
    private val serverPayloads = mutableMapOf<String, ByteArray>()
    private val sharedSecrets = mutableMapOf<String, ByteArray>()
    
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
        Log.i(TAG, "GATT Server stopped")
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

        service.addCharacteristic(versionChar)
        service.addCharacteristic(beaconChar)
        service.addCharacteristic(messageChar)

        gattServer?.addService(service)
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Peer connected to server: ${device.address}")
                connectedDevices.add(device)
                listener.onPeerConnected(device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Peer disconnected from server: ${device.address}")
                connectedDevices.remove(device)
                listener.onPeerDisconnected(device)
            }
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
                    val valueToSend = if (offset < payloadBytes.size) payloadBytes.copyOfRange(offset, payloadBytes.size) else ByteArray(0)
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
                MESSAGE_CHAR_UUID -> {
                    if (value.size >= 2) {
                        val chunkIndex = value[0].toInt() and 0xFF
                        val totalChunks = value[1].toInt() and 0xFF
                        val payload = value.copyOfRange(2, value.size)
                        
                        val buffer = messageBuffer[device.address] ?: ByteArray(0)
                        val newBuffer = buffer + payload
                        
                        if (chunkIndex == totalChunks - 1) {
                            val secret = sharedSecrets[device.address]
                            if (secret != null && newBuffer.size >= 12) {
                                try {
                                    val nonce = newBuffer.copyOfRange(0, 12)
                                    val ciphertext = newBuffer.copyOfRange(12, newBuffer.size)
                                    val decrypted = uniffi.meshlink_core.decryptTransport(secret, nonce, ciphertext)
                                    if (decrypted != null) {
                                        listener.onMessageReceived(device, decrypted)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to decrypt incoming message: ${e.message}")
                                }
                            }
                            messageBuffer.remove(device.address)
                        } else {
                            messageBuffer[device.address] = newBuffer
                        }
                    } else {
                        val secret = sharedSecrets[device.address]
                        if (secret != null && value.size >= 12) {
                            try {
                                val nonce = value.copyOfRange(0, 12)
                                val ciphertext = value.copyOfRange(12, value.size)
                                val decrypted = uniffi.meshlink_core.decryptTransport(secret, nonce, ciphertext)
                                if (decrypted != null) {
                                    listener.onMessageReceived(device, decrypted)
                                }
                            } catch (e: Exception) {}
                        }
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
