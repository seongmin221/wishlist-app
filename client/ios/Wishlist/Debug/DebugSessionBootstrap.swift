#if DEBUG
import Shared

/// Debug-only start of the demo session. The shared runtime owns the order
/// (starts signed out -> saved fake account restored -> that account seeded -> ready = true); requests made before
/// `ready` is true return UNAVAILABLE. Release builds never compile or call this.
enum DebugSessionBootstrap {
    static func start(_ runtime: SharedRuntime) {
        runtime.startDebugSession()
    }
}
#endif
