import Foundation

/// Platform-only presentation decisions. Business validation belongs to the shared Presenter.
enum PurposePresentation {
    enum Dismiss: Equatable { case allow, confirm, block }
    static func expanded(previous: String?, ids: [String]) -> String? {
        if let previous, ids.contains(previous) { return previous }
        return ids.first
    }
    static func iconSize(progress: CGFloat) -> CGFloat { 44 - 8 * min(1, max(0, progress)) }
    static func nameSize(progress: CGFloat) -> CGFloat { 28 - 8 * min(1, max(0, progress)) }
    static func detailOpacity(progress: CGFloat) -> Double { Double(max(0, min(1, 1 - 2 * progress))) }
    static func dismiss(submitting: Bool, dirty: Bool) -> Dismiss {
        submitting ? .block : (dirty ? .confirm : .allow)
    }
}
