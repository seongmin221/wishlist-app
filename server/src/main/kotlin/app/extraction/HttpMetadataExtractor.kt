package app.extraction

import java.net.InetAddress
import java.net.URI
import org.jsoup.Jsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class HttpFetchResponse(val status: Int, val headers: Map<String, String>, val body: String)

sealed interface ExtractionResult {
    data class Complete(val metadata: Metadata) : ExtractionResult
    data object NeedsBrowser : ExtractionResult
    data object Partial : ExtractionResult
}

data class Metadata(val title: String?, val description: String?, val imageUrl: String?, val canonicalUrl: String)

class HttpMetadataExtractor(
    private val safety: UrlSafetyPolicy,
    private val fetch: (String, List<InetAddress>) -> HttpFetchResponse,
) {
    fun extract(sourceUrl: String): ExtractionResult {
        var current = sourceUrl
        repeat(5) {
            val addresses = safety.validate(current)
            val response = fetch(current, addresses)
            if (response.status in 300..399) {
                val location = response.headers.entries.firstOrNull { it.key.equals("location", true) }?.value
                    ?: return ExtractionResult.Partial
                current = URI(current).resolve(location).toString()
                safety.validate(current)
            } else {
                if (response.status != 200) return ExtractionResult.Partial
                val document = Jsoup.parse(response.body, current)
                val product = document.select("script[type=application/ld+json]")
                    .firstNotNullOfOrNull { script -> runCatching { findProduct(Json.parseToJsonElement(script.data())) }.getOrNull() }
                val title = product?.get("name")?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
                    ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: document.title().trim().takeIf { it.isNotEmpty() }
                val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.takeIf { it.isNotBlank() }
                    ?: document.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() }
                val image = document.selectFirst("meta[property=og:image]")?.attr("abs:content")?.takeIf { it.isNotBlank() }
                return if (title == null) ExtractionResult.NeedsBrowser
                else ExtractionResult.Complete(Metadata(title, description, image, current))
            }
        }
        return ExtractionResult.Partial
    }

    private fun findProduct(element: JsonElement): kotlinx.serialization.json.JsonObject? = when (element) {
        is kotlinx.serialization.json.JsonArray -> element.firstNotNullOfOrNull(::findProduct)
        is kotlinx.serialization.json.JsonObject -> {
            val type = element["@type"]?.toString()?.lowercase().orEmpty()
            if ("product" in type) element else element["@graph"]?.let(::findProduct)
        }
        else -> null
    }
}
