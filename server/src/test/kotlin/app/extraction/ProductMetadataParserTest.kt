package app.extraction

import app.testutil.MetadataFixtures.jsonLd
import app.testutil.MetadataFixtures.meta
import app.testutil.MetadataFixtures.page
import app.testutil.MetadataFixtures.product
import java.math.BigDecimal
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProductMetadataParserTest {
    private fun parse(head: String, truncated: Boolean = false) =
        ProductMetadataParser.parse(Jsoup.parse(page(head), "https://example.com/p/1"), truncated)

    private fun priceOf(head: String): Pair<BigDecimal?, String?> = parse(head).let { it.price to it.currency }

    @Test fun `single offer price and currency are stored as exact decimal and uppercase code`() {
        assertEquals(BigDecimal("12900") to "KRW", priceOf(product(offers = """{"@type":"Offer","price":"12900","priceCurrency":"krw"}""")))
        assertEquals(BigDecimal("19.99") to "USD", priceOf(product(offers = """{"@type":"Offer","price":19.99,"priceCurrency":"USD"}""")))
        assertEquals(BigDecimal("1000") to "JPY", priceOf(product(offers = """[{"@type":"Offer","price":"1000","priceCurrency":"JPY"},{"@type":"Offer","price":"1000","priceCurrency":"JPY"}]""")))
    }

    @Test fun `ranges differing offers and invalid amounts or currencies leave both null`() {
        val nothing: Pair<BigDecimal?, String?> = null to null
        for (offers in listOf(
            """[{"@type":"Offer","price":"1000","priceCurrency":"KRW"},{"@type":"Offer","price":"2000","priceCurrency":"KRW"}]""",
            """[{"@type":"Offer","price":"1000","priceCurrency":"KRW"},{"@type":"Offer","price":"1000","priceCurrency":"USD"}]""",
            """{"@type":"AggregateOffer","lowPrice":"1000","highPrice":"2000","priceCurrency":"KRW"}""",
            """{"@type":"Offer","price":"12,900","priceCurrency":"KRW"}""",
            """{"@type":"Offer","price":"1e3","priceCurrency":"KRW"}""",
            """{"@type":"Offer","price":"-1","priceCurrency":"KRW"}""",
            """{"@type":"Offer","price":"1.23456","priceCurrency":"KRW"}""",
            """{"@type":"Offer","price":"1234567890123456","priceCurrency":"KRW"}""",
            """{"@type":"Offer","price":"1000","priceCurrency":"XYZ"}""",
            """{"@type":"Offer","price":"1000"}""",
            """{"@type":"Offer","priceCurrency":"KRW"}""",
        )) assertEquals(nothing, priceOf(product(offers = offers)), offers)
    }

    @Test fun `open graph price is used without JSON-LD and loses to JSON-LD on conflict`() {
        val og = meta("product:price:amount", "39000") + meta("product:price:currency", "KRW")
        assertEquals(BigDecimal("39000") to "KRW", priceOf(meta("og:title", "Cap") + og))
        assertEquals(BigDecimal("12900") to "KRW", priceOf(og + product(offers = """{"@type":"Offer","price":"12900","priceCurrency":"KRW"}""")))
    }

    @Test fun `brand accepts string object and open graph forms`() {
        assertEquals("Mizuno", parse(product(brand = "\"Mizuno\"")).brand)
        assertEquals("Mizuno", parse(product(brand = """{"@type":"Brand","name":"Mizuno"}""")).brand)
        assertEquals("Mizuno", parse(meta("og:title", "Shoe") + meta("product:brand", "Mizuno")).brand)
        assertNull(parse(product(brand = "\"${"a".repeat(201)}\"")).brand)
        assertNull(parse(product(brand = "\"   \"")).brand)
    }

    @Test fun `different products on one page leave brand and price null`() {
        val first = product("Shoe A", """{"@type":"Offer","price":"1000","priceCurrency":"KRW"}""", "\"A\"")
        val second = product("Shoe B", """{"@type":"Offer","price":"2000","priceCurrency":"KRW"}""", "\"B\"")
        val parsed = parse(first + second)
        assertNull(parsed.brand); assertNull(parsed.price); assertNull(parsed.currency)
        assertEquals("Shoe A", parsed.title)
    }

    @Test fun `graph wrapped and multi-typed products are found and broken scripts are ignored`() {
        val graph = jsonLd("""{"@graph":[{"@type":"WebPage"},{"@type":["Product","Thing"],"name":"Cap","brand":"CAYL",
            "offers":{"@type":"Offer","price":"45000","priceCurrency":"KRW","seller":{"@type":"Organization","name":"CAYL Store"}}}]}""")
        val parsed = parse(jsonLd("{ broken json") + graph)
        assertEquals("Cap", parsed.title); assertEquals("CAYL", parsed.brand); assertEquals(BigDecimal("45000"), parsed.price)
        assertEquals("CAYL Store", parsed.merchant)
        val ogOnly = parse(jsonLd("{ broken") + meta("og:title", "Fallback") + meta("og:site_name", "Shop"))
        assertEquals("Fallback", ogOnly.title); assertEquals("Shop", ogOnly.merchant)
    }

    @Test fun `merchant prefers seller then site name then null`() {
        val seller = product(offers = """{"@type":"Offer","price":"1","priceCurrency":"KRW","seller":{"name":"Seller"}}""")
        assertEquals("Seller", parse(seller + meta("og:site_name", "Site")).merchant)
        assertEquals("Site", parse(product() + meta("og:site_name", "Site")).merchant)
        assertNull(parse(product()).merchant)
    }

    @Test fun `declared canonical prefers link element over og url`() {
        val head = """<link rel="canonical" href="https://www.example.com/p/1">""" + meta("og:url", "https://www.example.com/og")
        assertEquals("https://www.example.com/p/1", parse(head).declaredCanonical)
        assertEquals("https://www.example.com/og", parse(meta("og:url", "https://www.example.com/og")).declaredCanonical)
        assertNull(parse(meta("og:title", "x")).declaredCanonical)
    }

    @Test fun `truncated page does not use generic document title`() {
        assertNull(parse("<title>Example Shop</title>", truncated = true).title)
        assertEquals("Example Shop", parse("<title>Example Shop</title>").title)
    }
}
