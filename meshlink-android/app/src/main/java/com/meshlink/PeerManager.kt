package com.meshlink

import java.util.concurrent.ConcurrentHashMap

/**
 * A peer we currently hold a BLE link with. These are the only nodes we can
 * physically transmit to, and therefore the set a flood is sent across.
 */
data class PeerInfo(
    val address: String,
    var beaconId: Int,
    var lastSeen: Long,
    var isConnected: Boolean,
    var sharedSecret: ByteArray? = null
)

/**
 * A node somewhere in the mesh, learned from presence announcements. It may be a
 * direct neighbour ([hops] == 1) or several relays away.
 */
data class MeshNode(
    val beaconId: Int,
    var identityKey: ByteArray? = null,
    var staticKey: ByteArray? = null,
    var name: String? = null,
    var hops: Int = 1,
    var lastSeen: Long = System.currentTimeMillis()
)

/**
 * Tracks mesh membership at two levels.
 *
 * Neighbours are link state: who we can transmit to right now. The roster is
 * network state: every node presence gossip says is reachable, direct or not.
 * Keeping them apart is what lets a node address a peer three hops away that it
 * has no BLE connection to.
 */
class PeerManager {

    companion object {
        /** A roster entry not refreshed within this window is treated as gone. */
        const val ROSTER_ENTRY_TTL_MS = 90_000L

        /** How often a node may redo the (expensive) cryptographic handshake. */
        private const val HANDSHAKE_THROTTLE_MS = 300_000L
    }

    private val peers = ConcurrentHashMap<String, PeerInfo>()
    private val handshakeCache = ConcurrentHashMap<Int, Long>()
    private val roster = ConcurrentHashMap<Int, MeshNode>()

    /**
     * Encryption keys of every node ever heard from, kept separately from the
     * roster and deliberately never expired.
     *
     * Reachability is temporary; a node's identity is not. Holding the keys apart
     * is what lets a message be composed for a peer that is currently out of
     * range or has its radio off — previously the key vanished with the roster
     * entry and the message could not even be sealed.
     */
    private val knownStaticKeys = ConcurrentHashMap<Int, ByteArray>()

    /** Identity keys, kept for the same reason and used to verify signatures. */
    private val knownIdentityKeys = ConcurrentHashMap<Int, ByteArray>()

    // ── Neighbours (direct BLE links) ──

    /**
     * Records or refreshes a direct link.
     *
     * This must stay outside the [shouldHandshake] throttle: BLE links drop and
     * re-establish constantly, and if the peer's connection state were only
     * updated when a fresh handshake was allowed, a peer reconnecting inside the
     * throttle window would stay marked offline — invisible to the UI and
     * skipped as a relay target.
     */
    fun addPeer(address: String, beaconId: Int, sharedSecret: ByteArray? = null) {
        val now = System.currentTimeMillis()
        val existing = peers[address]
        if (existing != null) {
            existing.lastSeen = now
            existing.isConnected = true
            existing.beaconId = beaconId
            if (sharedSecret != null) existing.sharedSecret = sharedSecret
        } else {
            peers[address] = PeerInfo(address, beaconId, now, true, sharedSecret)
        }
        // A neighbour is by definition one hop away and reachable now.
        recordNode(beaconId, hops = 1)
    }

    fun removePeer(address: String) {
        peers[address]?.isConnected = false
    }

    fun getPeer(address: String): PeerInfo? = peers[address]

    fun getConnectedPeers(): List<PeerInfo> = peers.values.filter { it.isConnected }

    /** Address of a connected neighbour with this beacon id, if we have one. */
    fun addressForBeacon(beaconId: Int): String? =
        peers.values.firstOrNull { it.isConnected && it.beaconId == beaconId }?.address

    /**
     * Throttles only the cryptographic handshake, not peer bookkeeping.
     * Redoing X25519 on every BLE reconnect is wasteful, but the handshake must
     * be allowed again if we have lost the link secret for that peer.
     */
    fun shouldHandshake(beaconId: Int): Boolean {
        val lastSeen = handshakeCache[beaconId] ?: return true
        return (System.currentTimeMillis() - lastSeen) > HANDSHAKE_THROTTLE_MS
    }

