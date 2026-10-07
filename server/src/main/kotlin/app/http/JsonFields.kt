package app.http

import kotlinx.serialization.json.*

internal fun parseJsonObject(raw: String): JsonObject? = try {
    Json.parseToJsonElement(raw) as? JsonObject
} catch (_: IllegalArgumentException) { null }

internal fun JsonElement?.strictString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.optionalStringValid(key: String): Boolean =
    this[key] == null || this[key] == JsonNull || this[key].strictString() != null

internal fun JsonObject.positiveVersion(key: String): Int? = (this[key] as? JsonPrimitive)
    ?.takeIf { !it.isString && it.content.matches(Regex("[1-9][0-9]*")) }?.content?.toIntOrNull()
