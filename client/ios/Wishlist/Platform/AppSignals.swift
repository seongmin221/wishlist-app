import Foundation
import OSLog
import Shared

/// App-wide signals to the shared `SubmissionCoordinator` (Ruling 1: the platform only signals).
/// - Each scene `.active`: import the share extension's inbox, delete only what the runtime
///   reported deletable, then `refresh(LAUNCH)` the first time and `refresh(FOREGROUND)` after.
///   Inbox passes run one after another (never two readers over the same files at once).
/// - Network unsatisfied → satisfied: `requestFlush(NETWORK_RESTORED)`.
/// Without an app-group container (unsigned builds) the inbox pass is disabled (logged once);
/// the refreshes still run. Under XCTest (the IOS_TEST host app) nothing runs: the host shares the
/// installed debug app's container, and an inbox import or send there would mutate that database.
@MainActor
final class AppSignals {
    /// The process is an XCTest host (`xcodebuild test` sets this for the app it injects into).
    nonisolated static let runningUnderXCTest = ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil

    private let enabled: Bool
    private let submissions: SubmissionCoordinator
    private let reader: ShareInboxReader
    private let network: NetworkSignals
    private var launched = false
    private var pass: Task<Void, Never>?

    private static let log = Logger(subsystem: "app.wishlist.ios", category: "signals")

    init(runtime: SharedRuntime, inboxDirectory: URL? = AppGroup.inboxDirectory(), enabled: Bool = !AppSignals.runningUnderXCTest) {
        self.enabled = enabled
        let submissions = runtime.submissions()
        self.submissions = submissions
        if enabled, inboxDirectory == nil {
            Self.log.error("app group container unavailable (unsigned build?): share inbox import disabled")
        }
        reader = ShareInboxReader(directory: inboxDirectory)
        network = NetworkSignals { submissions.requestFlush(trigger: .networkRestored) }
    }

    func start() {
        guard enabled else { return }
        network.start()
    }

    func sceneBecameActive() {
        guard enabled else { return }
        let trigger: FlushTrigger = launched ? .foreground : .launch
        launched = true
        let previous = pass
        let reader = reader
        let submissions = submissions
        pass = Task {
            await previous?.value
            let outcome = await reader.importPending { try await submissions.importInbox(records: $0) }
            if outcome != .empty {
                Self.log.info("inbox: deleted \(outcome.deleted.count), kept \(outcome.kept.count), corrupt \(outcome.corrupt.count), newer \(outcome.unknownVersion.count)")
            }
            // Not awaited by the next pass: a slow send must not hold the next inbox import back.
            Task { try? await submissions.refresh(trigger: trigger) }
        }
    }
}
