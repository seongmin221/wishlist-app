import XCTest
import Shared
@testable import Wishlist

final class CategoryTextTests: XCTestCase {
    func testEveryCategoryKeyIsTranslated() throws {
        let keys = [
            "category.title", "category.total", "category.add.chip", "category.signed.out.body", "category.signed.out.action",
            "category.empty.title", "category.empty.body", "category.empty.action", "category.list.custom.suffix", "category.list.more",
            "category.menu.edit", "category.list.empty.body", "category.card.unconfirmed", "category.card.no.purpose", "category.card.checked",
            "category.load.more.failed", "category.partial.failed", "category.loading", "category.gone",
            "category.form.create.title", "category.form.edit.title", "category.form.parent.label", "category.form.name.label",
            "category.form.name.hint", "category.form.name.placeholder", "category.form.desc.label", "category.form.desc.hint",
            "category.form.desc.placeholder", "category.form.examples.label", "category.form.examples.hint", "category.form.examples.count",
            "category.form.example.placeholder", "category.form.example.remove", "category.form.create.note", "category.form.picker.note",
            "category.form.usage", "category.form.edit.scope", "category.form.rename.note", "category.form.create", "category.form.save",
            "category.form.err.duplicate", "category.form.err.limit", "category.form.err.rate", "category.form.err.chars",
            "category.form.err.parent", "category.form.err.save", "category.form.conflict.loaded", "category.form.conflict.failed",
            "category.form.discard.title", "category.form.discard.body", "category.form.discard.keep", "category.form.discard.confirm",
            "category.picker.title", "category.picker.add.chip", "category.close", "category.back",
        ]
        for language in ["ko", "en"] {
            let bundle = try XCTUnwrap(HomeRowText.bundle(for: language), language)
            for key in keys {
                XCTAssertNotEqual(bundle.localizedString(forKey: key, value: nil, table: nil), key, "\(language): \(key)")
            }
        }
        for error in [FieldError.duplicate, .invalidChars, .parent] { XCTAssertTrue(keys.contains(CategoryText.fieldErrorKey(error))) }
        for message in [FormMessage.limitReached, .rateLimited, .saveFailed, .conflictLoaded, .conflictFailed] {
            XCTAssertTrue(keys.contains(CategoryText.messageKey(message)))
        }
    }

    func testWordingFormats() throws {
        let ko = try XCTUnwrap(HomeRowText.bundle(for: "ko"))
        let en = try XCTUnwrap(HomeRowText.bundle(for: "en"))
        XCTAssertEqual(CategoryText.total(69).resolve(ko), "상품 69개")
        XCTAssertEqual(CategoryText.total(69).resolve(en), "69 items")
        XCTAssertEqual(CategoryText.listSubtitle(parentName: "디지털·IT", custom: true).resolvedText(ko), "디지털·IT · 직접 만든 카테고리")
        XCTAssertEqual(CategoryText.listSubtitle(parentName: "디지털·IT", custom: false).resolvedText(ko), "디지털·IT")
        XCTAssertEqual(DetailLine("category.form.edit.scope", [.string("디지털·IT"), .int(2)]).resolve(ko), "‘디지털·IT’ 아래 · 상품 2개")
        XCTAssertEqual(DetailLine("category.form.usage", [.int(3), .int(20)]).resolve(ko), "내 세부 카테고리 3 / 20")
    }

    func testCardMetaDropsTheTimeWhenUnknown() {
        let named = CardMetaLine(purpose: PurposeLabelNamed(name: "출퇴근 헤드폰", colorKey: "CORAL"), checked: RelativeTimeDays(value: 2))
        XCTAssertTrue(CategoryText.cardMeta(named).hasPrefix("출퇴근 헤드폰 · "))
        XCTAssertTrue(CategoryText.cardDot(named).shown)
        XCTAssertEqual(CategoryText.cardDot(named).colorKey, "CORAL")
        let bare = CardMetaLine(purpose: PurposeLabelNone.shared, checked: nil)
        XCTAssertEqual(CategoryText.cardMeta(bare), String(localized: "category.card.no.purpose"))
        XCTAssertFalse(CategoryText.cardDot(bare).shown)
    }

