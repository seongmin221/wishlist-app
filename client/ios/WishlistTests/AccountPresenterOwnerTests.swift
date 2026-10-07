import Shared
import XCTest
@testable import Wishlist

/// The main-actor owner of the app's AccountPresenter republishes its state and owns its lifetime.
final class AccountPresenterOwnerTests: XCTestCase {
    @MainActor
    func testOwnerCollectsStateAndForwardsIntents() async throws {
        let runtime = await SharedTestRuntime.readyDebug() // signed in with Google
        defer { runtime.close() }
        let owner = AccountPresenterOwner(runtime: runtime)
        defer { owner.close() }

        await SharedTestRuntime.eventually { owner.state.account?.provider == .google && owner.state.restored }
        XCTAssertFalse(owner.state.showFirstRunLogin)
        XCTAssertEqual(owner.binding, .signedIn("fake-google-0001"))

        owner.signOut()
        await SharedTestRuntime.eventually { owner.state.account == nil }
        XCTAssertEqual(owner.binding, .signedOut)

        owner.signIn(.apple)
        await SharedTestRuntime.eventually { owner.state.account?.provider == .apple && owner.state.signingIn == nil }
        XCTAssertEqual(owner.state.account?.email, "apple@example.com")
        XCTAssertEqual(owner.binding, .signedIn("fake-apple-0001"))
    }

    @MainActor
    func testBindingIsUnknownUntilTheLoginIsRestored() {
        let restoring = AccountState(restored: false, account: nil, showFirstRunLogin: false, signingIn: nil, error: nil)
        XCTAssertEqual(SessionBinding(restoring), .unknown)
        let out = AccountState(restored: true, account: nil, showFirstRunLogin: true, signingIn: nil, error: nil)
        XCTAssertEqual(SessionBinding(out), .signedOut)
    }

    @MainActor
    func testCloseCancelsCollectionAndClosesThePresenter() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let presenter = runtime.accountPresenter()
        let owner = AccountPresenterOwner(presenter: presenter)
        await SharedTestRuntime.eventually { owner.state.account != nil }

        owner.close()
        owner.close() // idempotent
        owner.signOut()
        presenter.signOut()
        await SharedTestRuntime.stays(for: 0.5) { runtime.auth().account.value != nil && owner.state.account != nil }

        // The facade still changes; the closed owner no longer follows it.
        _ = try await runtime.auth().signOut()
        await SharedTestRuntime.stays(for: 0.3) { owner.state.account != nil }
    }

    @MainActor
    func testDeinitEndsCollectionAndClosesThePresenter() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let presenter = runtime.accountPresenter()
        weak var released: AccountPresenterOwner?
        do {
            let owner = AccountPresenterOwner(presenter: presenter)
            released = owner
            await SharedTestRuntime.eventually { owner.state.account != nil }
        }
        await SharedTestRuntime.eventually { released == nil }

        presenter.signOut()
        await SharedTestRuntime.stays(for: 0.5) { runtime.auth().account.value != nil }
    }
}

/// The account mirror the share extension reads (`wl.session.accountBinding` in the app group defaults).
final class SessionMirrorTests: XCTestCase {
    func testMirrorWritesTheAccountAndRemovesItOnSignOut() throws {
        let suite = "wishlist.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let mirror = SessionMirror(defaults: defaults)

        mirror.update(.unknown)
        XCTAssertNil(defaults.string(forKey: AppGroup.accountBindingKey))
        mirror.update(.signedIn("fake-google-0001"))
        XCTAssertEqual(defaults.string(forKey: AppGroup.accountBindingKey), "fake-google-0001")
        mirror.update(.unknown) // restoring again never clears a known binding
        XCTAssertEqual(defaults.string(forKey: AppGroup.accountBindingKey), "fake-google-0001")
        mirror.update(.signedOut)
        XCTAssertNil(defaults.object(forKey: AppGroup.accountBindingKey))
    }
}
