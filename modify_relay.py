import sys

content = open('meshlink-android/app/src/main/java/com/meshlink/RelayService.kt').read()

# Update onBeaconIdReceived
content = content.replace('override fun onBeaconIdReceived(device: BluetoothDevice, beaconId: Int) {', 'override fun onBeaconIdReceived(device: BluetoothDevice, beaconId: Int, sharedSecret: ByteArray) {')
content = content.replace('peerManager.addPeer(device.address, beaconId)', 'peerManager.addPeer(device.address, beaconId, sharedSecret)')

# Update onPeerHandshakeComplete
content = content.replace('override fun onPeerHandshakeComplete(device: BluetoothDevice, remoteBeaconId: Int) {', 'override fun onPeerHandshakeComplete(device: BluetoothDevice, remoteBeaconId: Int, sharedSecret: ByteArray) {')
content = content.replace('peerManager.addPeer(device.address, remoteBeaconId)', 'peerManager.addPeer(device.address, remoteBeaconId, sharedSecret)')
content = content.replace('gattClient.sendBeaconId(device.address, localBeaconId)', '')

# Update initialization in onStartCommand
content = content.replace('gattServer = GattServer(this, bluetoothManager, this)', 'val identityKey = KeyManager(this).getIdentityKey()\n        gattServer = GattServer(this, bluetoothManager, this, identityKey)')
content = content.replace('gattClient = GattClient(this, this)', 'gattClient = GattClient(this, this, identityKey)\n        gattClient.localBeaconId = localBeaconId')

open('meshlink-android/app/src/main/java/com/meshlink/RelayService.kt', 'w').write(content)
