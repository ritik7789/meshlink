package com.meshlink

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.meshlink.db.MessageEntity
import com.meshlink.db.MediaState
import com.meshlink.db.MessageType
import java.text.SimpleDateFormat
import java.util.*

class ChatAdapter(
    /** Tapping an attachment fetches it, or opens it once it has arrived. */
    private val onAttachmentClick: (MessageEntity) -> Unit = {},
    private val onMessageLongClick: (MessageEntity) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val messages = mutableListOf<MessageEntity>()
    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    companion object {
        private const val TYPE_SENT = 1
        private const val TYPE_RECEIVED = 2
    }

    fun setMessages(newMessages: List<MessageEntity>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].direction == "OUTBOUND") TYPE_SENT else TYPE_RECEIVED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SENT) {
            val view = inflater.inflate(R.layout.item_chat_message_sent, parent, false)
            SentMessageViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_chat_message_received, parent, false)
            ReceivedMessageViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        if (holder is SentMessageViewHolder) {
            holder.bind(message)
        } else if (holder is ReceivedMessageViewHolder) {
            holder.bind(message)
        }
    }

    override fun getItemCount(): Int = messages.size

    private val selectedMessageIds = mutableSetOf<String>()
    var isSelectionMode = false

    fun toggleSelection(messageId: String) {
        if (selectedMessageIds.contains(messageId)) {
            selectedMessageIds.remove(messageId)
        } else {
            selectedMessageIds.add(messageId)
        }
        if (selectedMessageIds.isEmpty()) {
            isSelectionMode = false
        }
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedMessageIds.clear()
        isSelectionMode = false
        notifyDataSetChanged()
    }

    fun getSelectedMessages(): List<MessageEntity> {
        return messages.filter { selectedMessageIds.contains(it.messageId) }
    }

    private val dateFormatDate = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())

    private fun isSameDay(time1: Long, time2: Long): Boolean {
        val cal1 = Calendar.getInstance().apply { timeInMillis = time1 }
        val cal2 = Calendar.getInstance().apply { timeInMillis = time2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    private fun getRelativeDate(time: Long): String {
        val calTime = Calendar.getInstance().apply { timeInMillis = time }
        val calToday = Calendar.getInstance()
        val calYesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

        return when {
            calTime.get(Calendar.YEAR) == calToday.get(Calendar.YEAR) &&
            calTime.get(Calendar.DAY_OF_YEAR) == calToday.get(Calendar.DAY_OF_YEAR) -> "Today"
            
            calTime.get(Calendar.YEAR) == calYesterday.get(Calendar.YEAR) &&
            calTime.get(Calendar.DAY_OF_YEAR) == calYesterday.get(Calendar.DAY_OF_YEAR) -> "Yesterday"
            
            else -> dateFormatDate.format(Date(time))
        }
    }

    inner class SentMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        private val ivPreview: android.widget.ImageView = itemView.findViewById(R.id.ivMediaPreview)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        private val tvDateHeader: TextView = itemView.findViewById(R.id.tvDateHeader)

        init {
            itemView.setOnClickListener {
                val message = messages.getOrNull(adapterPosition) ?: return@setOnClickListener
                when {
                    isSelectionMode -> onMessageLongClick(message)
                    message.mediaPath != null -> onAttachmentClick(message)
                }
            }
            itemView.setOnLongClickListener {
                onMessageLongClick(messages[adapterPosition])
                true
            }
        }

        fun bind(message: MessageEntity) {
            bindMessageBody(tvMessage, ivPreview, message)
            tvTimestamp.text = dateFormat.format(Date(message.timestamp))
            tvStatus.text = when (message.status) {
                "SENT" -> "✓"
                "DELIVERED" -> "✓✓"
                "PENDING_RELAY" -> "◷"
                "FAILED" -> "!"
                else -> ""
            }
            if (message.isStarred) tvStatus.text = "★ " + tvStatus.text
            
            itemView.setBackgroundColor(if (selectedMessageIds.contains(message.messageId)) 
                android.graphics.Color.parseColor("#3300A884") else android.graphics.Color.TRANSPARENT)

            // Date Header Logic
            if (adapterPosition == 0 || !isSameDay(message.timestamp, messages[adapterPosition - 1].timestamp)) {
                tvDateHeader.visibility = View.VISIBLE
                tvDateHeader.text = getRelativeDate(message.timestamp)
            } else {
                tvDateHeader.visibility = View.GONE
            }
        }
    }

    inner class ReceivedMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        private val ivPreview: android.widget.ImageView = itemView.findViewById(R.id.ivMediaPreview)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val tvDateHeader: TextView = itemView.findViewById(R.id.tvDateHeader)

        init {
            itemView.setOnClickListener {
                val message = messages.getOrNull(adapterPosition) ?: return@setOnClickListener
                when {
                    isSelectionMode -> onMessageLongClick(message)
                    message.mediaPath != null -> onAttachmentClick(message)
                }
            }
            itemView.setOnLongClickListener {
                onMessageLongClick(messages[adapterPosition])
                true
            }
        }

        fun bind(message: MessageEntity) {
            bindMessageBody(tvMessage, ivPreview, message)
            tvTimestamp.text = dateFormat.format(Date(message.timestamp))
            if (message.isStarred) tvTimestamp.text = "★ " + tvTimestamp.text
            
            itemView.setBackgroundColor(if (selectedMessageIds.contains(message.messageId)) 
                android.graphics.Color.parseColor("#3300A884") else android.graphics.Color.TRANSPARENT)

            // Date Header Logic
            if (adapterPosition == 0 || !isSameDay(message.timestamp, messages[adapterPosition - 1].timestamp)) {
                tvDateHeader.visibility = View.VISIBLE
                tvDateHeader.text = getRelativeDate(message.timestamp)
            } else {
                tvDateHeader.visibility = View.GONE
            }
        }
    }
}

