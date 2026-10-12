package app.extraction

import java.math.BigDecimal
import java.net.InetAddress
import java.net.URI
import org.jsoup.Jsoup

data class HttpFetchResponse(val status: Int, val headers: Map<String, String>, val body: String, val truncated: Boolean = false)

sealed interface ExtractionResult {
    data class Complete(val metadata: Metadata) : ExtractionResult
    data object NeedsBrowser : ExtractionResult
    data object Partial : ExtractionResult
}

data class Metadata(
    val title: String?, val description: String?, val imageUrl: String?, val canonicalUrl: String,
    val brand: String? = null, val price: BigDecimal? = null, val currency: String? = null, val merchant: String? = null,
)

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
                val parsed = ProductMetadataParser.parse(Jsoup.parse(response.body, current), response.truncated)
                val title = parsed.title ?: return ExtractionResult.NeedsBrowser
                return ExtractionResult.Complete(Metadata(title, parsed.description, parsed.imageUrl,
                    UrlCanonicalizer.choose(parsed.declaredCanonical, current), parsed.brand, parsed.price, parsed.currency, parsed.merchant))
            }
        }
        return ExtractionResult.Partial
    }
}
