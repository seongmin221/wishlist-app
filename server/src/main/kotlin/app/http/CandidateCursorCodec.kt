package app.http

import app.candidates.CandidateFilter
import app.candidates.DeletionKind
import app.common.CursorPrimitives
import app.common.parseCanonicalUuid
import app.wishlist.ReadPosition
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID

/** PUR-07 position hint bound to owner, target purpose and filter. Never an authorization credential. */
object CandidateCursorCodec {
    private val tokenCharacters = Regex("[A-Za-z0-9_-]+")

    fun encode(owner: UUID, purpose: UUID, filter: CandidateFilter, position: ReadPosition): String {
        val micros = CursorPrimitives.micros(position.createdAt)
        require(micros in 0..CursorPrimitives.MAX_MICROS)
        val (kind, value) = filterKey(filter)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            "v1|CANDIDATES|${CursorPrimitives.ownerTag(owner)}|$purpose|$kind|$value|$micros|${position.id}".toByteArray(Charsets.UTF_8))
    }

    fun decode(owner: UUID, purpose: UUID, filter: CandidateFilter, raw: String): ReadPosition? = runCatching {
        val fields = fields(raw)
        val (kind, value) = filterKey(filter)
        require(fields.size == 8 && fields[0] == "v1" && fields[1] == "CANDIDATES" && fields[2] == CursorPrimitives.ownerTag(owner))
        require(fields[3] == purpose.toString() && fields[4] == kind && fields[5] == value)
        val micros = fields[6].toLong()
        require(micros in 0..CursorPrimitives.MAX_MICROS)
        ReadPosition(CursorPrimitives.instant(micros), requireNotNull(parseCanonicalUuid(fields[7])))
    }.getOrNull()

    /** CAT-05/PUR-05 position hint bound to owner, resource and the whole-impact fingerprint it was read with. */
    fun encodeImpact(owner: UUID, kind: DeletionKind, id: UUID, fingerprint: String, position: ReadPosition): String {
        val micros = CursorPrimitives.micros(position.createdAt)
        require(micros in 0..CursorPrimitives.MAX_MICROS)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            "v1|IMPACT|${CursorPrimitives.ownerTag(owner)}|${kind.name}|$id|$fingerprint|$micros|${position.id}".toByteArray(Charsets.UTF_8))
    }

    /** The fingerprint and position, or null when the cursor is malformed or belongs to another owner or resource. */
    fun decodeImpact(owner: UUID, kind: DeletionKind, id: UUID, raw: String): Pair<String, ReadPosition>? = runCatching {
        val fields = fields(raw)
        require(fields.size == 8 && fields[0] == "v1" && fields[1] == "IMPACT" && fields[2] == CursorPrimitives.ownerTag(owner))
        require(fields[3] == kind.name && fields[4] == id.toString() && fields[5].matches(Regex("[0-9a-f]{64}")))
        val micros = fields[6].toLong()
        require(micros in 0..CursorPrimitives.MAX_MICROS)
        fields[5] to ReadPosition(CursorPrimitives.instant(micros), requireNotNull(parseCanonicalUuid(fields[7])))
    }.getOrNull()

    /** DUP-01 position hint bound to owner and the new item; the position is the existing item's (createdAt, id). */
    fun encodeDuplicates(owner: UUID, item: UUID, position: ReadPosition): String {
        val micros = CursorPrimitives.micros(position.createdAt)
        require(micros in 0..CursorPrimitives.MAX_MICROS)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            "v1|DUPLICATES|${CursorPrimitives.ownerTag(owner)}|$item|$micros|${position.id}".toByteArray(Charsets.UTF_8))
    }

    fun decodeDuplicates(owner: UUID, item: UUID, raw: String): ReadPosition? = runCatching {
        val fields = fields(raw)
        require(fields.size == 6 && fields[0] == "v1" && fields[1] == "DUPLICATES" && fields[2] == CursorPrimitives.ownerTag(owner))
        require(fields[3] == item.toString())
        val micros = fields[4].toLong()
        require(micros in 0..CursorPrimitives.MAX_MICROS)
        ReadPosition(CursorPrimitives.instant(micros), requireNotNull(parseCanonicalUuid(fields[5])))
    }.getOrNull()

    private fun fields(raw: String): List<String> {
        require(raw.length in 1..2048 && tokenCharacters.matches(raw))
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(raw))).toString().split('|')
    }

    private fun filterKey(filter: CandidateFilter): Pair<String, String> = when (filter) {
        CandidateFilter.None -> "ALL" to "-"
        is CandidateFilter.Category -> "CATEGORY" to filter.ref.value
        is CandidateFilter.Parent -> "PARENT" to filter.id
    }
}
