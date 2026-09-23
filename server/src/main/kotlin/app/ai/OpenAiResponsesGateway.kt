package app.ai

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class OpenAiConfig(val modelSnapshot: String, val apiKey: String) {
    init { require(modelSnapshot.isNotBlank() && modelSnapshot != "gpt-5.6-luna" && apiKey.isNotBlank()) }
}

data class GatewayResponse(val classification: ClassificationResult, val inputTokens: Int?, val outputTokens: Int?)

class OpenAiResponsesGateway(
    private val config: OpenAiConfig,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) {
    fun requestBody(metadata: String, candidates: CandidateSnapshot): JsonObject = JsonObject(mapOf(
        "model" to JsonPrimitive(config.modelSnapshot),
        "store" to JsonPrimitive(false),
        "max_output_tokens" to JsonPrimitive(80),
        "reasoning" to JsonObject(mapOf("effort" to JsonPrimitive("none"))),
        "input" to JsonPrimitive("Classify the product. Use only supplied IDs. Product: ${metadata.take(2400)}; categories: ${candidates.categoryIds.sorted()}; purposes: ${candidates.purposeIds.sorted()}"),
        "text" to JsonObject(mapOf("format" to JsonObject(mapOf(
            "type" to JsonPrimitive("json_schema"), "name" to JsonPrimitive("wishlist_classification"),
            "strict" to JsonPrimitive(true), "schema" to ClassificationSchema.outputSchema,
        )))),
    ))

    fun classify(metadata: String, candidates: CandidateSnapshot): GatewayResponse {
        val body = requestBody(metadata, candidates).toString()
        // UTF-8 byte length conservatively bounds token count for this text-only request.
        if (body.toByteArray(Charsets.UTF_8).size > 900) {
            return GatewayResponse(ClassificationResult.Unusable("input_too_large"), null, null)
        }
        val request = HttpRequest.newBuilder(URI("https://api.openai.com/v1/responses"))
            .timeout(Duration.ofSeconds(70))
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            catch (_: Exception) { return GatewayResponse(ClassificationResult.Retryable, null, null) }
        if (response.statusCode() == 429 || response.statusCode() >= 500) return GatewayResponse(ClassificationResult.Retryable, null, null)
        if (response.statusCode() !in 200..299) return GatewayResponse(ClassificationResult.Terminal("openai_http_${response.statusCode()}"), null, null)
        return parseResponse(response.body(), candidates)
    }

    internal fun parseResponse(raw: String, candidates: CandidateSnapshot): GatewayResponse = try {
        val root = Json.parseToJsonElement(raw).jsonObject
        val usage = root["usage"]?.jsonObject
        val input = usage?.get("input_tokens")?.jsonPrimitive?.intOrNull
        val output = usage?.get("output_tokens")?.jsonPrimitive?.intOrNull
        val status = root["status"]?.jsonPrimitive?.content
        if (status != "completed") return GatewayResponse(ClassificationResult.Terminal("incomplete_response"), input, output)
        val contents = root["output"]?.jsonArray.orEmpty().flatMap { it.jsonObject["content"]?.jsonArray.orEmpty() }
        if (contents.any { it.jsonObject["type"]?.jsonPrimitive?.content == "refusal" }) return GatewayResponse(ClassificationResult.Unusable("refusal"), input, output)
        val text = contents.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "output_text" }?.jsonObject?.get("text")?.jsonPrimitive?.content
        GatewayResponse(if (text == null) ClassificationResult.Unusable("missing_output") else ClassificationSchema.validate(text, candidates), input, output)
    } catch (_: Exception) { GatewayResponse(ClassificationResult.Unusable("invalid_response"), null, null) }
}
