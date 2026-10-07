package app.http

import app.purpose.PurposeCursorPosition
import app.purpose.PurposeProjection
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Opaque keyset cursor bound to projection and owner. Any undecodable or foreign cursor is rejected. */
internal object PurposeCursorCodec {
    fun encode(owner: UUID, projection: PurposeProjection, position: PurposeCursorPosition): String {
        val micros = Math.addExact(Math.multiplyExact(position.activityAt.epochSecond, 1_000_000L), position.activityAt.nano / 1000L)
        val raw = "v1|${projection.name}|${ownerTag(owner)}|$micros|${position.id}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    fun decode(owner: UUID, projection: PurposeProjection, cursor: String): PurposeCursorPosition? = runCatching {
        val parts = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8).split("|")
        require(parts.size == 5 && parts[0] == "v1" && parts[1] == projection.name && parts[2] == ownerTag(owner))
        val micros = parts[3].toLong()
        PurposeCursorPosition(Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1000),
            requireNotNull(parseCanonicalUuid(parts[4])))
    }.getOrNull()

    private fun ownerTag(owner: UUID): String = MessageDigest.getInstance("SHA-256")
        .digest(owner.toString().toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
}
