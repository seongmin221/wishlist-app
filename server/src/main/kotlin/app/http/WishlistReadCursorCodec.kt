package app.http

import app.wishlist.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID

enum class ReadEndpoint { WISHLIST_ITEMS, HOME_ACTION_ITEMS }
enum class ReadCursorUse { ANCHOR, NEXT, PREVIOUS }

/** A position hint, never an authorization credential. SQL still enforces owner and scope. */
object WishlistReadCursorCodec {
    private const val MAX_MICROS = 253_402_300_799_999_999L
    private val tokenCharacters = Regex("[A-Za-z0-9_-]+")

    fun encode(owner: UUID, endpoint: ReadEndpoint, scope: ReadScope, use: ReadCursorUse, position: ReadPosition): String {
        val micros = Math.addExact(Math.multiplyExact(position.createdAt.epochSecond, 1_000_000L), position.createdAt.nano / 1000L)
        require(micros in 0..MAX_MICROS)
        val (kind, value) = scopeKey(scope)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            "v1|${endpoint.name}|${ownerTag(owner)}|$kind|$value|${use.name}|$micros|${position.id}".toByteArray(Charsets.UTF_8))
    }

    fun decode(owner: UUID, endpoint: ReadEndpoint, scope: ReadScope, use: ReadCursorUse, raw: String): ReadPosition? = runCatching {
        require(raw.length in 1..2048 && tokenCharacters.matches(raw))
        val bytes = Base64.getUrlDecoder().decode(raw)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        val fields = text.split('|')
        val (kind, value) = scopeKey(scope)
        require(fields.size == 8 && fields[0] == "v1" && fields[1] == endpoint.name && fields[2] == ownerTag(owner))
        require(fields[3] == kind && fields[4] == value && fields[5] == use.name)
        val micros = fields[6].toLong()
        require(micros in 0..MAX_MICROS)
        val id = requireNotNull(app.common.parseCanonicalUuid(fields[7]))
        ReadPosition(Instant.ofEpochSecond(micros / 1_000_000L, (micros % 1_000_000L) * 1000L), id)
    }.getOrNull()

    private fun scopeKey(scope: ReadScope): Pair<String,String> = when (scope) {
        is ReadScope.Category -> "CATEGORY" to scope.ref.value
        is ReadScope.Purpose -> "PURPOSE" to scope.id.toString()
        ReadScope.PurposeUnassigned -> "UNASSIGNED" to "-"
        is ReadScope.Action -> "GROUP" to scope.group.name
    }
    private fun ownerTag(owner: UUID): String = MessageDigest.getInstance("SHA-256")
        .digest(owner.toString().toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
}
