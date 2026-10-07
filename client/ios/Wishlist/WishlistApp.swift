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
        // C3 Task 0 spike — remove after verification
        let groupContainer = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.app.wishlist")
        print("C3 spike: app group container = \(groupContainer?.path ?? "nil")")
        // End C3 Task 0 spike
        #endif
        self.runtime = runtime
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
