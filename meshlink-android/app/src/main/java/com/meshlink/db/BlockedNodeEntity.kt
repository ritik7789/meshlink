package com.meshlink.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A node whose messages this device refuses to deliver locally.
 *
 * Blocking is a delivery filter, never a routing one: a blocked node's traffic
 * is still relayed and carried for everyone else, and its presence is still
 * accepted so the mesh keeps working. One person blocking someone must not
 * partition the network for everybody else.
 */
@Entity(tableName = "blocked_nodes")
data class BlockedNodeEntity(
    /** Beacon id in its unsigned row form, matching `messages.senderId`. */
    @PrimaryKey val beaconRow: Long,
    /** Name at the time of blocking, so the unblock list is readable later. */
    val name: String?,
    val blockedAt: Long
)
