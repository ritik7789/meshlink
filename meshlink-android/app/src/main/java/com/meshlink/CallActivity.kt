package com.meshlink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * The call screen: a window onto [CallSession], which lives in the service.
 *
 * It holds no call state of its own beyond what it is currently drawing. Every
 * button sends an intent to the service and every change comes back as a
 * broadcast, so closing this screen — or having it killed — does not touch the
 * call. That is the whole reason the session is not here.
 */
class CallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PEER_ROW = "peer_row"
        const val EXTRA_PEER_NAME = "peer_name"
    }

    private lateinit var avatar: TextView
    private lateinit var name: TextView
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var row: LinearLayout
    private lateinit var cellMute: LinearLayout
    private lateinit var cellSpeaker: LinearLayout
    private lateinit var cellEnd: LinearLayout
    private lateinit var cellAnswer: LinearLayout
    private lateinit var declineLabel: TextView
    private lateinit var muteLabel: TextView
    private lateinit var btnMute: ImageButton
    private lateinit var btnSpeaker: ImageButton
    private lateinit var btnAnswer: ImageButton
    private lateinit var btnDecline: ImageButton

    private val handler = Handler(Looper.getMainLooper())
    private var peerRow = 0L
    private var startedAt = 0L
    private var muted = false
    private var speaker = false
    private var phase = CallSession.Phase.IDLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        setContentView(R.layout.activity_call)

        avatar = findViewById(R.id.tvCallAvatar)
        name = findViewById(R.id.tvCallName)
        status = findViewById(R.id.tvCallStatus)
        hint = findViewById(R.id.tvCallHint)
        row = findViewById(R.id.callRow)
        cellMute = findViewById(R.id.cellMute)
        cellSpeaker = findViewById(R.id.cellSpeaker)
        cellEnd = findViewById(R.id.cellEnd)
        cellAnswer = findViewById(R.id.cellAnswer)
        declineLabel = findViewById(R.id.tvDeclineLabel)
        muteLabel = findViewById(R.id.tvMuteLabel)
        btnMute = findViewById(R.id.btnMute)
        btnSpeaker = findViewById(R.id.btnSpeaker)
        btnAnswer = findViewById(R.id.btnAnswer)
        btnDecline = findViewById(R.id.btnDecline)

        listOf<View>(btnMute, btnSpeaker, btnAnswer, btnDecline)
            .forEach { UiMotion.attachPressFeedback(it) }

        applyPeer(intent)

        btnAnswer.setOnClickListener { answer() }
        btnDecline.setOnClickListener {
            command(
                if (phase == CallSession.Phase.RINGING) RelayService.ACTION_CALL_DECLINE
                else RelayService.ACTION_CALL_HANGUP
            )
        }
        // Neither button paints itself. Both ask the service and wait for the
        // state to come back, because the same toggles exist in the notification
        // shade — a button that updated locally would disagree with the shade
        // the moment the other one was used.
        btnMute.setOnClickListener { command(RelayService.ACTION_CALL_MUTE, !muted) }
        btnSpeaker.setOnClickListener { command(RelayService.ACTION_CALL_SPEAKER, !speaker) }
    }

    /**
     * Answers, asking for the microphone first if it has never been granted.
     *
     * The caller is asked when they press call; the callee has to be asked here,
     * because until now nothing on this device needed a microphone. Without this
     * answering simply failed: the audio engine could not open the mic and the
     * call ended as "Call failed", with no hint that a permission was the reason
     * and no way to grant it.
     */
    private fun answer() {
        if (!CallFeature.hasMicrophonePermission(this)) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                CallFeature.MIC_PERMISSION_REQUEST
            )
            return
        }
        command(RelayService.ACTION_CALL_ACCEPT)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CallFeature.MIC_PERMISSION_REQUEST) return

        val granted = grantResults.firstOrNull() ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        when {
            granted -> command(RelayService.ACTION_CALL_ACCEPT)
            // Refusing the microphone is a refusal of the call, not an error:
            // decline it so the caller stops ringing instead of waiting out the
            // timeout wondering whether anyone is there.
            grantResults.isNotEmpty() -> {
                Toast.makeText(this, "Calls need the microphone", Toast.LENGTH_SHORT).show()
                command(RelayService.ACTION_CALL_DECLINE)
            }
        }
    }

    /** A second call can reuse this screen rather than stacking another. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyPeer(intent)
    }

    private fun applyPeer(intent: Intent?) {
        peerRow = intent?.getLongExtra(EXTRA_PEER_ROW, 0L) ?: 0L
        val label = intent?.getStringExtra(EXTRA_PEER_NAME)?.takeIf { it.isNotBlank() }
            ?: "Node $peerRow"
        name.text = label
        avatar.text = label.trim().take(1).uppercase()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(CallSession.ACTION_CALL_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(callReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(callReceiver, filter)
        }
        handler.post(ticker)

        // The broadcast that opened this screen was almost certainly sent before
        // the receiver above existed, so ask for the current state rather than
        // sitting on a blank screen until something else changes.
        command(RelayService.ACTION_CALL_SYNC)
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(callReceiver) }
        handler.removeCallbacks(ticker)
    }

    /**
     * The back button does not end a call.
     *
     * Leaving the screen while still talking is normal — looking something up
     * mid-call should not hang up on the person. The call ends from the red
     * button, or from the far side.
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    // ── State ────────────────────────────────────────────────────────────────

    private val callReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != CallSession.ACTION_CALL_STATE) return

            val reason = intent.getStringExtra(CallSession.EXTRA_REASON)
            val next = runCatching {
                CallSession.Phase.valueOf(
                    intent.getStringExtra(CallSession.EXTRA_PHASE) ?: return
                )
            }.getOrNull() ?: return

            // A problem reported before any call exists arrives as IDLE with a
            // human-readable reason rather than an enum: say it and close, since
            // there is no call for this screen to show.
            if (next == CallSession.Phase.IDLE && reason != null &&
                CallProtocol.Reason.entries.none { it.name == reason }
            ) {
                Toast.makeText(this@CallActivity, reason, Toast.LENGTH_LONG).show()
                finish()
                return
            }

            // The service is the authority on who this call is with: the screen
            // may have been opened from a notification built before the peer's
            // name arrived over presence.
            val peer = intent.getLongExtra(CallSession.EXTRA_PEER, 0L)
            if (peer != 0L) {
                peerRow = peer
                intent.getStringExtra(CallSession.EXTRA_PEER_NAME)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { label ->
                        name.text = label
                        avatar.text = label.trim().take(1).uppercase()
                    }
            }
            startedAt = intent.getLongExtra(CallSession.EXTRA_STARTED_AT, 0L)
            muted = intent.getBooleanExtra(CallSession.EXTRA_MUTED, false)
            speaker = intent.getBooleanExtra(CallSession.EXTRA_SPEAKER, false)
            render(next, reason)
        }
    }

    private fun render(next: CallSession.Phase, reason: String?) {
        phase = next
        when (next) {
            CallSession.Phase.RINGING -> {
                status.text = "Incoming call"
                hint.visibility = View.GONE
                showCells(mute = false, speaker = false, end = true, answer = true)
                declineLabel.text = "Decline"
            }
            CallSession.Phase.DIALING -> {
                status.text = "Calling…"
                hint.visibility = View.GONE
                // Nothing to mute or route until the far side picks up, so the
                // row holds only the one control that does anything yet.
                showCells(mute = false, speaker = false, end = true, answer = false)
                declineLabel.text = "End"
            }
            CallSession.Phase.ACTIVE -> {
                hint.visibility = View.GONE
                showCells(mute = true, speaker = true, end = true, answer = false)
                declineLabel.text = "End"
                paintToggles()
            }
            CallSession.Phase.ENDED -> {
                status.text = endedText(reason)
                row.visibility = View.GONE
                handler.postDelayed({ finish() }, 1_400)
            }
            CallSession.Phase.IDLE -> finish()
        }
    }

    private fun showCells(mute: Boolean, speaker: Boolean, end: Boolean, answer: Boolean) {
        row.visibility = View.VISIBLE
        cellMute.visibility = if (mute) View.VISIBLE else View.GONE
        cellSpeaker.visibility = if (speaker) View.VISIBLE else View.GONE
        cellEnd.visibility = if (end) View.VISIBLE else View.GONE
        cellAnswer.visibility = if (answer) View.VISIBLE else View.GONE
    }

    /** An engaged toggle inverts: light circle, dark glyph. */
    private fun paintToggles() {
        paintToggle(btnMute, muted, if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic_on)
        paintToggle(btnSpeaker, speaker, R.drawable.ic_speaker)
        muteLabel.text = if (muted) "Unmute" else "Mute"
    }

    private fun paintToggle(button: ImageButton, on: Boolean, icon: Int) {
        button.setImageResource(icon)
        button.setBackgroundResource(
            if (on) R.drawable.bg_call_control_on else R.drawable.bg_call_control
        )
        button.imageTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor(if (on) "#0B141A" else "#E9EDEF")
        )
    }

    private fun endedText(reason: String?): String = when (reason) {
        CallProtocol.Reason.DECLINED.name -> "Call declined"
        CallProtocol.Reason.BUSY.name -> "On another call"
        CallProtocol.Reason.UNANSWERED.name -> "No answer"
        CallProtocol.Reason.LINK_LOST.name -> "Connection lost"
        CallProtocol.Reason.FAILED.name -> "Call failed"
        else -> "Call ended"
    }

    /** Counts up while the call is live; the timer is the only thing polling. */
    private val ticker = object : Runnable {
        override fun run() {
            if (phase == CallSession.Phase.ACTIVE && startedAt > 0) {
                val seconds = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
                status.text = String.format("%02d:%02d", seconds / 60, seconds % 60)
            }
            handler.postDelayed(this, 1_000)
        }
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    private fun command(action: String, value: Boolean? = null) {
        startService(
            Intent(this, RelayService::class.java).apply {
                this.action = action
                value?.let { putExtra(RelayService.EXTRA_CALL_ON, it) }
            }
        )
    }

    /**
     * Makes the screen usable without unlocking the phone, the way a call has
     * to be: the ring is worthless if answering it needs a PIN first.
     */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
