package com.meshlink

import org.json.JSONObject

/**
 * Wire shapes for the media handshake.
 *
 * Offers, requests and completions are small JSON documents. Chunks are framed
 * in binary instead, because a JSON wrapper around the bytes would inflate every
 * chunk on a link that has none to spare.
 */
object MediaProtocol {

    /** Bytes of file per chunk, before sealing and link-level chunking. */
    const val CHUNK_BYTES = 4096

    // ── Offer ────────────────────────────────────────────────────────────────

    data class Offer(
        val mediaId: String,
        val name: String,
        val mime: String,
        val size: Long,
        val sha256: String
    ) {
        fun encode(): String = JSONObject().apply {
            put("id", mediaId)
            put("name", name)
            put("mime", mime)
            put("size", size)
            put("sha", sha256)
        }.toString()

        companion object {
            fun decode(raw: String): Offer? = runCatching {
                val json = JSONObject(raw)
                Offer(
                    mediaId = json.getString("id"),
                    name = json.optString("name", "attachment"),
                    mime = json.optString("mime", "application/octet-stream"),
                    size = json.getLong("size"),
                    sha256 = json.optString("sha", "")
                )
            }.getOrNull()
        }
    }

    // ── Request ──────────────────────────────────────────────────────────────

    /** Asks for everything from [offset] onward, which is also how resume works. */
    data class Request(val mediaId: String, val offset: Long) {
        fun encode(): String =
            JSONObject().apply { put("id", mediaId); put("from", offset) }.toString()

        companion object {
            fun decode(raw: String): Request? = runCatching {
                val json = JSONObject(raw)
                Request(json.getString("id"), json.optLong("from", 0))
            }.getOrNull()
        }
    }

    // ── Completion ───────────────────────────────────────────────────────────

    data class Complete(val mediaId: String, val ok: Boolean) {
        fun encode(): String =
            JSONObject().apply { put("id", mediaId); put("ok", ok) }.toString()

        companion object {
            fun decode(raw: String): Complete? = runCatching {
                val json = JSONObject(raw)
                Complete(json.getString("id"), json.optBoolean("ok", true))
            }.getOrNull()
        }
    }

    // ── Chunk framing ────────────────────────────────────────────────────────

    data class Chunk(val mediaId: String, val offset: Long, val data: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Chunk && other.mediaId == mediaId && other.offset == offset

        override fun hashCode(): Int = mediaId.hashCode() * 31 + offset.hashCode()
    }

    /**
     * Two bytes of identifier length, the identifier, an eight-byte offset, then
     * the raw bytes. All integers big-endian.
     *
     * Deliberately not JSON: the identifier and offset cost about forty bytes
     * against a four-kilobyte payload, where a JSON or base64 wrapper would add
     * a third again to every chunk of every transfer.
     */
    fun encodeChunk(chunk: Chunk): ByteArray {
        val idBytes = chunk.mediaId.toByteArray()
        val out = ByteArray(2 + idBytes.size + 8 + chunk.data.size)
        out[0] = (idBytes.size shr 8).toByte()
        out[1] = idBytes.size.toByte()
        System.arraycopy(idBytes, 0, out, 2, idBytes.size)
        var cursor = 2 + idBytes.size
        for (shift in 56 downTo 0 step 8) {
            out[cursor++] = (chunk.offset shr shift).toByte()
        }
        System.arraycopy(chunk.data, 0, out, cursor, chunk.data.size)
        return out
    }

    fun decodeChunk(raw: ByteArray): Chunk? {
        if (raw.size < 10) return null
        val idLength = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
        if (raw.size < 2 + idLength + 8) return null

        val mediaId = String(raw, 2, idLength)
        var cursor = 2 + idLength
        var offset = 0L
        repeat(8) { offset = (offset shl 8) or (raw[cursor++].toLong() and 0xFF) }
        return Chunk(mediaId, offset, raw.copyOfRange(cursor, raw.size))
    }
}
