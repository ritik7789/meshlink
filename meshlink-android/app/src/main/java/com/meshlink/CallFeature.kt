package com.meshlink

import android.app.Activity
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton

/**
 * The single door to everything voice-calling.
 *
 * Every entry point the feature needs — a button in the chat header, inbound
 * signalling, the audio transport, teardown — passes through here, so disabling
 * it is one line in [Features] rather than an audit of the codebase. Each method
 * checks the flag itself and does nothing when off, which means callers never
 * need a guard of their own and cannot forget one.
 *
 * The methods are stubs today. They exist first on purpose: the implementation
 * lands inside an already-guarded shape, rather than being retro-fitted with a
 * switch afterwards and missing a path.
 */
object CallFeature {

    private const val TAG = "CallFeature"

    /** Request code for the microphone prompt, forwarded back by the activity. */
    const val MIC_PERMISSION_REQUEST = 4201

    /** Who was being called when the prompt interrupted, so it can resume. */
    private var pendingPeerBeaconId: Int? = null

    /** Whether calling is available, for callers that need to ask. */
    const val isEnabled: Boolean = Features.VOICE_CALLS

    /**
     * Adds a call button to a chat header, or nothing at all when disabled.
     *
     * The button is created here rather than declared in the layout so a
     * disabled build has no dead view in its hierarchy.
     */
    fun attachCallAction(
        activity: Activity,
        header: ViewGroup,
        peerBeaconId: Int,
        isGroup: Boolean
    ) {
        if (!Features.VOICE_CALLS) return
        // Group calling is a separate problem from one-to-one: mixing several
        // streams over a mesh is not a variation of the direct case.
        if (isGroup) return

        val button = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_call)
            background = null
            imageTintList = android.content.res.ColorStateList
                .valueOf(android.graphics.Color.parseColor("#E9EDEF"))
            layoutParams = ViewGroup.LayoutParams(dp(activity, 48), dp(activity, 48))
            contentDescription = "Call"
            setOnClickListener { startCall(activity, peerBeaconId) }
        }
        UiMotion.attachPressFeedback(button)
        header.addView(button)
    }

    /**
     * Begins an outgoing call, asking for the microphone only at this point.
     *
     * The permission is never requested at launch: the app works fully without
     * it, and asking for a microphone before there is anything to say invites a
     * refusal that then has to be undone in system settings. Asking here means
     * the request arrives with an obvious reason attached.
     */
    fun startCall(activity: Activity, peerBeaconId: Int) {
        if (!Features.VOICE_CALLS) return

        if (!hasMicrophonePermission(activity)) {
            requestMicrophone(activity, peerBeaconId)
            return
        }
        beginCall(activity, peerBeaconId)
    }

    /**
     * The point a real call would start. Today it only reports that it cannot.
     *
     * Deliberately *not* calling [setCallActive]: that suspends scanning and
     * presence for the duration of a call, and a stub call has no duration —
     * nothing ever ends it, so the mesh would stop discovering peers from the
     * first tap of the button until the service was restarted. The
     * prioritisation path stays wired up and tested; it is simply not claimed
     * by a call that does not exist yet.
     */
    private fun beginCall(activity: Activity, peerBeaconId: Int) {
        if (!Features.VOICE_CALLS) return
        Log.i(TAG, "Call requested to $peerBeaconId (not implemented)")
        android.widget.Toast.makeText(
            activity, "Calling isn't available yet", android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    fun hasMicrophonePermission(context: android.content.Context): Boolean {
        if (!Features.VOICE_CALLS) return false
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun requestMicrophone(activity: Activity, peerBeaconId: Int) {
        if (!Features.VOICE_CALLS) return
        pendingPeerBeaconId = peerBeaconId

        // A second ask deserves a reason; the first does not need one, and
        // front-loading an explanation before the system prompt reads as nagging.
        if (androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                activity, android.Manifest.permission.RECORD_AUDIO
            )
        ) {
            androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("Allow microphone to call?")
                .setMessage("MeshLink needs the microphone only while you are on a call.")
                .setPositiveButton("Allow") { _, _ -> askSystem(activity) }
                .setNegativeButton("Not now") { _, _ -> pendingPeerBeaconId = null }
                .show()
        } else {
            askSystem(activity)
        }
    }

    private fun askSystem(activity: Activity) {
        if (!Features.VOICE_CALLS) return
        androidx.core.app.ActivityCompat.requestPermissions(
            activity,
            arrayOf(android.Manifest.permission.RECORD_AUDIO),
            MIC_PERMISSION_REQUEST
        )
    }

    /**
     * Handles the microphone prompt's outcome. Returns true when it was ours.
     *
     * A refusal is simply accepted — no call starts, nothing is disabled
     * elsewhere, and the user is not asked again until they next try to call.
     */
    fun onPermissionResult(
        activity: Activity,
        requestCode: Int,
        grantResults: IntArray
    ): Boolean {
        if (!Features.VOICE_CALLS) return false
        if (requestCode != MIC_PERMISSION_REQUEST) return false

        val peer = pendingPeerBeaconId
        pendingPeerBeaconId = null

        // An empty result means the prompt was dismissed rather than answered —
        // tapping outside it, or the screen rotating underneath it. That is not
        // a refusal, and treating it as one would pop the Settings dialog at
        // someone who has not actually said no to anything yet.
        if (grantResults.isEmpty()) return true

        val granted = grantResults.firstOrNull() ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        when {
            granted && peer != null -> beginCall(activity, peer)

            // Denied and the system will no longer show its prompt: the only way
            // back is Settings, so offer that rather than silently doing nothing.
            !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                activity, android.Manifest.permission.RECORD_AUDIO
            ) -> offerSettings(activity)

            else -> android.widget.Toast.makeText(
                activity, "Calling needs the microphone", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        return true
    }

    private fun offerSettings(activity: Activity) {
        if (!Features.VOICE_CALLS) return
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("Microphone is off for MeshLink")
            .setMessage("Turn it on in Settings to make calls. Everything else keeps working without it.")
            .setPositiveButton("Open settings") { _, _ ->
                runCatching {
                    activity.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", activity.packageName, null)
                        )
                    )
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    /**
     * Tells the relay service a call is starting or finishing.
     *
     * While one is live the service stops the periodic work that interrupts the
     * radio — the scan cycle above all, whose one-second gap every fifteen
     * seconds is an audible dropout — and asks for a faster connection interval
     * on the link carrying the audio.
     */
    fun setCallActive(context: android.content.Context, active: Boolean, peerBeaconId: Int) {
        if (!Features.VOICE_CALLS) return
        context.startService(
            android.content.Intent(context, RelayService::class.java).apply {
                action = RelayService.ACTION_CALL_STATE
                putExtra(RelayService.EXTRA_CALL_ACTIVE, active)
                putExtra(RelayService.EXTRA_BEACON_ID, peerBeaconId)
            }
        )
    }

    /**
     * Offers an inbound envelope to the call layer.
     *
     * Returns true when the payload belonged to calling and has been consumed,
     * so the caller should stop processing it. Always false when disabled, which
     * makes call traffic from a node that does have the feature fall through to
     * the normal path and be discarded as an unrecognised payload.
     *
     * [isSenderBlocked] is supplied by the caller because the block list lives
     * with the relay service. A blocked node must not be able to make the phone
     * ring, and a call arrives ahead of the text path's own block check, so this
     * has to be consulted here rather than relied upon downstream.
     */
    fun handleIncomingPayload(
        envelope: uniffi.meshlink_core.MessageEnvelope,
        isSenderBlocked: (Int) -> Boolean
    ): Boolean {
        if (!Features.VOICE_CALLS) return false

        val sender = envelope.senderId.toInt()
        if (isSenderBlocked(sender)) {
            // Swallowed rather than passed on: nothing rings, and it does not
            // fall through to be logged as unrecognised traffic either.
            Log.i(TAG, "Ignoring call payload from blocked node $sender")
            return true
        }
        return false
    }

    /**
     * Registers the real-time audio characteristic on the GATT server.
     *
     * Voice needs its own unreliable channel: the message characteristic
     * retries and reassembles, and for audio a late frame is worse than a lost
     * one. Nothing is added when the feature is off, so a disabled build does
     * not advertise a channel it will never serve.
     */
    fun registerTransport(service: android.bluetooth.BluetoothGattService) {
        if (!Features.VOICE_CALLS) return
    }

    /** Tears down any active call, e.g. when the radio goes away. */
    fun endAllCalls(context: android.content.Context, reason: String) {
        if (!Features.VOICE_CALLS) return
        Log.i(TAG, "Ending calls: $reason")
        setCallActive(context, false, 0)
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
