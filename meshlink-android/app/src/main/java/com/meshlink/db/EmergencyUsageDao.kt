package com.meshlink.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface EmergencyUsageDao {

    @Insert
    suspend fun record(usage: EmergencyUsageEntity)

    /**
     * Emergency transfers this node has started inside the window.
     *
     * Records dated in the future are counted too: moving the clock forward is
     * the obvious way to try to age out an allowance, and counting them means
     * doing so cannot buy extra sends.
     */
    @Query(
        """
        SELECT COUNT(*) FROM emergency_usage
        WHERE nodeRow = :nodeRow AND kind = 'SEND' AND (usedAt > :since OR usedAt > :now)
        """
    )
    suspend fun sendsSince(nodeRow: Long, since: Long, now: Long): Int

    /** Bulk bytes already carried across hops for one sender in the window. */
    @Query(
        """
        SELECT COALESCE(SUM(bytes), 0) FROM emergency_usage
        WHERE nodeRow = :nodeRow AND kind = 'RELAY' AND (usedAt > :since OR usedAt > :now)
        """
    )
    suspend fun relayedBytesSince(nodeRow: Long, since: Long, now: Long): Long

    /** Drops records that have fallen out of every window. */
    @Query("DELETE FROM emergency_usage WHERE usedAt < :cutoff")
    suspend fun prune(cutoff: Long)
}
