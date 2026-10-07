import Shared
import SwiftUI

@main
struct WishlistApp: App {
    /// The process's single shared runtime (isolated graph, one auth session). Debug builds start
    /// the demo session; release builds are ready right after assembly with every API unavailable.
    private let runtime: SharedRuntime

    init() {
        let runtime = SharedRuntimeFactory.shared.create(bindings: AppRuntimeConfig.bindings(), remote: nil)
        #if DEBUG
        DebugSessionBootstrap.start(runtime)
        #endif
        self.runtime = runtime
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
