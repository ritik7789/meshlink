package com.meshlink

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.meshlink.db.MessageEntity
import java.text.SimpleDateFormat
import java.util.*

class ConversationAdapter(
    private val onConversationClick: (String, Long) -> Unit,
    private val onConversationLongClick: (Long) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.ViewHolder>() {

    private val conversations = mutableListOf<MessageEntity>()
    private val unreadCounts = mutableMapOf<Long, Int>()
    private val onlinePeers = mutableMapOf<String, Int>()
    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun setConversations(newConversations: List<MessageEntity>) {
        conversations.clear()
        conversations.addAll(newConversations)
        notifyDataSetChanged()
    }

    fun setUnreadCount(peerId: Long, count: Int) {
        unreadCounts[peerId] = count
        notifyDataSetChanged()
    }
    
    fun setOnlinePeers(peers: Map<String, Int>) {
        onlinePeers.clear()
        onlinePeers.putAll(peers)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_conversation, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val lastMessage = conversations[position]
        val peerId = if (lastMessage.direction == "OUTBOUND") lastMessage.recipientId else lastMessage.senderId
        
        holder.bind(lastMessage, peerId)
    }

    override fun getItemCount(): Int = conversations.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvAvatar: TextView = itemView.findViewById(R.id.tvAvatar)
        private val vOnlineStatus: View = itemView.findViewById(R.id.vOnlineStatus)
        private val tvPeerName: TextView = itemView.findViewById(R.id.tvPeerName)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val tvLastMessage: TextView = itemView.findViewById(R.id.tvLastMessage)
        private val tvUnreadCount: TextView = itemView.findViewById(R.id.tvUnreadCount)

        init {
            itemView.setOnClickListener {
                val lastMessage = conversations[adapterPosition]
                val peerId = if (lastMessage.direction == "OUTBOUND") lastMessage.recipientId else lastMessage.senderId
                val address = onlinePeers.entries.find { it.value.toLong() == peerId }?.key ?: ""
                onConversationClick(address, peerId)
            }
            itemView.setOnLongClickListener {
                val lastMessage = conversations[adapterPosition]
                val peerId = if (lastMessage.direction == "OUTBOUND") lastMessage.recipientId else lastMessage.senderId
                onConversationLongClick(peerId)
                true
            }
        }

        fun bind(lastMessage: MessageEntity, peerId: Long) {
            val peerStr = String.format("%04d", peerId % 10000)
            tvPeerName.text = "Node $peerStr"
            
            val initial = peerStr.firstOrNull()?.toString() ?: "?"
            tvAvatar.text = initial
            
            // Randomish color based on peerId
            val colors = arrayOf("#E57373", "#F06292", "#BA68C8", "#9575CD", "#7986CB", "#64B5F6", "#4FC3F7", "#4DD0E1", "#4DB6AC", "#81C784")
            val colorIndex = (peerId % colors.size).toInt()
            val bg = tvAvatar.background as GradientDrawable
            bg.setColor(Color.parseColor(colors[colorIndex]))

            tvLastMessage.text = lastMessage.plaintext
            tvTimestamp.text = dateFormat.format(Date(lastMessage.timestamp))

            val isOnline = onlinePeers.values.contains(peerId.toInt())
            vOnlineStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor(if (isOnline) "#4DCA59" else "#8E9BA7")
            )

            val unread = unreadCounts[peerId] ?: 0
            if (unread > 0) {
                tvUnreadCount.visibility = View.VISIBLE
                tvUnreadCount.text = unread.toString()
            } else {
                tvUnreadCount.visibility = View.GONE
            }
        }
    }
}
