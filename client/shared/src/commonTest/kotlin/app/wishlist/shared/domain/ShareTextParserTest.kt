package app.wishlist.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/** The same inputs and expectations as Task 6's Swift `ShareTextExtractorTests` (Review Focus 3). */
class ShareTextParserTest {
    private val vectors = listOf(
        "https://www.musinsa.com/products/123" to ParsedShare.Link("https://www.musinsa.com/products/123"),
        "[무신사] 오버핏 셔츠 https://musinsa.com/p/1 지금 확인하세요" to ParsedShare.Link("https://musinsa.com/p/1"),
        "링크: https://coupang.com/vp/2." to ParsedShare.Link("https://coupang.com/vp/2"),
        "(https://ohou.se/p/3)" to ParsedShare.Link("https://ohou.se/p/3"),
        "https://a.example/x_(y)" to ParsedShare.Link("https://a.example/x_(y)"),
        "첫 https://a.example/1 둘째 https://b.example/2" to ParsedShare.Link("https://a.example/1"),
        "HTTPS://A.EXAMPLE/Path" to ParsedShare.Link("HTTPS://A.EXAMPLE/Path"),
        "상품　https://a.example/1　끝" to ParsedShare.Link("https://a.example/1"),
        "ftp://a.example/1" to ParsedShare.NoLink,
        "https://" to ParsedShare.NoLink,
        "그냥 글이에요" to ParsedShare.NoLink,
        "" to ParsedShare.NoLink,
        null to ParsedShare.NoLink,
        "https://a.example/" + "a".repeat(2048 - 18) to ParsedShare.Link("https://a.example/" + "a".repeat(2048 - 18)),
        "https://a.example/" + "a".repeat(2048 - 17) to ParsedShare.TooLong,
    )

    @Test fun vectors() = vectors.forEach { (input, expected) ->
        assertEquals(expected, ShareTextParser.parse(input), "input=$input")
    }

    /** Mirrors Swift `testOnlyAsciiWhitespaceAndIdeographicSpaceEndALink` on both Kotlin runtimes. */
    @Test fun onlyAsciiWhitespaceAndIdeographicSpaceEndALink() {
        for (separator in listOf(" ", "\t", "\n", "\u000B", "\u000C", "\r", "<", ">", "\"", "'", "\u3000")) {
            assertEquals(
                ParsedShare.Link("https://a.example/1"),
                ShareTextParser.parse("https://a.example/1${separator}x"),
                "separator=${separator.map { it.code }}",
            )
        }
        assertEquals(ParsedShare.Link("https://a.example/1\u00A0x"), ShareTextParser.parse("https://a.example/1\u00A0x"))
        assertEquals(ParsedShare.Link("https://a.example/1\u2003x"), ShareTextParser.parse("https://a.example/1\u2003x"))
    }

    @Test fun trailingPunctuationAndUnpairedClosersAreTrimmedRepeatedly() {
        assertEquals(ParsedShare.Link("https://a.example/p"), ShareTextParser.parse("「https://a.example/p」!"))
        assertEquals(ParsedShare.Link("https://a.example/p(1)"), ShareTextParser.parse("https://a.example/p(1))."))
        assertEquals(ParsedShare.Link("https://a.example/p%20q"), ShareTextParser.parse("https://a.example/p%20q?!"))
    }

    @Test fun emptyHostAfterTrimmingIsNoLink() {
        assertEquals(ParsedShare.NoLink, ShareTextParser.parse("https://."))
        assertEquals(ParsedShare.NoLink, ShareTextParser.parse("https:///path"))
    }
}
