package com.meshlink

/**
 * The sticker packs shipped inside the app.
 *
 * A sticker is sent as a pack id and an index - a dozen bytes - rather than an
 * image, so it floods across hops at the cost of a short text message and works
 * with no transfer, no storage and no waiting. Rendering is a client concern, so
 * the glyphs here can later be swapped for drawables without touching the wire
 * format or anything already sent.
 */
object Stickers {

    /** Wire prefix, so a sticker payload is self-describing: "core:3". */
    private const val SEPARATOR = ":"

    private val packs: Map<String, List<String>> = mapOf(
        "core" to listOf(
            "👋", "👍", "👎", "❤️",
            "😂", "😢", "😡", "🎉",
            "👏", "🙏", "🔥", "✅"
        ),
        "sos" to listOf(
            "🆘", "⚠️", "🚨", "🏥",
            "💧", "🍽️", "🔦", "👤"
        )
    )

    /** Every sticker as its wire reference, for the picker. */
    fun all(): List<String> =
        packs.entries.flatMap { (pack, glyphs) ->
            glyphs.indices.map { index -> "$pack$SEPARATOR$index" }
        }

    /**
     * Resolves a reference to something displayable. Unknown packs or indices
     * render as a placeholder rather than throwing: a future version may send
     * stickers this build has never heard of.
     */
    fun glyphFor(reference: String): String {
        val parts = reference.split(SEPARATOR)
        if (parts.size != 2) return "❓"
        val index = parts[1].toIntOrNull() ?: return "❓"
        return packs[parts[0]]?.getOrNull(index) ?: "❓"
    }

    fun isSticker(reference: String): Boolean =
        reference.split(SEPARATOR).let { it.size == 2 && packs.containsKey(it[0]) }
}
