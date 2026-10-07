import XCTest
import Shared
@testable import Wishlist

/// Swift ↔ Kotlin interop against the real shared API (Task 1's probe guarantees, now kept by the
/// item detail Presenter): SKIE Flow collection and collector cancellation, suspend calls, close,
/// account switching through the runtime session, and the Swift `PlatformTokenSource` callback.
final class SharedInteropTests: XCTestCase {
    @MainActor
    func testPresenterStateFlowDeliversInitialLoadingAndItemThenCollectorCancels() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let seed = try await SharedTestRuntime.firstSeedItem(runtime)
        let presenter = runtime.itemDetailPresenter()
        defer { presenter.close() }

        let initial = expectation(description: "initial state")
        let loaded = expectation(description: "loaded item")
        var states: [ItemDetailState] = []
        let collector = Task { @MainActor in
            for await state in presenter.state {
                states.append(state)
                if states.count == 1 { initial.fulfill() }
                if state.item != nil { loaded.fulfill() }
            }
        }
        await fulfillment(of: [initial], timeout: 5)
        XCTAssertEqual(states.first, ItemDetailState.companion.Initial)

        presenter.load(id: seed.id)
        await fulfillment(of: [loaded], timeout: 5)
        // Concrete Swift types: the item and its fields, no casts.
        let shown: WishlistItem? = states.last?.item
        XCTAssertEqual(shown?.id, seed.id)
        XCTAssertEqual(shown?.product.name, seed.product.name)
        XCTAssertFalse(states.last?.loading ?? true)
        XCTAssertNil(states.last?.error)

        collector.cancel()
        await collector.value
        let collected = states.count
        // Later states must really be published, each one observably new: a retry of the same item
        // would end in a state equal to the current one, so the wait could pass before it ran.
        // Another account resets to Initial; its retry then ends in NOT_FOUND, which only the
        // processed retry can produce (so no request is still in flight when the test ends).
        try await SharedTestRuntime.switchAccount(runtime, to: .apple)
        await SharedTestRuntime.eventually { presenter.state.value == ItemDetailState.companion.Initial }
        presenter.retry()
        await SharedTestRuntime.eventually {
            presenter.state.value.loading == false && presenter.state.value.error?.kind == .notFound
        }
        XCTAssertEqual(states.count, collected, "a cancelled collector must not receive values")
    }

    @MainActor
    func testSuspendRepositoryCallsReturnConcreteResults() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let seed = try await SharedTestRuntime.firstSeedItem(runtime)

        let found = try await runtime.getItemRepository().get(id: seed.id)
        XCTAssertEqual((found as? ClientResultSuccess<WishlistItem>)?.value?.id, seed.id)

        let missing = try await runtime.getItemRepository().get(id: "00000000-0000-4000-8000-0000000000ff")
        XCTAssertEqual((missing as? ClientResultFailure)?.error.kind, .notFound)
    }

    @MainActor
    func testRetryAndAccountSwitchThroughTheRuntimeSession() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let seed = try await SharedTestRuntime.firstSeedItem(runtime)
        let presenter = runtime.itemDetailPresenter()
        defer { presenter.close() }

        presenter.load(id: seed.id)
        await SharedTestRuntime.eventually { presenter.state.value.item?.id == seed.id }

        // Another account: the previous item and error disappear at once.
        try await SharedTestRuntime.switchAccount(runtime, to: .apple)
        await SharedTestRuntime.eventually { presenter.state.value == ItemDetailState.companion.Initial }

        // That account cannot see the first account's item.
        presenter.retry()
        await SharedTestRuntime.eventually { presenter.state.value.error?.kind == .notFound }
        XCTAssertNil(presenter.state.value.item)

        // Back to the first account (a new generation): cleared again, then retry finds the item.
        try await SharedTestRuntime.switchAccount(runtime, to: .google)
        await SharedTestRuntime.eventually { presenter.state.value == ItemDetailState.companion.Initial }
        presenter.retry()
        await SharedTestRuntime.eventually { presenter.state.value.item?.id == seed.id }
        XCTAssertNil(presenter.state.value.error)
    }

    @MainActor
    func testClosedPresenterIgnoresLaterIntents() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let seed = try await SharedTestRuntime.firstSeedItem(runtime)
        let presenter = runtime.itemDetailPresenter()

        presenter.close()
        presenter.close() // idempotent
        presenter.load(id: seed.id)
        presenter.retry()
        await SharedTestRuntime.stays(for: 0.5) { presenter.state.value == ItemDetailState.companion.Initial }
    }

    // MARK: Swift PlatformTokenSource -> Kotlin callback ABI (public API only)

    /// A REMOTE ITEM-03 runtime with a Swift token source and an unreachable server: the Swift
    /// token reaches Kotlin (the request proceeds to the network and fails there, not as auth).
    @MainActor
    func testSwiftTokenSourceSuccessReachesKotlin() async throws {
        let source = RecordingTokenSource(token: "swift-token", errorCode: nil)
        let runtime = await SharedTestRuntime.readyRemote(tokenSource: source)
        defer { runtime.close() }

        let result = try await runtime.getItemRepository().get(id: SharedTestRuntime.remoteItemId)
        XCTAssertEqual(source.forceRefreshCalls, [false])
        let error = (result as? ClientResultFailure)?.error
        XCTAssertEqual(error?.kind, .network, "token accepted; only the unreachable server fails: \(String(describing: error))")
    }

    @MainActor
    func testSwiftTokenSourceErrorReachesKotlin() async throws {
        let source = RecordingTokenSource(token: nil, errorCode: "TOKEN_FAILED")
        let runtime = await SharedTestRuntime.readyRemote(tokenSource: source)
        defer { runtime.close() }

        let result = try await runtime.getItemRepository().get(id: SharedTestRuntime.remoteItemId)
        XCTAssertEqual(source.forceRefreshCalls, [false])
        let error = (result as? ClientResultFailure)?.error
        XCTAssertEqual(error?.kind, .unauthenticated)
        XCTAssertEqual(error?.code, "TOKEN_FAILED")
    }
}

