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
    if (body.keys.any { it !in INTENT_FIELDS }) return MediaRequestParseResult.Invalid("INVALID_MEDIA_REQUEST")
    val contentType = body["contentType"].strictString()
    val numbers = listOf("byteSize", "width", "height").associateWith { body.strictInt(it) }
    val shape = buildSet {
        if (contentType == null) add("contentType")
        numbers.forEach { (field, value) -> if (value == null || value <= 0) add(field) }
    }
    if (shape.isNotEmpty()) return MediaRequestParseResult.Invalid("INVALID_MEDIA_REQUEST", shape)
    val request = IntentRequest(contentType!!, numbers.getValue("byteSize")!!, numbers.getValue("width")!!, numbers.getValue("height")!!)
    return request.violation()?.let { MediaRequestParseResult.Invalid(it) } ?: MediaRequestParseResult.Valid(request)
}

private fun JsonObject.strictInt(key: String): Int? = (this[key] as? JsonPrimitive)
    ?.takeIf { !it.isString && it.content.matches(Regex("-?[0-9]+")) }?.content?.toIntOrNull()
