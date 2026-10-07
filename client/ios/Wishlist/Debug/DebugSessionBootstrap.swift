#if DEBUG
import Foundation
import OSLog
import Shared

/// Debug-only start of the demo session. The shared runtime owns the order
/// (starts signed out -> saved fake account restored -> that account seeded -> ready = true); requests made before
/// `ready` is true return UNAVAILABLE. Release builds never compile or call this.
enum DebugSessionBootstrap {
    static func start(_ runtime: SharedRuntime, arguments: [String] = ProcessInfo.processInfo.arguments) {
        runtime.startDebugSession()
        DebugLaunchHooks.apply(runtime, arguments: arguments)
    }
}

/// Debug-only demo hooks read from launch arguments, for on-device checks without a debug menu
/// (C3 Task 7):
///
///     xcrun simctl launch <udid> app.wishlist.ios -wl.fake.delayItem01 5000
///     xcrun simctl launch <udid> app.wishlist.ios -wl.fake.pendingCount 100
///
/// - `-wl.fake.delayItem01 <ms>`: the next Fake ITEM-01 send waits that long.
/// - `-wl.fake.pendingCount <N>`: saves N unbound pending shares once the runtime is ready.
enum DebugLaunchHooks {
    static let delayItem01 = "wl.fake.delayItem01"
    static let pendingCount = "wl.fake.pendingCount"
    private static let log = Logger(subsystem: "app.wishlist.ios", category: "debug")

    static func apply(_ runtime: SharedRuntime, arguments: [String]) {
        let delay = value(of: delayItem01, in: arguments)
        let count = value(of: pendingCount, in: arguments)
        guard delay != nil || count != nil, let controls = runtime.debugControls() else { return }
        if let delay {
            controls.delayNextItem01(millis: delay)
            log.info("next ITEM-01 delayed \(delay)ms")
        }
        if let count {
            Task {
                let result = try? await controls.createUnboundPending(count: Int32(clamping: count))
                if let success = result as? ClientResultSuccess<KotlinInt> {
                    log.info("created \(success.value?.intValue ?? 0) unbound pending rows")
                } else {
                    log.error("pending rows not created: \(String(describing: result))")
                }
            }
        }
    }

    /// `-<key> <integer>` from the launch arguments.
    static func value(of key: String, in arguments: [String]) -> Int64? {
        guard let index = arguments.firstIndex(of: "-\(key)"), arguments.indices.contains(index + 1) else { return nil }
        return Int64(arguments[index + 1].trimmingCharacters(in: .whitespaces))
    }
}
#endif
