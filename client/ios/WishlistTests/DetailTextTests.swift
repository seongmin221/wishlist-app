import Shared
import XCTest
@testable import Wishlist

/// Every detail value maps to its 문구 표 key (Android `DetailTextTest`), the lines resolve in a fixed
/// language, and every key the detail screens use is in the catalog in both languages.
final class DetailTextTests: XCTestCase {
    func testEveryNoticeHasItsSentence() {
        let expected: [DetailNotice: String] = [
            .informationMissing: "detail.incomplete.info",
            .categoryUndecided: "detail.incomplete.category",
            .categoryDeleted: "detail.incomplete.reassign",
        ]
        XCTAssertEqual(Set(DetailNotice.allCases), Set(expected.keys))
        for (notice, key) in expected {
            XCTAssertEqual(DetailText.noticeKey(notice), key, notice.name)
        }
    }

    func testSavedLabelsMapToSavedKeysWithTheirDate() {
        XCTAssertEqual(DetailText.savedText(SavedLabelJustNow.shared), DetailLine("detail.saved.just.now"))
        XCTAssertEqual(DetailText.savedText(SavedLabelToday.shared), DetailLine("detail.saved.today"))
        XCTAssertEqual(DetailText.savedText(SavedLabelOnDate(year: nil, month: 9, day: 28)), DetailLine("detail.saved.date", [.int(9), .int(28)]))
        XCTAssertEqual(
            DetailText.savedText(SavedLabelOnDate(year: KotlinInt(int: 2025), month: 12, day: 31)),
            DetailLine("detail.saved.date.year", [.int(2025), .int(12), .int(31)])
        )
    }

    func testEveryErrorKindHasASentence() {
        for kind in ErrorKind.allCases {
            let expected: String = switch kind {
            case .network, .timeout: "detail.error.network"
            case .notFound: "detail.not.found"
            default: "detail.error.server"
            }
            let error = ClientError(kind: kind, code: nil, requestId: nil, currentVersion: nil, retryAfterSeconds: nil)
            XCTAssertEqual(DetailText.errorKey(error), expected, kind.name)
        }
    }

    func testPriceCheckedTimeUsesTheTimeKeys() {
        XCTAssertEqual(DetailText.priceCheckedText(RelativeTimeDays(value: 2)), DetailLine("detail.price.checked", [.text(HomeText("time.days", 2))]))
        XCTAssertEqual(DetailText.priceCheckedText(RelativeTimeJustNow.shared), DetailLine("detail.price.checked", [.text(HomeText("time.just.now"))]))
    }

    func testPurposeColorReadsTheWireKeyAndFallsBackToNeutral() {
        XCTAssertEqual(DetailText.purposeColor("CORAL"), .coral)
        XCTAssertEqual(DetailText.purposeColor("periwinkle"), .periwinkle)
        for key in PurposeKeys.shared.colorKeys {
            XCTAssertEqual(DetailText.purposeColor(key.uppercased()).map { "\($0)" }, key, key)
        }
        XCTAssertNil(DetailText.purposeColor(nil))
        XCTAssertNil(DetailText.purposeColor("TEAL"))
        XCTAssertNil(DetailText.purposeColor(""))
    }

    /// Ruling 7: the waiting tile says why the link waits (the home row's line); a signed-out link uses the home card title.
    func testLocalTileSaysWhyTheLinkWaits() {
        for status in RowStatus.allCases {
            let expected = status == .localOnly ? "home.pending.title" : HomeRowText.metaKey(status)
            XCTAssertEqual(DetailText.localTileKey(status), expected, status.name)
        }
    }

    func testLocalSavedLineIsTheHomePendingLine() {
        XCTAssertEqual(DetailText.localSavedText(RelativeTimeMinutes(value: 3)), DetailLine("home.pending.meta", [.text(HomeText("time.minutes", 3))]))
    }

