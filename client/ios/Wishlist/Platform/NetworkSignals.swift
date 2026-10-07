import Foundation
import Network

/// Network restore signal (C3: the platform only signals, the shared coordinator decides).
/// `onRestored` fires on each unsatisfied → satisfied transition of the default path. The first
/// update is the baseline, so starting online does not count as a restore. Updates arrive on a
/// private queue; `onRestored` must be thread-safe (`requestFlush` is).
final class NetworkSignals: @unchecked Sendable {
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "app.wishlist.network-signals")
    private let onRestored: @Sendable () -> Void
    private var satisfied: Bool? // touched only on `queue`

    init(onRestored: @escaping @Sendable () -> Void) {
        self.onRestored = onRestored
    }

    /// Starts once for the process lifetime (the app owns it).
    func start() {
        monitor.pathUpdateHandler = { [weak self] path in self?.update(path.status == .satisfied) }
        monitor.start(queue: queue)
    }

    /// The transition rule, separate from NWPathMonitor (called on `queue`).
    func update(_ now: Bool) {
        let restored = satisfied == false && now
        satisfied = now
        if restored { onRestored() }
    }

    deinit {
        monitor.cancel()
    }
}
