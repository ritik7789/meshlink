package com.meshlink

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.meshlink.db.MessageEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Starred messages, each one a route back to where it was said.
 *
 * A saved message is only useful if you can return to its context, so a row
 * opens that conversation scrolled to the message rather than showing the text
 * in isolation. The star itself is the control for removing it, which keeps the
 * action next to the thing it acts on.
 */
class StarredAdapter(
    private val nameFor: (Long) -> String,
    private val onOpen: (MessageEntity) -> Unit,
    private val onUnstar: (MessageEntity) -> Unit
) : RecyclerView.Adapter<StarredAdapter.ViewHolder>() {

    private val items = mutableListOf<MessageEntity>()
    private val stamp = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

    private val avatarColours = arrayOf(
        "#E57373", "#F06292", "#BA68C8", "#9575CD", "#7986CB",
        "#64B5F6", "#4FC3F7", "#4DD0E1", "#4DB6AC", "#81C784"
    )

    fun submit(messages: List<MessageEntity>) {
        items.clear()
        items.addAll(messages)
        notifyDataSetChanged()
    }

    fun remove(message: MessageEntity) {
        val index = items.indexOfFirst { it.messageId == message.messageId }
        if (index < 0) return
        items.removeAt(index)
        notifyItemRemoved(index)
    }

    fun isEmpty(): Boolean = items.isEmpty()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_starred, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatar: TextView = itemView.findViewById(R.id.tvStarAvatar)
        private val who: TextView = itemView.findViewById(R.id.tvStarWho)
        private val body: TextView = itemView.findViewById(R.id.tvStarBody)
        private val unstar: ImageView = itemView.findViewById(R.id.btnUnstar)

        init {
            itemView.setOnClickListener {
                items.getOrNull(adapterPosition)?.let(onOpen)
            }
            unstar.setOnClickListener {
                items.getOrNull(adapterPosition)?.let(onUnstar)
            }
        }

        fun bind(message: MessageEntity) {
            // Outbound messages are from this device, so the counterpart is the
            // recipient; inbound, the sender.
            val counterpart =
                if (message.direction == "OUTBOUND") message.recipientId else message.senderId
            val label = if (message.direction == "OUTBOUND") {
                "You → ${nameFor(counterpart)}"
            } else {
                nameFor(counterpart)
            }

            who.text = "$label  ·  ${stamp.format(Date(message.timestamp))}"
            body.text = when (message.messageType) {
                com.meshlink.db.MessageType.STICKER ->
                    Stickers.glyphFor(message.plaintext) + " Sticker"
                com.meshlink.db.MessageType.CONTACT ->
                    "👤 " + (ContactCard.parse(message.plaintext)?.name ?: "Contact")
                com.meshlink.db.MessageType.IMAGE -> "🖼️ Photo"
                com.meshlink.db.MessageType.FILE -> "📎 Attachment"
                else -> message.plaintext
            }

            avatar.text = label.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            (avatar.background as GradientDrawable).setColor(
                Color.parseColor(avatarColours[(counterpart % avatarColours.size).toInt()])
            )
        }
    }
}
