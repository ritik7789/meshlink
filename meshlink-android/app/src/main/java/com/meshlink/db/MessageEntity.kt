package com.meshlink.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val senderId: Long,
    val recipientId: Long,
    val plaintext: String,
    val envelopeData: ByteArray,
    val timestamp: Long,
    val direction: String,  // "INBOUND" or "OUTBOUND"
    val status: String,     // "SENT", "DELIVERED", "PENDING_RELAY", "FAILED", "RECEIVED"
    val isBroadcast: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MessageEntity
        return messageId == other.messageId
    }

    override fun hashCode(): Int = messageId.hashCode()
}
