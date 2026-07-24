package com.meshlink

import java.util.concurrent.ConcurrentHashMap

data class PeerInfo(val address: String, val beaconId: Int, var lastSeen: Long, var isConnected: Boolean, var sharedSecret: ByteArray? = null)

class PeerManager {
    private val peers = ConcurrentHashMap<String, PeerInfo>()
    private val handshakeCache = ConcurrentHashMap<Int, Long>()

    fun addPeer(address: String, beaconId: Int, sharedSecret: ByteArray? = null) {
        val now = System.currentTimeMillis()
        if (peers.containsKey(address)) {
            val p = peers[address]!!
            p.lastSeen = now
            p.isConnected = true
            if (sharedSecret != null) p.sharedSecret = sharedSecret
        } else {
            peers[address] = PeerInfo(address, beaconId, now, true, sharedSecret)
        }
    }

    fun removePeer(address: String) {
        peers[address]?.isConnected = false
    }

    fun getPeer(address: String): PeerInfo? {
        return peers[address]
    }

    fun getConnectedPeers(): List<PeerInfo> {
        return peers.values.filter { it.isConnected }
    }

    fun shouldHandshake(beaconId: Int): Boolean {
        val now = System.currentTimeMillis()
        val lastSeen = handshakeCache[beaconId] ?: return true
        // FR-2.1.4: limit handshakes to once per 5 minutes (300,000 ms)
        return (now - lastSeen) > 300_000
    }

    fun recordHandshake(beaconId: Int) {
        handshakeCache[beaconId] = System.currentTimeMillis()
    }

    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val expired = handshakeCache.filter { (now - it.value) > 300_000 }.keys
        expired.forEach { handshakeCache.remove(it) }
    }
}
