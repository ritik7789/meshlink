package com.meshlink

/**
 * Helpers for moving a node's mesh address between its three representations.
 *
 * A beacon id is a 32-bit value derived from the node's identity key, so roughly
 * half of all ids have the high bit set and appear negative as a Kotlin `Int`.
 * The database stores them as `Long`, and a plain `toLong()` would sign-extend
 * those into a different number than the one the UI displays — which is why the
 * conversion is masked in one place rather than done ad hoc at each call site.
 */

/** Widens a beacon id to the unsigned value used as a database row key. */
fun beaconIdToRow(beaconId: Int): Long = beaconId.toLong() and 0xFFFFFFFFL

/** Narrows a stored row key back to the beacon id the protocol uses. */
fun rowToBeaconId(row: Long): Int = row.toInt()

/** Fallback label for a node that has not announced a username yet. */
fun defaultNodeName(beaconId: Int): String =
    "Node %04d".format(beaconIdToRow(beaconId) % 10000)
