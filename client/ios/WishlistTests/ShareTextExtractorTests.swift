import XCTest

/// The share extension's link extractor (compiled into this test target from `ShareExtension/`).
/// The vector table is Kotlin `ShareTextParserTest`'s, in the same order with the same values
/// (Review Focus 3): both platforms must agree on every messy shared text.
final class ShareTextExtractorTests: XCTestCase {
    private let vectors: [(String?, ShareExtraction)] = [
        ("https://www.musinsa.com/products/123", .link("https://www.musinsa.com/products/123")),
        ("[무신사] 오버핏 셔츠 https://musinsa.com/p/1 지금 확인하세요", .link("https://musinsa.com/p/1")),
        ("링크: https://coupang.com/vp/2.", .link("https://coupang.com/vp/2")),
        ("(https://ohou.se/p/3)", .link("https://ohou.se/p/3")),
        ("https://a.example/x_(y)", .link("https://a.example/x_(y)")),
        ("첫 https://a.example/1 둘째 https://b.example/2", .link("https://a.example/1")),
        ("HTTPS://A.EXAMPLE/Path", .link("HTTPS://A.EXAMPLE/Path")),
        ("상품\u{3000}https://a.example/1\u{3000}끝", .link("https://a.example/1")),
        ("ftp://a.example/1", .noLink),
        ("https://", .noLink),
        ("그냥 글이에요", .noLink),
        ("", .noLink),
        (nil, .noLink),
        ("https://a.example/" + String(repeating: "a", count: 2048 - 18), .link("https://a.example/" + String(repeating: "a", count: 2048 - 18))),
        ("https://a.example/" + String(repeating: "a", count: 2048 - 17), .tooLong),
    ]

    func testVectorsMatchTheKotlinParser() {
        for (input, expected) in vectors {
            XCTAssertEqual(ShareTextExtractor.extract(input), expected, "input=\(input.map { String($0.prefix(60)) } ?? "nil")")
        }
    }

    func testTrailingPunctuationAndUnpairedClosersAreTrimmedRepeatedly() {
        XCTAssertEqual(ShareTextExtractor.extract("「https://a.example/p」!"), .link("https://a.example/p"))
        XCTAssertEqual(ShareTextExtractor.extract("https://a.example/p(1))."), .link("https://a.example/p(1)"))
        XCTAssertEqual(ShareTextExtractor.extract("https://a.example/p%20q?!"), .link("https://a.example/p%20q"))
    }

    func testEmptyHostAfterTrimmingIsNoLink() {
        XCTAssertEqual(ShareTextExtractor.extract("https://."), .noLink)
        XCTAssertEqual(ShareTextExtractor.extract("https:///path"), .noLink)
    }

    /// Kotlin's `\s` is the ASCII set only; Unicode spaces other than U+3000 stay inside the link
    /// (Swift/ICU `\s` would cut at them and disagree with Android).
    func testOnlyAsciiWhitespaceAndIdeographicSpaceEndALink() {
        for separator in [" ", "\t", "\n", "\u{0B}", "\u{0C}", "\r", "<", ">", "\"", "'", "\u{3000}"] {
            XCTAssertEqual(ShareTextExtractor.extract("https://a.example/1\(separator)x"), .link("https://a.example/1"), "separator=\(separator.unicodeScalars.map { $0.value })")
        }
        XCTAssertEqual(ShareTextExtractor.extract("https://a.example/1\u{00A0}x"), .link("https://a.example/1\u{00A0}x"))
        XCTAssertEqual(ShareTextExtractor.extract("https://a.example/1\u{2003}x"), .link("https://a.example/1\u{2003}x"))
    }

    /// The 2048 limit counts UTF-16 units like Kotlin `String.length` (a non-BMP character is 2).
    func testLengthCountsUtf16Units() {
        let base = "https://a.example/" + String(repeating: "a", count: 2048 - 18 - 2)
        XCTAssertEqual(ShareTextExtractor.extract(base + "😀"), .link(base + "😀"))
        XCTAssertEqual(ShareTextExtractor.extract(base + "a😀"), .tooLong)
    }
}
