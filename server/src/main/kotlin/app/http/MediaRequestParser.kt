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
    val numbers = listOf("byteSize", "width", "height").associateWith { body.strictCount(it) }
    val shape = buildSet {
        if (contentType == null) add("contentType")
        numbers.forEach { (field, value) -> if (value == null || value <= 0) add(field) }
    }
    if (shape.isNotEmpty()) return MediaRequestParseResult.Invalid("INVALID_MEDIA_REQUEST", shape)
    // Shape only. Value rules (type, size, edge limits and their priority) live in IntentRequest.violation, which the
    // service applies.
    return MediaRequestParseResult.Valid(IntentRequest(contentType!!, numbers.getValue("byteSize")!!, numbers.getValue("width")!!, numbers.getValue("height")!!))
}

/**
 * A JSON integer of any length. Values past Int saturate so an oversized size or edge reports MEDIA_TOO_LARGE or
 * MEDIA_DIMENSIONS_EXCEEDED rather than a shape error; negative ones stay non-positive.
 */
private fun JsonObject.strictCount(key: String): Int? {
    val text = (this[key] as? JsonPrimitive)?.takeIf { !it.isString && it.content.matches(Regex("-?[0-9]+")) }?.content ?: return null
    val negative = text.startsWith("-")
    val digits = text.removePrefix("-").trimStart('0').ifEmpty { "0" }
    val magnitude = if (digits.length > 10) Long.MAX_VALUE else digits.toLong()
    return if (negative) -minOf(magnitude, Int.MAX_VALUE.toLong()).toInt() else minOf(magnitude, Int.MAX_VALUE.toLong()).toInt()
}