    fun recordHandshake(beaconId: Int) {
        handshakeCache[beaconId] = System.currentTimeMillis()
    }

    // ── Mesh roster (presence gossip) ──

    /**
     * Merges a presence announcement. The lowest hop count seen inside the
     * entry's lifetime wins, so a node reachable both directly and via a relay
     * is reported at its shortest distance.
     */
    fun recordNode(
        beaconId: Int,
        identityKey: ByteArray? = null,
        staticKey: ByteArray? = null,
        name: String? = null,
        hops: Int = 1
    ) {
        val now = System.currentTimeMillis()
        val existing = roster[beaconId]
        if (staticKey != null) knownStaticKeys[beaconId] = staticKey
        if (identityKey != null) knownIdentityKeys[beaconId] = identityKey
        if (existing == null) {
            roster[beaconId] = MeshNode(beaconId, identityKey, staticKey, name, hops, now)
            return
        }
        // An entry that had already aged out restarts its hop measurement rather
        // than inheriting a stale (possibly shorter) distance.
        if (staticKey != null) knownStaticKeys[beaconId] = staticKey
        val expired = now - existing.lastSeen > ROSTER_ENTRY_TTL_MS
        existing.hops = if (expired) hops else minOf(existing.hops, hops)
        existing.lastSeen = now
        if (identityKey != null) existing.identityKey = identityKey
        if (staticKey != null) existing.staticKey = staticKey
        if (name != null) existing.name = name
    }

    /** Records a node's long-lived encryption key, independent of reachability. */
    fun rememberStaticKey(beaconId: Int, staticKey: ByteArray) {
        knownStaticKeys[beaconId] = staticKey
    }

    fun rememberIdentityKey(beaconId: Int, identityKey: ByteArray) {
        knownIdentityKeys[beaconId] = identityKey
    }

    /** Verification key for [beaconId], or null if this node has never seen it. */
    fun identityKeyFor(beaconId: Int): ByteArray? =
        knownIdentityKeys[beaconId] ?: roster[beaconId]?.identityKey

    /**
     * Whether this node has ever learned who [beaconId] is. Carrying a message
     * is only worthwhile for a destination we have some reason to expect back.
     */
    fun isKnown(beaconId: Int): Boolean =
        knownIdentityKeys.containsKey(beaconId) ||
            knownStaticKeys.containsKey(beaconId) ||
            roster.containsKey(beaconId)

    /**
     * The key needed to seal a message for [beaconId], whether or not that node
     * is currently reachable.
     */
    fun staticKeyFor(beaconId: Int): ByteArray? =
        knownStaticKeys[beaconId] ?: roster[beaconId]?.staticKey

    fun getNode(beaconId: Int): MeshNode? = roster[beaconId]

    /** Every node currently believed reachable, nearest first. */
    fun getReachableNodes(): List<MeshNode> {
        val now = System.currentTimeMillis()
        return roster.values
            .filter { now - it.lastSeen <= ROSTER_ENTRY_TTL_MS }
            .sortedWith(compareBy({ it.hops }, { it.beaconId }))
    }

    fun isReachable(beaconId: Int): Boolean {
        val node = roster[beaconId] ?: return false
        return System.currentTimeMillis() - node.lastSeen <= ROSTER_ENTRY_TTL_MS
    }

    /**
     * Drops roster entries and handshake records that have aged out. Roster
     * entries are removed rather than just hidden so that a node returning after
     * a long absence is re-measured instead of reusing an old hop count.
     */
    /**
     * Forgets every neighbour and roster entry. Used when the radio is turned
     * off: nothing is reachable any more, and stale entries would otherwise keep
     * showing peers as online until they aged out.
     */
    fun reset() {
        peers.clear()
        roster.clear()
        handshakeCache.clear()
        // knownStaticKeys survives deliberately: the radio being off says nothing
        // about who those nodes are, and queued messages still need to be sealed.
    }

    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        handshakeCache.entries.removeAll { (now - it.value) > HANDSHAKE_THROTTLE_MS }
        roster.entries.removeAll { (now - it.value.lastSeen) > ROSTER_ENTRY_TTL_MS * 4 }
    }
}
