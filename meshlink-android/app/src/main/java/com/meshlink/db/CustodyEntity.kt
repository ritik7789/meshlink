package com.meshlink.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A message this node is carrying on someone else's behalf.
 *
 * Kept in its own table rather than alongside `messages`: this is ciphertext
 * addressed to a third party that this device cannot read and must never show
 * in a conversation. It exists only so that a sender and a recipient who are
 * never on the mesh at the same moment can still reach each other.
 */
@Entity(tableName = "custody")
data class CustodyEntity(
    @PrimaryKey val messageId: String,
    /** Final destination, so the entry can be offered the moment that node appears. */
    val recipientId: Long,
    val envelopeData: ByteArray,
    val receivedAt: Long,
    /** SOS traffic is evicted last when the store is full. */
    val isSos: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CustodyEntity
        return messageId == other.messageId
    }

    override fun hashCode(): Int = messageId.hashCode()
}
