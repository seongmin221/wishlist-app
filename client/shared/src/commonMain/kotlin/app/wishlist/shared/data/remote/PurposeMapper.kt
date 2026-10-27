package app.wishlist.shared.data.remote

import app.wishlist.shared.core.*
import app.wishlist.shared.model.*
import kotlinx.serialization.json.*
import kotlin.time.Instant

private class InvalidPurpose : Exception()
private val invalidPurpose = ClientResult.Failure(ClientError(ErrorKind.INVALID_RESPONSE))
private inline fun <T> purposeReading(body: String, read: (JsonObject) -> T): ClientResult<T> = try {
    ClientResult.Success(read(Json.parseToJsonElement(body) as? JsonObject ?: throw InvalidPurpose()))
} catch (_: InvalidPurpose) { invalidPurpose } catch (_: IllegalArgumentException) { invalidPurpose }
private fun JsonObject.s(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw InvalidPurpose()
private fun JsonObject.optional(key: String): String? = when (val v = get(key)) {
    JsonNull -> null
    is JsonPrimitive -> v.takeIf { it.isString }?.content ?: throw InvalidPurpose()
    else -> throw InvalidPurpose()
}
private fun JsonObject.n(key: String, min: Int = 0): Int = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= min } ?: throw InvalidPurpose()
private fun JsonObject.o(key: String) = get(key) as? JsonObject ?: throw InvalidPurpose()
private fun JsonObject.a(key: String) = get(key) as? JsonArray ?: throw InvalidPurpose()
private fun JsonElement.stringValue() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw InvalidPurpose()
private fun JsonObject.id(key: String): String = canonicalUuidOrNull(s(key)) ?: throw InvalidPurpose()
private fun JsonObject.at(key: String): Instant = Instant.parse(s(key))
private fun activity(o: JsonObject) = PurposeActivity(o.at("at"), o.s("kind"))
private fun detail(o: JsonObject) = PurposeDetail(
    o.id("id"), o.s("name"), o.optional("description"), PurposeStyleKeys.colorFromWire(o.s("colorKey")), PurposeStyleKeys.iconFromWire(o.s("iconKey")),
    o.n("candidateCount"), o.n("membershipVersion", 1), o.n("version", 1), activity(o.o("activity")), o.at("createdAt"), o.at("updatedAt"),
    o.a("allowedActions").map { it.stringValue() }.toSet(),
)
internal fun parsePurposeDetail(body: String): ClientResult<PurposeDetail> = purposeReading(body, ::detail)
internal fun parsePurposeCreated(body: String): ClientResult<PurposeCreated> = purposeReading(body) {
    PurposeCreated(detail(it.o("purpose")), it.n("activeCount"), it.n("purposeLimit", 1))
}
/** The B3 summary item DTO (PUR-01 rows, HOME-01 recentPurposes), wire keys converted to UI keys. */
private fun summaryItem(raw: JsonElement): PurposeSummary {
    val o = raw as? JsonObject ?: throw InvalidPurpose()
    return PurposeSummary(o.id("id"), o.s("name"), o.optional("description"), PurposeStyleKeys.colorFromWire(o.s("colorKey")), PurposeStyleKeys.iconFromWire(o.s("iconKey")),
        o.n("version", 1), o.n("candidateCount"), activity(o.o("activity")), o.a("previews").map {
            val preview = it as? JsonObject ?: throw InvalidPurpose()
            PurposePreview(preview.id("itemId"), preview.optional("imageUrl"))
        })
}
internal fun parsePurposeSummaryItem(raw: JsonElement): ClientResult<PurposeSummary> = try {
    ClientResult.Success(summaryItem(raw))
} catch (_: InvalidPurpose) { invalidPurpose } catch (_: IllegalArgumentException) { invalidPurpose }
internal fun parsePurposeCatalog(body: String): ClientResult<PurposeCatalog> = purposeReading(body) { root ->
    if (root.s("projection") != "SUMMARY" || root.optional("nextCursor") != null) throw InvalidPurpose()
    val purposes = root.a("purposes").map(::summaryItem)
    val archive = root.o("archiveSummary")
    PurposeCatalog(purposes, root.n("activeCount"), root.n("purposeLimit", 1), ArchiveSummary(archive.n("count"), archive.a("recentTitles").map { it.stringValue() }))
}
internal fun purposeCreateBody(command: PurposeCommand): String = buildJsonObject {
    put("name", command.name)
    command.description?.let { put("description", it) }
    put("colorKey", requireNotNull(PurposeStyleKeys.colorToWire(command.colorKey)))
    put("iconKey", requireNotNull(PurposeStyleKeys.iconToWire(command.iconKey)))
}.toString()
internal fun purposePatchBody(expectedVersion: Int, patch: PurposePatch): String = buildJsonObject {
    put("expectedVersion", expectedVersion)
    patch.name?.let { put("name", it) }
    (patch.description as? DescriptionChange.Set)?.let { put("description", it.value) }
    patch.colorKey?.let { put("colorKey", requireNotNull(PurposeStyleKeys.colorToWire(it))) }
    patch.iconKey?.let { put("iconKey", requireNotNull(PurposeStyleKeys.iconToWire(it))) }
}.toString()
