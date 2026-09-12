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
                RelayService.ACTION_MESSAGE_SENT -> loadConversations()
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
            onConversationLongClick = { peerId ->
                showDeleteConversationDialog(peerId)
            }
        )
        
        rvConversations.layoutManager = LinearLayoutManager(this)
        rvConversations.adapter = conversationAdapter

        btnBroadcast.setOnClickListener { showNewChatDialog() }

        // Both the banner and its action label re-open the enable prompt, so a
        // user who dismissed it once still has an obvious way back.
        bannerBluetooth.setOnClickListener { promptEnableBluetooth() }
        findViewById<TextView>(R.id.btnEnableBluetooth).setOnClickListener { promptEnableBluetooth() }

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar.setTitleTextColor(android.graphics.Color.WHITE)
        toolbar.title = "MeshLink"
        setSupportActionBar(toolbar)

        checkAndRequestPermissions()
    }
    
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(RelayService.ACTION_ROSTER_UPDATED)
            addAction(RelayService.ACTION_BLUETOOTH_STATE)
            addAction(RelayService.ACTION_MESSAGE_RECEIVED)
            addAction(RelayService.ACTION_MESSAGE_SENT)
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
            withContext(Dispatchers.Main) {
                conversationAdapter.setConversations(convos)
                tvEmptyState.visibility = if (convos.isEmpty()) View.VISIBLE else View.GONE
            }
            
            // Load unread counts
            convos.forEach {
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

    private fun openChat(peerId: Long) {
        startActivity(Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_PEER_BEACON_ID, peerId)
        })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_set_username -> {
                showSetUsernameDialog()
                true
            }
            R.id.action_starred_messages -> {
                showStarredMessagesDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSetUsernameDialog() {
        val input = EditText(this)
        val prefs = getSharedPreferences(RelayService.PREFS_NAME, MODE_PRIVATE)
        input.setText(prefs.getString(RelayService.PREF_USERNAME, ""))
        input.hint = "Enter your username"
        
        AlertDialog.Builder(this)
            .setTitle("Set Username")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotBlank()) {
                    prefs.edit().putString(RelayService.PREF_USERNAME, text).apply()
                    // The name travels in presence gossip, so re-announcing
                    // pushes it to the whole mesh rather than only to neighbours.
                    val intent = Intent(this, RelayService::class.java).apply {
                        action = RelayService.ACTION_ANNOUNCE_PRESENCE
                    }
                    startService(intent)
                    Toast.makeText(this, "Username updated", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showStarredMessagesDialog() {
        CoroutineScope(Dispatchers.IO).launch {
            val starred = db.messageDao().getStarredMessages()
            withContext(Dispatchers.Main) {
                if (starred.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No starred messages", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                
                val texts = starred.map { it.plaintext }.toTypedArray()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Starred Messages")
                    .setItems(texts) { _, _ -> }
                    .setPositiveButton("Close", null)
                    .show()
            }
        }
    }

    /**
     * Offers every node the mesh can currently reach, not just direct
     * neighbours, with its distance shown so relayed peers are identifiable.
     */
    private fun showNewChatDialog() {
        val targets = mutableListOf<Pair<String, Long>>()
        targets.add(Pair("Broadcast to All", 0L))

        reachableNodes.entries
            .sortedWith(compareBy({ it.value }, { it.key }))
            .forEach { (peerId, hops) ->
                val name = nodeNames[peerId] ?: defaultNodeName(rowToBeaconId(peerId))
                val label = if (hops > 1) "$name — $hops hops away" else "$name — direct"
                targets.add(Pair(label, peerId))
            }

        if (targets.size == 1) {
            Toast.makeText(this, "No other nodes reachable yet", Toast.LENGTH_SHORT).show()
        }

        AlertDialog.Builder(this)
            .setTitle("New Chat")
            .setItems(targets.map { it.first }.toTypedArray()) { _, which ->
                val (_, peerId) = targets[which]
                if (peerId == 0L) showBroadcastDialog() else openChat(peerId)
            }
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
