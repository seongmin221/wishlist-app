import Foundation
import Shared

/// A string-catalog key with its format arguments in order (resolved only for display). An argument
/// that is itself a `HomeText` (the `time.*` words) is resolved first, in the same table.
struct DetailLine: Equatable {
    enum Arg: Equatable {
        case int(Int32)
        case string(String)
        case text(HomeText)
    }

    let key: String
    let args: [Arg]

    init(_ key: String, _ args: [Arg] = []) {
        self.key = key
        self.args = args
    }

    /// Reads the format and the nested `time.*` words from the same table (like `HomeText.resolve`).
    /// No locale for the numbers themselves: years and dates must not get grouping ("2,025"); these
    /// lines have no plural variations (the nested `HomeText` applies its own language's rules).
    func resolve(_ bundle: Bundle = .main) -> String {
        let format = bundle.localizedString(forKey: key, value: nil, table: nil)
        guard !args.isEmpty else { return format }
        let values: [CVarArg] = args.map { arg in
            switch arg {
            case .int(let value): value
            case .string(let value): value
            case .text(let text): text.resolve(bundle)
            }
        }
        return String(format: format, arguments: values)
    }
}

/// Maps the shared detail values to the 문구 표 keys (Android `DetailText`; pure, the shared code only classifies).
enum DetailText {
    /// The INCOMPLETE notice line (C4 spec `DetailKind` table).
    static func noticeKey(_ notice: DetailNotice) -> String {
        switch notice {
        case .informationMissing: "detail.incomplete.info"
        case .categoryUndecided: "detail.incomplete.category"
        case .categoryDeleted: "detail.incomplete.reassign"
        }
    }

    /// "방금 저장" · "오늘 저장" · "M월 d일 저장" (another year: "y년 M월 d일 저장").
    static func savedText(_ label: SavedLabel) -> DetailLine {
        switch onEnum(of: label) {
        case .justNow: DetailLine("detail.saved.just.now")
        case .today: DetailLine("detail.saved.today")
        case .onDate(let date):
            if let year = date.year {
                DetailLine("detail.saved.date.year", [.int(year.int32Value), .int(date.month), .int(date.day)])
            } else {
                DetailLine("detail.saved.date", [.int(date.month), .int(date.day)])
            }
        }
    }

    /// D12: connection problems say "불러오지 못했어요", a deleted item its own line, everything else the server line.
    static func errorKey(_ error: ClientError) -> String {
        switch error.kind {
        case .network, .timeout: "detail.error.network"
        case .notFound: "detail.not.found"
        default: "detail.error.server"
        }
    }

    /// "N일 전 확인한 가격이에요. …" with the home row's relative time words.
    static func priceCheckedText(_ time: RelativeTime) -> DetailLine {
        DetailLine("detail.price.checked", [.text(HomeRowText.time(time))])
    }

    /// The purpose dot's color from the wire key (`CORAL`): its token when the lowercased key is one of
    /// `PurposeKeys.colorKeys`, otherwise nil (the caller draws a neutral `textSecondary` dot).
    static func purposeColor(_ colorKey: String?) -> WLPurposeColor? {
        guard let key = colorKey?.lowercased(), PurposeKeys.shared.colorKeys.contains(key) else { return nil }
        return WLPurposeColor.allCases.first { "\($0)" == key }
    }

    /// Ruling 7: the local screen's waiting tile caption is the home row's reason (`row.*`). A signed-out
    /// link's home line carries its saved time, so its tile uses the home card's title "분석 대기" instead.
    static func localTileKey(_ status: RowStatus) -> String {
        status == .localOnly ? "home.pending.title" : HomeRowText.metaKey(status)
    }

    /// The local screen's saved line: "N분 전 저장 · 이 기기에만 있어요" (every local link is still only here).
    static func localSavedText(_ savedAt: RelativeTime) -> DetailLine {
        DetailLine("home.pending.meta", [.text(HomeRowText.time(savedAt))])
    }

    /// The dialog's target row "host · 이 기기에만 있어요".
    static func deleteTargetText(_ host: String) -> DetailLine {
        DetailLine("local.delete.target", [.string(host)])
    }
}

/// Shared calendar inputs for the detail labels (kotlin `Instant` + the device's UTC offset at an instant).
enum DetailClock {
    static func instant(_ date: Date) -> KotlinInstant {
        KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: Int64((date.timeIntervalSince1970 * 1000).rounded(.down)))
    }

    /// The device's UTC offset (seconds) at `instant`, for `DisplayFormat.relative`/`saved`.
    static let utcOffset: (KotlinInstant) -> KotlinInt = { instant in
        let date = Date(timeIntervalSince1970: TimeInterval(instant.toEpochMilliseconds()) / 1000)
        return KotlinInt(int: Int32(TimeZone.current.secondsFromGMT(for: date)))
    }
}
