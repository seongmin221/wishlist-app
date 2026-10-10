import Foundation
import Shared

/// Keeps a page from flooding FWebViewExternal (spec §4, D16; Android `ExternalPromptGate`). A request without a
/// user tap is asked about at most once at a time; once the user cancels, further non-gesture requests are dropped
/// until the main frame starts a page on a different host (Ruling 13: lowercased, `www.`-less, like the top bar).
/// Taps always launch (D16). Main thread only; lives in `WebViewModel`.
final class ExternalPromptGate {
    enum Action: Equatable { case launch, prompt, drop }

    private var prompting = false

    /// The main-frame host the user cancelled on; non-nil while non-gesture requests are silenced.
    private var silencedOn: String?

    /// `confirm`: the request needs FWebViewExternal (no user gesture).
    func onRequest(confirm: Bool) -> Action {
        if !confirm { return .launch }
        if prompting || silencedOn != nil { return .drop }
        prompting = true
        return .prompt
    }

    /// The dialog closed: 열기 (`confirmed`) or 취소 / VoiceOver escape.
    func onPromptClosed(confirmed: Bool, currentURL: String) {
        guard prompting else { return }
        prompting = false
        if !confirmed { silencedOn = Self.host(currentURL) }
    }

    /// `showDialog` refused (another overlay was opening, or no screen): nothing was asked, nothing is silenced.
    func onPromptNotShown() {
        prompting = false
    }

    /// The main frame started a navigation; another page on the cancelled-on host does not lift the silence.
    func onMainFrameNavigation(_ url: String) {
        if silencedOn != nil, Self.host(url) != silencedOn { silencedOn = nil }
    }

    /// The top bar's host (`WebPageState.host`, Android `DisplayFormat.host`).
    private static func host(_ url: String) -> String { DisplayFormat.shared.host(url: url) }
}
