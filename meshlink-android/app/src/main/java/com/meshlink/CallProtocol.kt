package com.meshlink

import org.json.JSONObject

/**
 * Wire shapes for call signalling — the ringing, not the talking.
 *
 * These four messages travel as ordinary sealed envelopes, so they inherit the
 * mesh's encryption and sender authentication unchanged. They are small and
 * rare: four per call at most. The audio does not come through here at all; it
 * has its own characteristic, because a voice stream cannot afford the message
 * path's acknowledgements and retries.
 */
object CallProtocol {

    /** Sample rate the codec runs at. AMR-NB is defined at 8 kHz only. */
    const val SAMPLE_RATE = 8_000

    /** Samples per 20 ms frame at [SAMPLE_RATE]. */
    const val SAMPLES_PER_FRAME = 160

    /** Frames bundled into one radio packet, trading latency for airtime. */
    const val FRAMES_PER_PACKET = 4

    /** Milliseconds of audio in one packet. */
    const val PACKET_MS = 20 * FRAMES_PER_PACKET

    /**
     * AMR-NB bitrate, in bits per second.
     *
     * 7.95 kbps is the highest mode that still leaves the link comfortable:
     * about 21 bytes per 20 ms frame, so a four-frame packet is under 100 bytes
     * sealed, and a call costs roughly 1 KB/s in each direction. Every Android
     * device with a microphone is required to ship an AMR-NB encoder, which is
     * why this codec and not Opus — Opus would sound better at the same rate,
     * but its encoder is not guaranteed present and a call that cannot start on
     * some phones is worse than one that sounds thin on all of them.
     */
    const val BITRATE = 7_950

    /** MIME type of the codec, for MediaCodec. */
    const val MIME = "audio/3gpp"

    /** How long a ring goes unanswered before it gives up, in milliseconds. */
    const val RING_TIMEOUT_MS = 30_000L

    /**
     * How long a live call tolerates hearing nothing at all before it assumes
     * the other side is gone. Generous, because a few seconds of silence is a
     * peer walking behind a wall, not a peer that hung up.
     */
    const val MEDIA_TIMEOUT_MS = 12_000L

    /** Why a call ended, for the far side and for the call log. */
    enum class Reason {
        /** A person pressed the red button. */
        HUNG_UP,

        /** The callee is already on another call. */
        BUSY,

        /** The callee pressed decline. */
        DECLINED,

        /** Nobody picked up before [RING_TIMEOUT_MS]. */
        UNANSWERED,

        /** The link went away: out of range, Bluetooth off, peer shut down. */
        LINK_LOST,

        /** Something local made the call impossible — no codec, no microphone. */
        FAILED;

        companion object {
            fun from(raw: String?): Reason =
                entries.firstOrNull { it.name == raw } ?: HUNG_UP
        }
    }

    // ── Invite ───────────────────────────────────────────────────────────────

    /**
     * Rings a peer. [callId] tags every later message about this call, so a
     * hangup arriving from a previous call cannot end the current one — which
     * matters more than it sounds, because redialling a flaky link produces
     * exactly that race.
     */
    data class Invite(
        val callId: String,
        val bitrate: Int = BITRATE,
        val framesPerPacket: Int = FRAMES_PER_PACKET
    ) {
        fun encode(): String = JSONObject().apply {
            put("call", callId)
            put("rate", bitrate)
            put("fpp", framesPerPacket)
        }.toString()

        companion object {
            fun decode(raw: String): Invite? = runCatching {
                val json = JSONObject(raw)
                Invite(
                    callId = json.getString("call"),
                    bitrate = json.optInt("rate", BITRATE),
                    framesPerPacket = json.optInt("fpp", FRAMES_PER_PACKET)
                )
            }.getOrNull()
        }
    }

    // ── Accept, decline, end ─────────────────────────────────────────────────

    /** Picking up. Audio starts in both directions as soon as this is sent. */
    data class Accept(val callId: String) {
        fun encode(): String = JSONObject().apply { put("call", callId) }.toString()

        companion object {
            fun decode(raw: String): Accept? = runCatching {
                Accept(JSONObject(raw).getString("call"))
            }.getOrNull()
        }
    }

    /** Refusing a call that has not started, with the reason why. */
    data class Decline(val callId: String, val reason: Reason) {
        fun encode(): String = JSONObject().apply {
            put("call", callId)
            put("why", reason.name)
        }.toString()

        companion object {
            fun decode(raw: String): Decline? = runCatching {
                val json = JSONObject(raw)
                Decline(json.getString("call"), Reason.from(json.optString("why")))
            }.getOrNull()
        }
    }

    /** Ending a call that had started, or withdrawing one that had not. */
    data class End(val callId: String, val reason: Reason) {
        fun encode(): String = JSONObject().apply {
            put("call", callId)
            put("why", reason.name)
        }.toString()

        companion object {
            fun decode(raw: String): End? = runCatching {
                val json = JSONObject(raw)
                End(json.getString("call"), Reason.from(json.optString("why")))
            }.getOrNull()
        }
    }
}
