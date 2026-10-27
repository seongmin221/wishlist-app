package app.http

import app.category.CategoryRef
import app.common.FieldChange
import app.common.parseCanonicalUuid
import app.wishlist.ItemChanges
import app.wishlist.ReviewDecision
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.util.UUID

sealed interface ItemParseResult<out T> {
    data class Valid<T>(val request: T) : ItemParseResult<T>
    /** Value errors name their fields; structural errors (not an object, unknown keys) carry none. */
    data class Invalid(val fields: Set<String> = emptySet()) : ItemParseResult<Nothing>
}

data class ItemPatchRequest(val expectedVersion: Int, val changes: ItemChanges)
data class ManualCompletionRequest(val expectedVersion: Int, val changes: ItemChanges)
data class ReviewDecisionRequest(val expectedVersion: Int, val decision: ReviewDecision, val changes: ItemChanges)

private val PATCH_FIELDS = setOf("name", "brand", "categoryId", "purposeId", "mediaId")
private val REVIEW_FIELDS = setOf("decision", "categoryId", "purposeId")

/** Types and nulls only: text length/character rules need the stored value and run in the service. */
private class ChangeReader(private val body: JsonObject) {
    val invalid = sortedSetOf<String>()

    fun name(): FieldChange<String> = when {
        "name" !in body -> FieldChange.Keep
        else -> body["name"].strictString()?.let { FieldChange.Set(it) } ?: FieldChange.Keep.also { invalid += "name" }
    }

    fun brand(): FieldChange<String?> = nullableString("brand")

    fun category(): FieldChange<CategoryRef> = when {
        "categoryId" !in body -> FieldChange.Keep
        else -> body["categoryId"].strictString()?.let(CategoryRef::parse)?.let { FieldChange.Set(it) }
            ?: FieldChange.Keep.also { invalid += "categoryId" }
    }

    fun uuid(key: String): FieldChange<UUID?> = when (val value = body[key]) {
        null -> FieldChange.Keep
        JsonNull -> FieldChange.Set(null)
        else -> parseCanonicalUuid(value.strictString())?.let { FieldChange.Set(it) } ?: FieldChange.Keep.also { invalid += key }
    }

    fun version(): Int? = body.positiveVersion("expectedVersion").also { if (it == null) invalid += "expectedVersion" }

    private fun nullableString(key: String): FieldChange<String?> = when (val value = body[key]) {
        null -> FieldChange.Keep
        JsonNull -> FieldChange.Set(null)
        else -> value.strictString()?.let { FieldChange.Set(it) } ?: FieldChange.Keep.also { invalid += key }
    }
}

fun parseItemPatchRequest(raw: String): ItemParseResult<ItemPatchRequest> {
    val body = parseJsonObject(raw) ?: return ItemParseResult.Invalid()
    if (body.keys.any { it != "expectedVersion" && it !in PATCH_FIELDS } || body.keys.none { it in PATCH_FIELDS }) {
        return ItemParseResult.Invalid()
    }
    val reader = ChangeReader(body)
    val version = reader.version()
    val changes = ItemChanges(reader.name(), reader.brand(), reader.category(), reader.uuid("purposeId"), reader.uuid("mediaId"))
    return if (reader.invalid.isEmpty()) ItemParseResult.Valid(ItemPatchRequest(version!!, changes)) else ItemParseResult.Invalid(reader.invalid)
}

fun parseManualCompletionRequest(raw: String): ItemParseResult<ManualCompletionRequest> {
    val body = parseJsonObject(raw) ?: return ItemParseResult.Invalid()
    if (body.keys.any { it != "expectedVersion" && it !in PATCH_FIELDS }) return ItemParseResult.Invalid()
    val reader = ChangeReader(body)
    val version = reader.version()
    val name = reader.name()
    val category = reader.category()
    if ("name" !in body) reader.invalid += "name"
    if ("categoryId" !in body) reader.invalid += "categoryId"
    val changes = ItemChanges(name, reader.brand(), category, reader.uuid("purposeId"), reader.uuid("mediaId"))
    return if (reader.invalid.isEmpty()) ItemParseResult.Valid(ManualCompletionRequest(version!!, changes))
    else ItemParseResult.Invalid(reader.invalid)
}

fun parseReviewDecisionRequest(raw: String): ItemParseResult<ReviewDecisionRequest> {
    val body = parseJsonObject(raw) ?: return ItemParseResult.Invalid()
    if (body.keys.any { it != "expectedVersion" && it !in REVIEW_FIELDS }) return ItemParseResult.Invalid()
    val reader = ChangeReader(body)
    val version = reader.version()
    val decision = body["decision"].strictString()?.let { value -> ReviewDecision.entries.firstOrNull { it.name == value } }
    if (decision == null) reader.invalid += "decision"
    val changes = ItemChanges(category = reader.category(), purpose = reader.uuid("purposeId"))
    return if (reader.invalid.isEmpty()) ItemParseResult.Valid(ReviewDecisionRequest(version!!, decision!!, changes))
    else ItemParseResult.Invalid(reader.invalid)
}

/** ITEM-06 carries its intent in the path and key; only an empty body or `{}` is accepted. */
fun isValidReanalysisBody(raw: String): Boolean = raw.isBlank() || parseJsonObject(raw)?.isEmpty() == true
