import Foundation
import Shared

/// C7 home to-do and continuous-processing wording (Android `HomeTodoText`). Keys only where a screen
/// picks a phrase from data; fixed labels are read with `String(localized:)` where they are drawn.
enum HomeTodoText {
    /// Header counter "n / 전체" (numbers only, the same in every language).
    static func position(_ position: Int, of total: Int) -> String { "\(position) / \(total)" }

    /// Review flow end summary: only this session's temporary passes (C7 saves no 확정·보류).
    static func passedSummary(_ count: Int, bundle: Bundle = .main) -> String {
        HomeText("flow.summary.passed", Int32(clamping: count)).resolve(bundle)
    }

    /// Fill flow end summary: the skips.
    static func skippedSummary(_ count: Int, bundle: Bundle = .main) -> String {
        HomeText("flow.summary.skipped", Int32(clamping: count)).resolve(bundle)
    }

    /// The review card's bottom line from the category / purpose sources (Ruling 7, same on Android): both
    /// AI → 카테고리와 목적을 연결, category only → 카테고리를 정함, purpose only → 목적을 연결, else nil (no line).
    static func aiLineKey(category: ValueSource?, purpose: ValueSource) -> String? {
        switch (category == .ai, purpose == .ai) {
        case (true, true): "flow.review.ai.both"
        case (true, false): "flow.review.ai.category"
        case (false, true): "flow.review.ai.purpose"
        case (false, false): nil
        }
    }

    /// The fill flow header reason for the item's required action.
    static func fillReasonKey(_ action: RequiredAction) -> String {
        switch action {
        case .categoryAssignment: "flow.fill.reason.category"
        case .categoryReassignment: "flow.fill.reason.deleted"
        default: "flow.fill.reason.info"
        }
    }

    /// The carousel preview's small line: the category for review and analysis, the missing piece for fill.
    static func previewCaption(_ item: WishlistItem, group: HomeActionGroup, bundle: Bundle = .main) -> String {
        func text(_ key: String) -> String { bundle.localizedString(forKey: key, value: nil, table: nil) }
        switch group {
        case .informationCompletion:
            switch item.requiredAction {
            case .categoryAssignment: return text("home.preview.no.category")
            case .categoryReassignment: return text("home.preview.category.deleted")
            default: return host(item.sourceUrl)
            }
        default:
            return item.category.name ?? host(item.sourceUrl)
        }
    }

    /// The item's name, or the C6 status wording when it has none (PROCESSING → 분류 중, else 정보 보완 필요).
    static func title(_ item: WishlistItem, bundle: Bundle = .main) -> String {
        PurposeCandidateText.title(name: item.product.name, processing: item.analysis.status == .processing, bundle: bundle)
    }

    /// A link's site for display (the shared rule: host without `www.`, else the text's start).
    static func host(_ url: String) -> String { DisplayFormat.shared.host(url: url) }

    /// A HOME-01 group card's title (분류 중 reuses the C3 card's keys).
    static func groupTitleKey(_ group: HomeActionGroup) -> String {
        switch group {
        case .classificationReview: "home.group.review.title"
        case .informationCompletion: "home.group.fill.title"
        default: "home.processing.title"
        }
    }

    /// A HOME-01 group card's one-line subtitle.
    static func groupSubtitleKey(_ group: HomeActionGroup) -> String {
        switch group {
        case .classificationReview: "home.group.review.subtitle"
        case .informationCompletion: "home.group.fill.subtitle"
        default: "home.processing.subtitle"
        }
    }

    /// Every key the C7 screens read (tests check both languages have them).
    static let allKeys = [
        "flow.summary.passed", "flow.summary.skipped", "flow.review.ai.both", "flow.review.ai.category", "flow.review.ai.purpose",
        "flow.fill.reason.info", "flow.fill.reason.category", "flow.fill.reason.deleted",
        "home.preview.no.category", "home.preview.category.deleted", "home.preview.no.photo",
        "home.group.review.title", "home.group.review.subtitle", "home.group.fill.title", "home.group.fill.subtitle",
        "home.group.open", "home.pending.subtitle.signed.in","home.purposes", "home.purposes.all", "home.summary.failed", "home.refresh.failed",
        "flow.review.hint", "flow.review.notice", "flow.review.hold", "flow.review.confirm", "flow.review.category",
        "flow.review.purpose", "flow.review.change", "flow.review.purpose.none", "flow.review.category.none",
        "flow.review.done.title", "flow.fill.done.title", "flow.home", "flow.restart",
        "flow.fill.reanalyze", "flow.fill.or", "flow.fill.image", "flow.fill.image.add", "flow.fill.name",
        "flow.fill.name.placeholder", "flow.fill.category", "flow.fill.category.select", "flow.fill.skip",
        "flow.fill.save", "flow.fill.notice",
    ]
}
