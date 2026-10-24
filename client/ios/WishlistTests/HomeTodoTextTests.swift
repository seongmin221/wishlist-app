import Shared
import XCTest
@testable import Wishlist

/// C7 home and flow wording (Android `HomeTodoTextTest`, same values): the header count, the flow
/// position, the end summaries (passes only, never 확정·보류 counts), the review card's AI line and the
/// fill header reason.
final class HomeTodoTextTests: XCTestCase {
    private func ko() throws -> Bundle { try XCTUnwrap(HomeRowText.bundle(for: "ko")) }
    private func en() throws -> Bundle { try XCTUnwrap(HomeRowText.bundle(for: "en")) }

    func testHeaderCountsEveryTodo() throws {
        XCTAssertEqual(HomeRowText.todoCount(8, bundle: try ko()), "할 일 8개")
    }

    func testPositionReadsCurrentOverTotal() {
        XCTAssertEqual(HomeTodoText.position(2, of: 5), "2 / 5")
    }

    func testSummariesCountOnlyPasses() throws {
        XCTAssertEqual(HomeTodoText.passedSummary(3, bundle: try ko()), "넘김 3")
        XCTAssertEqual(HomeTodoText.skippedSummary(2, bundle: try ko()), "건너뜀 2")
        XCTAssertEqual(HomeTodoText.passedSummary(1, bundle: try en()), "Passed 1")
        XCTAssertEqual(HomeTodoText.skippedSummary(2, bundle: try en()), "Skipped 2")
    }

    func testAILineFollowsTheSources() {
        XCTAssertEqual(HomeTodoText.aiLineKey(category: .ai, purpose: .ai), "flow.review.ai.both")
        XCTAssertEqual(HomeTodoText.aiLineKey(category: .ai, purpose: .unassigned), "flow.review.ai.category")
        XCTAssertEqual(HomeTodoText.aiLineKey(category: .ai, purpose: .user), "flow.review.ai.category")
        XCTAssertEqual(HomeTodoText.aiLineKey(category: .user, purpose: .ai), "flow.review.ai.purpose")
        XCTAssertNil(HomeTodoText.aiLineKey(category: .user, purpose: .user))
        XCTAssertNil(HomeTodoText.aiLineKey(category: nil, purpose: .unassigned))
    }

    func testFillReasonFollowsTheRequiredAction() throws {
        let bundle = try ko()
        func reason(_ action: RequiredAction) -> String {
            bundle.localizedString(forKey: HomeTodoText.fillReasonKey(action), value: nil, table: nil)
        }
        XCTAssertEqual(reason(.informationCompletion), "상품 정보를 가져오지 못했어요")
        XCTAssertEqual(reason(.categoryAssignment), "카테고리를 정하지 못했어요")
        XCTAssertEqual(reason(.categoryReassignment), "카테고리가 삭제됐어요")
    }

    func testHostDropsTheSchemeAndWww() {
        XCTAssertEqual(HomeTodoText.host("https://www.coupang.com/vp/1"), "coupang.com")
        XCTAssertEqual(HomeTodoText.host("not a url"), "not a url")
    }

    /// Every key the C7 screens read exists in both languages.
    func testEveryKeyIsInBothLanguages() throws {
        let keys = HomeTodoText.allKeys
        for bundle in [try ko(), try en()] {
            for key in keys {
                XCTAssertNotEqual(bundle.localizedString(forKey: key, value: "\u{0}", table: nil), "\u{0}", key)
            }
        }
    }
}