    /// Resolution in a fixed language (the catalog's ko and en tables), independent of the simulator locale.
    func testLinesResolveInBothLanguages() throws {
        let ko = try XCTUnwrap(HomeRowText.bundle(for: "ko"))
        let en = try XCTUnwrap(HomeRowText.bundle(for: "en"))
        let dated = DetailText.savedText(SavedLabelOnDate(year: nil, month: 9, day: 28))
        XCTAssertEqual(dated.resolve(ko), "9월 28일 저장")
        XCTAssertEqual(dated.resolve(en), "Saved 9/28")
        let yeared = DetailText.savedText(SavedLabelOnDate(year: KotlinInt(int: 2025), month: 12, day: 31))
        XCTAssertEqual(yeared.resolve(ko), "2025년 12월 31일 저장")
        XCTAssertEqual(yeared.resolve(en), "Saved 12/31/2025")
        let price = DetailText.priceCheckedText(RelativeTimeDays(value: 2))
        XCTAssertEqual(price.resolve(ko), "2일 전 확인한 가격이에요. 지금 가격은 원본에서 확인해 주세요.")
        XCTAssertEqual(price.resolve(en), "Price checked 2 days ago. Check the current price on the original page.")
        let local = DetailText.localSavedText(RelativeTimeJustNow.shared)
        XCTAssertEqual(local.resolve(ko), "방금 저장 · 이 기기에만 있어요")
        XCTAssertEqual(local.resolve(en), "Saved just now · Only on this device")
        XCTAssertEqual(DetailLine("local.delete.target", [.string("musinsa.com")]).resolve(ko), "musinsa.com · 이 기기에만 있어요")
        XCTAssertEqual(DetailLine("local.delete.target", [.string("musinsa.com")]).resolve(en), "musinsa.com · Only on this device")
    }

    func testEveryDetailKeyIsTranslated() throws {
        let keys = [
            "detail.back", "detail.more", "detail.open.original", "detail.price.checked", "detail.category", "detail.category.empty",
            "detail.purpose", "detail.purpose.none", "detail.name.empty", "detail.saved.just.now", "detail.saved.today",
            "detail.saved.date", "detail.saved.date.year", "detail.processing.note", "detail.incomplete.info",
            "detail.incomplete.category", "detail.incomplete.reassign", "detail.error.network", "detail.error.server",
            "detail.retry", "detail.not.found", "detail.close",
            "local.delete", "local.delete.title", "local.delete.target", "local.delete.line.unsent", "local.delete.line.irreversible", "local.delete.failed",
            "home.row.open.detail", "home.row.open.original",
        ]
        let expected: [String: (String, String)] = [
            "detail.back": ("뒤로", "Back"),
            "detail.more": ("더보기", "More"),
            "detail.open.original": ("원본 보기", "View original"),
            "detail.category.empty": ("골라 주세요", "Choose one"),
            "detail.purpose.none": ("목적 미지정", "No purpose"),
            "detail.name.empty": ("제품명 · 입력해 주세요", "Name · Add one"),
            "detail.processing.note": ("정보를 가져오는 동안은 편집할 수 없어요. 끝나면 앱을 다시 열거나 새로고침할 때 반영돼요.",
                                       "You can't edit while we fetch the details. They appear when you reopen the app or refresh."),
            "detail.not.found": ("삭제된 상품이에요", "This item was deleted"),
            "local.delete.title": ("링크를 삭제할까요?", "Delete this link?"),
            "local.delete.line.unsent": ("아직 보내지 않은 링크예요", "This link hasn't been sent yet"),
            "local.delete.line.irreversible": ("되돌릴 수 없어요", "This can't be undone"),
            "local.delete.failed": ("지우지 못했어요", "Couldn't delete"),
            "home.row.open.detail": ("상세 보기", "View details"),
            "home.row.open.original": ("원본 열기", "Open original"),
        ]
        let ko = try XCTUnwrap(HomeRowText.bundle(for: "ko"))
        let en = try XCTUnwrap(HomeRowText.bundle(for: "en"))
        for key in keys {
            for bundle in [ko, en] {
                XCTAssertNotEqual(bundle.localizedString(forKey: key, value: "\u{1}", table: nil), "\u{1}", key)
            }
            if let (k, e) = expected[key] {
                XCTAssertEqual(ko.localizedString(forKey: key, value: nil, table: nil), k, key)
                XCTAssertEqual(en.localizedString(forKey: key, value: nil, table: nil), e, key)
            }
        }
    }
}
