package app.extraction

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlCanonicalizerTest {
    @Test fun `normalize lowercases scheme and host and drops fragment and tracking query only`() {
        assertEquals("https://shop.example.com/p/1?color=red",
            UrlCanonicalizer.normalize("HTTPS://Shop.EXAMPLE.com/p/1?utm_source=a&color=red&fbclid=x#reviews"))
        assertEquals("https://example.com/p/1?size=270&color=red",
            UrlCanonicalizer.normalize("https://example.com/p/1?size=270&gclid=1&color=red&utm_campaign=b"))
        assertEquals("https://example.com/p/1", UrlCanonicalizer.normalize("https://example.com/p/1?utm_medium=x&msclkid=y&igshid=z&mc_cid=1&mc_eid=2"))
        assertEquals("https://example.com/P/Case", UrlCanonicalizer.normalize("https://example.com/P/Case"))
        assertEquals("not a url", UrlCanonicalizer.normalize("not a url"))
    }

    @Test fun `declared canonical on the same registrable domain is chosen`() {
        assertEquals("https://www.example.com/p/1", UrlCanonicalizer.choose("https://www.example.com/p/1#top", "https://m.example.com/p/1?utm_source=x"))
        assertEquals("https://m.example.com/p/1", UrlCanonicalizer.choose("/p/1?utm_source=x", "https://m.example.com/other"))
        assertEquals("https://shop.example.co.uk/p", UrlCanonicalizer.choose("https://shop.example.co.uk/p", "https://www.example.co.uk/q"))
        assertEquals("https://www.example.com/p/1", UrlCanonicalizer.choose("https://www.example.com/p/1", "http://www.example.com/p/1"))
    }

    @Test fun `unsafe or foreign declared canonical falls back to the normalized final URL`() {
        val final = "https://m.example.com/p/1?utm_source=x&color=red"
        val expected = "https://m.example.com/p/1?color=red"
        for (declared in listOf(null, "", "not a url", "https://other.com/p/1", "http://user@example.com/p", "https://93.184.216.34/p",
            "https://[2001:db8::1]/p", "https://www.example.com:8443/p", "ftp://example.com/p", "https://other.co.uk/p", "javascript:alert(1)", "http://m.example.com/p/1")) {
            assertEquals(expected, UrlCanonicalizer.choose(declared, final), declared.toString())
        }
        assertEquals("https://www.example.co.uk/q", UrlCanonicalizer.choose("https://other.co.uk/q", "https://www.example.co.uk/q"))
    }
}
