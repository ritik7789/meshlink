package com.meshlink

import android.util.Log
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Framing for a single BLE hop: link encryption plus chunking and reassembly.
 *
 * Both GATT directions need identical handling — the client writes to the
 * message characteristic and the server notifies back over the same ACL link —
 * so the logic lives here once instead of being duplicated (and drifting) in
 * [GattClient] and [GattServer].
 *
 * Wire format of every chunk: one chunk-index byte, one total-chunks byte, then
 * payload. The concatenated payloads form `nonce(12) || ciphertext` for one
 * whole envelope.
 */
object LinkCodec {
    private const val TAG = "LinkCodec"

    /** Two header bytes, each a single unsigned byte, so a message caps at 255 chunks. */
    private const val HEADER_SIZE = 2
    private const val MAX_CHUNKS = 255

    /** ATT overhead: an MTU of N carries N-3 bytes of notification/write payload. */
    private const val ATT_OVERHEAD = 3

    /**
     * How long a partially received message may sit without progress. Measured
     * between chunks rather than from the start, so a large but healthy transfer
     * is never cut off partway.
     */
    private const val REASSEMBLY_STALL_TIMEOUT_MS = 15_000L

    /**
     * Encrypts [plaintext] under the hop's [secret] and splits it into chunks
     * that fit the negotiated [mtu]. Returns an empty list if the payload cannot
     * be represented in [MAX_CHUNKS] chunks, rather than sending a truncated
     * message the far side would silently fail to decrypt.
     */
    fun frame(secret: ByteArray, plaintext: ByteArray, mtu: Int): List<ByteArray> {
        val nonce = ByteArray(12)
        SecureRandom().nextBytes(nonce)
        val ciphertext = uniffi.meshlink_core.encryptTransport(secret, nonce, plaintext)
        if (ciphertext.isEmpty()) {
            Log.e(TAG, "Link encryption produced no output; dropping frame")
            return emptyList()
        }
        val combined = nonce + ciphertext

        val chunkSize = (mtu - ATT_OVERHEAD - HEADER_SIZE).coerceAtLeast(16)
        val total = (combined.size + chunkSize - 1) / chunkSize
        if (total > MAX_CHUNKS) {
            Log.e(TAG, "Message of ${combined.size}B needs $total chunks, over the $MAX_CHUNKS limit")
            return emptyList()
        }

        return (0 until total).map { index ->
            val start = index * chunkSize
            val end = minOf(start + chunkSize, combined.size)
            val chunk = ByteArray(HEADER_SIZE + (end - start))
            chunk[0] = index.toByte()
            chunk[1] = total.toByte()
            System.arraycopy(combined, start, chunk, HEADER_SIZE, end - start)
            chunk
        }
    }

    /**
     * Accumulates chunks per peer and decrypts once the last one arrives.
     *
     * The previous implementation appended blindly to a per-address buffer, so a
     * single dropped chunk left stale bytes that corrupted every later message
     * from that peer. This tracks the expected index and resets on any gap.
     */
    class Reassembler {
        private class Partial(var buffer: ByteArray, var nextIndex: Int, var total: Int, var lastChunkAt: Long)

        private val partials = ConcurrentHashMap<String, Partial>()

        /**
         * Feeds one received chunk. Returns the decrypted envelope bytes when
         * [chunk] completes a message, or null while more chunks are expected or
         * the message was discarded.
         */
        fun accept(address: String, chunk: ByteArray, secret: ByteArray?): ByteArray? {
            if (secret == null) {
                Log.w(TAG, "No link secret for $address yet; dropping chunk")
                return null
            }
            if (chunk.size < HEADER_SIZE) return null

            val index = chunk[0].toInt() and 0xFF
            val total = chunk[1].toInt() and 0xFF
            if (total == 0) return null
            val payload = chunk.copyOfRange(HEADER_SIZE, chunk.size)

            val now = System.currentTimeMillis()
            var partial = partials[address]

            // Start fresh on the first chunk, on a stalled transfer, or whenever
            // the sender's numbering disagrees with what we expect next.
            if (partial == null ||
                index == 0 ||
                partial.total != total ||
                partial.nextIndex != index ||
                now - partial.lastChunkAt > REASSEMBLY_STALL_TIMEOUT_MS
            ) {
                if (index != 0) {
                    Log.w(TAG, "Out-of-sequence chunk $index/$total from $address; dropping partial")
                    partials.remove(address)
                    return null
                }
                partial = Partial(ByteArray(0), 0, total, now)
                partials[address] = partial
            }

            partial.buffer += payload
            partial.nextIndex = index + 1
            partial.lastChunkAt = now

            if (partial.nextIndex < total) return null

            partials.remove(address)
            if (partial.buffer.size <= 12) return null

            return try {
                val nonce = partial.buffer.copyOfRange(0, 12)
                val ciphertext = partial.buffer.copyOfRange(12, partial.buffer.size)
                uniffi.meshlink_core.decryptTransport(secret, nonce, ciphertext)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt message from $address: ${e.message}")
                null
            }
        }

        fun forget(address: String) {
            partials.remove(address)
        }
    }
}
