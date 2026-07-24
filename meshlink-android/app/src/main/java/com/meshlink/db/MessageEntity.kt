package com.meshlink.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val envelopeData: ByteArray,
    val timestamp: Long
)
