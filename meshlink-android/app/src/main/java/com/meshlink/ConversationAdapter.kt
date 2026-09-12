package com.meshlink

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.meshlink.db.MessageEntity
import com.meshlink.db.MessageType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Conversation list. Peers are identified by their mesh beacon id rather than a
 * BLE address, so a conversation stays addressable whether the node is a direct
 * neighbour or several relays away.
 */
class ConversationAdapter(
    private val onConversationClick: (Long) -> Unit,
    private val onGroupClick: (String) -> Unit,
    private val onConversationLongClick: (Long) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.ViewHolder>() {

    private val conversations = mutableListOf<MessageEntity>()
    private val unreadCounts = mutableMapOf<Long, Int>()

    /** Reachable node id to its distance in hops. Absent means unreachable. */
    private val reachable = mutableMapOf<Long, Int>()

    /** Group id to display name, so group threads are labelled properly. */
    private val groupNames = mutableMapOf<String, String>()

    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormatDate = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    private fun getRelativeDate(time: Long): String {
        val calTime = Calendar.getInstance().apply { timeInMillis = time }
        val calToday = Calendar.getInstance()
        val calYesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

        return when {
            calTime.get(Calendar.YEAR) == calToday.get(Calendar.YEAR) &&
                calTime.get(Calendar.DAY_OF_YEAR) == calToday.get(Calendar.DAY_OF_YEAR) ->
                dateFormat.format(Date(time))

            calTime.get(Calendar.YEAR) == calYesterday.get(Calendar.YEAR) &&
                calTime.get(Calendar.DAY_OF_YEAR) == calYesterday.get(Calendar.DAY_OF_YEAR) ->
                "Yesterday"

            else -> dateFormatDate.format(Date(time))
        }
    }

    fun setConversations(newConversations: List<MessageEntity>) {
        conversations.clear()
        conversations.addAll(newConversations)
        notifyDataSetChanged()
    }

    fun setUnreadCount(peerId: Long, count: Int) {
        unreadCounts[peerId] = count
        notifyDataSetChanged()
    }

    fun setGroupNames(names: Map<String, String>) {
        groupNames.clear()
        groupNames.putAll(names)
        notifyDataSetChanged()
    }

    fun setReachableNodes(nodes: Map<Long, Int>) {
        reachable.clear()
        reachable.putAll(nodes)
        notifyDataSetChanged()
    }

    private fun previewOf(message: MessageEntity): String = when {
        message.isDeleted -> "🚫 " + message.plaintext
        else -> previewByType(message)
    }

    private fun previewByType(message: MessageEntity): String = when (message.messageType) {
        MessageType.STICKER -> Stickers.glyphFor(message.plaintext) + " Sticker"
        MessageType.CONTACT -> "👤 " + (ContactCard.parse(message.plaintext)?.name ?: "Contact")
        MessageType.IMAGE -> "🖼️ Photo"
        MessageType.FILE -> "📎 " + (message.mediaMime ?: "File")
        else -> message.plaintext
    }

    /** Group threads are keyed by their group, not by whoever spoke last. */
    fun keyOf(message: MessageEntity): String =
        message.groupId ?: peerIdOf(message).toString()

    private fun peerIdOf(message: MessageEntity): Long =
        if (message.direction == "OUTBOUND") message.recipientId else message.senderId

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val lastMessage = conversations[position]
        holder.bind(lastMessage, peerIdOf(lastMessage))
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
                conversations.getOrNull(adapterPosition)?.let { message ->
                    message.groupId?.let { onGroupClick(it) } ?: onConversationClick(peerIdOf(message))
                }
            }
            itemView.setOnLongClickListener {
                conversations.getOrNull(adapterPosition)?.let { message ->
                    if (message.groupId == null) onConversationLongClick(peerIdOf(message))
                }
                true
            }
        }

        private fun bindGroup(lastMessage: MessageEntity, groupId: String) {
            val name = groupNames[groupId] ?: "Group"
            tvPeerName.text = name
            tvAvatar.text = "\uD83D\uDC65"
            (tvAvatar.background as GradientDrawable).setColor(Color.parseColor("#00A884"))
            tvLastMessage.text = previewOf(lastMessage)
            tvTimestamp.text = getRelativeDate(lastMessage.timestamp)
            vOnlineStatus.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#4DCA59"))
            tvUnreadCount.visibility = View.GONE
        }

        fun bind(lastMessage: MessageEntity, peerId: Long) {
            val groupId = lastMessage.groupId
            if (groupId != null) {
                bindGroup(lastMessage, groupId)
                return
            }
            val isBroadcastThread = lastMessage.isBroadcast && peerId == 0L
            val prefs = itemView.context.getSharedPreferences(
                RelayService.PREFS_NAME, android.content.Context.MODE_PRIVATE
            )
            val username = if (isBroadcastThread) {
                "Broadcast"
            } else {
                prefs.getString(peerNameKeyForRow(peerId), null)?.takeIf { it.isNotBlank() }
                    ?: defaultNodeName(rowToBeaconId(peerId))
            }

            val hops = reachable[peerId]
            tvPeerName.text = when {
                isBroadcastThread -> username
                // Surfacing the distance makes it obvious when a peer is being
                // reached through relays rather than directly.
                hops != null && hops > 1 -> "$username · $hops hops"
                else -> username
            }

            tvAvatar.text = username.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

            val colors = arrayOf(
                "#E57373", "#F06292", "#BA68C8", "#9575CD", "#7986CB",
                "#64B5F6", "#4FC3F7", "#4DD0E1", "#4DB6AC", "#81C784"
            )
            val colorIndex = (peerId % colors.size).toInt()
            (tvAvatar.background as GradientDrawable).setColor(Color.parseColor(colors[colorIndex]))

            // The list shows a human summary, never the wire payload: a sticker
            // reference or raw vCard markup would be meaningless here.
            tvLastMessage.text = previewOf(lastMessage)
            tvTimestamp.text = getRelativeDate(lastMessage.timestamp)

            val isOnline = isBroadcastThread || hops != null
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
