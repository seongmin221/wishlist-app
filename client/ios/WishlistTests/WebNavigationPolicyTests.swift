import XCTest
@testable import Wishlist

/// Exhaustive scheme × frame × gesture table (spec §4). Same vectors as Android `WebNavigationPolicyTest`;
/// change both together.
final class WebNavigationPolicyTests: XCTestCase {
    private struct Row {
        let scheme: String
        let isAboutBlank: Bool
        /// nil = external app, at once on a gesture, otherwise after the confirmation.
        let main: WebDecision?
        let sub: WebDecision?
    }

    private let table: [Row] = [
        Row(scheme: "http", isAboutBlank: false, main: .loadInside, sub: .loadInside),
        Row(scheme: "https", isAboutBlank: false, main: .loadInside, sub: .loadInside),
        Row(scheme: "HTTPS", isAboutBlank: false, main: .loadInside, sub: .loadInside),
        Row(scheme: "about", isAboutBlank: true, main: .loadInside, sub: .loadInside),
        Row(scheme: "about", isAboutBlank: false, main: .block, sub: .loadInside),
        Row(scheme: "data", isAboutBlank: false, main: .block, sub: .loadInside),
        Row(scheme: "blob", isAboutBlank: false, main: .block, sub: .loadInside),
        Row(scheme: "javascript", isAboutBlank: false, main: .block, sub: .block),
        Row(scheme: "JavaScript", isAboutBlank: false, main: .block, sub: .block),
        Row(scheme: "file", isAboutBlank: false, main: .block, sub: .block),
        Row(scheme: "content", isAboutBlank: false, main: .block, sub: .block),
        Row(scheme: "", isAboutBlank: false, main: .block, sub: .block),
        Row(scheme: "intent", isAboutBlank: false, main: nil, sub: nil),
        Row(scheme: "tel", isAboutBlank: false, main: nil, sub: nil),
        Row(scheme: "mailto", isAboutBlank: false, main: nil, sub: nil),
        Row(scheme: "market", isAboutBlank: false, main: nil, sub: nil),
        Row(scheme: "itms-apps", isAboutBlank: false, main: nil, sub: nil),
        Row(scheme: "kakaotalk", isAboutBlank: false, main: nil, sub: nil),
        Row(scheme: "ispmobile", isAboutBlank: false, main: nil, sub: nil),
    ]

    func testEverySchemeFrameAndGestureCombinationMatchesTheTable() {
        for row in table {
            for mainFrame in [true, false] {
                for gesture in [true, false] {
                    let expected = (mainFrame ? row.main : row.sub) ?? (gesture ? .openExternal : .confirmExternal)
                    XCTAssertEqual(
                        WebNavigationPolicy.decide(scheme: row.scheme, mainFrame: mainFrame, userGesture: gesture, isAboutBlank: row.isAboutBlank),
                        expected,
                        "\(row.scheme) blank=\(row.isAboutBlank) main=\(mainFrame) gesture=\(gesture)"
                    )
                }
            }
        }
    }

    func testTopLevelDataUrlIsBlockedEvenWithAGesture() {
        XCTAssertEqual(WebNavigationPolicy.decide(scheme: "data", mainFrame: true, userGesture: true, isAboutBlank: false), .block)
    }
}
