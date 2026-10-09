package app.http

import app.common.parseCanonicalUuid
import app.purpose.PurposeCursorPosition
import app.purpose.PurposeProjection
import app.common.CursorPrimitives
import java.util.Base64
import java.util.UUID

/** Opaque keyset cursor bound to projection and owner. Any undecodable or foreign cursor is rejected. */
internal object PurposeCursorCodec {
    // PostgreSQL MIN_TIMESTAMP (-211813488000000000, epoch 2000) converted to Unix microseconds.
    private const val MIN_MICROS = -210_866_803_200_000_000L
    fun encode(owner: UUID, projection: PurposeProjection, position: PurposeCursorPosition): String {
        val micros = CursorPrimitives.micros(position.activityAt)
        require(micros >= MIN_MICROS)
        val raw = "v1|${projection.name}|${CursorPrimitives.ownerTag(owner)}|$micros|${position.id}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    fun decode(owner: UUID, projection: PurposeProjection, cursor: String): PurposeCursorPosition? = runCatching {
        val parts = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8).split("|")
        require(parts.size == 5 && parts[0] == "v1" && parts[1] == projection.name && parts[2] == CursorPrimitives.ownerTag(owner))
        val micros = parts[3].toLong()
        require(micros >= MIN_MICROS)
        PurposeCursorPosition(CursorPrimitives.instant(micros),
            requireNotNull(parseCanonicalUuid(parts[4])))
    }.getOrNull()

}
