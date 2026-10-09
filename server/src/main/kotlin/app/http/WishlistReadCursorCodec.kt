package app.http

import app.wishlist.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import app.common.CursorPrimitives
import java.util.Base64
import java.util.UUID

enum class ReadEndpoint { WISHLIST_ITEMS, HOME_ACTION_ITEMS }
enum class ReadCursorUse { ANCHOR, NEXT, PREVIOUS, NEXT_INCLUSIVE, PREVIOUS_INCLUSIVE }

/** A position hint, never an authorization credential. SQL still enforces owner and scope. */
object WishlistReadCursorCodec {
    private val tokenCharacters = Regex("[A-Za-z0-9_-]+")

    class OwnerContext internal constructor(private val ownerTag: String) {
        fun encode(endpoint: ReadEndpoint, scope: ReadScope, use: ReadCursorUse, position: ReadPosition): String {
            val micros = CursorPrimitives.micros(position.createdAt)
            require(micros in 0..CursorPrimitives.MAX_MICROS)
            val (kind,value) = scopeKey(scope)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                "v1|${endpoint.name}|$ownerTag|$kind|$value|${use.name}|$micros|${position.id}".toByteArray(Charsets.UTF_8))
        }
    }
    fun forOwner(owner: UUID) = OwnerContext(CursorPrimitives.ownerTag(owner))

    fun decode(owner: UUID, endpoint: ReadEndpoint, scope: ReadScope, use: ReadCursorUse, raw: String): ReadPosition? =
        decodeToken(owner,endpoint,scope,raw)?.takeIf { it.first==use }?.second

    /** Parse a page hint once, including its direction and recovery-boundary semantics. */
    fun decodePage(owner: UUID, endpoint: ReadEndpoint, scope: ReadScope, raw: String): Pair<ReadCursorUse,ReadPosition>? =
        decodeToken(owner,endpoint,scope,raw)?.takeIf { it.first!=ReadCursorUse.ANCHOR }

    private fun decodeToken(owner: UUID, endpoint: ReadEndpoint, scope: ReadScope, raw: String): Pair<ReadCursorUse,ReadPosition>? = runCatching {
        require(raw.length in 1..2048 && tokenCharacters.matches(raw))
        val bytes = Base64.getUrlDecoder().decode(raw)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        val fields = text.split('|')
        val (kind,value) = scopeKey(scope)
        require(fields.size==8 && fields[0]=="v1" && fields[1]==endpoint.name && fields[2]==CursorPrimitives.ownerTag(owner))
        require(fields[3]==kind && fields[4]==value)
        val use = ReadCursorUse.valueOf(fields[5])
        val micros = fields[6].toLong()
        require(micros in 0..CursorPrimitives.MAX_MICROS)
        val time = CursorPrimitives.instant(micros)
        val id = requireNotNull(app.common.parseCanonicalUuid(fields[7]))
        use to ReadPosition(time,id)
    }.getOrNull()

    private fun scopeKey(scope: ReadScope): Pair<String,String> = when (scope) {
        is ReadScope.Category -> "CATEGORY" to scope.ref.value
        is ReadScope.Purpose -> "PURPOSE" to scope.id.toString()
        ReadScope.PurposeUnassigned -> "UNASSIGNED" to "-"
        is ReadScope.Action -> "GROUP" to scope.group.name
    }
}
