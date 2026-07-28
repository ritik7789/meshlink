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
        WHERE status = 'PENDING_RELAY' 
        ORDER BY timestamp ASC
    """)
    suspend fun getPendingMessages(): List<MessageEntity>

    @Query("""
        SELECT * FROM messages 
        WHERE status = 'PENDING_RELAY' AND recipientId = :peerId 
        ORDER BY timestamp ASC
    """)
    suspend fun getPendingMessagesForPeer(peerId: Long): List<MessageEntity>

    // ── Conversation List (last message per peer) ──

    @Query("""
        SELECT * FROM messages 
        WHERE messageId IN (
            SELECT messageId FROM messages 
            WHERE isBroadcast = 0
            GROUP BY CASE WHEN direction = 'OUTBOUND' THEN recipientId ELSE senderId END 
            HAVING timestamp = MAX(timestamp)
        )
        ORDER BY timestamp DESC
    """)
    suspend fun getConversationList(): List<MessageEntity>

    // ── Update Operations ──

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId")
    suspend fun updateStatus(messageId: String, status: String)

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

    @Query("""
        SELECT COUNT(*) FROM messages 
        WHERE senderId = :peerId 
        AND direction = 'INBOUND' 
        AND status = 'RECEIVED'
    """)
    suspend fun getUnreadCount(peerId: Long): Int
}
