package com.meshlink.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    // ── Read Operations ──

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    suspend fun getAllMessages(): List<MessageEntity>

    @Query("""
        SELECT * FROM messages 
        WHERE (senderId = :peerId OR recipientId = :peerId) 
        ORDER BY timestamp ASC
    """)
    suspend fun getMessagesForPeer(peerId: Long): List<MessageEntity>

    @Query("""
        SELECT * FROM messages 
        WHERE isBroadcast = 1 
        ORDER BY timestamp ASC
    """)
    suspend fun getBroadcastMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Query("""
        SELECT * FROM messages
        WHERE status = 'PENDING_RELAY' AND mediaPath IS NULL
        ORDER BY timestamp ASC
    """)
    suspend fun getPendingMessages(): List<MessageEntity>

    @Query("""
        SELECT * FROM messages 
        WHERE status = 'PENDING_RELAY' AND recipientId = :peerId 
        ORDER BY timestamp ASC
    """)
    suspend fun getPendingMessagesForPeer(peerId: Long): List<MessageEntity>

    /**
     * Outbound direct messages the recipient has not acknowledged yet.
     *
     * A message handed to a neighbour is not necessarily a message delivered:
     * with flooding the neighbour may simply not be on a path to the recipient.
     * These are the ones still owed an acknowledgement.
     *
     * Attachments are excluded. Their delivery is tracked by `mediaState` through
     * the offer and transfer handshake, not by acknowledgement, so they never
     * leave the `SENT` status — and retrying one as if it were text re-sent its
     * caption, making a stray "Photo" message appear beside every picture.
     */
    @Query("""
        SELECT * FROM messages
        WHERE direction = 'OUTBOUND' AND isBroadcast = 0 AND status != 'DELIVERED'
        AND mediaPath IS NULL
        ORDER BY timestamp ASC
    """)
    suspend fun getUnacknowledgedMessages(): List<MessageEntity>

    // ── Conversation List (last message per peer) ──

    /**
     * Newest message per conversation.
     *
     * Grouped by group id when there is one, so a group is a single thread rather
     * than splitting across each member who spoke in it.
     */
    @Query("""
        SELECT * FROM messages
        WHERE messageId IN (
            SELECT messageId FROM messages
            GROUP BY COALESCE(
                groupId,
                CAST(CASE WHEN direction = 'OUTBOUND' THEN recipientId ELSE senderId END AS TEXT)
            )
            HAVING timestamp = MAX(timestamp)
        )
        ORDER BY timestamp DESC
    """)
    suspend fun getConversationList(): List<MessageEntity>

    // ── Update Operations ──

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId")
    suspend fun updateStatus(messageId: String, status: String)

    /** Media rows are keyed by their media id, which is stored in mediaPath. */
    @Query("UPDATE messages SET mediaState = :state WHERE mediaPath = :mediaId")
    suspend fun updateMediaState(mediaId: String, state: String)

    /** Retracts a message for everyone, keeping the row as a visible tombstone. */
    @Query("UPDATE messages SET isDeleted = 1, plaintext = :placeholder WHERE messageId = :messageId")
    suspend fun markDeleted(messageId: String, placeholder: String)

    @Query("SELECT * FROM messages WHERE groupId = :groupId ORDER BY timestamp ASC")
    suspend fun getMessagesForGroup(groupId: String): List<MessageEntity>

    @Query("UPDATE messages SET isStarred = :isStarred WHERE messageId = :messageId")
    suspend fun updateStarStatus(messageId: String, isStarred: Boolean)

    @Query("SELECT * FROM messages WHERE isStarred = 1 ORDER BY timestamp DESC")
    suspend fun getStarredMessages(): List<MessageEntity>

    // ── Delete Operations ──

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("""
        DELETE FROM messages 
        WHERE (senderId = :peerId OR recipientId = :peerId) 
        AND isBroadcast = 0
    """)
    suspend fun deleteConversation(peerId: Long)

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()

    // ── Count Operations ──

    /**
     * How many messages from this peer the user has not seen yet.
     *
     * Counts rows, so every item is worth exactly one regardless of what it
     * contains: when media or contact messages are added later, each one is a
     * single row and therefore a single unread, with no change needed here.
     */
    @Query("""
        SELECT COUNT(*) FROM messages
        WHERE senderId = :peerId
        AND direction = 'INBOUND'
        AND isRead = 0
    """)
    suspend fun getUnreadCount(peerId: Long): Int

    /** Total unseen messages across every conversation. */
    @Query("SELECT COUNT(*) FROM messages WHERE direction = 'INBOUND' AND isRead = 0")
    suspend fun getTotalUnreadCount(): Int

    /** Marks a whole conversation seen. Returns how many rows actually changed. */
    @Query("""
        UPDATE messages SET isRead = 1
        WHERE senderId = :peerId AND direction = 'INBOUND' AND isRead = 0
    """)
    suspend fun markConversationRead(peerId: Long): Int
}
