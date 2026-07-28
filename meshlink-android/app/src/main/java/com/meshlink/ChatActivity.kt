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

class ChatActivity : AppCompatActivity() {

    private lateinit var rvChat: RecyclerView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var tvPeerName: TextView
    private lateinit var tvBeaconId: TextView
    private lateinit var vOnlineStatus: View
    private lateinit var btnBack: ImageButton

    private lateinit var chatAdapter: ChatAdapter
    private var peerAddress: String = ""
    private var peerBeaconId: Long = 0

    private val db by lazy { AppDatabase.getDatabase(this) }

    private val chatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RelayService.ACTION_MESSAGE_RECEIVED -> {
                    val address = intent.getStringExtra(RelayService.EXTRA_PEER_ADDRESS)
                    val senderBeacon = intent.getIntExtra("extra_sender_beacon", -1)
                    if (address == peerAddress || senderBeacon.toLong() == peerBeaconId) {
                        loadMessages()
                    }
                }
                RelayService.ACTION_PEER_CONNECTED -> {
                    val beaconId = intent.getIntExtra(RelayService.EXTRA_BEACON_ID, 0)
                    if (beaconId.toLong() == peerBeaconId) {
                        updateOnlineStatus(true)
                    }
                }
                RelayService.ACTION_PEER_DISCONNECTED -> {
                    val address = intent.getStringExtra(RelayService.EXTRA_PEER_ADDRESS)
                    if (address == peerAddress) {
                        updateOnlineStatus(false)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        peerAddress = intent.getStringExtra("peer_address") ?: ""
        peerBeaconId = intent.getLongExtra("peer_beacon_id", 0)

        rvChat = findViewById(R.id.rvChat)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        tvPeerName = findViewById(R.id.tvPeerName)
        tvBeaconId = findViewById(R.id.tvBeaconId)
        vOnlineStatus = findViewById(R.id.vOnlineStatus)
        btnBack = findViewById(R.id.btnBack)

        val peerStr = String.format("%04d", peerBeaconId % 10000)
        tvPeerName.text = "Node $peerStr"
        tvBeaconId.text = "Beacon: $peerBeaconId"
        
        // Assume online initially if we opened from the list, though you could pass it in intent.
        // It will update when broadcast received.
        updateOnlineStatus(true) 

        chatAdapter = ChatAdapter { message ->
            showDeleteDialog(message)
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
            addAction(RelayService.ACTION_PEER_CONNECTED)
            addAction(RelayService.ACTION_PEER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(chatReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(chatReceiver, filter)
        }
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

        // Send to service
        val intent = Intent(this, RelayService::class.java).apply {
            action = "SEND_MESSAGE"
            putExtra("address", peerAddress)
            putExtra("message", text)
        }
        startService(intent)

        // Save to DB locally for immediate feedback
        val msg = MessageEntity(
            messageId = UUID.randomUUID().toString(),
            senderId = 0, // Local user
            recipientId = peerBeaconId,
            plaintext = text,
            envelopeData = ByteArray(0),
            timestamp = System.currentTimeMillis(),
            direction = "OUTBOUND",
            status = "PENDING_RELAY",
            isBroadcast = false
        )

        CoroutineScope(Dispatchers.IO).launch {
            db.messageDao().insertMessage(msg)
            withContext(Dispatchers.Main) {
                etMessage.text.clear()
                loadMessages()
            }
        }
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
