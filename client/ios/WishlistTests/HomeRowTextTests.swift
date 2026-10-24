import Shared
import XCTest
@testable import Wishlist

/// Every RelativeTime and RowStatus maps to its 문구 표 key (Android `HomeRowTextTest`), and every
/// key the home screen uses exists in the app's string catalog in both languages.
final class HomeRowTextTests: XCTestCase {
    func testRelativeTimesMapToTimeKeysWithTheirValue() {
        XCTAssertEqual(HomeRowText.time(RelativeTimeJustNow.shared), HomeText("time.just.now"))
        XCTAssertEqual(HomeRowText.time(RelativeTimeMinutes(value: 5)), HomeText("time.minutes", 5))
        XCTAssertEqual(HomeRowText.time(RelativeTimeHours(value: 3)), HomeText("time.hours", 3))
        XCTAssertEqual(HomeRowText.time(RelativeTimeYesterday.shared), HomeText("time.yesterday"))
        XCTAssertEqual(HomeRowText.time(RelativeTimeDays(value: 2)), HomeText("time.days", 2))
        XCTAssertEqual(HomeRowText.time(RelativeTimeWeeks(value: 3)), HomeText("time.weeks", 3))
        XCTAssertEqual(HomeRowText.time(RelativeTimeOnDate(year: nil, month: 9, day: 9)), HomeText("time.date", args: [9, 9]))
        XCTAssertEqual(HomeRowText.time(RelativeTimeOnDate(year: KotlinInt(value: 2025), month: 10, day: 7)), HomeText("time.date.year", args: [2025, 10, 7]))
    }

    /// D15: the week and date keys format in both languages (several arguments in order).
    func testWeekAndDateWording() throws {
        let ko = try XCTUnwrap(HomeRowText.bundle(for: "ko"))
        let en = try XCTUnwrap(HomeRowText.bundle(for: "en"))
        XCTAssertEqual(HomeText("time.weeks", 2).resolve(ko), "2주 전")
        XCTAssertEqual(HomeText("time.weeks", 2).resolve(en), "2 wk ago")
        XCTAssertEqual(HomeText("time.date", args: [9, 9]).resolve(ko), "9월 9일")
        XCTAssertEqual(HomeText("time.date.year", args: [2025, 10, 7]).resolve(ko), "2025년 10월 7일")
        XCTAssertEqual(HomeText("time.date.year", args: [2025, 10, 7]).resolve(en), "10/7/2025")
    }

    func testEveryRowStatusHasAMetaKey() {
        let expected: [RowStatus: String] = [
            .localOnly: "home.pending.meta",
            .sending: "row.sending",
            .waitingNetwork: "row.waiting.network",
            .retrying: "row.retrying",
            .needsSignIn: "row.needs.sign.in",
            .failed: "row.failed",
            .processing: "row.processing",
        ]
        XCTAssertEqual(Set(RowStatus.allCases), Set(expected.keys))
        for (status, key) in expected {
            XCTAssertEqual(HomeRowText.metaKey(status), key, status.name)
        }
    }

    func testOnlyTheLocalRowMetaCarriesTheSavedTime() {
        for status in RowStatus.allCases {
            XCTAssertEqual(HomeRowText.metaShowsTime(status), status == .localOnly, status.name)
        }
    }

    /// VoiceOver names the row's two actions apart (Android `home_row_open_detail` / `home_row_open_original`).
    func testRowsNameTheirTwoActionsApart() throws {
        XCTAssertEqual(HomeRowText.openDetailKey, "home.row.open.detail")
        XCTAssertEqual(HomeRowText.openOriginalKey, "home.row.open.original")
        let ko = try XCTUnwrap(HomeRowText.bundle(for: "ko"))
        XCTAssertEqual(ko.localizedString(forKey: HomeRowText.openDetailKey, value: nil, table: nil), "상세 보기")
        XCTAssertEqual(ko.localizedString(forKey: HomeRowText.openOriginalKey, value: nil, table: nil), "원본 열기")
    }

    /// Resolution in a fixed language (the catalog's ko and en tables), independent of the simulator locale.
    func testMetaAndHeaderTextResolveInBothLanguages() throws {
        let ko = try XCTUnwrap(HomeRowText.bundle(for: "ko"))
        let en = try XCTUnwrap(HomeRowText.bundle(for: "en"))
        let local = HomeRow(key: "local-1", target: HomeRowTargetLocal(submissionId: "1"), host: "musinsa.com", sourceUrl: "https://musinsa.com/p/1", savedAt: RelativeTimeDays(value: 2), status: .localOnly)
        XCTAssertEqual(HomeRowText.meta(local, bundle: ko), "2일 전 저장 · 이 기기에만 있어요")
        XCTAssertEqual(HomeRowText.meta(local, bundle: en), "Saved 2 days ago · Only on this device")
        let processing = HomeRow(key: "item-1", target: HomeRowTargetItem(itemId: "1"), host: "29cm.co.kr", sourceUrl: "https://29cm.co.kr/p", savedAt: RelativeTimeJustNow.shared, status: .processing)
        XCTAssertEqual(HomeRowText.meta(processing, bundle: ko), "상품 정보 추출 중")
        XCTAssertEqual(HomeRowText.meta(processing, bundle: en), "Extracting product info")
        XCTAssertEqual(HomeRowText.todoCount(7, bundle: ko), "할 일 7개")
        XCTAssertEqual(HomeRowText.todoCount(7, bundle: en), "7 to-dos")
        XCTAssertEqual(HomeRowText.todoCount(1, bundle: ko), "할 일 1개")
        XCTAssertEqual(HomeRowText.todoCount(1, bundle: en), "1 to-do")
    }

    /// Every key of the C3 screens is in the catalog (a missing key would show the raw key on screen).
    func testEveryScreenKeyIsTranslated() throws {
        let keys = [
            "login.title", "login.point.share", "login.point.fetch", "login.point.devices", "login.apple", "login.google", "login.later",
            "home.logged.out.caption", "home.login.card.title", "home.login.card.fill", "home.login.card.devices", "home.login.button",
            "home.todo", "home.todo.count", "home.pending.title", "home.pending.subtitle", "home.pending.meta", "home.original",
            "home.processing.title", "home.processing.subtitle", "home.expand", "home.collapse",
            "row.sending", "row.waiting.network", "row.retrying", "row.needs.sign.in", "row.failed", "row.processing",
            "time.just.now", "time.minutes", "time.hours", "time.yesterday", "time.days", "time.weeks", "time.date", "time.date.year",
            "settings.title", "settings.back", "settings.account", "settings.signed.in.google", "settings.signed.in.apple", "settings.logout",
            "settings.login.hint", "settings.login", "settings.original.links", "settings.webview.clear", "settings.webview.clear.hint",
            "settings.webview.cleared", "settings.app.info", "settings.version",
            "logout.title", "logout.line.device", "logout.line.kept", "logout.line.resume", "dialog.cancel",
            "webview.clear.title", "webview.clear.line.login", "webview.clear.line.cookies", "webview.clear.line.kept",
            "webview.clear.line.irreversible", "webview.clear.confirm",
        ]
        for language in ["ko", "en"] {
            let bundle = try XCTUnwrap(HomeRowText.bundle(for: language), language)
            for key in keys {
                XCTAssertNotEqual(bundle.localizedString(forKey: key, value: "\u{1}", table: nil), "\u{1}", "\(language): \(key)")
            }
        }
    }
}
