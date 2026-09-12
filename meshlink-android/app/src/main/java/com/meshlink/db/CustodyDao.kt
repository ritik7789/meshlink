package com.meshlink.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CustodyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: CustodyEntity)

    /** Everything being carried for a node that has just become reachable. */
    @Query("SELECT * FROM custody WHERE recipientId = :recipientId ORDER BY receivedAt ASC")
    suspend fun forRecipient(recipientId: Long): List<CustodyEntity>

    @Query("SELECT COUNT(*) FROM custody")
    suspend fun count(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM custody WHERE messageId = :messageId)")
    suspend fun contains(messageId: String): Boolean

    /** Called when an acknowledgement for this message passes through. */
    @Query("DELETE FROM custody WHERE messageId = :messageId")
    suspend fun release(messageId: String)

    @Query("DELETE FROM custody WHERE receivedAt < :cutoff")
    suspend fun releaseExpired(cutoff: Long)

    /**
     * Drops the [excess] least valuable entries: ordinary traffic before SOS, and
     * oldest first within each, so a full store sheds what matters least.
     */
    @Query(
        """
        DELETE FROM custody WHERE messageId IN (
            SELECT messageId FROM custody ORDER BY isSos ASC, receivedAt ASC LIMIT :excess
        )
        """
    )
    suspend fun evictLeastValuable(excess: Int)
}
