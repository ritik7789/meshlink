package com.meshlink

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
    }

    private lateinit var rvChat: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var tvPeerName: TextView
    private lateinit var tvBeaconId: TextView
    private lateinit var vOnlineStatus: View
    private lateinit var btnBack: ImageButton

    private lateinit var chatAdapter: ChatAdapter
    private var peerBeaconId: Long = 0

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
                RelayService.ACTION_ROSTER_UPDATED -> applyRoster(intent)
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
                    CoroutineScope(Dispatchers.IO).launch {
                        selected.forEach { db.messageDao().deleteMessage(it.messageId) }
                        loadMessages()
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

        rvChat = findViewById(R.id.rvChat)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        tvPeerName = findViewById(R.id.tvPeerName)
        tvBeaconId = findViewById(R.id.tvBeaconId)
        vOnlineStatus = findViewById(R.id.vOnlineStatus)
        btnBack = findViewById(R.id.btnBack)

        val prefs = getSharedPreferences(RelayService.PREFS_NAME, MODE_PRIVATE)
        tvPeerName.text = prefs.getString(peerNameKeyForRow(peerBeaconId), null)?.takeIf { it.isNotBlank() }
            ?: defaultNodeName(rowToBeaconId(peerBeaconId))
        tvBeaconId.text = "Beacon: $peerBeaconId"

        // Start pessimistic: the next roster broadcast says whether this node is
        // actually reachable and how far away it is.
        updateOnlineStatus(false)
        
        chatAdapter = ChatAdapter { message ->
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

        loadMessages()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(RelayService.ACTION_MESSAGE_RECEIVED)
            addAction(RelayService.ACTION_MESSAGE_SENT)
            addAction(RelayService.ACTION_ROSTER_UPDATED)
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
            val messages = db.messageDao().getMessagesForPeer(peerBeaconId)
            withContext(Dispatchers.Main) {
                chatAdapter.setMessages(messages)
                if (messages.isNotEmpty()) {
                    rvChat.scrollToPosition(messages.size - 1)
                }
            }
        }
    }

    private fun sendMessage() {
        val text = etMessage.text.toString().trim()
        if (text.isEmpty()) return

        // The relay service is the single writer for messages: it knows the real
        // envelope id and whether the message actually left the device, and
        // echoes ACTION_MESSAGE_SENT once the row is stored. Inserting a second
        // copy here produced a duplicate that never matched the sent envelope.
        startService(Intent(this, RelayService::class.java).apply {
            action = RelayService.ACTION_SEND_MESSAGE
            putExtra(RelayService.EXTRA_BEACON_ID, rowToBeaconId(peerBeaconId))
            putExtra(RelayService.EXTRA_MESSAGE, text)
        })
        etMessage.text.clear()
    }

    /** Updates the header from the relay service's reachability snapshot. */
    private fun applyRoster(intent: Intent) {
        val ids = intent.getIntArrayExtra(RelayService.EXTRA_ROSTER_IDS) ?: IntArray(0)
        val hops = intent.getIntArrayExtra(RelayService.EXTRA_ROSTER_HOPS) ?: IntArray(0)
        val names = intent.getStringArrayExtra(RelayService.EXTRA_ROSTER_NAMES) ?: emptyArray()

        val index = ids.indexOfFirst { beaconIdToRow(it) == peerBeaconId }
        if (index < 0) {
            updateOnlineStatus(false)
            tvBeaconId.text = "Beacon: $peerBeaconId · unreachable"
            return
        }
        names.getOrNull(index)?.takeIf { it.isNotBlank() }?.let { tvPeerName.text = it }
        val distance = hops.getOrElse(index) { 1 }
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
