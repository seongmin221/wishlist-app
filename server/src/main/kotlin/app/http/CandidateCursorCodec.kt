package app.http

import app.candidates.CandidateFilter
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
        require(raw.length in 1..2048 && tokenCharacters.matches(raw))
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(raw))).toString()
        val fields = text.split('|')
        val (kind, value) = filterKey(filter)
        require(fields.size == 8 && fields[0] == "v1" && fields[1] == "CANDIDATES" && fields[2] == CursorPrimitives.ownerTag(owner))
        require(fields[3] == purpose.toString() && fields[4] == kind && fields[5] == value)
        val micros = fields[6].toLong()
        require(micros in 0..CursorPrimitives.MAX_MICROS)
        ReadPosition(CursorPrimitives.instant(micros), requireNotNull(parseCanonicalUuid(fields[7])))
    }.getOrNull()

    private fun filterKey(filter: CandidateFilter): Pair<String, String> = when (filter) {
        CandidateFilter.None -> "ALL" to "-"
        is CandidateFilter.Category -> "CATEGORY" to filter.ref.value
        is CandidateFilter.Parent -> "PARENT" to filter.id
    }
}
