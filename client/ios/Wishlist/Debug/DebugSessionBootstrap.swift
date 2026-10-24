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
///     xcrun simctl launch --terminate-running-process <udid> app.wishlist.ios -wl.fake.delayItem01 5000
///     xcrun simctl launch --terminate-running-process <udid> app.wishlist.ios -wl.fake.pendingCount 100
///
/// Cold start only: the arguments are read once, when the process assembles its runtime.
///
/// - `-wl.fake.delayItem01 <ms>`: the next Fake ITEM-01 send waits that long.
/// - `-wl.fake.pendingCount <N>`: saves N unbound pending shares once the runtime is ready.
enum DebugLaunchHooks {
    static let delayItem01 = "wl.fake.delayItem01"
    static let pendingCount = "wl.fake.pendingCount"
    /// C5: `-wl.fake.longCategory <category id>` puts 120 items (tall and slow photos) in that category.
    static let longCategory = "wl.fake.longCategory"
    /// C5: `-wl.fake.delayCategoryCreate <ms>` delays the next Fake CAT-03 (a lost answer when the network drops meanwhile).
    static let delayCategoryCreate = "wl.fake.delayCategoryCreate"
    /// C5: `-wl.fake.editCategoryOnSave <name>` — the first edit-sheet save of the session first renames that
    /// category to `<name>` in the Fake ("another device" saved, version + 1), so the save meets
    /// CATEGORY_VERSION_CONFLICT and the form shows "다른 곳에서 바뀐 내용을 불러왔어요".
    static let editCategoryOnSave = "wl.fake.editCategoryOnSave"
    @MainActor private static var editOnSaveUsed = false
    private static let log = Logger(subsystem: "app.wishlist.ios", category: "debug")

    /// The edit sheet's `beforeSubmit` for `-wl.fake.editCategoryOnSave` (nil without the argument or once used).
    @MainActor
    static func editOnSave(runtime: SharedRuntime?, customId: String, arguments: [String] = ProcessInfo.processInfo.arguments) -> (() async -> Void)? {
        guard !editOnSaveUsed, let name = string(of: editCategoryOnSave, in: arguments), let controls = runtime?.debugControls() else { return nil }
        return {
            guard !editOnSaveUsed else { return }
            editOnSaveUsed = true
            let result = try? await controls.editCategoryElsewhere(customId: customId, name: name)
            log.info("edit category elsewhere \(customId): \(String(describing: result))")
        }
    }

    static func apply(_ runtime: SharedRuntime, arguments: [String]) {
        let delay = value(of: delayItem01, in: arguments)
        let count = value(of: pendingCount, in: arguments)
        let long = string(of: longCategory, in: arguments)
        let createDelay = value(of: delayCategoryCreate, in: arguments)
        guard delay != nil || count != nil || long != nil || createDelay != nil, let controls = runtime.debugControls() else { return }
        if let createDelay { controls.delayNextCategoryCreate(millis: createDelay) }
        if let long {
            Task {
                let result = try? await controls.seedLongCategory(categoryId: long, count: 120)
                log.info("long category \(long): \(String(describing: result))")
            }
        }
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

    /// `-<key> <text>` from the launch arguments.
    static func string(of key: String, in arguments: [String]) -> String? {
        guard let index = arguments.firstIndex(of: "-\(key)"), arguments.indices.contains(index + 1) else { return nil }
        return arguments[index + 1].trimmingCharacters(in: .whitespaces)
    }

    /// `-<key> <integer>` from the launch arguments.
    static func value(of key: String, in arguments: [String]) -> Int64? {
        guard let index = arguments.firstIndex(of: "-\(key)"), arguments.indices.contains(index + 1) else { return nil }
        return Int64(arguments[index + 1].trimmingCharacters(in: .whitespaces))
    }
}
#endif
