package com.meshlink

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turns the microphone into packets and packets back into sound.
 *
 * Two threads, deliberately: capture-encode-send on one, receive-decode-play on
 * the other. Voice is the one thing in this app with a hard deadline — a packet
 * that arrives late is worse than one that never arrives, because the late one
 * has to be thrown away anyway after the delay it caused. Neither direction is
 * allowed to block the other, and neither touches the main thread.
 *
 * The packet on the wire is a sequence number and a handful of AMR frames:
 *
 *     seq (2 bytes, big-endian) | [len][frame] | [len][frame] | ...
 *
 * Length-prefixing each frame rather than assuming a fixed size costs one byte
 * per 20 ms and buys correctness: AMR emits shorter frames during silence, and
 * a fixed stride would desynchronise the decoder the moment someone stopped
 * talking. The sender seals this with the hop key before it goes out, so the
 * bytes above are never what is actually on the air.
 */
class CallAudio(
    private val audioManager: AudioManager,
    private val onPacket: (ByteArray) -> Unit,
    private val onFailure: (String) -> Unit
) {

    private companion object {
        const val TAG = "CallAudio"

        /** Bytes in one 20 ms frame of 16-bit mono PCM at 8 kHz. */
        const val PCM_FRAME_BYTES = CallProtocol.SAMPLES_PER_FRAME * 2

        /**
         * Packets held before the player starts dropping them.
         *
         * Small on purpose. A deep queue would smooth over jitter at the cost of
         * latency that never comes back: once the playout is a second behind it
         * stays a second behind for the rest of the call. Dropping instead keeps
         * the conversation close to live.
         */
        const val JITTER_CAPACITY = 6

        /** How far a sequence number may jump before it counts as a new stream. */
        const val RESYNC_GAP = 32
    }

    private val running = AtomicBoolean(false)
    private val muted = AtomicBoolean(false)

    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null

    private val inbound = ArrayBlockingQueue<ByteArray>(JITTER_CAPACITY)

    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false

    /** True once [start] has succeeded and before [stop] runs. */
    val isRunning: Boolean get() = running.get()

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Claims the microphone and the speaker and begins streaming.
     *
     * Returns false rather than throwing when the device cannot do it — no AMR
     * encoder, microphone held by another app — so a call can fail politely
     * instead of taking the service down with it.
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running.getAndSet(true)) return true

        previousAudioMode = audioManager.mode
        @Suppress("DEPRECATION")
        previousSpeaker = audioManager.isSpeakerphoneOn

        // Routes audio to the earpiece and enables the platform's echo control.
        // Without this the call plays through the media stream at media volume,
        // which is both loud and prone to feeding the speaker back into the mic.
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        return try {
            captureThread = Thread({ captureLoop() }, "call-capture").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            playbackThread = Thread({ playbackLoop() }, "call-playback").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not start audio: ${e.message}")
            stop()
            false
        }
    }

    /** Stops both directions and gives the audio hardware back. */
    fun stop() {
        if (!running.getAndSet(false)) return

        captureThread?.interrupt()
        playbackThread?.interrupt()
        captureThread = null
        playbackThread = null
        inbound.clear()

        runCatching {
            audioManager.mode = previousAudioMode
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = previousSpeaker
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
        }
        Log.i(TAG, "Audio stopped")
    }

    /** Stops sending. Playback continues, so a muted caller still hears. */
    fun setMuted(value: Boolean) {
        muted.set(value)
    }

    /** Moves playback between the earpiece and the loudspeaker. */
    fun setSpeaker(on: Boolean) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val target = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == target }
                    ?.let { audioManager.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = on
            }
        }.onFailure { Log.w(TAG, "Speaker toggle failed: ${it.message}") }
    }

    /**
     * Accepts one packet from the link.
     *
     * Never blocks: if the player is already behind, the oldest packet is
     * discarded to make room. Losing the stale one keeps the call live, whereas
     * blocking here would stall the Bluetooth callback thread that called us.
     */
    fun receive(packet: ByteArray) {
        if (!running.get()) return
        if (!inbound.offer(packet)) {
            inbound.poll()
            inbound.offer(packet)
        }
    }

    // ── Capture ──────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        var record: AudioRecord? = null
        var encoder: MediaCodec? = null
        val effects = mutableListOf<android.media.audiofx.AudioEffect>()

        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                CallProtocol.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(PCM_FRAME_BYTES * 4)

            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                CallProtocol.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                onFailure("The microphone is not available")
                return
            }

            // VOICE_COMMUNICATION usually applies these already; attaching them
            // explicitly covers the devices where it does not, and costs nothing
            // where it does.
            attachEffect(effects) { AcousticEchoCanceler.create(record.audioSessionId) }
            attachEffect(effects) { NoiseSuppressor.create(record.audioSessionId) }
            attachEffect(effects) { AutomaticGainControl.create(record.audioSessionId) }

            encoder = MediaCodec.createEncoderByType(CallProtocol.MIME).apply {
                configure(
                    MediaFormat.createAudioFormat(
                        CallProtocol.MIME, CallProtocol.SAMPLE_RATE, 1
                    ).apply {
                        setInteger(MediaFormat.KEY_BIT_RATE, CallProtocol.BITRATE)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_FRAME_BYTES * 2)
                    },
                    null, null, MediaCodec.CONFIGURE_FLAG_ENCODE
                )
                start()
            }

            record.startRecording()
            Log.i(TAG, "Capturing at ${CallProtocol.BITRATE} bps")

            val pcm = ByteArray(PCM_FRAME_BYTES)
            val silence = ByteArray(PCM_FRAME_BYTES)
            val bundle = ArrayList<ByteArray>(CallProtocol.FRAMES_PER_PACKET)
            val info = MediaCodec.BufferInfo()
            var sequence = 0

            while (running.get() && !Thread.currentThread().isInterrupted) {
                val read = record.read(pcm, 0, PCM_FRAME_BYTES)
                if (read <= 0) continue

                // Muting feeds silence rather than stopping the stream: the far
                // side keeps receiving packets, so its media timeout does not
                // fire and mute does not look like a dropped call.
                val source = if (muted.get()) silence else pcm

                val inputIndex = encoder.dequeueInputBuffer(20_000)
                if (inputIndex >= 0) {
                    encoder.getInputBuffer(inputIndex)?.apply {
                        clear()
                        put(source, 0, read)
                    }
                    encoder.queueInputBuffer(inputIndex, 0, read, 0, 0)
                }

                while (true) {
                    val outputIndex = encoder.dequeueOutputBuffer(info, 0)
                    if (outputIndex < 0) break

                    val buffer = encoder.getOutputBuffer(outputIndex)
                    if (buffer != null && info.size in 1..255) {
                        val frame = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(frame)
                        bundle.add(frame)
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)

                    if (bundle.size >= CallProtocol.FRAMES_PER_PACKET) {
                        onPacket(pack(sequence, bundle))
                        sequence = (sequence + 1) and 0xFFFF
                        bundle.clear()
                    }
                }
            }
        } catch (e: InterruptedException) {
            // Expected: stop() interrupts this thread.
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed: ${e.message}")
            if (running.get()) onFailure("Microphone stopped working")
        } finally {
            effects.forEach { runCatching { it.release() } }
            runCatching { record?.stop() }
            runCatching { record?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
        }
    }

    private inline fun attachEffect(
        into: MutableList<android.media.audiofx.AudioEffect>,
        create: () -> android.media.audiofx.AudioEffect?
    ) {
        runCatching { create()?.also { it.enabled = true; into.add(it) } }
    }

    /** Builds the on-wire packet: sequence number, then length-prefixed frames. */
    private fun pack(sequence: Int, frames: List<ByteArray>): ByteArray {
        val size = 2 + frames.sumOf { it.size + 1 }
        val packet = ByteArray(size)
        packet[0] = (sequence shr 8).toByte()
        packet[1] = sequence.toByte()
        var offset = 2
        frames.forEach { frame ->
            packet[offset++] = frame.size.toByte()
            System.arraycopy(frame, 0, packet, offset, frame.size)
            offset += frame.size
        }
        return packet
    }

    // ── Playback ─────────────────────────────────────────────────────────────

    private fun playbackLoop() {
        var track: AudioTrack? = null
        var decoder: MediaCodec? = null

        try {
            val minBuffer = AudioTrack.getMinBufferSize(
                CallProtocol.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(PCM_FRAME_BYTES * 8)

            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(CallProtocol.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            decoder = MediaCodec.createDecoderByType(CallProtocol.MIME).apply {
                configure(
                    MediaFormat.createAudioFormat(
                        CallProtocol.MIME, CallProtocol.SAMPLE_RATE, 1
                    ),
                    null, null, 0
                )
                start()
            }

            track.play()
            val info = MediaCodec.BufferInfo()
            var expected = -1

            while (running.get() && !Thread.currentThread().isInterrupted) {
                val packet = inbound.take()
                if (packet.size < 3) continue

                val sequence = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)

                // A gap means packets were lost. Feeding the decoder silence for
                // the missing span keeps the timeline honest, so speech after a
                // dropout resumes in the right place instead of sounding rushed.
                if (expected >= 0 && sequence != expected) {
                    val missing = (sequence - expected) and 0xFFFF
                    if (missing in 1 until RESYNC_GAP) {
                        repeat(missing * CallProtocol.FRAMES_PER_PACKET) {
                            writeSilence(track)
                        }
                    }
                }
                expected = (sequence + 1) and 0xFFFF

                forEachFrame(packet) { frame ->
                    val inputIndex = decoder.dequeueInputBuffer(20_000)
                    if (inputIndex >= 0) {
                        decoder.getInputBuffer(inputIndex)?.apply { clear(); put(frame) }
                        decoder.queueInputBuffer(inputIndex, 0, frame.size, 0, 0)
                    }
                    while (true) {
                        val outputIndex = decoder.dequeueOutputBuffer(info, 0)
                        if (outputIndex < 0) break
                        val buffer = decoder.getOutputBuffer(outputIndex)
                        if (buffer != null && info.size > 0) {
                            val pcm = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(pcm)
                            track.write(pcm, 0, pcm.size)
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } catch (e: InterruptedException) {
            // Expected: stop() interrupts this thread.
        } catch (e: Exception) {
            Log.e(TAG, "Playback failed: ${e.message}")
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
        }
    }

    private val silentFrame = ByteArray(PCM_FRAME_BYTES)

    private fun writeSilence(track: AudioTrack) {
        runCatching { track.write(silentFrame, 0, silentFrame.size) }
    }

    /** Walks the length-prefixed frames in a packet, ignoring a truncated tail. */
    private inline fun forEachFrame(packet: ByteArray, action: (ByteArray) -> Unit) {
        var offset = 2
        while (offset < packet.size) {
            val length = packet[offset].toInt() and 0xFF
            offset++
            if (length == 0 || offset + length > packet.size) return
            action(packet.copyOfRange(offset, offset + length))
            offset += length
        }
    }
}
