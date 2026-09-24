package app.extraction

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UrlSafetyPolicyTest {
    @Test
    fun `private redirect target is rejected before fetching it`() {
        val visited = mutableListOf<String>()
        val policy = UrlSafetyPolicy { host ->
            if (host == "shop.example") listOf(InetAddress.getByName("93.184.215.14"))
            else listOf(InetAddress.getByName("127.0.0.1"))
        }
        val fetcher = HttpMetadataExtractor(policy) { url, _ ->
            visited += url
            HttpFetchResponse(302, mapOf("location" to "http://internal.example/secret"), "")
        }

        assertFailsWith<UnsafeUrlException> { fetcher.extract("https://shop.example/item") }
        assertEquals(listOf("https://shop.example/item"), visited)
    }

    @Test
    fun `mixed public and private dns answers are rejected`() {
        val policy = UrlSafetyPolicy { listOf(InetAddress.getByName("93.184.215.14"), InetAddress.getByName("10.0.0.1")) }
        assertFailsWith<UnsafeUrlException> { policy.validate("https://shop.example/item") }
    }

    @Test
    fun `json ld product name wins over open graph and title`() {
        val policy = UrlSafetyPolicy { listOf(InetAddress.getByName("93.184.215.14")) }
        val extractor = HttpMetadataExtractor(policy) { _, _ ->
            HttpFetchResponse(200, mapOf("content-type" to "text/html"), """<html><head><title>Generic</title><meta property="og:title" content="Open Graph"><script type="application/ld+json">{"@type":"Product","name":"Product Name"}</script></head></html>""")
        }
        val result = extractor.extract("https://shop.example/item")
        assertIs<ExtractionResult.Complete>(result)
        assertEquals("Product Name", result.metadata.title)
    }
}
