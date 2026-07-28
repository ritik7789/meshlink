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

    inner class SentMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)

        init {
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
        }
    }

    inner class ReceivedMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tvMessage)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)

        init {
            itemView.setOnLongClickListener {
                onMessageLongClick(messages[adapterPosition])
                true
            }
        }

        fun bind(message: MessageEntity) {
            tvMessage.text = message.plaintext
            tvTimestamp.text = dateFormat.format(Date(message.timestamp))
        }
    }
}
