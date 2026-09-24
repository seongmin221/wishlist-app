package app.extraction

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HttpMetadataExtractorTest {
    private val safety = UrlSafetyPolicy { listOf(InetAddress.getByName("93.184.215.14")) }

    @Test fun `truncated page without product metadata does not use generic site title`() {
        val extractor = HttpMetadataExtractor(safety) { _, _ ->
            HttpFetchResponse(200, mapOf("content-type" to "text/html"), "<html><head><title>Example Shop</title></head>", truncated = true)
        }
        assertEquals(ExtractionResult.NeedsBrowser, extractor.extract("https://example.com/product"))
    }

    @Test fun `truncated page with product OG title remains usable`() {
        val extractor = HttpMetadataExtractor(safety) { _, _ ->
            HttpFetchResponse(200, mapOf("content-type" to "text/html"),
                "<html><head><meta property=\"og:title\" content=\"MIZUNO NEO DAICHI 10\"></head>", truncated = true)
        }
        val result = assertIs<ExtractionResult.Complete>(extractor.extract("https://example.com/product"))
        assertEquals("MIZUNO NEO DAICHI 10", result.metadata.title)
    }
}
