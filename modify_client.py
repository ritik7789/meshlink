import sys
content = open('meshlink-android/app/src/main/java/com/meshlink/GattClient.kt').read()
content = content.replace('fun onPeerHandshakeComplete(device: BluetoothDevice, remoteBeaconId: Int)', 'fun onPeerHandshakeComplete(device: BluetoothDevice, remoteBeaconId: Int, sharedSecret: ByteArray)')
content = content.replace('private val listener: GattClientListener\n) {', 'private val listener: GattClientListener,\n    private val identityKey: uniffi.meshlink_core.IdentityKeyPair\n) {\n    var localBeaconId: Int = 0\n    private val clientEphemeralKeys = ConcurrentHashMap<String, uniffi.meshlink_core.EphemeralKeyPair>()\n    private val sharedSecrets = ConcurrentHashMap<String, ByteArray>()')
content = content.replace('''    fun sendMessage(deviceAddress: String, data: ByteArray) {
        val gatt = connections[deviceAddress] ?: return
        val service = gatt.getService(RelayService.MESHLINK_SERVICE_UUID.uuid) ?: return
        val char = service.getCharacteristic(GattServer.MESSAGE_CHAR_UUID) ?: return

        val mtu = 500
        val chunks = data.toList().chunked(mtu)''', '''    fun sendMessage(deviceAddress: String, data: ByteArray) {
        val gatt = connections[deviceAddress] ?: return
        val service = gatt.getService(RelayService.MESHLINK_SERVICE_UUID.uuid) ?: return
        val char = service.getCharacteristic(GattServer.MESSAGE_CHAR_UUID) ?: return
        val secret = sharedSecrets[deviceAddress] ?: return

        val encrypted = uniffi.meshlink_core.encryptTransport(secret, ByteArray(12) { 0 }, data)
        val mtu = 500
        val chunks = encrypted.toList().chunked(mtu)''')

beacon_old = '''    @SuppressLint("MissingPermission")
    fun sendBeaconId(deviceAddress: String, beaconId: Int) {
        val gatt = connections[deviceAddress] ?: return
        val service = gatt.getService(RelayService.MESHLINK_SERVICE_UUID.uuid) ?: return
        val char = service.getCharacteristic(GattServer.BEACON_CHAR_UUID) ?: return

        val data = byteArrayOf(
            (beaconId shr 24).toByte(),
            (beaconId shr 16).toByte(),
            (beaconId shr 8).toByte(),
            beaconId.toByte()
        )
        char.value = data
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt.writeCharacteristic(char)
    }'''
content = content.replace(beacon_old, '')

read_v_old = '''                    GattServer.VERSION_CHAR_UUID -> {
                        val version = characteristic.value?.firstOrNull()
                        if (version == GattServer.PROTOCOL_VERSION) {
                            val beaconChar = gatt.getService(RelayService.MESHLINK_SERVICE_UUID.uuid)
                                ?.getCharacteristic(GattServer.BEACON_CHAR_UUID)
                            if (beaconChar != null) {
                                gatt.readCharacteristic(beaconChar)
                            }
                        } else {
                            listener.onHandshakeFailed(gatt.device, "Incompatible protocol version")
                            disconnect(gatt.device.address)
                        }
                    }'''
read_v_new = '''                    GattServer.VERSION_CHAR_UUID -> {
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
                    }'''
content = content.replace(read_v_old, read_v_new)

read_b_old = '''                    GattServer.BEACON_CHAR_UUID -> {
                        val value = characteristic.value
                        if (value != null && value.size >= 4) {
                            val beaconId = (value[0].toInt() and 0xFF shl 24) or
                                    (value[1].toInt() and 0xFF shl 16) or
                                    (value[2].toInt() and 0xFF shl 8) or
                                    (value[3].toInt() and 0xFF)
                            listener.onPeerHandshakeComplete(gatt.device, beaconId)
                        }
                    }'''
read_b_new = '''                    GattServer.BEACON_CHAR_UUID -> {
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
                    }'''
content = content.replace(read_b_old, read_b_new)

add_write_new = '''        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == GattServer.BEACON_CHAR_UUID) {
                val beaconChar = gatt.getService(RelayService.MESHLINK_SERVICE_UUID.uuid)
                    ?.getCharacteristic(GattServer.BEACON_CHAR_UUID)
                if (beaconChar != null) {
                    gatt.readCharacteristic(beaconChar)
                }
            }
        }
'''
if 'override fun onCharacteristicWrite' not in content:
    content = content.replace('override fun onCharacteristicChanged', add_write_new + '\n        override fun onCharacteristicChanged')

open('meshlink-android/app/src/main/java/com/meshlink/GattClient.kt', 'w').write(content)
