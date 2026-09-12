package com.meshlink.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BlockedNodeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun block(node: BlockedNodeEntity)

    @Query("DELETE FROM blocked_nodes WHERE beaconRow = :beaconRow")
    suspend fun unblock(beaconRow: Long)

    @Query("SELECT * FROM blocked_nodes ORDER BY blockedAt DESC")
    suspend fun all(): List<BlockedNodeEntity>

    /** Just the ids, for the relay service's in-memory filter. */
    @Query("SELECT beaconRow FROM blocked_nodes")
    suspend fun blockedRows(): List<Long>

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_nodes WHERE beaconRow = :beaconRow)")
    suspend fun isBlocked(beaconRow: Long): Boolean
}
