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

    @Test fun `complete metadata carries product fields and canonical chosen against the redirected final URL`() {
        val html = app.testutil.MetadataFixtures.page(
            """<link rel="canonical" href="https://www.example.com/p/1">""" +
                app.testutil.MetadataFixtures.product("Cap", """{"@type":"Offer","price":"45000","priceCurrency":"KRW","seller":{"name":"CAYL"}}""", "\"CAYL\""))
        val extractor = HttpMetadataExtractor(safety) { url, _ ->
            if (url == "https://example.com/start") HttpFetchResponse(302, mapOf("location" to "https://m.example.com/p/1?utm_source=x"), "")
            else HttpFetchResponse(200, mapOf("content-type" to "text/html"), html)
        }
        val metadata = assertIs<ExtractionResult.Complete>(extractor.extract("https://example.com/start")).metadata
        assertEquals("Cap", metadata.title)
        assertEquals("CAYL", metadata.brand)
        assertEquals(java.math.BigDecimal("45000"), metadata.price)
        assertEquals("KRW", metadata.currency)
        assertEquals("CAYL", metadata.merchant)
        assertEquals("https://www.example.com/p/1", metadata.canonicalUrl)
    }

    @Test fun `foreign declared canonical keeps the normalized final URL`() {
        val html = app.testutil.MetadataFixtures.page("""<link rel="canonical" href="https://other.com/p/1"><meta property="og:title" content="Cap">""")
        val extractor = HttpMetadataExtractor(safety) { _, _ -> HttpFetchResponse(200, mapOf("content-type" to "text/html"), html) }
        val metadata = assertIs<ExtractionResult.Complete>(extractor.extract("https://example.com/p/1?fbclid=1#x")).metadata
        assertEquals("https://example.com/p/1", metadata.canonicalUrl)
    }
}
