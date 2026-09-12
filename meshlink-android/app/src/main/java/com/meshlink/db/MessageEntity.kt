package com.meshlink.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** The kinds of message a conversation can hold. */
object MessageType {
    const val TEXT = "TEXT"
    const val STICKER = "STICKER"
    const val CONTACT = "CONTACT"
    const val IMAGE = "IMAGE"
    const val FILE = "FILE"

    /** A call that happened, rather than anything anyone said. */
    const val CALL = "CALL"
}

/** Lifecycle of an attachment's bytes, independent of message delivery. */
object MediaState {
    /** The offer arrived; the bytes have not been fetched. */
    const val OFFERED = "OFFERED"
    const val TRANSFERRING = "TRANSFERRING"
    const val READY = "READY"
    const val FAILED = "FAILED"
}

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
    val isBroadcast: Boolean = false,
    val isStarred: Boolean = false,
    /**
     * Whether the user has actually seen this message.
     *
     * Kept separate from [status], which tracks delivery (SENT / DELIVERED) and
     * says nothing about whether anyone looked at it. Conflating the two is why
     * the unread badge never cleared: a received message stays RECEIVED forever.
     */
    val isRead: Boolean = false,

    /**
     * What this message carries: TEXT, STICKER, CONTACT, IMAGE, FILE or CALL.
     *
     * Every kind is one row, which is what keeps the unread badge honest: a
     * photo or a contact card counts as exactly one unseen message, the same as
     * a line of text.
     */
    val messageType: String = MessageType.TEXT,

    /** Local file for received or sent media; null for text, stickers and contacts. */
    val mediaPath: String? = null,
    val mediaMime: String? = null,
    val mediaSize: Long = 0,
    /** OFFERED, TRANSFERRING, READY or FAILED. Meaningless for non-media rows. */
    val mediaState: String? = null,

    /** Set when this message belongs to a group rather than a one-to-one chat. */
    val groupId: String? = null,

    /**
     * Retracted for everyone. The row is kept rather than dropped so the
     * conversation still shows that something was removed, which is less
     * confusing than a silent gap.
     */
    val isDeleted: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MessageEntity
        return messageId == other.messageId
    }

    override fun hashCode(): Int = messageId.hashCode()
}
