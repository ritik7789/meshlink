package com.meshlink

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
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
    
    private val PERMISSION_REQUEST_CODE = 100
    
    private lateinit var tvStatus: TextView
    private lateinit var rvConversations: RecyclerView
    private lateinit var btnBroadcast: FloatingActionButton
    
    private lateinit var conversationAdapter: ConversationAdapter
    private val connectedPeers = mutableMapOf<String, Int>()
    
    private val db by lazy { AppDatabase.getDatabase(this) }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RelayService.ACTION_PEER_CONNECTED -> {
                    val address = intent.getStringExtra(RelayService.EXTRA_PEER_ADDRESS) ?: return
                    val beaconId = intent.getIntExtra(RelayService.EXTRA_BEACON_ID, 0)
                    connectedPeers[address] = beaconId
                    updateUI()
                }
                RelayService.ACTION_PEER_DISCONNECTED -> {
                    val address = intent.getStringExtra(RelayService.EXTRA_PEER_ADDRESS) ?: return
                    connectedPeers.remove(address)
                    updateUI()
                }
                RelayService.ACTION_MESSAGE_RECEIVED -> {
                    loadConversations()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        tvStatus = findViewById(R.id.tvStatus)
        rvConversations = findViewById(R.id.rvConversations)
        btnBroadcast = findViewById(R.id.btnBroadcast)

        conversationAdapter = ConversationAdapter(
            onConversationClick = { address, peerId ->
                val intent = Intent(this, ChatActivity::class.java).apply {
                    putExtra("peer_address", address)
                    putExtra("peer_beacon_id", peerId)
                }
                startActivity(intent)
            },
            onConversationLongClick = { peerId ->
                showDeleteConversationDialog(peerId)
            }
        )
        
        rvConversations.layoutManager = LinearLayoutManager(this)
        rvConversations.adapter = conversationAdapter

        btnBroadcast.setOnClickListener { showBroadcastDialog() }

        checkAndRequestPermissions()
    }
    
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(RelayService.ACTION_PEER_CONNECTED)
            addAction(RelayService.ACTION_PEER_DISCONNECTED)
            addAction(RelayService.ACTION_MESSAGE_RECEIVED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceReceiver, filter)
        }
        
        val syncIntent = Intent(this, RelayService::class.java).apply {
            action = "SYNC_STATE"
        }
        startService(syncIntent)
        
        loadConversations()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(serviceReceiver)
    }
    
    private fun loadConversations() {
        CoroutineScope(Dispatchers.IO).launch {
            val conversations = db.messageDao().getConversationList()
            
            withContext(Dispatchers.Main) {
                conversationAdapter.setConversations(conversations)
            }
            
            // Load unread counts
            conversations.forEach {
                val peerId = if (it.direction == "OUTBOUND") it.recipientId else it.senderId
                val unread = db.messageDao().getUnreadCount(peerId)
                withContext(Dispatchers.Main) {
                    conversationAdapter.setUnreadCount(peerId, unread)
                }
            }
        }
    }

    private fun updateUI() {
        tvStatus.text = "${connectedPeers.size} peers online"
        conversationAdapter.setOnlinePeers(connectedPeers)
        loadConversations()
    }

    private fun showBroadcastDialog() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("Broadcast Message")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val text = input.text.toString()
                if (text.isNotBlank()) {
                    val intent = Intent(this, RelayService::class.java).apply {
                        action = "BROADCAST_MESSAGE"
                        putExtra("message", text)
                    }
                    startService(intent)
                    Toast.makeText(this, "Broadcast sent", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        val permissions = mutableListOf<String>()
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        
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
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startMeshService()
            } else {
                Toast.makeText(this, "Permissions Denied. Cannot start MeshLink.", Toast.LENGTH_LONG).show()
            }
        }
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
