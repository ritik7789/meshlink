package com.meshlink

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.meshlink.db.AppDatabase
import com.meshlink.db.GroupMemberEntity
import com.meshlink.db.MessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything about one group on a single page: what it is, what has been shared
 * in it, who belongs to it, and the actions that change your own relationship to
 * it. A dialog could not hold all of that without becoming a scrolling box, and
 * the member list is the part most likely to grow.
 */
class GroupInfoActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GROUP_ID = "group_id"
    }

    private lateinit var groupId: String
    private val db by lazy { AppDatabase.getDatabase(this) }

    private lateinit var avatar: TextView
    private lateinit var name: TextView
    private lateinit var subtitle: TextView
    private lateinit var mediaList: RecyclerView
    private lateinit var noMedia: TextView
    private lateinit var membersHeader: TextView
    private lateinit var membersList: RecyclerView
    private lateinit var addMemberRow: LinearLayout

    private var isAdmin = false
    private var localBeaconId: Int = 0

    /** Reachability snapshot, so members show who is actually in range. */
    private val reachable = mutableMapOf<Long, Int>()

    private val rosterReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                RelayService.ACTION_ROSTER_UPDATED -> {
                    localBeaconId = intent.getIntExtra(RelayService.EXTRA_LOCAL_ID, localBeaconId)
                    val ids = intent.getIntArrayExtra(RelayService.EXTRA_ROSTER_IDS) ?: IntArray(0)
                    val hops = intent.getIntArrayExtra(RelayService.EXTRA_ROSTER_HOPS) ?: IntArray(0)
                    reachable.clear()
                    ids.forEachIndexed { i, id -> reachable[beaconIdToRow(id)] = hops.getOrElse(i) { 1 } }
                    load()
                }
                // Roster edits are published by the service, so reload on its echo.
                RelayService.ACTION_MESSAGE_SENT -> load()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_info)

        groupId = intent.getStringExtra(EXTRA_GROUP_ID) ?: run { finish(); return }

        avatar = findViewById(R.id.tvInfoAvatar)
        name = findViewById(R.id.tvInfoName)
        subtitle = findViewById(R.id.tvInfoSubtitle)
        mediaList = findViewById(R.id.rvInfoMedia)
        noMedia = findViewById(R.id.tvNoMedia)
        membersHeader = findViewById(R.id.tvMembersHeader)
        membersList = findViewById(R.id.rvInfoMembers)
        addMemberRow = findViewById(R.id.rowAddMember)

        mediaList.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        membersList.layoutManager = LinearLayoutManager(this)

        findViewById<ImageButton>(R.id.btnInfoBack).setOnClickListener { finish() }
        addMemberRow.setOnClickListener { showAddMembers() }

        findViewById<TextView>(R.id.btnClearChat).setOnClickListener { confirmClearChat() }
        findViewById<TextView>(R.id.btnLeaveGroup).setOnClickListener { confirmLeave() }
    }

    override fun onStart() {
        super.onStart()
        val filter = android.content.IntentFilter().apply {
            addAction(RelayService.ACTION_ROSTER_UPDATED)
            addAction(RelayService.ACTION_MESSAGE_SENT)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(rosterReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(rosterReceiver, filter)
        }
        startService(Intent(this, RelayService::class.java).apply {
            action = RelayService.ACTION_SYNC_STATE
        })
        load()
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(rosterReceiver) }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    private fun load() {
        CoroutineScope(Dispatchers.IO).launch {
            val group = db.groupDao().group(groupId)
            val members = db.groupDao().members(groupId)
            val admin = db.groupDao().isAdmin(groupId, beaconIdToRow(localBeaconId))
            val media = db.messageDao().getGroupMedia(groupId)

            withContext(Dispatchers.Main) {
                if (group == null) {
                    finish()
                    return@withContext
                }
                isAdmin = admin
                name.text = group.name
                avatar.text = group.name.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
                val live = members.count { reachable.containsKey(it.beaconRow) }
                subtitle.text = "${members.size} members · $live reachable" +
                    if (!group.isActive) " · you have left" else ""

                membersHeader.text = "${members.size} of ${GroupProtocol.MAX_MEMBERS} members"
                // Only admins can change membership, and only while still a member.
                addMemberRow.visibility =
                    if (admin && group.isActive && members.size < GroupProtocol.MAX_MEMBERS) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }

                membersList.adapter = GroupMemberAdapter(
                    selfRow = beaconIdToRow(localBeaconId),
                    reachability = reachable,
                    canManage = admin && group.isActive,
                    onManage = { member -> showMemberActions(members, member) }
                ).also { it.submit(members) }

                mediaList.adapter = MediaThumbAdapter(media) { openInChat(it) }
                noMedia.visibility = if (media.isEmpty()) View.VISIBLE else View.GONE
                mediaList.visibility = if (media.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun openInChat(message: MessageEntity) {
        startActivity(Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_GROUP_ID, groupId)
            putExtra(ChatActivity.EXTRA_FOCUS_MESSAGE_ID, message.messageId)
        })
    }

    // ── Membership ──────────────────────────────────────────────────────────

    /** Offers reachable nodes not already in the group, within the member cap. */
    private fun showAddMembers() {
        CoroutineScope(Dispatchers.IO).launch {
            val members = db.groupDao().members(groupId)
            val existing = members.map { it.beaconRow }.toSet()
            val candidates = reachable.keys.filterNot { existing.contains(it) }

            withContext(Dispatchers.Main) {
                if (candidates.isEmpty()) {
                    Toast.makeText(
                        this@GroupInfoActivity,
                        "No other reachable nodes to add",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@withContext
                }
                val room = GroupProtocol.MAX_MEMBERS - members.size
                val labels = candidates.map { peerName(it) }.toTypedArray()
                val chosen = BooleanArray(candidates.size)

                AlertDialog.Builder(this@GroupInfoActivity)
                    .setTitle("Add members (room for $room)")
                    .setMultiChoiceItems(labels, chosen) { dialog, which, checked ->
                        if (checked && chosen.count { it } > room) {
                            // Undo the tick that went over rather than dropping it
                            // silently when the roster is published.
                            chosen[which] = false
                            (dialog as AlertDialog).listView.setItemChecked(which, false)
                            Toast.makeText(
                                this@GroupInfoActivity,
                                "This group can hold ${GroupProtocol.MAX_MEMBERS} members",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    .setPositiveButton("Add") { _, _ ->
                        val added = candidates.filterIndexed { index, _ -> chosen[index] }
                        if (added.isEmpty()) return@setPositiveButton
                        publishRoster(
                            members.map { it.beaconRow } + added,
                            members.filter { it.isAdmin }.map { it.beaconRow }.toSet()
                        )
                        Toast.makeText(
                            this@GroupInfoActivity,
                            "Added ${added.size}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun showMemberActions(members: List<GroupMemberEntity>, member: GroupMemberEntity) {
        if (member.beaconRow == beaconIdToRow(localBeaconId)) return
        val label = peerName(member.beaconRow)
        val admins = members.filter { it.isAdmin }.map { it.beaconRow }.toMutableSet()

        ThemedMenu.showCentred(
            this,
            label,
            listOf(
                ThemedMenu.Item(
                    if (member.isAdmin) "Remove admin rights" else "Make admin",
                    if (member.isAdmin) R.drawable.ic_star_outline else R.drawable.ic_star_filled
                ) {
                    val next = admins.toMutableSet()
                    if (member.isAdmin) next -= member.beaconRow else next += member.beaconRow
                    publishRoster(members.map { it.beaconRow }, next)
                },
                ThemedMenu.Item("Remove from group", R.drawable.ic_delete, "#F2685C") {
                    confirm("Remove $label from the group?") {
                        publishRoster(
                            members.map { it.beaconRow }.filterNot { it == member.beaconRow },
                            admins - member.beaconRow
                        )
                    }
                }
            )
        )
    }

    private fun publishRoster(members: List<Long>, admins: Set<Long>) {
        startService(Intent(this, RelayService::class.java).apply {
            action = RelayService.ACTION_UPDATE_GROUP_ROSTER
            putExtra(RelayService.EXTRA_GROUP_ID, groupId)
            putExtra(RelayService.EXTRA_GROUP_MEMBERS, members.distinct().toLongArray())
            putExtra(RelayService.EXTRA_GROUP_ADMINS, admins.toLongArray())
        })
        load()
    }

    // ── Destructive actions ─────────────────────────────────────────────────

    private fun confirmClearChat() {
        confirm("Do you want to clear the chat?") {
            CoroutineScope(Dispatchers.IO).launch {
                db.messageDao().clearGroupHistory(groupId)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@GroupInfoActivity, "Chat cleared", Toast.LENGTH_SHORT).show()
                    load()
                }
            }
        }
    }

    private fun confirmLeave() {
        confirm("Do you want to leave the group?") {
            startService(Intent(this, RelayService::class.java).apply {
                action = RelayService.ACTION_LEAVE_GROUP
                putExtra(RelayService.EXTRA_GROUP_ID, groupId)
            })
            finish()
        }
    }

    /**
     * Every irreversible action passes through here first.
     *
     * A short question only: spelling out the consequences turned each of these
     * into a paragraph people stop reading, which defeats the point of asking.
     */
    private fun confirm(title: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setPositiveButton("Yes") { _, _ -> onYes() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun peerName(row: Long): String =
        getSharedPreferences(RelayService.PREFS_NAME, MODE_PRIVATE)
            .getString(peerNameKeyForRow(row), null)?.takeIf { it.isNotBlank() }
            ?: defaultNodeName(rowToBeaconId(row))
}

/** Horizontal strip of shared images; tapping one jumps to it in the chat. */
private class MediaThumbAdapter(
    private val items: List<MessageEntity>,
    private val onOpen: (MessageEntity) -> Unit
) : RecyclerView.Adapter<MediaThumbAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_media_thumb, parent, false) as ImageView
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val image: ImageView) : RecyclerView.ViewHolder(image) {
        init {
            image.setOnClickListener {
                items.getOrNull(adapterPosition)?.let(onOpen)
            }
        }

        fun bind(message: MessageEntity) {
            val thumb = message.mediaPath?.let { id ->
                runCatching {
                    val file = MediaStore.fileFor(image.context, id)
                    if (!file.exists()) return@runCatching null
                    // Sampled down: a strip of full-size decodes would stutter.
                    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
                }.getOrNull()
            }
            if (thumb != null) image.setImageBitmap(thumb) else image.setImageDrawable(null)
        }
    }
}
