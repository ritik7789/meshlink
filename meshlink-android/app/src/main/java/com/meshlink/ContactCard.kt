package com.meshlink

/**
 * A shared contact, carried as a vCard.
 *
 * vCard is used rather than a private format so a received contact can be handed
 * straight to the system contacts app, and so a card sent by a future version
 * with more fields still parses here instead of failing outright. A typical card
 * is well under a kilobyte, which keeps contacts inside the inline limit and so
 * able to travel across hops like any message.
 */
data class ContactCard(val name: String, val phone: String?) {

    fun toVCard(): String = buildString {
        append("BEGIN:VCARD\r\n")
        append("VERSION:3.0\r\n")
        append("FN:").append(escape(name)).append("\r\n")
        phone?.let { append("TEL:").append(escape(it)).append("\r\n") }
        append("END:VCARD\r\n")
    }

    companion object {
        /** Lenient by design: unknown lines are skipped, not treated as errors. */
        fun parse(vcard: String): ContactCard? {
            if (!vcard.contains("BEGIN:VCARD")) return null
            var name: String? = null
            var phone: String? = null
            vcard.lineSequence().forEach { raw ->
                val line = raw.trim()
                when {
                    line.startsWith("FN:", ignoreCase = true) ->
                        name = unescape(line.substringAfter(":"))
                    // TEL may carry parameters, as in TEL;TYPE=CELL:+1234
                    line.startsWith("TEL", ignoreCase = true) && line.contains(":") ->
                        if (phone == null) phone = unescape(line.substringAfter(":"))
                }
            }
            return name?.takeIf { it.isNotBlank() }?.let { ContactCard(it, phone) }
        }

        private fun escape(value: String): String =
            value.replace("\\", "\\\\")
                .replace(",", "\\,")
                .replace(";", "\\;")
                .replace("\n", " ")
                .replace("\r", "")

        private fun unescape(value: String): String =
            value.replace("\\,", ",")
                .replace("\\;", ";")
                .replace("\\\\", "\\")
    }
}
