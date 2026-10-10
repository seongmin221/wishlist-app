import Foundation

/// Keeps a page from flooding FWebViewExternal (spec §4, D16; Android `ExternalPromptGate`). A request without a
/// user tap is asked about at most once at a time; once the user cancels, further non-gesture requests are dropped
/// until the main frame starts a different page. Taps always launch (D16). Main thread only; lives in `WebViewModel`.
final class ExternalPromptGate {
    enum Action: Equatable { case launch, prompt, drop }

    private var prompting = false

    /// The main-frame URL the user cancelled on; non-nil while non-gesture requests are silenced.
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
        if !confirmed { silencedOn = currentURL }
    }

    /// `showDialog` refused (another overlay was opening, or no screen): nothing was asked, nothing is silenced.
    func onPromptNotShown() {
        prompting = false
    }

    /// The main frame started a navigation; reloading the page that was cancelled on does not lift the silence.
    func onMainFrameNavigation(_ url: String) {
        if silencedOn != nil, url != silencedOn { silencedOn = nil }
    }
}
