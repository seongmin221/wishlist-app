package app.wishlist.shared.data.remote

import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.canonicalUuidOrNull
import app.wishlist.shared.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/*
 * B2 CAT-01~04 and B4 ITEM-02 page bodies. Read from the JSON tree with exact wire types (a quoted
 * number is not a number, unlike kotlinx's lenient Int decoding) so the contract shape is checked.
 * Unknown keys are ignored. Any missing field or wrong type is INVALID_RESPONSE; nothing throws.
 */

private val invalid = ClientResult.Failure(ClientError(ErrorKind.INVALID_RESPONSE))

/** Signals a malformed body inside the readers below; never escapes this file. */
private class Malformed : Exception()

private inline fun <T> reading(body: String, read: (JsonObject) -> T): ClientResult<T> = try {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: throw Malformed()
    ClientResult.Success(read(root))
} catch (e: Malformed) {
    invalid
} catch (e: IllegalArgumentException) { // SerializationException (bad JSON) is one
    invalid
}

private fun JsonObject.obj(key: String): JsonObject = get(key) as? JsonObject ?: throw Malformed()
private fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray ?: throw Malformed()
private fun JsonObject.string(key: String): String =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw Malformed()
private fun JsonObject.optionalString(key: String): String? = when (val v = get(key)) {
    null, JsonNull -> null
    is JsonPrimitive -> if (v.isString) v.content else throw Malformed()
    else -> throw Malformed()
}
private fun JsonObject.int(key: String, min: Int = 0): Int =
    (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= min } ?: throw Malformed()

/** Custom UUIDs are kept in canonical lowercase (cache and route comparisons); C-/G-IDs stay as is. */
private fun categoryId(raw: String): String = canonicalUuidOrNull(raw) ?: raw

private fun readEntry(o: JsonObject): CategoryEntry {
    val rawKind = o.string("kind")
    return CategoryEntry(
        id = categoryId(o.string("id")),
        name = o.string("name"),
        parentId = o.string("parentId"),
        kind = CategoryKind.entries.firstOrNull { it.name == rawKind && it != CategoryKind.UNKNOWN } ?: CategoryKind.UNKNOWN,
        rawKind = rawKind,
        displayOrder = o.int("displayOrder"),
        itemCount = o.int("itemCount"),
    )
}

private fun readGroup(o: JsonObject) = CategoryGroup(
    id = o.string("id"),
    name = o.string("name"),
    displayOrder = o.int("displayOrder"),
    itemCount = o.int("itemCount"),
    categories = o.array("categories").map { readEntry(it as? JsonObject ?: throw Malformed()) },
)

private fun readDetail(o: JsonObject) = CustomCategoryDetail(
    id = categoryId(o.string("id")),
    name = o.string("name"),
    parentId = o.string("parentId"),
    description = o.optionalString("description"),
    examples = when (val v = o["examples"]) {
        null, JsonNull -> emptyList()
        is JsonArray -> v.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw Malformed() }
        else -> throw Malformed()
    },
    itemCount = o.int("itemCount"),
    version = o.int("version", min = 1),
)

internal fun parseCatalog(body: String): ClientResult<CategoryCatalog> = reading(body) { o ->
    val scope = o.string("scope").let { s -> CategoryScope.entries.firstOrNull { it.name == s } ?: throw Malformed() }
    CategoryCatalog(
        scope = scope,
        taxonomyVersion = o.string("taxonomyVersion"),
        groups = o.array("groups").map { readGroup(it as? JsonObject ?: throw Malformed()) },
        customUsedCount = o.int("customUsedCount"),
        customLimit = o.int("customLimit"),
    )
}

internal fun parseCustomDetail(body: String): ClientResult<CustomCategoryDetail> = reading(body, ::readDetail)

internal fun parseCreated(body: String): ClientResult<CategoryCreated> = reading(body) { o ->
    CategoryCreated(readDetail(o.obj("category")), o.int("customUsedCount"), o.int("customLimit"))
}

private val pageJson = Json { ignoreUnknownKeys = true }

/** ITEM-02 page: `items[].item` uses the shared item mapper; a card that fails it fails the page. */
internal fun parseItemPage(body: String): ClientResult<ItemPage> {
    val page = reading(body) { o ->
        Triple(o.array("items"), o.int("totalCount"), o.optionalString("nextCursor"))
    }
    val (rows, total, next) = when (page) {
        is ClientResult.Failure -> return page
        is ClientResult.Success -> page.value
    }
    val items = rows.map { row ->
        val item = (row as? JsonObject)?.get("item") as? JsonObject ?: return invalid
        val dto = try {
            pageJson.decodeFromJsonElement(WishlistItemDto.serializer(), item)
        } catch (e: IllegalArgumentException) {
            return invalid
        }
        when (val mapped = mapItem(dto)) {
            is ClientResult.Failure -> return mapped
            is ClientResult.Success -> mapped.value
        }
    }
    return ClientResult.Success(ItemPage(items, total, next))
}

/** CAT-03 body (description omitted when null, examples always sent). */
internal fun createBody(command: CategoryCommand): String = JsonObject(
    buildMap {
        put("parentId", JsonPrimitive(command.parentId))
        put("name", JsonPrimitive(command.name))
        command.description?.let { put("description", JsonPrimitive(it)) }
        put("examples", JsonArray(command.examples.map(::JsonPrimitive)))
    },
).toString()

/** CAT-04 body: only changed fields; a cleared description is an explicit null. */
internal fun patchBody(expectedVersion: Int, patch: CategoryPatch): String = JsonObject(
    buildMap<String, JsonElement> {
        put("expectedVersion", JsonPrimitive(expectedVersion))
        patch.name?.let { put("name", JsonPrimitive(it)) }
        (patch.description as? DescriptionChange.Set)?.let { put("description", it.value?.let(::JsonPrimitive) ?: JsonNull) }
        patch.examples?.let { put("examples", JsonArray(it.map(::JsonPrimitive))) }
    },
).toString()