/**
 * Renders a message body according to its kind.
 *
 * Stickers are shown as a large glyph with no bubble text styling, and contacts
 * as a readable card rather than raw vCard markup - the wire format should never
 * be what the user reads.
 */
internal fun bindMessageBody(
    view: TextView,
    preview: android.widget.ImageView,
    message: MessageEntity
) {
    preview.setImageDrawable(null)
    preview.visibility = View.GONE

    if (message.isDeleted) {
        // The row is kept so the gap is visible; the original content is gone.
        view.text = message.plaintext
        view.textSize = 15f
        view.alpha = 0.6f
        return
    }
    view.alpha = 1f

    when (message.messageType) {
        MessageType.STICKER -> {
            view.text = Stickers.glyphFor(message.plaintext)
            view.textSize = 48f
        }
        MessageType.CONTACT -> {
            val card = ContactCard.parse(message.plaintext)
            view.text = if (card == null) {
                "👤 Contact"
            } else {
                "👤 ${card.name}" + (card.phone?.let { "\n$it" } ?: "")
            }
            view.textSize = 16f
        }
        MessageType.IMAGE, MessageType.FILE -> {
            val isImage = message.messageType == MessageType.IMAGE
            val arrived = message.mediaState == MediaState.READY
            val thumbnail = if (isImage && arrived) {
                loadThumbnail(preview.context, message.mediaPath)
            } else {
                null
            }

            if (thumbnail != null) {
                preview.setImageBitmap(thumbnail)
                preview.visibility = View.VISIBLE
            }

            // Once the picture itself is on screen, repeating its name above the
            // size is noise; the label shrinks to just what the image cannot say.
            view.text = when {
                thumbnail != null -> formatSize(message.mediaSize)
                else -> {
                    val icon = if (isImage) "🖼️" else "📎"
                    val state = when (message.mediaState) {
                        MediaState.OFFERED -> "Tap to download · ${formatSize(message.mediaSize)}"
                        MediaState.TRANSFERRING -> "Downloading…"
                        MediaState.FAILED -> "Failed · tap to retry"
                        else -> formatSize(message.mediaSize)
                    }
                    "$icon ${message.plaintext}\n$state"
                }
            }
            view.textSize = if (thumbnail != null) 11f else 16f
        }
        else -> {
            view.text = message.plaintext
            view.textSize = 16f
        }
    }
}

/**
 * Decodes a downscaled thumbnail for the bubble.
 *
 * Sampled down on decode rather than loaded whole: a list that decodes every
 * attachment at full size scrolls badly and can exhaust memory on older phones.
 */
private fun loadThumbnail(context: android.content.Context, mediaId: String?): android.graphics.Bitmap? {
    if (mediaId == null) return null
    return runCatching {
        val file = MediaStore.fileFor(context, mediaId)
        if (!file.exists()) return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null

        val options = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = generateSequence(1) { it * 2 }.first { longest / it <= 512 }
        }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
    }.getOrNull()
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
