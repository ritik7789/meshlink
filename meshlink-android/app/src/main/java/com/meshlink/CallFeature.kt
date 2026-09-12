package com.meshlink

import android.app.Activity
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton

/**
 * The screen's door into calling: the button, and the microphone behind it.
 *
 * This is the half of the feature that belongs to an activity — putting a call
 * action in a chat header, asking for the microphone at the moment it is first
 * needed, and handling the answer. The call itself lives in [CallSession] inside
 * the relay service, because it has to outlive any screen.
 *
 * Every method checks [Features.VOICE_CALLS] itself, so callers never need a
 * guard of their own and cannot forget one. With the flag off this object adds
 * no view, attaches no listener and asks for nothing.
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
     * Hands the dial off to the relay service, which owns the call.
     *
     * The session deliberately does not live here: a call has to survive the
     * chat screen being closed, the phone being locked and the app being
     * switched away from, and only the foreground service outlives all three.
     * This object's job ends at asking.
     */
    private fun beginCall(activity: Activity, peerBeaconId: Int) {
        if (!Features.VOICE_CALLS) return

        activity.startService(
            android.content.Intent(activity, RelayService::class.java).apply {
                action = RelayService.ACTION_CALL_DIAL
                putExtra(RelayService.EXTRA_BEACON_ID, peerBeaconId)
            }
        )
        Log.i(TAG, "Dial requested to $peerBeaconId")
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

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
