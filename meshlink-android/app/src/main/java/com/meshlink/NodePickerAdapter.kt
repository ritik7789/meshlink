package com.meshlink

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * One row in the node picker: either a reachable node or the broadcast entry.
 *
 * [beaconRow] of 0 marks the broadcast row, which is given its own icon rather
 * than an initial so it never reads as just another node in the list.
 */
data class NodeChoice(
    val beaconRow: Long,
    val name: String,
    val hops: Int,
    val isBroadcast: Boolean = false,
    val isNewGroup: Boolean = false
)

class NodePickerAdapter(
    private val onPick: (NodeChoice) -> Unit
) : RecyclerView.Adapter<NodePickerAdapter.ViewHolder>() {

    private val all = mutableListOf<NodeChoice>()
    private val visible = mutableListOf<NodeChoice>()
    private var query: String = ""

    private val avatarColours = arrayOf(
        "#E57373", "#F06292", "#BA68C8", "#9575CD", "#7986CB",
        "#64B5F6", "#4FC3F7", "#4DD0E1", "#4DB6AC", "#81C784"
    )

    fun submit(choices: List<NodeChoice>) {
        all.clear()
        all.addAll(choices)
        applyFilter()
    }

    /** Filters by name, keeping the broadcast row pinned while the query is empty. */
    fun filter(text: String) {
        query = text.trim()
        applyFilter()
    }

    private fun applyFilter() {
        visible.clear()
        visible.addAll(
            if (query.isEmpty()) all
            else all.filter {
                !it.isBroadcast && !it.isNewGroup && it.name.contains(query, ignoreCase = true)
            }
        )
        notifyDataSetChanged()
    }

    fun isEmpty(): Boolean = visible.isEmpty()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_node, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(visible[position])

    override fun getItemCount(): Int = visible.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvAvatar: TextView = itemView.findViewById(R.id.tvNodeAvatar)
        private val ivIcon: ImageView = itemView.findViewById(R.id.ivNodeIcon)
        private val tvName: TextView = itemView.findViewById(R.id.tvNodeName)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvNodeSubtitle)
        private val vOnline: View = itemView.findViewById(R.id.vNodeOnline)

        init {
            itemView.setOnClickListener {
                visible.getOrNull(adapterPosition)?.let(onPick)
            }
        }

        fun bind(choice: NodeChoice) {
            tvName.text = choice.name

            if (choice.isBroadcast || choice.isNewGroup) {
                ivIcon.visibility = View.GONE
                tvAvatar.visibility = View.VISIBLE
                tvAvatar.text = if (choice.isBroadcast) "" else "\uD83D\uDC65"
                if (choice.isBroadcast) {
                    ivIcon.visibility = View.VISIBLE
                    tvAvatar.visibility = View.GONE
                    ivIcon.setImageResource(R.drawable.ic_broadcast_tower)
                    (ivIcon.background as GradientDrawable).setColor(Color.parseColor("#F2A33C"))
                } else {
                    (tvAvatar.background as GradientDrawable).setColor(Color.parseColor("#00A884"))
                }
                tvSubtitle.text =
                    if (choice.isBroadcast) "Everyone on the mesh" else "Up to ${GroupProtocol.MAX_MEMBERS} members"
                vOnline.visibility = View.INVISIBLE
                return
            }

            ivIcon.visibility = View.GONE
            tvAvatar.visibility = View.VISIBLE
            tvAvatar.text = choice.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            val colour = avatarColours[(choice.beaconRow % avatarColours.size).toInt()]
            (tvAvatar.background as GradientDrawable).setColor(Color.parseColor(colour))

            tvSubtitle.text = when {
                choice.hops <= 0 -> "Unreachable"
                choice.hops == 1 -> "Direct"
                else -> "${choice.hops} hops away"
            }
            vOnline.visibility = if (choice.hops > 0) View.VISIBLE else View.INVISIBLE
        }
    }
}