/// Shared runtime helpers for the interop and owner tests (tests run in the Debug configuration).
enum SharedTestRuntime {
    static let remoteItemId = "00000000-0000-4000-8000-000000000101"

    /// The app's debug bindings (ITEM-01/03 Fake) after the debug bootstrap reported ready.
    @MainActor
    static func readyDebug() async -> SharedRuntime {
        let runtime = SharedRuntimeFactory.shared.create(bindings: AppRuntimeConfig.bindings(), remote: nil)
        await start(runtime)
        _ = try? await runtime.auth().signIn(provider: .google)
        return runtime
    }

    /// ITEM-03 REMOTE against an unreachable local port, with a Swift token source.
    @MainActor
    static func readyRemote(tokenSource: PlatformTokenSource) async -> SharedRuntime {
        var backends = AppRuntimeConfig.allBackends(.unavailable)
        backends[.item01] = .fake
        backends[.item03] = .remote
        let runtime = SharedRuntimeFactory.shared.create(
            bindings: RepositoryBindings(buildMode: .debug, backends: backends),
            remote: RemoteConfig(baseUrl: "http://127.0.0.1:9", tokenSource: tokenSource)
        )
        await start(runtime)
        _ = try? await runtime.auth().signIn(provider: .google)
        return runtime
    }

    @MainActor
    private static func start(_ runtime: SharedRuntime) async {
        runtime.startDebugSession()
        let ready = XCTestExpectation(description: "runtime ready")
        let collector = Task { @MainActor in
            for await value in runtime.ready where value.boolValue {
                ready.fulfill()
                break
            }
        }
        _ = await XCTWaiter.fulfillment(of: [ready], timeout: 10)
        collector.cancel()
    }

    static func firstSeedItem(_ runtime: SharedRuntime) async throws -> WishlistItem {
        let result = try await runtime.catalogRepository().items(categoryId: nil, purposeId: nil)
        let items = (result as? ClientResultSuccess<NSArray>)?.value as? [WishlistItem]
        return try XCTUnwrap(items?.first)
    }

    /// The debug database persists on the simulator, so a saved fake login from an earlier test would be
    /// restored at the next start. Tests that need "signed out at start" clear it first.
    @MainActor
    static func clearSavedLogin() async {
        let runtime = await readyDebug()
        _ = try? await runtime.auth().signOut()
        runtime.close()
    }

    /// Logout then login as `provider` through the runtime's fake auth facade (the same session its Presenters use).
    static func switchAccount(_ runtime: SharedRuntime, to provider: AuthProvider) async throws {
        _ = try await runtime.auth().signOut()
        _ = try await runtime.auth().signIn(provider: provider)
    }

    /// Waits (bounded) until `condition` holds on the main actor.
    @MainActor
    static func eventually(
        timeout: TimeInterval = 5,
        file: StaticString = #filePath,
        line: UInt = #line,
        _ condition: @MainActor () -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            if Date() > deadline {
                XCTFail("condition not met within \(timeout)s", file: file, line: line)
                return
            }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
    }

    /// Checks that `condition` keeps holding for `duration`.
    @MainActor
    static func stays(
        for duration: TimeInterval,
        file: StaticString = #filePath,
        line: UInt = #line,
        _ condition: @MainActor () -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(duration)
        while Date() < deadline {
            if !condition() {
                XCTFail("condition stopped holding", file: file, line: line)
                return
            }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
    }
}

/// Swift implementation of the Kotlin token boundary; Kotlin calls it from a background thread.
private final class RecordingTokenSource: NSObject, PlatformTokenSource {
    private let lock = NSLock()
    private let token: String?
    private let errorCode: String?
    private var calls: [Bool] = []

    init(token: String?, errorCode: String?) {
        self.token = token
        self.errorCode = errorCode
    }

    var forceRefreshCalls: [Bool] {
        lock.lock(); defer { lock.unlock() }
        return calls
    }

    func fetchToken(forceRefresh: Bool, completion: any TokenCallback) -> any TokenRequest {
        lock.lock()
        calls.append(forceRefresh)
        lock.unlock()
        completion.complete(token: token, errorCode: errorCode)
        return NoOpTokenRequest()
    }
}

private final class NoOpTokenRequest: NSObject, TokenRequest {
    func cancel() {}
}
