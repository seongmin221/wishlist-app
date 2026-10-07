import UniformTypeIdentifiers
import XCTest

/// The share extension's attachment decision (compiled into this test target from `ShareExtension/`).
final class ShareInputTests: XCTestCase {
    private let shared = "[무신사] 오버핏 셔츠 https://musinsa.com/p/1 지금 확인하세요"

    // MARK: I1 — plain-text Data is text, not a URL representation

    func testPlainTextDataDecodesAsUtf8Text() {
        let data = Data(shared.utf8)
        XCTAssertEqual(ShareInput.string(data as NSData, type: .plainText), shared)
    }

    func testPlainTextDataYieldsOnlyTheLink() async {
        let text = ShareInput.string(Data(shared.utf8) as NSData, type: .plainText)
        let result = await ShareInput.decide(urlText: nil, plainText: { text })
        XCTAssertEqual(result, .link("https://musinsa.com/p/1"))
    }

    func testUrlDataStillUsesTheUrlRepresentation() {
        let data = URL(string: "https://musinsa.com/p/1")!.dataRepresentation
        XCTAssertEqual(ShareInput.string(data as NSData, type: .url), "https://musinsa.com/p/1")
    }

    func testUrlStringAndAttributedItemsPassThrough() {
        XCTAssertEqual(ShareInput.string(URL(string: "https://a.example/1")! as NSURL, type: .url), "https://a.example/1")
        XCTAssertEqual(ShareInput.string(shared as NSString, type: .plainText), shared)
        XCTAssertEqual(ShareInput.string(NSAttributedString(string: shared), type: .plainText), shared)
    }

    // MARK: I2 — a URL attachment without a web link falls back to the plain text

    func testAppSchemeUrlFallsBackToPlainText() async {
        let result = await ShareInput.decide(urlText: "musinsa://product/1", plainText: { self.shared })
        XCTAssertEqual(result, .link("https://musinsa.com/p/1"))
    }

    func testWebUrlWinsWithoutLoadingPlainText() async {
        var loadedPlain = false
        let result = await ShareInput.decide(urlText: "https://a.example/1", plainText: {
            loadedPlain = true
            return "https://b.example/2"
        })
        XCTAssertEqual(result, .link("https://a.example/1"))
        XCTAssertFalse(loadedPlain)
    }

    func testNoLinkAnywhereIsNoLink() async {
        let result = await ShareInput.decide(urlText: "musinsa://product/1", plainText: { "그냥 글이에요" })
        XCTAssertEqual(result, .noLink)
        let none = await ShareInput.decide(urlText: nil, plainText: { nil })
        XCTAssertEqual(none, .noLink)
    }

    func testTooLongUrlDoesNotFallBack() async {
        let long = "https://a.example/" + String(repeating: "a", count: 2048)
        let result = await ShareInput.decide(urlText: long, plainText: { "https://b.example/2" })
        XCTAssertEqual(result, .tooLong)
    }

    // MARK: end to end through real item providers

    func testExtensionItemWithAppUrlAndTextUsesTheTextLink() async {
        let item = NSExtensionItem()
        item.attachments = [
            NSItemProvider(item: URL(string: "musinsa://product/1")! as NSURL, typeIdentifier: UTType.url.identifier),
            NSItemProvider(item: Data(shared.utf8) as NSData, typeIdentifier: UTType.plainText.identifier),
        ]
        let result = await ShareInput.extraction(from: [item])
        XCTAssertEqual(result, .link("https://musinsa.com/p/1"))
    }
}
