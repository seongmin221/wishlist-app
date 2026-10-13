package app.http

import app.media.IntentRequest
import kotlinx.serialization.json.*

sealed interface MediaRequestParseResult {
    data class Valid(val request: IntentRequest) : MediaRequestParseResult
    data class Invalid(val code: String, val fields: Set<String> = emptySet()) : MediaRequestParseResult
}

private val INTENT_FIELDS = listOf("contentType", "byteSize", "width", "height")

fun parseMediaIntentRequest(raw: String): MediaRequestParseResult {
    val body = parseJsonObject(raw) ?: return MediaRequestParseResult.Invalid("INVALID_MEDIA_REQUEST")
    val unknown = body.keys.filterNot { it in INTENT_FIELDS }.toSet()
    if (unknown.isNotEmpty()) return MediaRequestParseResult.Invalid("INVALID_MEDIA_REQUEST", unknown)
    val contentType = body["contentType"].strictString()
    // Parse as Long so an oversized byteSize reports MEDIA_TOO_LARGE instead of a shape error; values past the
    // limits never reach Int arithmetic.
    val numbers = listOf("byteSize", "width", "height").associateWith { body.strictLong(it) }
    val shape = buildSet {
        if (contentType == null) add("contentType")
        numbers.forEach { (field, value) -> if (value == null || value <= 0) add(field) }
    }
    if (shape.isNotEmpty()) return MediaRequestParseResult.Invalid("INVALID_MEDIA_REQUEST", shape)
    fun clamp(value: Long) = minOf(value, Int.MAX_VALUE.toLong()).toInt()
    val request = IntentRequest(contentType!!, clamp(numbers.getValue("byteSize")!!), clamp(numbers.getValue("width")!!), clamp(numbers.getValue("height")!!))
    return request.violation()?.let { MediaRequestParseResult.Invalid(it) } ?: MediaRequestParseResult.Valid(request)
}

private fun JsonObject.strictLong(key: String): Long? = (this[key] as? JsonPrimitive)
    ?.takeIf { !it.isString && it.content.matches(Regex("-?[0-9]{1,18}")) }?.content?.toLongOrNull()
