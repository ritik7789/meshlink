package com.meshlink

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.meshlink.db.MessageEntity
import java.text.SimpleDateFormat
import java.util.*

class ChatAdapter(
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
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        private val tvDateHeader: TextView = itemView.findViewById(R.id.tvDateHeader)

        init {
            itemView.setOnClickListener {
                if (isSelectionMode) {
                    onMessageLongClick(messages[adapterPosition])
                }
            }
            itemView.setOnLongClickListener {
                onMessageLongClick(messages[adapterPosition])
                true
            }
        }

        fun bind(message: MessageEntity) {
            tvMessage.text = message.plaintext
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
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val tvDateHeader: TextView = itemView.findViewById(R.id.tvDateHeader)

        init {
            itemView.setOnClickListener {
                if (isSelectionMode) {
                    onMessageLongClick(messages[adapterPosition])
                }
            }
            itemView.setOnLongClickListener {
                onMessageLongClick(messages[adapterPosition])
                true
            }
        }

        fun bind(message: MessageEntity) {
            tvMessage.text = message.plaintext
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
