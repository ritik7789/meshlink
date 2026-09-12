package com.meshlink.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GroupDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: GroupEntity)

    @Query("SELECT * FROM groups WHERE groupId = :groupId LIMIT 1")
    suspend fun group(groupId: String): GroupEntity?

    @Query("SELECT * FROM groups WHERE isActive = 1 ORDER BY joinedAt DESC")
    suspend fun activeGroups(): List<GroupEntity>

    @Query("SELECT * FROM groups")
    suspend fun allGroups(): List<GroupEntity>

    @Query("UPDATE groups SET isActive = 0 WHERE groupId = :groupId")
    suspend fun deactivate(groupId: String)

    // ── Membership ──

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: GroupMemberEntity)

    @Query("DELETE FROM group_members WHERE groupId = :groupId")
    suspend fun clearMembers(groupId: String)

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND beaconRow = :beaconRow")
    suspend fun removeMember(groupId: String, beaconRow: Long)

    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY isAdmin DESC, name ASC")
    suspend fun members(groupId: String): List<GroupMemberEntity>

    @Query("SELECT COUNT(*) FROM group_members WHERE groupId = :groupId")
    suspend fun memberCount(groupId: String): Int

    @Query(
        "SELECT EXISTS(SELECT 1 FROM group_members WHERE groupId = :groupId " +
            "AND beaconRow = :beaconRow AND isAdmin = 1)"
    )
    suspend fun isAdmin(groupId: String, beaconRow: Long): Boolean

    @Query(
        "SELECT EXISTS(SELECT 1 FROM group_members WHERE groupId = :groupId AND beaconRow = :beaconRow)"
    )
    suspend fun isMember(groupId: String, beaconRow: Long): Boolean
}
