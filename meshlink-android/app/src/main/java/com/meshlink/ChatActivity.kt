package com.meshlink

import android.app.NotificationManager
import android.provider.ContactsContract
import androidx.activity.result.contract.ActivityResultContracts
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.meshlink.db.AppDatabase
import com.meshlink.db.MessageEntity
import com.meshlink.db.MediaState
import com.meshlink.db.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * One conversation, addressed by the peer's mesh beacon id. The peer does not
 * need to be a direct BLE neighbour: the relay service floods the message until
 * it reaches that node.
 */
class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PEER_BEACON_ID = "peer_beacon_id"

        /** Present when this screen is showing a group rather than one peer. */
        const val EXTRA_GROUP_ID = "group_id"

        /** Emitted after messages are marked seen, so the list can drop the badge. */
        const val ACTION_CONVERSATION_READ = "com.meshlink.ACTION_CONVERSATION_READ"
    }

    private lateinit var rvChat: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var tvPeerName: TextView
    private lateinit var tvBeaconId: TextView
    private lateinit var vOnlineStatus: View
    private lateinit var btnBack: ImageButton
    private lateinit var btnAttach: ImageButton

    /**
     * Contact picking uses ACTION_PICK on the phone-number table rather than
     * requesting READ_CONTACTS: the picker hands back a URI this app is granted
     * access to for that one row, so sharing a contact never asks for the whole
     * address book.
     */
    private val pickContactLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        readContact(uri)?.let { sendAttachment(it.toVCard(), MessageType.CONTACT) }
            ?: Toast.makeText(this, "Could not read that contact", Toast.LENGTH_SHORT).show()
    }

    private lateinit var chatAdapter: ChatAdapter
    private var peerBeaconId: Long = 0
    private var groupId: String? = null
    private var isGroupAdmin: Boolean = false
    private var localBeaconId: Int = 0

    /** Distance to this peer; 1 means a direct link, 0 means unreachable. */
    private var peerHops: Int = 0

    private val db by lazy { AppDatabase.getDatabase(this) }

    private val chatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RelayService.ACTION_MESSAGE_RECEIVED -> {
                    val senderBeacon = intent.getIntExtra(RelayService.EXTRA_SENDER_BEACON, 0)
                    if (beaconIdToRow(senderBeacon) == peerBeaconId) loadMessages()
                }
                RelayService.ACTION_MESSAGE_SENT -> {
                    val peer = intent.getIntExtra(RelayService.EXTRA_BEACON_ID, 0)
                    if (beaconIdToRow(peer) == peerBeaconId) loadMessages()
                }
                RelayService.ACTION_EMERGENCY_REFUSED ->
                    Toast.makeText(
                        this@ChatActivity,
                        "You have used both emergency transfers this week",
                        Toast.LENGTH_LONG
                    ).show()
                RelayService.ACTION_ROSTER_UPDATED -> {
                    localBeaconId = intent.getIntExtra(RelayService.EXTRA_LOCAL_ID, localBeaconId)
                    applyRoster(intent)
                }
            }
        }
    }

    private var actionMode: android.view.ActionMode? = null

    private val actionModeCallback = object : android.view.ActionMode.Callback {
        override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean {
            menu?.add(0, 1, 0, "Delete")?.setIcon(android.R.drawable.ic_menu_delete)?.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu?.add(0, 2, 0, "Copy")?.setIcon(android.R.drawable.ic_menu_set_as)?.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            menu?.add(0, 3, 0, "Star")?.setIcon(android.R.drawable.btn_star)?.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            return true
        }

        override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean = false

        override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?): Boolean {
            val selected = chatAdapter.getSelectedMessages()
            when (item?.itemId) {
                1 -> { // Delete
                    val group = groupId
                    if (group != null) {
                        // In a group, deletion is a retraction others also see -
                        // the service checks the roster before honouring it.
                        selected.forEach { message ->
                            startService(Intent(this@ChatActivity, RelayService::class.java).apply {
                                action = RelayService.ACTION_DELETE_GROUP_MESSAGE
                                putExtra(RelayService.EXTRA_GROUP_ID, group)
                                putExtra(RelayService.EXTRA_TARGET_MESSAGE_ID, message.messageId)
                            })
                        }
                    } else {
                        CoroutineScope(Dispatchers.IO).launch {
                            selected.forEach { db.messageDao().deleteMessage(it.messageId) }
                            loadMessages()
                        }
                    }
                    mode?.finish()
                    return true
                }
                2 -> { // Copy
                    val text = selected.joinToString("\n") { it.plaintext }
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Copied Messages", text))
                    Toast.makeText(this@ChatActivity, "Copied", Toast.LENGTH_SHORT).show()
                    mode?.finish()
                    return true
                }
                3 -> { // Star
                    CoroutineScope(Dispatchers.IO).launch {
                        selected.forEach { db.messageDao().updateStarStatus(it.messageId, !it.isStarred) }
                        loadMessages()
                    }
                    mode?.finish()
                    return true
                }
            }
            return false
        }

        override fun onDestroyActionMode(mode: android.view.ActionMode?) {
            chatAdapter.clearSelection()
            actionMode = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        peerBeaconId = intent.getLongExtra(EXTRA_PEER_BEACON_ID, 0)
        groupId = intent.getStringExtra(EXTRA_GROUP_ID)

        rvChat = findViewById(R.id.rvChat)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        tvPeerName = findViewById(R.id.tvPeerName)
        tvBeaconId = findViewById(R.id.tvBeaconId)
        vOnlineStatus = findViewById(R.id.vOnlineStatus)
        btnBack = findViewById(R.id.btnBack)
        btnAttach = findViewById(R.id.btnAttach)

        val prefs = getSharedPreferences(RelayService.PREFS_NAME, MODE_PRIVATE)
        if (groupId != null) {
            refreshGroupHeader()
            tvPeerName.setOnClickListener { showGroupSheet() }
            tvBeaconId.setOnClickListener { showGroupSheet() }
        } else {
            tvPeerName.text = prefs.getString(peerNameKeyForRow(peerBeaconId), null)?.takeIf { it.isNotBlank() }
                ?: defaultNodeName(rowToBeaconId(peerBeaconId))
            tvBeaconId.text = "Beacon: $peerBeaconId"
        }

        // Start pessimistic: the next roster broadcast says whether this node is
        // actually reachable and how far away it is.
        updateOnlineStatus(false)
        
        chatAdapter = ChatAdapter(
            onAttachmentClick = { message -> onAttachmentTapped(message) }
        ) { message ->
            if (actionMode == null) {
                actionMode = startActionMode(actionModeCallback)
                chatAdapter.isSelectionMode = true
            }
            chatAdapter.toggleSelection(message.messageId)
            
            val count = chatAdapter.getSelectedMessages().size
            if (count == 0) {
                actionMode?.finish()
            } else {
                actionMode?.title = "$count selected"
            }
        }
        
        rvChat.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        rvChat.adapter = chatAdapter

        btnBack.setOnClickListener { finish() }
        btnSend.setOnClickListener { sendMessage() }
        btnAttach.setOnClickListener { showAttachmentOptions() }

        loadMessages()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(RelayService.ACTION_MESSAGE_RECEIVED)
            addAction(RelayService.ACTION_MESSAGE_SENT)
            addAction(RelayService.ACTION_ROSTER_UPDATED)
            addAction(RelayService.ACTION_EMERGENCY_REFUSED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(chatReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(chatReceiver, filter)
        }

        // Ask for a roster snapshot so the header is correct on entry rather
        // than only after the next periodic update.
        startService(Intent(this, RelayService::class.java).apply {
            action = RelayService.ACTION_SYNC_STATE
        })
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(chatReceiver)
    }

    private fun loadMessages() {
        CoroutineScope(Dispatchers.IO).launch {
            val group = groupId
            val messages = if (group != null) {
                db.messageDao().getMessagesForGroup(group)
            } else {
                db.messageDao().getMessagesForPeer(peerBeaconId)
            }
            withContext(Dispatchers.Main) {
                chatAdapter.setMessages(messages)
                if (messages.isNotEmpty()) {
                    rvChat.scrollToPosition(messages.size - 1)
                }
            }
            if (groupId == null) markConversationSeen() else refreshGroupHeader()
        }
    }

    /**
     * Marks everything from this peer as seen, because the user is looking at it.
     *
     * Also dismisses that peer's notification: leaving it in the shade after the
     * conversation has been opened is the same staleness the badge had.
     */
    private suspend fun markConversationSeen() {
        val updated = db.messageDao().markConversationRead(peerBeaconId)

        withContext(Dispatchers.Main) {
            getSystemService(NotificationManager::class.java)
                ?.cancel(rowToBeaconId(peerBeaconId))
        }
        if (updated == 0) return

        // Tell the conversation list to refresh its badges.
        sendBroadcast(
            Intent(ACTION_CONVERSATION_READ).apply {
                putExtra(RelayService.EXTRA_BEACON_ID, rowToBeaconId(peerBeaconId))
                setPackage(packageName)
            }
        )
    }

    private fun sendMessage() {
        val text = etMessage.text.toString().trim()
        if (text.isEmpty()) return

        // The relay service is the single writer for messages: it knows the real
        // envelope id and whether the message actually left the device, and
        // echoes ACTION_MESSAGE_SENT once the row is stored. Inserting a second
        // copy here produced a duplicate that never matched the sent envelope.
        sendBody(text, MessageType.TEXT)
        etMessage.text.clear()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Attachments
    //
    // Stickers and contacts are small enough to travel inline, so they cross
    // hops exactly like a text message. Anything larger belongs to the media
    // path and is handled separately.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * An attachment the user tapped: fetch it if it is only an offer, open it
     * once the bytes are here.
     */
    private fun onAttachmentTapped(message: MessageEntity) {
        val mediaId = message.mediaPath ?: return
        when (message.mediaState) {
            MediaState.OFFERED, MediaState.FAILED -> {
                startService(Intent(this, RelayService::class.java).apply {
                    action = RelayService.ACTION_FETCH_MEDIA
                    putExtra(RelayService.EXTRA_BEACON_ID, rowToBeaconId(peerBeaconId))
                    putExtra(RelayService.EXTRA_MEDIA_ID, mediaId)
                })
                Toast.makeText(this, "Downloading…", Toast.LENGTH_SHORT).show()
            }
            MediaState.TRANSFERRING ->
                Toast.makeText(this, "Still downloading", Toast.LENGTH_SHORT).show()
            MediaState.READY -> openMedia(mediaId, message.mediaMime)
        }
    }

    private fun openMedia(mediaId: String, mime: String?) {
        val file = MediaStore.fileFor(this, mediaId)
        if (!file.exists()) {
            Toast.makeText(this, "File is missing", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure {
            Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Attachment menu, anchored just above the paperclip.
     *
     * The panel grows from its bottom-left corner — the corner nearest the button
     * that opened it — so the motion reads as the menu coming out of that button
     * rather than arriving from nowhere. Rows then stagger in just behind the
     * panel, which is what gives the open its sense of unfolding.
     */
    private fun showAttachmentOptions() {
        val panel = layoutInflater.inflate(R.layout.popup_attachment, null)
        val popup = android.widget.PopupWindow(
            panel,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 24f
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            // Suppress the platform animation; the panel animates itself below.
            animationStyle = 0
        }

        fun choose(action: () -> Unit) = View.OnClickListener {
            popup.dismiss()
            action()
        }
        val rows = listOf(
            panel.findViewById<View>(R.id.optionPhoto).also { it.setOnClickListener(choose(::pickImage)) },
            panel.findViewById<View>(R.id.optionSticker).also { it.setOnClickListener(choose(::showStickerPicker)) },
            panel.findViewById<View>(R.id.optionContact).also { it.setOnClickListener(choose(::pickContact)) }
        )

        // Measure so the panel can be offset to sit above the button rather than
        // over it, and so the rows can be primed before the first frame draws.
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val verticalOffset = -(panel.measuredHeight + btnAttach.height + dp(8))

        panel.alpha = 0f
        panel.scaleX = 0.82f
        panel.scaleY = 0.82f
        panel.pivotX = 0f
        panel.pivotY = panel.measuredHeight.toFloat()
        rows.forEach { row ->
            row.alpha = 0f
            row.translationY = dp(10).toFloat()
        }

        popup.showAsDropDown(btnAttach, dp(4), verticalOffset)

        panel.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(160)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
            .start()

        rows.forEachIndexed { index, row ->
            row.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(40L + index * 35L)
                .setDuration(150)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showStickerPicker() {
        val references = Stickers.all()
        val glyphs = references.map { Stickers.glyphFor(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Send a sticker")
            .setItems(glyphs) { _, which ->
                sendAttachment(references[which], MessageType.STICKER)
            }
            .show()
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { importAndOffer(it) } }

    private fun pickImage() {
        runCatching { pickImageLauncher.launch("image/*") }
            .onFailure { Toast.makeText(this, "No gallery available", Toast.LENGTH_SHORT).show() }
    }

    /**
     * Downscales the picked image, records it locally, then offers it.
     *
     * Only the offer goes out now. The bytes wait until the recipient accepts, so
     * nothing is spent on a file they may not want.
     */
    private fun importAndOffer(uri: android.net.Uri) {
        val mediaId = UUID.randomUUID().toString()
        Toast.makeText(this, "Preparing photo…", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            val file = MediaStore.importImage(this@ChatActivity, uri, mediaId)
            if (file == null || file.length() == 0L) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ChatActivity, "Could not read that image", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            if (file.length() > MediaStore.MAX_MEDIA_BYTES) {
                MediaStore.delete(this@ChatActivity, mediaId)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@ChatActivity,
                        "That image is too large for the mesh even after resizing",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                offerMedia(mediaId, file.length())
            }
        }
    }

    /**
     * Sends the offer, asking about the emergency allowance only when it is
     * actually needed.
     *
     * A direct link carries the file without spending anything. It is only when
     * the recipient is several hops away that the bytes would have to cross other
     * people's devices, and that is what the allowance rations.
     */
    private fun offerMedia(mediaId: String, size: Long) {
        if (peerHops == 1) {
            dispatchOffer(mediaId, emergency = false)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Send over the mesh?")
            .setMessage(
                if (peerHops == 0) {
                    "This node is not reachable right now, so the photo cannot be sent yet."
                } else {
                    "This node is $peerHops hops away, so the photo would have to travel " +
                        "through other people's devices.\n\nThat uses one of your " +
                        "${RelayService.EMERGENCY_SENDS_PER_WINDOW} emergency transfers this week. " +
                        "Otherwise it waits until you are in direct range."
                }
            )
            .setPositiveButton(if (peerHops == 0) "OK" else "Use emergency") { _, _ ->
                if (peerHops > 0) dispatchOffer(mediaId, emergency = true)
            }
            .setNegativeButton(if (peerHops == 0) "Cancel" else "Wait for direct") { _, _ ->
                if (peerHops > 0) dispatchOffer(mediaId, emergency = false)
            }
            .show()
    }

    private fun dispatchOffer(mediaId: String, emergency: Boolean) {
        startService(Intent(this, RelayService::class.java).apply {
            action = RelayService.ACTION_SEND_MEDIA
            putExtra(RelayService.EXTRA_BEACON_ID, rowToBeaconId(peerBeaconId))
            putExtra(RelayService.EXTRA_MEDIA_ID, mediaId)
            putExtra(RelayService.EXTRA_MEDIA_NAME, "Photo")
            putExtra(RelayService.EXTRA_MEDIA_MIME, "image/jpeg")
            putExtra(RelayService.EXTRA_EMERGENCY, emergency)
        })
    }

    private fun pickContact() {
        val intent = Intent(
            Intent.ACTION_PICK,
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        )
        runCatching { pickContactLauncher.launch(intent) }
            .onFailure { Toast.makeText(this, "No contacts app available", Toast.LENGTH_SHORT).show() }
    }

    private fun readContact(uri: android.net.Uri): ContactCard? = runCatching {
        contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: return@use null
            ContactCard(name, cursor.getString(1))
        }
    }.getOrNull()

    private fun sendAttachment(payload: String, messageType: String) = sendBody(payload, messageType)

    /**
     * One send path for both kinds of conversation, so stickers, contacts and
     * text behave identically in a group and in a one-to-one chat.
     */
    private fun sendBody(body: String, messageType: String) {
        val group = groupId
        startService(Intent(this, RelayService::class.java).apply {
            if (group != null) {
                action = RelayService.ACTION_SEND_GROUP_MESSAGE
                putExtra(RelayService.EXTRA_GROUP_ID, group)
            } else {
                action = RelayService.ACTION_SEND_MESSAGE
                putExtra(RelayService.EXTRA_BEACON_ID, rowToBeaconId(peerBeaconId))
            }
            putExtra(RelayService.EXTRA_MESSAGE, body)
            putExtra(RelayService.EXTRA_MESSAGE_TYPE, messageType)
        })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Group administration
    // ─────────────────────────────────────────────────────────────────────────

    private fun refreshGroupHeader() {
        val group = groupId ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val info = db.groupDao().group(group)
            val members = db.groupDao().members(group)
            val admin = db.groupDao().isAdmin(group, beaconIdToRow(localBeaconId))
            withContext(Dispatchers.Main) {
                isGroupAdmin = admin
                tvPeerName.text = info?.name ?: "Group"
                tvBeaconId.text = when {
                    info?.isActive == false -> "You are no longer a member"
                    else -> "${members.size} members · tap for info"
                }
                updateOnlineStatus(info?.isActive != false)
            }
        }
    }

    private fun showGroupSheet() {
        val group = groupId ?: return
        CoroutineScope(Dispatchers.IO).launch {
            val members = db.groupDao().members(group)
            val info = db.groupDao().group(group)
            withContext(Dispatchers.Main) {
                val labels = members.map { member ->
                    val name = member.name ?: defaultNodeName(rowToBeaconId(member.beaconRow))
                    if (member.isAdmin) "$name  ·  admin" else name
                }.toTypedArray()

                AlertDialog.Builder(this@ChatActivity)
                    .setTitle(info?.name ?: "Group")
                    .setItems(labels) { _, which ->
                        if (isGroupAdmin) showMemberActions(group, members, which)
                    }
                    .setNegativeButton("Leave group") { _, _ -> confirmLeaveGroup(group) }
                    .setPositiveButton("Close", null)
                    .show()
            }
        }
    }

    private fun showMemberActions(
        group: String,
        members: List<com.meshlink.db.GroupMemberEntity>,
        index: Int
    ) {
        val member = members[index]
        if (member.beaconRow == beaconIdToRow(localBeaconId)) return
        val name = member.name ?: defaultNodeName(rowToBeaconId(member.beaconRow))
        val promote = if (member.isAdmin) "Remove admin rights" else "Make admin"

        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf(promote, "Remove from group")) { _, which ->
                val remaining = if (which == 1) members.filter { it != member } else members
                val admins = remaining.filter {
                    if (it == member && which == 0) !member.isAdmin else it.isAdmin
                }.map { it.beaconRow }.toMutableSet()
                if (which == 0 && !member.isAdmin) admins += member.beaconRow

                startService(Intent(this, RelayService::class.java).apply {
                    action = RelayService.ACTION_UPDATE_GROUP_ROSTER
                    putExtra(RelayService.EXTRA_GROUP_ID, group)
                    putExtra(RelayService.EXTRA_GROUP_MEMBERS, remaining.map { it.beaconRow }.toLongArray())
                    putExtra(RelayService.EXTRA_GROUP_ADMINS, admins.toLongArray())
                })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmLeaveGroup(group: String) {
        AlertDialog.Builder(this)
            .setTitle("Leave group?")
            .setMessage("You will stop receiving its messages. The conversation stays on this device.")
            .setPositiveButton("Leave") { _, _ ->
                startService(Intent(this, RelayService::class.java).apply {
                    action = RelayService.ACTION_LEAVE_GROUP
                    putExtra(RelayService.EXTRA_GROUP_ID, group)
                })
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Updates the header from the relay service's reachability snapshot. */
    private fun applyRoster(intent: Intent) {
        // A group header shows membership, not one peer's reachability, so the
        // roster snapshot must not overwrite it.
        if (groupId != null) {
            refreshGroupHeader()
            return
        }
        val ids = intent.getIntArrayExtra(RelayService.EXTRA_ROSTER_IDS) ?: IntArray(0)
        val hops = intent.getIntArrayExtra(RelayService.EXTRA_ROSTER_HOPS) ?: IntArray(0)
        val names = intent.getStringArrayExtra(RelayService.EXTRA_ROSTER_NAMES) ?: emptyArray()

        val index = ids.indexOfFirst { beaconIdToRow(it) == peerBeaconId }
        if (index < 0) {
            updateOnlineStatus(false)
            peerHops = 0
            tvBeaconId.text = "Beacon: $peerBeaconId · unreachable"
            return
        }
        names.getOrNull(index)?.takeIf { it.isNotBlank() }?.let { tvPeerName.text = it }
        val distance = hops.getOrElse(index) { 1 }
        peerHops = distance
        tvBeaconId.text = if (distance > 1) {
            "Beacon: $peerBeaconId · $distance hops away"
        } else {
            "Beacon: $peerBeaconId · direct"
        }
        updateOnlineStatus(true)
    }

    private fun showDeleteDialog(message: MessageEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete Message")
            .setMessage("Are you sure you want to delete this message?")
            .setPositiveButton("Delete") { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    db.messageDao().deleteMessage(message.messageId)
                    loadMessages()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun updateOnlineStatus(isOnline: Boolean) {
        vOnlineStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(
            Color.parseColor(if (isOnline) "#4DCA59" else "#8E9BA7")
        )
    }
}
