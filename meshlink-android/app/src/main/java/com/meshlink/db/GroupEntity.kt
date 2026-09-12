package com.meshlink.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A group this device belongs to.
 *
 * [groupKey] is the shared symmetric key every member holds. One flooded,
 * group-key-encrypted message therefore reaches the whole group for the cost of
 * a single message, and nodes outside it cannot open one. The key is rotated
 * whenever a member is removed, so removal is a real boundary rather than a
 * convention: [keyVersion] says which generation this device holds.
 */
@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val groupId: String,
    val name: String,
    val groupKey: ByteArray,
    val keyVersion: Int,
    /**
     * Membership generation. Roster changes are signed by an admin and carry an
     * incrementing version; on reconnect after a split the highest version wins.
     */
    val rosterVersion: Int,
    val createdBy: Long,
    val joinedAt: Long,
    /** False once this device leaves or is removed; history is kept either way. */
    val isActive: Boolean = true
) {
    override fun equals(other: Any?): Boolean =
        other is GroupEntity && other.groupId == groupId

    override fun hashCode(): Int = groupId.hashCode()
}

/** One membership row. Composite key, because a node appears once per group. */
@Entity(tableName = "group_members", primaryKeys = ["groupId", "beaconRow"])
data class GroupMemberEntity(
    val groupId: String,
    val beaconRow: Long,
    val name: String?,
    val isAdmin: Boolean = false
)
