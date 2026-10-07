package app.http

import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

enum class CreateRequestError { INVALID_URL, INVALID_CLIENT_CREATED_AT }

sealed interface CreateRequestParseResult {
    data class Valid(val sourceUrl: String, val clientCreatedAt: Instant?) : CreateRequestParseResult
    data class Invalid(val code: CreateRequestError) : CreateRequestParseResult
}

/** Parses wire types only; URL admissibility stays with the creation service. */
fun parseCreateRequest(text: String): CreateRequestParseResult {
    val body = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: SerializationException) { null }
    val sourceUrl = (body?.get("sourceUrl") as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: return CreateRequestParseResult.Invalid(CreateRequestError.INVALID_URL)
    val value = body["clientCreatedAt"]
    if (value == null || value == JsonNull) return CreateRequestParseResult.Valid(sourceUrl, null)
    val textTime = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: return CreateRequestParseResult.Invalid(CreateRequestError.INVALID_CLIENT_CREATED_AT)
    val time = try {
        val offsetTime = OffsetDateTime.parse(textTime)
        val instant = offsetTime.toInstant()
        if (offsetTime.year !in 1..9999 || instant.atOffset(ZoneOffset.UTC).year !in 1..9999) {
            return CreateRequestParseResult.Invalid(CreateRequestError.INVALID_CLIENT_CREATED_AT)
        }
        // Avoid PostgreSQL's rounding carrying the final microsecond into year 10000.
        instant.truncatedTo(ChronoUnit.MICROS)
    } catch (_: DateTimeException) {
        return CreateRequestParseResult.Invalid(CreateRequestError.INVALID_CLIENT_CREATED_AT)
    }
    return CreateRequestParseResult.Valid(sourceUrl, time)
}
