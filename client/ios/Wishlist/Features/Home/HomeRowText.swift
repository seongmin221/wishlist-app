import Foundation
import Shared

/// A string-catalog key with its integer argument, if any (resolved only for display).
struct HomeText: Equatable {
    let key: String
    let arg: Int32?

    init(_ key: String, _ arg: Int32? = nil) {
        self.key = key
        self.arg = arg
    }

    /// Formats in the language the table was read from, so a plural variation follows that
    /// language's rules (not the device region's: Korean has no "one", English does).
    func resolve(_ bundle: Bundle = .main) -> String {
        let format = bundle.localizedString(forKey: key, value: nil, table: nil)
        let locale = bundle.preferredLocalizations.first.map(Locale.init(identifier:)) ?? .current
        return arg.map { String(format: format, locale: locale, $0) } ?? format
    }
}

/// Maps the Presenter's row values to the 문구 표 keys (Android `HomeRowText`). Wording is the
/// platform's; the Presenter only classifies.
enum HomeRowText {
    static func time(_ time: RelativeTime) -> HomeText {
        switch onEnum(of: time) {
        case .justNow: HomeText("time.just.now")
        case .minutes(let t): HomeText("time.minutes", t.value)
        case .hours(let t): HomeText("time.hours", t.value)
        case .yesterday: HomeText("time.yesterday")
        case .days(let t): HomeText("time.days", t.value)
        }
    }

    /// The row's second line. Only the signed-out row (`%@ 저장 · 이 기기에만 있어요`) takes the saved time.
    static func metaKey(_ status: RowStatus) -> String {
        switch status {
        case .localOnly: "home.pending.meta"
        case .sending: "row.sending"
        case .waitingNetwork: "row.waiting.network"
        case .retrying: "row.retrying"
        case .needsSignIn: "row.needs.sign.in"
        case .failed: "row.failed"
        case .processing: "row.processing"
        }
    }

    static func metaShowsTime(_ status: RowStatus) -> Bool { status == .localOnly }

    static func meta(_ row: HomeRow, bundle: Bundle = .main) -> String {
        let format = bundle.localizedString(forKey: metaKey(row.status), value: nil, table: nil)
        guard metaShowsTime(row.status) else { return format }
        return String(format: format, time(row.savedAt).resolve(bundle))
    }

    /// Logged-in header caption "할 일 N개" (N = the 분류 중 rows, the only to-do card in C3); a plural
    /// variation in the catalog ("1 to-do" / "7 to-dos").
    static func todoCount(_ count: Int, bundle: Bundle = .main) -> String {
        HomeText("home.todo.count", Int32(clamping: count)).resolve(bundle)
    }

    /// VoiceOver hint of a tappable row (opens the item or local link detail; Android `onClickLabel`).
    static let openDetailKey = "home.row.open.detail"

    /// VoiceOver name of the signed-out row's "원본" button (PR A: the system browser).
    static let openOriginalKey = "home.row.open.original"

    /// The catalog's table for one language (tests and previews); the app uses `.main`.
    static func bundle(for language: String, in base: Bundle = .main) -> Bundle? {
        base.path(forResource: language, ofType: "lproj").flatMap(Bundle.init(path:))
    }
}
