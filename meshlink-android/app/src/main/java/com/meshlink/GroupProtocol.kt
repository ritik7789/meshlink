package com.meshlink

import com.meshlink.db.GroupMemberEntity
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Everything that travels inside a group.
 *
 * Only the invite is sealed per member; everything else — chat, roster changes,
 * deletions, leaves — is one flooded envelope encrypted with the shared group
 * key, carrying an inner type. That keeps a group message the same cost whether
 * the group has three members or twenty-five, and keeps non-members unable to
 * open any of it.
 */
object GroupProtocol {

    /** Beyond this, a busy group would crowd out everything else on the mesh. */
    const val MAX_MEMBERS = 25

    /** ChaCha20-Poly1305 key length, matching the transport cipher. */
    private const val KEY_BYTES = 32

    // Inner payload kinds.
    const val KIND_CHAT = "chat"
    const val KIND_ROSTER = "roster"
    const val KIND_DELETE = "delete"
    const val KIND_LEAVE = "leave"

    fun newGroupKey(): ByteArray = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }

    fun newGroupId(): String = java.util.UUID.randomUUID().toString()

    // ── Invite: sealed to one member, carries the key and roster ─────────────

    data class Invite(
        val groupId: String,
        val name: String,
        val groupKey: ByteArray,
        val keyVersion: Int,
        val rosterVersion: Int,
        val createdBy: Long,
        val members: List<GroupMemberEntity>
    ) {
        fun encode(): String = JSONObject().apply {
            put("gid", groupId)
            put("name", name)
            put("key", android.util.Base64.encodeToString(groupKey, android.util.Base64.NO_WRAP))
            put("kv", keyVersion)
            put("rv", rosterVersion)
            put("by", createdBy)
            put("members", encodeMembers(members))
        }.toString()

        override fun equals(other: Any?): Boolean = other is Invite && other.groupId == groupId
        override fun hashCode(): Int = groupId.hashCode()
    }

    fun decodeInvite(raw: String): Invite? = runCatching {
        val json = JSONObject(raw)
        Invite(
            groupId = json.getString("gid"),
            name = json.optString("name", "Group"),
            groupKey = android.util.Base64.decode(json.getString("key"), android.util.Base64.NO_WRAP),
            keyVersion = json.optInt("kv", 1),
            rosterVersion = json.optInt("rv", 1),
            createdBy = json.optLong("by", 0),
            members = decodeMembers(json.getString("gid"), json.optJSONArray("members"))
        )
    }.getOrNull()

    // ── Inner group payloads ────────────────────────────────────────────────

    /** A chat message: the same kinds a one-to-one conversation carries. */
    fun encodeChat(messageId: String, messageType: String, body: String): String =
        JSONObject().apply {
            put("k", KIND_CHAT)
            put("mid", messageId)
            put("mt", messageType)
            put("body", body)
        }.toString()

    /** A membership change, authoritative at [rosterVersion]. */
    fun encodeRoster(
        rosterVersion: Int,
        keyVersion: Int,
        name: String,
        members: List<GroupMemberEntity>
    ): String = JSONObject().apply {
        put("k", KIND_ROSTER)
        put("rv", rosterVersion)
        put("kv", keyVersion)
        put("name", name)
        put("members", encodeMembers(members))
    }.toString()

    /** Retraction of one message, for everyone. */
    fun encodeDelete(targetMessageId: String, byAdmin: Boolean): String =
        JSONObject().apply {
            put("k", KIND_DELETE)
            put("mid", targetMessageId)
            put("admin", byAdmin)
        }.toString()

    fun encodeLeave(): String = JSONObject().apply { put("k", KIND_LEAVE) }.toString()

    fun kindOf(raw: String): String? = runCatching { JSONObject(raw).getString("k") }.getOrNull()

    fun field(raw: String, name: String): String? =
        runCatching { JSONObject(raw).optString(name).takeIf { it.isNotEmpty() } }.getOrNull()

    fun intField(raw: String, name: String, fallback: Int = 0): Int =
        runCatching { JSONObject(raw).optInt(name, fallback) }.getOrDefault(fallback)

    fun boolField(raw: String, name: String): Boolean =
        runCatching { JSONObject(raw).optBoolean(name, false) }.getOrDefault(false)

    fun membersFrom(raw: String, groupId: String): List<GroupMemberEntity> =
        runCatching { decodeMembers(groupId, JSONObject(raw).optJSONArray("members")) }
            .getOrDefault(emptyList())

    // ── Shared helpers ──────────────────────────────────────────────────────

    private fun encodeMembers(members: List<GroupMemberEntity>): JSONArray =
        JSONArray().apply {
            members.forEach { member ->
                put(
                    JSONObject().apply {
                        put("id", member.beaconRow)
                        put("n", member.name ?: "")
                        put("a", member.isAdmin)
                    }
                )
            }
        }

    private fun decodeMembers(groupId: String, array: JSONArray?): List<GroupMemberEntity> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            GroupMemberEntity(
                groupId = groupId,
                beaconRow = item.optLong("id"),
                name = item.optString("n").takeIf { it.isNotBlank() },
                isAdmin = item.optBoolean("a", false)
            )
        }
    }
}
