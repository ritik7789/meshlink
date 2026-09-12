package com.meshlink

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * One call at a time, and everything that can happen to it.
 *
 * The state machine lives in the relay service rather than in an activity
 * because a call has to survive the screen: locking the phone, switching apps
 * or rotating must not drop it. The activity is a view onto this, and can come
 * and go while the call continues.
 *
 * Signalling and audio arrive here from two different places — signalling from
 * the mesh's envelope path, audio straight off the link — and both are funnelled
 * through one object so there is a single answer to "are we on a call".
 */
class CallSession(
    private val context: Context,
    private val localBeaconId: () -> Int,
    private val sendSignal: (Int, String, uniffi.meshlink_core.PayloadType) -> Boolean,
    private val sendAudioPacket: (Int, ByteArray) -> Boolean,
    private val isDirectNeighbour: (Int) -> Boolean,
    private val prepareAudio: () -> Unit,
    private val onStateChanged: (State) -> Unit
) {

    companion object {
        private const val TAG = "CallSession"

        /** Broadcast when anything about the call changes, for the UI. */
        const val ACTION_CALL_STATE = "com.meshlink.CALL_STATE"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_PEER = "peer"
        const val EXTRA_PEER_NAME = "peer_name"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_INCOMING = "incoming"
        const val EXTRA_STARTED_AT = "started_at"
        const val EXTRA_MUTED = "muted"
        const val EXTRA_SPEAKER = "speaker"
        const val EXTRA_REASON = "reason"
    }

    enum class Phase {
        /** Nothing happening. */
        IDLE,

        /** We rang someone and are waiting for them to pick up. */
        DIALING,

        /** Someone rang us and the user has not answered yet. */
        RINGING,

        /** Audio is flowing in both directions. */
        ACTIVE,

        /** Finished. Carries the reason, then falls back to [IDLE]. */
        ENDED
    }

    data class State(
        val phase: Phase,
        val peerBeaconId: Int = 0,
        val callId: String = "",
        val incoming: Boolean = false,
        val startedAt: Long = 0L,
        val reason: CallProtocol.Reason? = null
    )

    /** Why a dial attempt could not even start. */
    sealed class DialResult {
        object Ringing : DialResult()
        object AlreadyOnACall : DialResult()

        /**
         * The peer is reachable through the mesh but not on a direct link.
         * Relaying audio would mean every hop carrying a kilobyte a second in
         * each direction, and adding its own latency on top — a call routed over
         * two hops is not a worse call, it is an unusable one.
         */
        object NotDirectlyReachable : DialResult()
        data class Failed(val message: String) : DialResult()
    }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Runs a state transition on the main thread.
     *
     * Signalling arrives on whichever Bluetooth callback thread delivered the
     * envelope, while the buttons arrive on the main thread, and both mutate the
     * same state machine. Funnelling them through one thread is what makes
     * "check the phase, then act on it" actually atomic — otherwise a hangup
     * landing while the user presses answer can interleave halfway through.
     *
     * Audio packets deliberately do not come this way: they are the hot path,
     * they touch only a lock-free queue, and a hop through the main thread would
     * add exactly the latency the rest of this class works to avoid.
     */
    private inline fun onMain(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else handler.post { action() }
    }
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile
    var state: State = State(Phase.IDLE)
        private set

    private var audio: CallAudio? = null
    private var lastPacketAt = 0L

    /** True while a call occupies the radio, for the service's own housekeeping. */
    val isBusy: Boolean
        get() = state.phase != Phase.IDLE && state.phase != Phase.ENDED

    // ── Outgoing ─────────────────────────────────────────────────────────────

    fun dial(peerBeaconId: Int): DialResult {
        if (isBusy) return DialResult.AlreadyOnACall
        if (!isDirectNeighbour(peerBeaconId)) return DialResult.NotDirectlyReachable

        val callId = UUID.randomUUID().toString()
        val invite = CallProtocol.Invite(callId).encode()
        if (!sendSignal(peerBeaconId, invite, uniffi.meshlink_core.PayloadType.CALL_INVITE)) {
            return DialResult.Failed("Could not reach that node")
        }

        moveTo(State(Phase.DIALING, peerBeaconId, callId, incoming = false))
        handler.postDelayed(ringTimeout, CallProtocol.RING_TIMEOUT_MS)
        Log.i(TAG, "Dialling $peerBeaconId as $callId")
        return DialResult.Ringing
    }

    // ── Incoming ─────────────────────────────────────────────────────────────

    fun onInvite(senderId: Int, invite: CallProtocol.Invite) = onMain {
        val current = state

        // Glare: both sides rang at the same moment. Someone has to give way, so
        // the lower beacon id's call wins. Without a rule both ends decline each
        // other as busy and neither call ever connects.
        if (current.phase == Phase.DIALING && current.peerBeaconId == senderId) {
            if (localBeaconId() < senderId) {
                Log.i(TAG, "Glare with $senderId: keeping our call")
                return@onMain
            }
            Log.i(TAG, "Glare with $senderId: yielding to theirs")
            handler.removeCallbacks(ringTimeout)
        } else if (isBusy) {
            sendSignal(
                senderId,
                CallProtocol.Decline(invite.callId, CallProtocol.Reason.BUSY).encode(),
                uniffi.meshlink_core.PayloadType.CALL_DECLINE
            )
            Log.i(TAG, "Declined $senderId: already on a call")
            return@onMain
        }

        moveTo(State(Phase.RINGING, senderId, invite.callId, incoming = true))
        handler.postDelayed(ringTimeout, CallProtocol.RING_TIMEOUT_MS)
        Log.i(TAG, "Incoming call from $senderId as ${invite.callId}")
    }

    fun accept() {
        val current = state
        if (current.phase != Phase.RINGING) return

        handler.removeCallbacks(ringTimeout)
        sendSignal(
            current.peerBeaconId,
            CallProtocol.Accept(current.callId).encode(),
            uniffi.meshlink_core.PayloadType.CALL_ACCEPT
        )
        beginAudio(current)
    }

    fun decline() {
        val current = state
        if (current.phase != Phase.RINGING) return

        handler.removeCallbacks(ringTimeout)
        sendSignal(
            current.peerBeaconId,
            CallProtocol.Decline(current.callId, CallProtocol.Reason.DECLINED).encode(),
            uniffi.meshlink_core.PayloadType.CALL_DECLINE
        )
        finish(CallProtocol.Reason.DECLINED)
    }

    // ── Signalling from the far side ─────────────────────────────────────────

    fun onAccept(senderId: Int, accept: CallProtocol.Accept) = onMain {
        val current = state
        if (current.phase != Phase.DIALING) return@onMain
        if (current.callId != accept.callId || current.peerBeaconId != senderId) return@onMain

        handler.removeCallbacks(ringTimeout)
        beginAudio(current)
    }

    fun onDecline(senderId: Int, decline: CallProtocol.Decline) = onMain {
        val current = state
        if (current.phase != Phase.DIALING) return@onMain
        if (current.callId != decline.callId || current.peerBeaconId != senderId) return@onMain

        handler.removeCallbacks(ringTimeout)
        finish(decline.reason)
    }

    fun onEnd(senderId: Int, end: CallProtocol.End) = onMain {
        val current = state
        // The call id is what makes a late hangup from a previous call harmless:
        // redialling a flaky link produces exactly that race.
        if (!isBusy || current.callId != end.callId || current.peerBeaconId != senderId) return@onMain

        handler.removeCallbacks(ringTimeout)
        finish(end.reason)
    }

    /** One voice packet from the link. Ignored unless it belongs to this call. */
    fun onAudioPacket(senderId: Int, packet: ByteArray) {
        val current = state
        if (current.phase != Phase.ACTIVE || current.peerBeaconId != senderId) return
        lastPacketAt = System.currentTimeMillis()
        audio?.receive(packet)
    }

    // ── Ending ───────────────────────────────────────────────────────────────

    fun hangUp(reason: CallProtocol.Reason = CallProtocol.Reason.HUNG_UP) {
        val current = state
        if (!isBusy) return

        handler.removeCallbacks(ringTimeout)
        sendSignal(
            current.peerBeaconId,
            CallProtocol.End(current.callId, reason).encode(),
            uniffi.meshlink_core.PayloadType.CALL_END
        )
        finish(reason)
    }

    /** The peer's link went away, so there is nobody left to tell. */
    fun onPeerLost(beaconId: Int) = onMain {
        if (!isBusy || state.peerBeaconId != beaconId) return@onMain
        Log.i(TAG, "Peer $beaconId vanished mid-call")
        finish(CallProtocol.Reason.LINK_LOST)
    }

    /** Bluetooth off, service stopping, feature disabled. */
    fun shutdown(reason: CallProtocol.Reason) {
        if (!isBusy) return
        hangUp(reason)
    }

    fun setMuted(value: Boolean) = audio?.setMuted(value)

    fun setSpeaker(on: Boolean) = audio?.setSpeaker(on)

    // ── Internals ────────────────────────────────────────────────────────────

    private fun beginAudio(current: State) {
        // Before the microphone is touched, not after. From Android 14 a service
        // may only record while its foreground type includes `microphone`, and
        // answering from a notification with the screen off happens with the app
        // in the background — where opening the mic without that type yields
        // silence or an outright failure. Promoting first closes that window.
        prepareAudio()

        val engine = CallAudio(
            audioManager = audioManager,
            onPacket = { packet -> sendAudioPacket(current.peerBeaconId, packet) },
            onFailure = { message ->
                handler.post {
                    Log.e(TAG, "Audio failed: $message")
                    hangUp(CallProtocol.Reason.FAILED)
                }
            }
        )
        audio = engine

        if (!engine.start()) {
            audio = null
            hangUp(CallProtocol.Reason.FAILED)
            return
        }

        lastPacketAt = System.currentTimeMillis()
        moveTo(current.copy(phase = Phase.ACTIVE, startedAt = System.currentTimeMillis()))
        handler.postDelayed(mediaWatchdog, CallProtocol.MEDIA_TIMEOUT_MS)
        Log.i(TAG, "Call ${current.callId} active with ${current.peerBeaconId}")
    }

    private fun finish(reason: CallProtocol.Reason) {
        handler.removeCallbacks(mediaWatchdog)
        handler.removeCallbacks(ringTimeout)
        audio?.stop()
        audio = null

        moveTo(state.copy(phase = Phase.ENDED, reason = reason))
        Log.i(TAG, "Call ended: $reason")

        // Held briefly in ENDED so the screen can show why before it closes,
        // then cleared so the next call starts from a clean slate.
        handler.postDelayed({
            if (state.phase == Phase.ENDED) moveTo(State(Phase.IDLE))
        }, 2_000)
    }

    private fun moveTo(next: State) {
        state = next
        onStateChanged(next)
    }

    private val ringTimeout = Runnable {
        if (state.phase == Phase.DIALING || state.phase == Phase.RINGING) {
            Log.i(TAG, "Nobody answered ${state.callId}")
            hangUp(CallProtocol.Reason.UNANSWERED)
        }
    }

    /**
     * Ends a call that has gone quiet.
     *
     * A link can drop without either GATT layer noticing promptly — walking out
     * of range does not always produce a disconnect callback — and a call that
     * sits silently "active" forever is worse than one that ends. Silence alone
     * does not trigger this: a muted peer still sends packets, precisely so the
     * far side can tell the difference between quiet and gone.
     */
    private val mediaWatchdog = object : Runnable {
        override fun run() {
            if (state.phase != Phase.ACTIVE) return
            if (System.currentTimeMillis() - lastPacketAt > CallProtocol.MEDIA_TIMEOUT_MS) {
                Log.w(TAG, "No audio for ${CallProtocol.MEDIA_TIMEOUT_MS}ms; ending call")
                hangUp(CallProtocol.Reason.LINK_LOST)
                return
            }
            handler.postDelayed(this, CallProtocol.MEDIA_TIMEOUT_MS / 3)
        }
    }
}
