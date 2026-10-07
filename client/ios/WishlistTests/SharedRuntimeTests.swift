import XCTest
import Shared
@testable import Wishlist

/// The app's runtime configuration and the debug bootstrap through the real platform factory.
/// Tests run in the Debug configuration only.
final class SharedRuntimeTests: XCTestCase {
    func testDebugConfigurationBindsOnlyC2ApisToFakeAndTheRestUnavailable() {
        let bindings = AppRuntimeConfig.bindings()
        XCTAssertEqual(bindings.buildMode, .debug)
        XCTAssertEqual(bindings.backends.count, 37)
        for api in ApiId.allCases {
            let expected: Backend = (api == .item01 || api == .item03) ? .fake : .unavailable
            XCTAssertEqual(bindings.backends[api], expected, api.name)
        }
    }

    @MainActor
    func testDebugBootstrapStartsSignedOutAndReady() async {
        await SharedTestRuntime.clearSavedLogin()
        let runtime = SharedRuntimeFactory.shared.create(bindings: AppRuntimeConfig.bindings(), remote: nil)
        defer { runtime.close() }
        XCTAssertFalse(runtime.ready.value.boolValue)

        let ready = expectation(description: "ready becomes true")
        var observed: [Bool] = []
        let collector = Task { @MainActor in
            for await value in runtime.ready {
                observed.append(value.boolValue)
                if value.boolValue {
                    ready.fulfill()
                    break
                }
            }
        }
        DebugSessionBootstrap.start(runtime)
        await fulfillment(of: [ready], timeout: 10)
        await collector.value

        XCTAssertEqual(observed.last, true)
        XCTAssertNil(runtime.session.state.value.accountId)
        XCTAssertTrue(runtime.auth().restored.value.boolValue)
        XCTAssertNil(runtime.auth().account.value)
        runtime.close()
        XCTAssertFalse(runtime.ready.value.boolValue)
    }

    func testReleaseBindingsAreReadyRightAfterAssembly() {
        let runtime = SharedRuntimeFactory.shared.create(
            bindings: RepositoryBindings(buildMode: .theRelease, backends: AppRuntimeConfig.allBackends(.unavailable)),
            remote: nil
        )
        XCTAssertTrue(runtime.ready.value.boolValue)
        runtime.close()
        runtime.close()
        XCTAssertFalse(runtime.ready.value.boolValue)
    }
}
