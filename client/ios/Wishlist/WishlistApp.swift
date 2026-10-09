import Shared
import SwiftUI

@main
struct WishlistApp: App {
    /// The process's single shared runtime (isolated graph, one auth session). Debug builds start
    /// the demo session; release builds are ready right after assembly with every API unavailable.
    private let runtime: SharedRuntime
    /// The app's one AccountPresenter and the home tab's HomePresenter (the root lives as long as the process).
    @State private var account: AccountPresenterOwner
    @State private var home: HomePresenterOwner
    private let signals: AppSignals
    private let mirror = SessionMirror()

    @Environment(\.scenePhase) private var scenePhase

    init() {
        let runtime = SharedRuntimeFactory.shared.create(bindings: AppRuntimeConfig.bindings(), remote: nil)
        #if DEBUG
        DebugSessionBootstrap.start(runtime)
        #endif
        self.runtime = runtime
        _account = State(initialValue: AccountPresenterOwner(runtime: runtime))
        _home = State(initialValue: HomePresenterOwner(runtime: runtime))
        signals = AppSignals(runtime: runtime)
        signals.start()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(account)
                .environment(home)
                // Every account change (once restored) reaches the share extension's defaults.
                .onChange(of: account.binding, initial: true) { _, binding in mirror.update(binding) }
        }
        // Launch, then every return from the background: inbox import + refresh (Ruling 1).
        .onChange(of: scenePhase, initial: true) { _, phase in signals.scenePhaseChanged(phase) }
    }
}
