package com.meshlink

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.meshlink.db.BlockedNodeEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Blocked contacts, each with the action that undoes it sitting on the row. */
class BlockedAdapter(
    private val onUnblock: (BlockedNodeEntity) -> Unit
) : RecyclerView.Adapter<BlockedAdapter.ViewHolder>() {

    private val items = mutableListOf<BlockedNodeEntity>()
    private val stamp = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

    private val avatarColours = arrayOf(
        "#E57373", "#F06292", "#BA68C8", "#9575CD", "#7986CB",
        "#64B5F6", "#4FC3F7", "#4DD0E1", "#4DB6AC", "#81C784"
    )

    fun submit(nodes: List<BlockedNodeEntity>) {
        items.clear()
        items.addAll(nodes)
        notifyDataSetChanged()
    }

    fun remove(node: BlockedNodeEntity) {
        val index = items.indexOfFirst { it.beaconRow == node.beaconRow }
        if (index < 0) return
        items.removeAt(index)
        notifyItemRemoved(index)
    }

    fun isEmpty(): Boolean = items.isEmpty()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_blocked, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatar: TextView = itemView.findViewById(R.id.tvBlockedAvatar)
        private val name: TextView = itemView.findViewById(R.id.tvBlockedName)
        private val when_: TextView = itemView.findViewById(R.id.tvBlockedWhen)
        private val unblock: TextView = itemView.findViewById(R.id.btnUnblock)

        init {
            unblock.setOnClickListener {
                items.getOrNull(adapterPosition)?.let(onUnblock)
            }
        }

        fun bind(node: BlockedNodeEntity) {
            val label = node.name?.takeIf { it.isNotBlank() }
                ?: defaultNodeName(rowToBeaconId(node.beaconRow))
            name.text = label
            when_.text = "Blocked ${stamp.format(Date(node.blockedAt))}"
            avatar.text = label.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            (avatar.background as GradientDrawable).setColor(
                Color.parseColor(avatarColours[(node.beaconRow % avatarColours.size).toInt()])
            )
        }
    }
}