    func testRailLabelBreaksOnlyAfterADotOrASpace() {
        let label = CategoryText.railLabel("스포츠·아웃도어·여행")
        XCTAssertEqual(label.replacingOccurrences(of: "\u{2060}", with: "").replacingOccurrences(of: "\u{200B}", with: ""), "스포츠·아웃도어·여행")
        XCTAssertTrue(label.contains("\u{2060}·\u{200B}"))
        XCTAssertFalse(label.contains("·\u{2060}")) // a break point after each dot
        XCTAssertTrue(label.contains("스\u{2060}포"))
    }

    func testPagerStepScrollsTheTallPageFirst() {
        var r = CategoryPagerMath.step(delta: -30, inner: 0, innerMax: 100, pager: 0)
        XCTAssertEqual(r.inner, 30)
        XCTAssertEqual(r.pager, 0)
        r = CategoryPagerMath.step(delta: -30, inner: 90, innerMax: 100, pager: 0)
        XCTAssertEqual(r.inner, 100)
        XCTAssertEqual(r.pager, -20)
        r = CategoryPagerMath.step(delta: 15, inner: 0, innerMax: 0, pager: 0)
        XCTAssertEqual(r.pager, 15)
        r = CategoryPagerMath.step(delta: 30, inner: 100, innerMax: 100, pager: -20)
        XCTAssertEqual(r.pager, 0)
        XCTAssertEqual(r.inner, 90)
    }

    func testPagerTarget() {
        XCTAssertEqual(CategoryPagerMath.target(page: 1, count: 3, offset: -150, velocity: 0, height: 600), 2)
        XCTAssertEqual(CategoryPagerMath.target(page: 1, count: 3, offset: -50, velocity: 0, height: 600), 1)
        XCTAssertEqual(CategoryPagerMath.target(page: 1, count: 3, offset: -50, velocity: -900, height: 600), 2)
        XCTAssertEqual(CategoryPagerMath.target(page: 1, count: 3, offset: 200, velocity: 0, height: 600), 0)
        XCTAssertEqual(CategoryPagerMath.target(page: 2, count: 3, offset: -300, velocity: -900, height: 600), 2)
        XCTAssertEqual(CategoryPagerMath.target(page: 0, count: 3, offset: 300, velocity: 900, height: 600), 0)
    }

    // MARK: Review fixes

    func testLoadMoreAsksWhenIdleAtTheEnd() {
        let ids = (1...40).map { "i\($0)" }
        // A short first page: every card is on screen.
        XCTAssertTrue(CategoryLoadMore.wants(hasMore: true, busy: false, waiting: false, laidIds: ["a", "b"], visible: ["a", "b"]))
        // At the end of a long list.
        XCTAssertTrue(CategoryLoadMore.wants(hasMore: true, busy: false, waiting: false, laidIds: ids, visible: ["i39", "i40"]))
        // Not near the end, nothing more, in flight / failed, or a page still laying out.
        XCTAssertFalse(CategoryLoadMore.wants(hasMore: true, busy: false, waiting: false, laidIds: ids, visible: ["i1", "i2"]))
        XCTAssertFalse(CategoryLoadMore.wants(hasMore: false, busy: false, waiting: false, laidIds: ids, visible: ["i40"]))
        XCTAssertFalse(CategoryLoadMore.wants(hasMore: true, busy: true, waiting: false, laidIds: ids, visible: ["i40"]))
        XCTAssertFalse(CategoryLoadMore.wants(hasMore: true, busy: false, waiting: true, laidIds: ids, visible: ["i40"]))
        XCTAssertFalse(CategoryLoadMore.wants(hasMore: true, busy: false, waiting: false, laidIds: [], visible: []))
    }

    func testOnlyTheEditBaselineReplacesTheTypedDraft() {
        XCTAssertTrue(CategoryFormView.replacesDraft(mode: .edit, from: .loading, to: .ready))
        // A create form's SELECT arriving keeps what is being typed.
        XCTAssertFalse(CategoryFormView.replacesDraft(mode: .create, from: .loading, to: .ready))
        XCTAssertFalse(CategoryFormView.replacesDraft(mode: .create, from: .loadFailed, to: .ready))
        XCTAssertFalse(CategoryFormView.replacesDraft(mode: .edit, from: .ready, to: .ready))
        XCTAssertFalse(CategoryFormView.replacesDraft(mode: .edit, from: .conflictUnresolved, to: .ready))
    }
}
