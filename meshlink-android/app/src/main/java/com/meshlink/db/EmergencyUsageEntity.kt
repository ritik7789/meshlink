package com.meshlink.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** What an emergency record accounts for. */
object EmergencyKind {
    /** This device spent one of its own weekly allowances. */
    const val SEND = "SEND"

    /** This device carried bulk across hops for someone else. */
    const val RELAY = "RELAY"
}

/**
 * One use of the emergency allowance, which is what lets bulk data cross hops.
 *
 * Two independent ledgers share this table. A node records its own SENDs to
 * enforce the weekly limit on itself, and records RELAY bytes per sender so a
 * modified client that ignores its own limit still cannot make other people's
 * devices carry unlimited data for it.
 */
@Entity(tableName = "emergency_usage")
data class EmergencyUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Whose allowance this is: the originating node, never the relay. */
    val nodeRow: Long,
    val kind: String,
    val bytes: Long,
    val usedAt: Long
)
