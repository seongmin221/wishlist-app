package app.wishlist.shared.data.remote

import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.domain.sanitizeAllowedActions
import app.wishlist.shared.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.time.Instant

private val itemJson = Json { ignoreUnknownKeys = true }

private val invalid = ClientResult.Failure(ClientError(ErrorKind.INVALID_RESPONSE))

/** Decodes a response body and maps it; any malformed JSON, missing field or wrong type is INVALID_RESPONSE. */
internal fun parseItem(body: String): ClientResult<WishlistItem> {
    val dto = try {
        itemJson.decodeFromString<WishlistItemDto>(body)
    } catch (e: IllegalArgumentException) { // SerializationException is one
        return invalid
    }
    return mapItem(dto)
}

/**
 * Maps the wire item to the model. Never throws: every model `require` invariant and every parse
 * is checked before a model is built. requiredAction/allowedActions come from the server and are
 * not recomputed (evaluateItem is Fake-only). Enum strings are read per field: an unknown value
 * becomes UNKNOWN, except lifecycleStatus (unknown is INVALID_RESPONSE). Unknown allowed actions
 * are dropped; when analysis status or requiredAction is UNKNOWN only a server-supplied DELETE
 * survives. The raw failureCode, known or not, is kept as is.
 */
internal fun mapItem(dto: WishlistItemDto): ClientResult<WishlistItem> {
    val version = (dto.version as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it > 0 } ?: return invalid
    val lifecycle = enumOrNull<LifecycleStatus>(dto.lifecycleStatus) ?: return invalid
    val createdAt = instantOrNull(dto.createdAt) ?: return invalid
    val updatedAt = instantOrNull(dto.updatedAt) ?: return invalid
    val manualCompletionAt = optionalInstant(dto.manualCompletionAt) ?: return invalid
    val clientCreatedAt = optionalInstant(dto.clientCreatedAt) ?: return invalid
    val metadataCheckedAt = optionalInstant(dto.product.metadataCheckedAt) ?: return invalid
    val price = when (val read = readPrice(dto.product.price)) {
        is ClientResult.Failure -> return read
        is ClientResult.Success -> read.value
    }
    val missingReason = dto.category.missingReason?.let { enumOr(it, CategoryMissingReason.UNKNOWN) }
    if (dto.category.id != null && missingReason != null) return invalid

    val analysisStatus = enumOr(dto.analysis.status, AnalysisStatus.UNKNOWN)
    val requiredAction = enumOr(dto.requiredAction, RequiredAction.UNKNOWN)
    val serverActions = dto.allowedActions.mapNotNullTo(mutableSetOf()) { enumOrNull<ItemAction>(it) }

    return ClientResult.Success(
        WishlistItem(
            id = dto.id,
            clientSubmissionId = dto.clientSubmissionId,
            version = version,
            sourceUrl = dto.sourceUrl,
            product = ProductSnapshot(
                name = dto.product.name,
                imageUrl = dto.product.imageUrl,
                price = price,
                currency = dto.product.currency,
                brand = dto.product.brand,
                merchant = dto.product.merchant,
                metadataCheckedAt = metadataCheckedAt.value,
                nameSource = dto.product.nameSource?.let { enumOr(it, ValueSource.UNKNOWN) },
                imageSource = dto.product.imageSource?.let { enumOr(it, ValueSource.UNKNOWN) },
            ),
            category = ItemCategory(
                id = dto.category.id,
                source = dto.category.source?.let { enumOr(it, ValueSource.UNKNOWN) },
                missingReason = missingReason,
                name = dto.category.name,
                parentId = dto.category.parentId,
                kind = dto.category.kind,
            ),
            purpose = ItemPurpose(
                id = dto.purpose.id,
                name = dto.purpose.name,
                colorKey = dto.purpose.colorKey,
                iconKey = dto.purpose.iconKey,
                source = dto.purpose.source?.let { enumOr(it, ValueSource.UNKNOWN) } ?: ValueSource.UNASSIGNED,
            ),
            analysis = ItemAnalysis(analysisStatus, dto.analysis.failureCode),
            reviewStatus = enumOr(dto.reviewStatus, ReviewStatus.UNKNOWN),
            lifecycleStatus = lifecycle,
            requiredAction = requiredAction,
            createdAt = createdAt,
            updatedAt = updatedAt,
            manualCompletionAt = manualCompletionAt.value,
            allowedActions = sanitizeAllowedActions(analysisStatus, requiredAction, serverActions),
            clientCreatedAt = clientCreatedAt.value,
        ),
    )
}

private inline fun <reified E : Enum<E>> enumOrNull(raw: String): E? = enumValues<E>().firstOrNull { it.name == raw }

/** Unknown raw values (including a literal "UNKNOWN" sent by a server) fall back to [fallback]. */
private inline fun <reified E : Enum<E>> enumOr(raw: String, fallback: E): E = enumOrNull<E>(raw) ?: fallback

private fun instantOrNull(raw: String): Instant? = try {
    Instant.parse(raw)
} catch (e: IllegalArgumentException) {
    null
}

/** Wraps an optional instant so "absent" and "unparseable" stay distinguishable. */
private class Optional<T>(val value: T?)

private fun optionalInstant(raw: String?): Optional<Instant>? = when {
    raw == null -> Optional(null)
    else -> instantOrNull(raw)?.let { Optional(it) }
}
