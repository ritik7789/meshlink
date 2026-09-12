package com.meshlink

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.provider.Settings
import android.net.Uri
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.meshlink.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        /** Row id of a conversation to open straight away, set by a notification tap. */
        const val EXTRA_OPEN_PEER = "extra_open_peer"
    }

    private val PERMISSION_REQUEST_CODE = 100
    
    private lateinit var tvStatus: TextView
    private lateinit var rvConversations: RecyclerView
    private lateinit var btnBroadcast: FloatingActionButton
    private lateinit var tvEmptyState: TextView
    private lateinit var bannerBluetooth: View
    private lateinit var statusDot: View

    private var bluetoothEnabled = true

    /**
     * Whether the system enable-dialog has already been offered for this instance
     * of the screen. It is deliberately not reset in `onStop`: the system prompt
     * runs in its own activity, so stopping this one is part of the normal ask
     * flow, and clearing the flag there made a declined prompt reappear
     * instantly in a loop. It resets when the screen is created again, so a
     * fresh launch asks once more, and the banner offers it any time in between.
     */
    private var promptedForBluetooth = false

    /**
     * Result is judged by the adapter's actual state rather than the result code:
     * some OEM builds report RESULT_CANCELED even after the user agrees.
     */
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK || isBluetoothOn()) {
            refreshBluetoothState()
        } else {
            showBluetoothDeclinedDialog()
        }
    }
    
    private lateinit var conversationAdapter: ConversationAdapter

    /** Reachable mesh node id to its distance in hops, newest roster wins. */
    private val reachableNodes = linkedMapOf<Long, Int>()
    private val nodeNames = mutableMapOf<Long, String>()
    private var localBeaconId: Int = 0
    
    private val db by lazy { AppDatabase.getDatabase(this) }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RelayService.ACTION_ROSTER_UPDATED -> {
                    applyRoster(intent)
                    updateUI()
                }
                RelayService.ACTION_BLUETOOTH_STATE -> {
                    bluetoothEnabled = intent.getBooleanExtra(
                        RelayService.EXTRA_BLUETOOTH_ENABLED, true
                    )
                    applyBluetoothState()
                }
                RelayService.ACTION_MESSAGE_RECEIVED,
                RelayService.ACTION_MESSAGE_SENT,
                ChatActivity.ACTION_CONVERSATION_READ -> loadConversations()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        tvStatus = findViewById(R.id.tvStatus)
        tvEmptyState = findViewById(R.id.tvEmptyState)
        bannerBluetooth = findViewById(R.id.bannerBluetooth)
        statusDot = findViewById(R.id.vStatusDot)
        rvConversations = findViewById(R.id.rvConversations)
        btnBroadcast = findViewById(R.id.btnBroadcast)

        conversationAdapter = ConversationAdapter(
            onConversationClick = { peerId ->
                if (peerId != 0L) openChat(peerId)
            },
            onGroupClick = { groupId -> openGroup(groupId) },
            onConversationLongClick = { peerId ->
                showConversationActions(peerId)
            }
        )
        
        rvConversations.layoutManager = LinearLayoutManager(this)
        rvConversations.adapter = conversationAdapter

        btnBroadcast.setOnClickListener { showNewChatDialog() }
        UiMotion.attachPressFeedback(btnBroadcast, scale = 0.9f)

        // Both the banner and its action label re-open the enable prompt, so a
        // user who dismissed it once still has an obvious way back.
        bannerBluetooth.setOnClickListener { promptEnableBluetooth() }
        findViewById<TextView>(R.id.btnEnableBluetooth).setOnClickListener { promptEnableBluetooth() }

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar.setTitleTextColor(android.graphics.Color.WHITE)
        toolbar.title = "MeshLink"
        setSupportActionBar(toolbar)

        checkAndRequestPermissions()

        // Arrived from a notification tap: go straight to that conversation.
        intent?.getLongExtra(EXTRA_OPEN_PEER, 0L)?.takeIf { it != 0L }?.let { openChat(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getLongExtra(EXTRA_OPEN_PEER, 0L).takeIf { it != 0L }?.let { openChat(it) }
    }
    
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(RelayService.ACTION_ROSTER_UPDATED)
            addAction(RelayService.ACTION_BLUETOOTH_STATE)
            addAction(RelayService.ACTION_MESSAGE_RECEIVED)
            addAction(RelayService.ACTION_MESSAGE_SENT)
            addAction(ChatActivity.ACTION_CONVERSATION_READ)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceReceiver, filter)
        }
        
        if (hasCriticalPermissions()) {
            val syncIntent = Intent(this, RelayService::class.java).apply {
                action = RelayService.ACTION_SYNC_STATE
            }
            startService(syncIntent)
        }

        refreshBluetoothState()
        loadConversations()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(serviceReceiver)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bluetooth availability
    //
    // Every feature of this app rides on the BLE radio, so a disabled adapter is
    // not a detail to fail silently on: the user is asked to turn it on, told
    // plainly if they decline, and given a permanent way back.
    // ─────────────────────────────────────────────────────────────────────────

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun isBluetoothOn(): Boolean = bluetoothAdapter()?.isEnabled == true

    /** Re-reads the adapter and offers the enable prompt once per visit. */
    private fun refreshBluetoothState() {
        bluetoothEnabled = isBluetoothOn()
        applyBluetoothState()

        if (!bluetoothEnabled && !promptedForBluetooth && hasCriticalPermissions()) {
            promptEnableBluetooth()
        }
    }

    private fun applyBluetoothState() {
        bannerBluetooth.visibility = if (bluetoothEnabled) View.GONE else View.VISIBLE
        updateStatusLabel()
    }

    private fun promptEnableBluetooth() {
        if (isBluetoothOn()) {
            refreshBluetoothState()
            return
        }
        if (bluetoothAdapter() == null) {
            Toast.makeText(this, "This device has no Bluetooth adapter.", Toast.LENGTH_LONG).show()
            return
        }
        // Asking for the system dialog needs BLUETOOTH_CONNECT on Android 12+.
        if (!hasCriticalPermissions()) {
            checkAndRequestPermissions()
            return
        }
        promptedForBluetooth = true
        try {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (e: SecurityException) {
            showBluetoothSettingsFallback()
        }
    }

    private fun showBluetoothDeclinedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Bluetooth Required")
            .setMessage(
                "MeshLink forms its off-grid network over Bluetooth. " +
                    "Until Bluetooth is on, this device cannot discover nodes, " +
                    "send messages, or relay for anyone else.\n\n" +
                    "You can turn it on later from the banner at the top of the screen."
            )
            .setPositiveButton("Turn On") { _, _ -> promptEnableBluetooth() }
            .setNegativeButton("Not Now", null)
            .show()
    }

    /** Last resort when the in-app prompt is refused by the platform. */
    private fun showBluetoothSettingsFallback() {
        AlertDialog.Builder(this)
            .setTitle("Enable Bluetooth")
            .setMessage("MeshLink could not open the Bluetooth prompt. Please enable Bluetooth in Settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Permissions RelayService needs before it can start its foreground BLE mesh. */
    private fun criticalPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return permissions
    }

    private fun hasCriticalPermissions(): Boolean {
        return criticalPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun loadConversations() {
        CoroutineScope(Dispatchers.IO).launch {
            val convos = db.messageDao().getConversationList()
            val active = db.groupDao().activeGroups()
            val groups = db.groupDao().allGroups().associate { it.groupId to it.name }

            // A group exists the moment it is created, before anyone has spoken
            // in it. Without a placeholder row it would be invisible until the
            // first message, which makes creating one look like it failed.
            val spoken = convos.mapNotNull { it.groupId }.toSet()
            val placeholders = active.filterNot { spoken.contains(it.groupId) }.map { group ->
                com.meshlink.db.MessageEntity(
                    messageId = "group-placeholder-${'$'}{group.groupId}",
                    senderId = 0L,
                    recipientId = 0L,
                    plaintext = "No messages yet",
                    envelopeData = ByteArray(0),
                    timestamp = group.joinedAt,
                    direction = "INBOUND",
                    status = "RECEIVED",
                    isRead = true,
                    groupId = group.groupId
                )
            }
            val merged = (convos + placeholders).sortedByDescending { it.timestamp }

            withContext(Dispatchers.Main) {
                conversationAdapter.setGroupNames(groups)
                conversationAdapter.setConversations(merged)
                UiMotion.fade(tvEmptyState, merged.isEmpty())
            }
            
            // Load unread counts
            convos.filter { it.groupId == null }.forEach {
                val peerId = if (it.direction == "OUTBOUND") it.recipientId else it.senderId
                val unread = db.messageDao().getUnreadCount(peerId)
                withContext(Dispatchers.Main) {
                    conversationAdapter.setUnreadCount(peerId, unread)
                }
            }
        }
    }

    /** Replaces the cached roster with the snapshot the relay service just sent. */
    private fun applyRoster(intent: Intent) {
        localBeaconId = intent.getIntExtra(RelayService.EXTRA_LOCAL_ID, localBeaconId)
        val ids = intent.getIntArrayExtra(RelayService.EXTRA_ROSTER_IDS) ?: IntArray(0)
        val hops = intent.getIntArrayExtra(RelayService.EXTRA_ROSTER_HOPS) ?: IntArray(0)
        val names = intent.getStringArrayExtra(RelayService.EXTRA_ROSTER_NAMES) ?: emptyArray()

        reachableNodes.clear()
        nodeNames.clear()
        ids.forEachIndexed { index, beaconId ->
            val row = beaconIdToRow(beaconId)
            reachableNodes[row] = hops.getOrElse(index) { 1 }
            names.getOrNull(index)?.let { nodeNames[row] = it }
        }
    }

    private fun updateUI() {
        updateStatusLabel()
        conversationAdapter.setReachableNodes(reachableNodes)
        loadConversations()
    }

    /**
     * Summarises reachability in the toolbar. A disabled radio takes precedence
     * over the node count: reporting "No nodes reachable" when Bluetooth is off
     * reads as "nobody is around" rather than "this device cannot look".
     */
    private fun updateStatusLabel() {
        val live = bluetoothEnabled && reachableNodes.isNotEmpty()
        statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor(if (live) "#4DCA59" else "#8E9BA7")
        )
        if (!bluetoothEnabled) {
            tvStatus.text = "Bluetooth off"
            return
        }
        val direct = reachableNodes.count { it.value <= 1 }
        val relayed = reachableNodes.size - direct
        tvStatus.text = when {
            reachableNodes.isEmpty() -> "No nodes reachable"
            relayed == 0 -> "$direct node${if (direct == 1) "" else "s"} reachable"
            else -> "${reachableNodes.size} nodes reachable ($direct direct, $relayed relayed)"
        }
    }

    private fun openGroup(groupId: String) {
        startActivity(Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_GROUP_ID, groupId)
        })
        applyOpenTransition()
    }

    @Suppress("DEPRECATION")
    private fun applyOpenTransition() {
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    private fun openChat(peerId: Long) {
        startActivity(Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_PEER_BEACON_ID, peerId)
        })
        applyOpenTransition()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    /**
     * Shows our own menu anchored to the overflow button.
     *
     * The framework's menu ignores the activity palette and renders light and
     * square-cornered, which looked pasted in from another app.
     */
    private fun showOverflowMenu() {
        val anchor: View = findViewById(R.id.action_overflow)
            ?: findViewById<View>(R.id.toolbar)
        ThemedMenu.showAnchored(
            this,
            anchor,
            listOf(
                ThemedMenu.Item("Set username", R.drawable.ic_person) { showSetUsernameDialog() },
                ThemedMenu.Item("Starred messages", R.drawable.ic_star_filled) { showStarredMessagesDialog() },
                ThemedMenu.Item("Blocked contacts", R.drawable.ic_block) { showBlockedNodesDialog() }
            )
        )
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_overflow -> {
                showOverflowMenu()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** Wraps a custom view in a transparent, themed dialog shell. */
    private fun themedDialog(view: View): AlertDialog =
        AlertDialog.Builder(this).setView(view).create().apply {
            window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
        }

    /**
     * Your name is what every other node shows for you, so the dialog previews
     * the avatar they will see and updates it as you type.
     */
    private fun showSetUsernameDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_username, null)
        val input = view.findViewById<EditText>(R.id.etUsername)
        val avatar = view.findViewById<TextView>(R.id.tvAvatarPreview)
        val idHint = view.findViewById<TextView>(R.id.tvNodeIdHint)
        val prefs = getSharedPreferences(RelayService.PREFS_NAME, MODE_PRIVATE)

        val current = prefs.getString(RelayService.PREF_USERNAME, "").orEmpty()
        input.setText(current)
        input.setSelection(input.text.length)
        idHint.text = "Node ${beaconIdToRow(localBeaconId)}"

        fun paintPreview(name: String) {
            avatar.text = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        }
        paintPreview(current)
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = paintPreview(s?.toString().orEmpty())
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })

        val dialog = themedDialog(view)
        view.findViewById<TextView>(R.id.btnUsernameCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.btnUsernameSave).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isBlank()) {
                Toast.makeText(this, "Pick a name first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString(RelayService.PREF_USERNAME, text).apply()
            // The name travels in presence gossip, so re-announcing pushes it to
            // the whole mesh rather than only to current neighbours.
            startService(Intent(this, RelayService::class.java).apply {
                action = RelayService.ACTION_ANNOUNCE_PRESENCE
            })
            Toast.makeText(this, "Name updated", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        dialog.show()
    }

    /**
     * Starred messages, each a route back to where it was said.
     *
     * Tapping one opens that conversation scrolled to the message: a saved
     * message is only worth keeping if you can find its context again.
     */
    private fun showStarredMessagesDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_list, null)
        val list = view.findViewById<RecyclerView>(R.id.rvDialogList)
        val empty = view.findViewById<TextView>(R.id.tvListEmpty)
        view.findViewById<TextView>(R.id.tvListTitle).text = "Starred messages"
        view.findViewById<TextView>(R.id.tvListSubtitle).text =
            "Tap to open the conversation it came from"
        empty.text = "Nothing starred yet.\nLong-press a message and tap the star."

        val dialog = themedDialog(view)
        val adapter = StarredAdapter(
            nameFor = { row -> peerDisplayName(row) },
            onOpen = { message ->
                dialog.dismiss()
                openChatAtMessage(message)
            },
            onUnstar = { message ->
                CoroutineScope(Dispatchers.IO).launch {
                    db.messageDao().updateStarStatus(message.messageId, false)
                }
                (list.adapter as StarredAdapter).remove(message)
                if ((list.adapter as StarredAdapter).isEmpty()) UiMotion.fade(empty, true)
                loadConversations()
            }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        CoroutineScope(Dispatchers.IO).launch {
            val starred = db.messageDao().getStarredMessages()
            withContext(Dispatchers.Main) {
                adapter.submit(starred)
                empty.visibility = if (starred.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        view.findViewById<TextView>(R.id.btnListClose).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** Opens the conversation a starred message belongs to, focused on it. */
    private fun openChatAtMessage(message: com.meshlink.db.MessageEntity) {
        val intent = Intent(this, ChatActivity::class.java).apply {
            if (message.groupId != null) {
                putExtra(ChatActivity.EXTRA_GROUP_ID, message.groupId)
            } else {
                val peer = if (message.direction == "OUTBOUND") message.recipientId else message.senderId
                putExtra(ChatActivity.EXTRA_PEER_BEACON_ID, peer)
            }
            putExtra(ChatActivity.EXTRA_FOCUS_MESSAGE_ID, message.messageId)
        }
        startActivity(intent)
        applyOpenTransition()
    }

    /** Blocked contacts, with the action that undoes each one on its own row. */
    private fun showBlockedNodesDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_list, null)
        val list = view.findViewById<RecyclerView>(R.id.rvDialogList)
        val empty = view.findViewById<TextView>(R.id.tvListEmpty)
        view.findViewById<TextView>(R.id.tvListTitle).text = "Blocked contacts"
        view.findViewById<TextView>(R.id.tvListSubtitle).text =
            "Their messages are discarded, but your device still relays for them"
        empty.text = "No blocked contacts."

        val dialog = themedDialog(view)
        val adapter = BlockedAdapter { node ->
            CoroutineScope(Dispatchers.IO).launch {
                db.blockedNodeDao().unblock(node.beaconRow)
                notifyBlockListChanged()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Unblocked", Toast.LENGTH_SHORT).show()
                    loadConversations()
                }
            }
            (list.adapter as BlockedAdapter).remove(node)
            if ((list.adapter as BlockedAdapter).isEmpty()) UiMotion.fade(empty, true)
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        CoroutineScope(Dispatchers.IO).launch {
            val blocked = db.blockedNodeDao().all()
            withContext(Dispatchers.Main) {
                adapter.submit(blocked)
                empty.visibility = if (blocked.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        view.findViewById<TextView>(R.id.btnListClose).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /**
     * Node picker: everything the mesh can reach, searchable, with broadcast
     * given its own icon so it is distinguishable at a glance.
     */
    private fun showNewChatDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_new_chat, null)
        val list = view.findViewById<RecyclerView>(R.id.rvNodes)
        val search = view.findViewById<EditText>(R.id.etNodeSearch)
        val empty = view.findViewById<TextView>(R.id.tvNoNodes)

        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )

        val adapter = NodePickerAdapter { choice ->
            dialog.dismiss()
            when {
                choice.isBroadcast -> showBroadcastDialog()
                choice.isNewGroup -> showCreateGroupDialog()
                else -> openChat(choice.beaconRow)
            }
        }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        adapter.submit(buildNodeChoices())
        empty.visibility = if (adapter.isEmpty()) View.VISIBLE else View.GONE

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                adapter.filter(s?.toString().orEmpty())
                empty.visibility = if (adapter.isEmpty()) View.VISIBLE else View.GONE
            }

            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })

        dialog.show()
    }

    /** Broadcast first, then reachable nodes nearest-first. */
    private fun buildNodeChoices(): List<NodeChoice> {
        val choices = mutableListOf(
            NodeChoice(beaconRow = 0L, name = "Broadcast to All", hops = 0, isBroadcast = true),
            NodeChoice(beaconRow = -1L, name = "New group", hops = 0, isNewGroup = true)
        )
        reachableNodes.entries
            .sortedWith(compareBy({ it.value }, { it.key }))
            .forEach { (peerId, hops) ->
                choices += NodeChoice(
                    beaconRow = peerId,
                    name = nodeNames[peerId] ?: defaultNodeName(rowToBeaconId(peerId)),
                    hops = hops
                )
            }
        return choices
    }

    /**
     * Group creation: a name and up to [GroupProtocol.MAX_MEMBERS] members.
     *
     * The cap exists so a busy group cannot crowd the mesh out; the creator
     * counts toward it, so the picker offers one fewer.
     */
    private fun showCreateGroupDialog() {
        val candidates = reachableNodes.keys.toList()
        if (candidates.isEmpty()) {
            Toast.makeText(this, "No nodes reachable to add", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = candidates.map {
            nodeNames[it] ?: defaultNodeName(rowToBeaconId(it))
        }.toTypedArray()
        val chosen = BooleanArray(candidates.size)

        AlertDialog.Builder(this)
            .setTitle("Add members (max ${GroupProtocol.MAX_MEMBERS - 1})")
            .setMultiChoiceItems(labels, chosen) { dialog, which, isChecked ->
                val selected = chosen.count { it }
                if (isChecked && selected > GroupProtocol.MAX_MEMBERS - 1) {
                    // Undo the tick that went over the limit rather than silently
                    // dropping it later at send time.
                    chosen[which] = false
                    (dialog as AlertDialog).listView.setItemChecked(which, false)
                    Toast.makeText(
                        this,
                        "A group can hold ${GroupProtocol.MAX_MEMBERS} members including you",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setPositiveButton("Next") { _, _ ->
                val members = candidates.filterIndexed { index, _ -> chosen[index] }
                if (members.isEmpty()) {
                    Toast.makeText(this, "Pick at least one member", Toast.LENGTH_SHORT).show()
                } else {
                    promptGroupName(members)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptGroupName(members: List<Long>) {
        val input = EditText(this).apply { hint = "Group name" }
        AlertDialog.Builder(this)
            .setTitle("Name this group")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "Group" }
                startService(Intent(this, RelayService::class.java).apply {
                    action = RelayService.ACTION_CREATE_GROUP
                    putExtra(RelayService.EXTRA_GROUP_NAME, name)
                    putExtra(RelayService.EXTRA_GROUP_MEMBERS, members.toLongArray())
                })
                Toast.makeText(this, "Group created", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBroadcastDialog() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("Broadcast Message")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val text = input.text.toString()
                if (text.isBlank()) return@setPositiveButton
                startService(Intent(this, RelayService::class.java).apply {
                    action = RelayService.ACTION_BROADCAST_MESSAGE
                    putExtra(RelayService.EXTRA_MESSAGE, text)
                })
                Toast.makeText(this, "Broadcast sent", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Long-press menu for a conversation. */
    private fun showConversationActions(peerId: Long) {
        if (peerId == 0L) {
            // The broadcast thread has no single counterpart to block.
            showDeleteConversationDialog(peerId)
            return
        }
        val name = peerDisplayName(peerId)
        ThemedMenu.showCentred(
            this,
            name,
            listOf(
                ThemedMenu.Item("Delete conversation", R.drawable.ic_delete, "#F2685C") {
                    showDeleteConversationDialog(peerId)
                },
                ThemedMenu.Item("Block $name", R.drawable.ic_block) {
                    showBlockDialog(peerId, name)
                }
            )
        )
    }

    private fun peerDisplayName(peerId: Long): String =
        getSharedPreferences(RelayService.PREFS_NAME, MODE_PRIVATE)
            .getString(peerNameKeyForRow(peerId), null)?.takeIf { it.isNotBlank() }
            ?: defaultNodeName(rowToBeaconId(peerId))

    private fun showBlockDialog(peerId: Long, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Block $name?")
            .setMessage(
                "You will stop receiving messages from $name, and this " +
                    "conversation will be hidden from your node list.\n\n" +
                    "Your device will keep relaying their messages for other " +
                    "people, so blocking them does not cut them off from the mesh."
            )
            .setPositiveButton("Block") { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    db.blockedNodeDao().block(
                        com.meshlink.db.BlockedNodeEntity(peerId, name, System.currentTimeMillis())
                    )
                    notifyBlockListChanged()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "$name blocked", Toast.LENGTH_SHORT).show()
                        loadConversations()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The relay service keeps the filter in memory; tell it to reload. */
    private fun notifyBlockListChanged() {
        startService(
            Intent(this, RelayService::class.java).apply {
                action = RelayService.ACTION_BLOCKLIST_CHANGED
            }
        )
    }

    private fun showDeleteConversationDialog(peerId: Long) {
        AlertDialog.Builder(this)
            .setTitle("Delete Conversation")
            .setMessage("Are you sure you want to delete this conversation?")
            .setPositiveButton("Delete") { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    db.messageDao().deleteConversation(peerId)
                    loadConversations()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkAndRequestPermissions() {
        val permissions = criticalPermissions().toMutableList()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter { 
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            startMeshService()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (hasCriticalPermissions()) {
                startMeshService()
            } else {
                showPermissionSettingsDialog()
            }
        }
    }

    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("MeshLink needs Bluetooth and Location permissions to discover and connect to nearby devices off-grid. Please grant them in app settings to use the app.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel") { _, _ ->
                Toast.makeText(this, "MeshLink cannot function without BLE permissions.", Toast.LENGTH_LONG).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun startMeshService() {
        val serviceIntent = Intent(this, RelayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}
