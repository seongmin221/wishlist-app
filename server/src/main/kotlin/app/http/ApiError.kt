package app.http

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ApiErrorEnvelope(val error: ApiError)

@Serializable
data class ApiError(
    val code: String,
    val requestId: String,
    val details: Map<String, JsonElement> = emptyMap(),
)
