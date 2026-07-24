package com.meshlink

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    
    private val PERMISSION_REQUEST_CODE = 100
    
    private lateinit var tvStatus: TextView
    private lateinit var llPeersContainer: LinearLayout
    private lateinit var llChatLog: LinearLayout
    private lateinit var svChatLog: ScrollView
    private lateinit var etMessage: EditText
    private lateinit var btnSendSelected: Button
    private lateinit var btnBroadcast: Button
    
    private val connectedPeers = mutableMapOf<String, Int>()
    private var selectedPeer: String? = null

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RelayService.ACTION_PEER_CONNECTED -> {
                    val address = intent.getStringExtra(RelayService.EXTRA_PEER_ADDRESS) ?: return
                    val beaconId = intent.getIntExtra(RelayService.EXTRA_BEACON_ID, 0)
                    connectedPeers[address] = beaconId
                    updatePeersUI()
                    appendLog("System", "Connected to $address (Beacon: $beaconId)")
                }
                RelayService.ACTION_PEER_DISCONNECTED -> {
                    val address = intent.getStringExtra(RelayService.EXTRA_PEER_ADDRESS) ?: return
                    connectedPeers.remove(address)
                    if (selectedPeer == address) {
                        selectedPeer = null
                    }
                    updatePeersUI()
                    appendLog("System", "Disconnected from $address")
                }
                RelayService.ACTION_MESSAGE_RECEIVED -> {
                    val address = intent.getStringExtra(RelayService.EXTRA_PEER_ADDRESS)
                    val message = intent.getStringExtra(RelayService.EXTRA_MESSAGE_DATA)
                    val senderBeacon = intent.getIntExtra("extra_sender_beacon", -1)
                    
                    val sender = if (senderBeacon != -1) "Beacon $senderBeacon" else (address ?: "Unknown")
                    if (message != null) {
                        appendLog(sender, message)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        tvStatus = findViewById(R.id.tvStatus)
        llPeersContainer = findViewById(R.id.llPeersContainer)
        llChatLog = findViewById(R.id.llChatLog)
        svChatLog = findViewById(R.id.svChatLog)
        etMessage = findViewById(R.id.etMessage)
        btnSendSelected = findViewById(R.id.btnSendSelected)
        btnBroadcast = findViewById(R.id.btnBroadcast)

        btnSendSelected.setOnClickListener { sendMessage() }
        btnBroadcast.setOnClickListener { broadcastMessage() }

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
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val db = com.meshlink.db.AppDatabase.getDatabase(this@MainActivity)
            val messages = db.messageDao().getAllMessages()
            
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                llChatLog.removeAllViews()
                messages.forEach { entity ->
                    try {
                        val env = uniffi.meshlink_core.deserializeEnvelope(entity.envelopeData)
                        appendLog("Beacon ${env.senderId}", env.encryptedPayload, isLocal = env.senderId.toInt() == 0) // Approximation
                    } catch(e: Exception) {}
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(serviceReceiver)
    }

    private fun updatePeersUI() {
        tvStatus.text = "${connectedPeers.size} peers connected"
        
        llPeersContainer.removeAllViews()
        for ((address, beaconId) in connectedPeers) {
            val peerView = TextView(this).apply {
                text = "Beacon: $beaconId\n$address"
                textSize = 14f
                setTextColor(Color.parseColor("#F8F8F2"))
                setPadding(24, 24, 24, 24)
                
                val bg = if (address == selectedPeer) "#6272A4" else "#282A36"
                setBackgroundColor(Color.parseColor(bg))
                
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, 8)
                }

                setOnClickListener {
                    selectedPeer = address
                    updatePeersUI()
                }
            }
            llPeersContainer.addView(peerView)
        }
    }

    private fun appendLog(sender: String, msg: String, isLocal: Boolean = false) {
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bg = if (sender == "System") "#44475A" else if (isLocal) "#50FA7B" else "#8BE9FD"
            val textCol = if (isLocal || sender != "System") "#282A36" else "#F8F8F2"
            
            setBackgroundColor(Color.parseColor(bg))
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 8)
                gravity = if (isLocal) Gravity.END else Gravity.START
            }

            val senderView = TextView(context).apply {
                text = sender
                textSize = 12f
                setTextColor(Color.parseColor(textCol))
                alpha = 0.8f
            }
            val msgView = TextView(context).apply {
                text = msg
                textSize = 16f
                setTextColor(Color.parseColor(textCol))
            }
            addView(senderView)
            addView(msgView)
        }
        llChatLog.addView(bubble)
        svChatLog.post { svChatLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun sendMessage() {
        val target = selectedPeer
        val text = etMessage.text.toString()
        if (target != null && text.isNotBlank()) {
            val intent = Intent(this, RelayService::class.java).apply {
                action = "SEND_MESSAGE"
                putExtra("address", target)
                putExtra("message", text)
            }
            startService(intent)
            appendLog("Me to $target", text, isLocal = true)
            etMessage.text.clear()
        } else {
            Toast.makeText(this, "Select a peer and type a message", Toast.LENGTH_SHORT).show()
        }
    }

    private fun broadcastMessage() {
        val text = etMessage.text.toString()
        if (text.isNotBlank()) {
            val intent = Intent(this, RelayService::class.java).apply {
                action = "BROADCAST_MESSAGE"
                putExtra("message", text)
            }
            startService(intent)
            appendLog("Me (Broadcast)", text, isLocal = true)
            etMessage.text.clear()
        } else {
            Toast.makeText(this, "Type a message to broadcast", Toast.LENGTH_SHORT).show()
        }
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
        appendLog("System", "MeshLink Service started")
    }
}
