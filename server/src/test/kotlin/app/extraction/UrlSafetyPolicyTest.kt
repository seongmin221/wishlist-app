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
    fun `dns failures are not reported as blocked addresses`() {
        for (resolve in listOf<(String) -> List<InetAddress>>({ throw java.net.UnknownHostException("nx") }, { emptyList() },
            { throw IllegalStateException("resolver crashed") }, { throw DnsLookupFailed() })) {
            assertFailsWith<DnsLookupFailed> { UrlSafetyPolicy(resolve).validate("https://shop.example/item") }
        }
    }

    @Test
    fun `scheme port credentials local hosts and malformed URLs are unsafe`() {
        val policy = UrlSafetyPolicy { listOf(InetAddress.getByName("93.184.215.14")) }
        for (url in listOf("ftp://shop.example/item", "https://shop.example:8443/item", "https://user@shop.example/item",
            "https://localhost/item", "https://printer.local/item", "https://127.0.0.1/item", "https://[::1]/item", "http://exa mple.com/")) {
            assertFailsWith<UnsafeUrlException>(url) { policy.validate(url) }
        }
        for (address in listOf("127.0.0.1", "10.0.0.1", "169.254.169.254", "100.64.0.1")) {
            assertFailsWith<UnsafeUrlException>(address) { UrlSafetyPolicy { listOf(InetAddress.getByName(address)) }.validate("https://shop.example/item") }
        }
    }

    @Test
    fun `internal and translated ipv6 ranges are blocked while public ipv6 passes`() {
        for (address in listOf("fd00::1", "fc00::1", "fd20:1234::5", "64:ff9b::7f00:1", "2002:7f00:1::1", "::7f00:1", "fe80::1", "::1")) {
            assertFailsWith<UnsafeUrlException>(address) { UrlSafetyPolicy { listOf(InetAddress.getByName(address)) }.validate("https://shop.example/item") }
        }
        assertEquals(1, UrlSafetyPolicy { listOf(InetAddress.getByName("2606:4700::1111")) }.validate("https://shop.example/item").size)
        assertFailsWith<UnsafeUrlException> { UrlSafetyPolicy { listOf(InetAddress.getByName("93.184.215.14")) }.validate("https://[fd12:3456::5]/") }
    }

    @Test
    fun `dns ipv6 answers embedding ipv4 targets are checked like native resolution returns them`() {
        // Native DNS keeps mapped AAAA answers as Inet6Address; getByName would convert them to Inet4Address.
        fun dnsAnswer(literal: String) = java.net.Inet6Address.getByAddress("shop.example", InetAddress.getByName("[$literal]").let { parsed ->
            if (parsed.address.size == 16) parsed.address else ByteArray(10) + byteArrayOf(-1, -1) + parsed.address
        }, -1)
        for (address in listOf("::ffff:127.0.0.1", "::ffff:169.254.169.254", "::ffff:10.0.0.1", "::ffff:0:7f00:1",
            "2001:0:4136:e378::1", "64:ff9b:1::a00:1")) {
            assertFailsWith<UnsafeUrlException>(address) { UrlSafetyPolicy { listOf(dnsAnswer(address)) }.validate("https://shop.example/item") }
        }
        assertEquals(1, UrlSafetyPolicy { listOf(dnsAnswer("::ffff:93.184.215.14")) }.validate("https://shop.example/item").size)
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
