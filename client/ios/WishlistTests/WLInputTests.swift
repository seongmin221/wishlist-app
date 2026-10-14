import XCTest
@testable import Wishlist

/// C5 code point limit of `WLInput` (the cut itself; while IME marks text, `WLInputComposition` skips it as before).
final class WLInputTests: XCTestCase {
    func testGraphemeDefaultIsUnchanged() {
        XCTAssertEqual(WLInput.truncate("가나다", maxLength: 2), "가나")
        XCTAssertEqual(WLInput.truncate("👨‍👩‍👧😀", maxLength: 1), "👨‍👩‍👧")
        XCTAssertEqual(WLInput.truncate("abc", maxLength: 5), "abc")
    }

    func testCodePointLimitCountsScalars() {
        XCTAssertEqual(WLInput.truncate(String(repeating: "가", count: 41), maxLength: 40, unit: .codePoint).count, 40)
        XCTAssertEqual(WLInput.truncate(String(repeating: "😀", count: 41), maxLength: 40, unit: .codePoint).unicodeScalars.count, 40)
        // NFD 한글 "각" is three scalars.
        XCTAssertEqual(WLInput.truncate("\u{1100}\u{1161}\u{11A8}", maxLength: 2, unit: .codePoint), "")
        XCTAssertEqual(WLInput.truncate("ab", maxLength: 2, unit: .codePoint), "ab")
    }

    func testCodePointLimitCutsAtGraphemeBoundary() {
        // 38 letters + a 7-scalar family emoji: the emoji does not fit in 40 and goes whole.
        let text = String(repeating: "a", count: 38) + "👨‍👩‍👧‍👦"
        let cut = WLInput.truncate(text, maxLength: 40, unit: .codePoint)
        XCTAssertEqual(cut, String(repeating: "a", count: 38))
        // A flag (2 regional indicators) is never split into one indicator.
        XCTAssertEqual(WLInput.truncate("a🇰🇷", maxLength: 2, unit: .codePoint), "a")
        XCTAssertEqual(WLInput.truncate("a🇰🇷", maxLength: 3, unit: .codePoint), "a🇰🇷")
    }

    func testCodePointLimitKeepsCompositionBeforeTheLimit() {
        // A committed syllable right at the limit stays; nothing past the limit survives once committed.
        let committed = String(repeating: "가", count: 39) + "각"
        XCTAssertEqual(WLInput.truncate(committed, maxLength: 40, unit: .codePoint), committed)
        XCTAssertEqual(WLInput.truncate(committed + "ㄱ", maxLength: 40, unit: .codePoint), committed)
    }
}
