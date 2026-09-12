package com.meshlink

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.meshlink.db.GroupMemberEntity

/**
 * The member list for a group.
 *
 * Each row reports reachability alongside the name, because in a mesh "who is in
 * this group" and "who can actually receive right now" are different questions,
 * and the second is usually the one being asked.
 */
class GroupMemberAdapter(
    private val selfRow: Long,
    /** Distance in hops per node; absent means unreachable. */
    private val reachability: Map<Long, Int>,
    private val canManage: Boolean,
    private val onManage: (GroupMemberEntity) -> Unit
) : RecyclerView.Adapter<GroupMemberAdapter.ViewHolder>() {

    private val items = mutableListOf<GroupMemberEntity>()

    private val avatarColours = arrayOf(
        "#E57373", "#F06292", "#BA68C8", "#9575CD", "#7986CB",
        "#64B5F6", "#4FC3F7", "#4DD0E1", "#4DB6AC", "#81C784"
    )

    fun submit(members: List<GroupMemberEntity>) {
        items.clear()
        // Admins first, then the rest, so authority is visible at a glance.
        items.addAll(members.sortedWith(compareByDescending<GroupMemberEntity> { it.isAdmin }
            .thenBy { it.name ?: "" }))
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_group_member, parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatar: TextView = itemView.findViewById(R.id.tvMemberAvatar)
        private val name: TextView = itemView.findViewById(R.id.tvMemberName)
        private val reach: TextView = itemView.findViewById(R.id.tvMemberReach)
        private val role: TextView = itemView.findViewById(R.id.tvMemberRole)

        init {
            itemView.setOnClickListener {
                val member = items.getOrNull(adapterPosition) ?: return@setOnClickListener
                // Only an admin has anything to do here, and never to themselves.
                if (canManage && member.beaconRow != selfRow) {
                    UiMotion.longPressTick(itemView)
                    onManage(member)
                }
            }
        }

        fun bind(member: GroupMemberEntity) {
            val label = member.name?.takeIf { it.isNotBlank() }
                ?: defaultNodeName(rowToBeaconId(member.beaconRow))
            val isSelf = member.beaconRow == selfRow

            name.text = if (isSelf) "$label (you)" else label
            reach.text = when {
                isSelf -> "This device"
                reachability[member.beaconRow] == null -> "Not reachable"
                reachability[member.beaconRow] == 1 -> "Direct"
                else -> "${reachability[member.beaconRow]} hops away"
            }

            role.visibility = if (member.isAdmin) View.VISIBLE else View.GONE
            avatar.text = label.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            (avatar.background as GradientDrawable).setColor(
                Color.parseColor(avatarColours[(member.beaconRow % avatarColours.size).toInt()])
            )
        }
    }
}
