import XCTest
import Shared

final class SharedInteropTests: XCTestCase {
    @MainActor
    func testFlowPublishesInitialAndIncrementedValueAndCollectionCancels() async throws {
        let probe = InteropProbe()
        defer { probe.close() }
        let initial = expectation(description: "initial zero")
        let incremented = expectation(description: "incremented one")
        var values: [Int] = []
        let collector = Task { @MainActor in
            for await value in probe.state {
                values.append(Int(truncating: value))
                if values.count == 1 { initial.fulfill() }
                if values.count == 2 { incremented.fulfill() }
            }
        }
        await fulfillment(of: [initial], timeout: 5)
        let result = try await probe.increment()
        XCTAssertEqual(result, 1)
        await fulfillment(of: [incremented], timeout: 5)
        collector.cancel()
        await collector.value
        _ = try await probe.increment()
        XCTAssertEqual(values, [0, 1])
    }

    @MainActor
    func testSuspendIncrementReturnsResult() async throws {
        let probe = InteropProbe()
        defer { probe.close() }
        let result = try await probe.increment()
        XCTAssertEqual(result, 1)
    }

    @MainActor
    func testCloseCancelsFutureSuspendWork() async {
        let probe = InteropProbe()
        probe.close()
        do {
            _ = try await probe.increment()
            XCTFail("closed probe must reject new work")
        } catch {
            XCTAssertTrue(error is CancellationError)
        }
    }

    func testSwiftTokenSourceReturnsSuccessThroughKotlin() {
        let probe = InteropProbe()
        defer { probe.close() }
        let source = TestTokenSource(token: "test-token", errorCode: nil)
        let observer = TestTokenObserver()
        let request = probe.invokeToken(source: source, completion: observer)
        XCTAssertEqual(observer.token, "test-token")
        XCTAssertNil(observer.errorCode)
        XCTAssertEqual(observer.completions, 1)
        XCTAssertEqual(source.forceRefresh, false)
        XCTAssertTrue((request as AnyObject) === source.request)
    }

    func testSwiftTokenSourceReturnsErrorThroughKotlin() {
        let probe = InteropProbe()
        defer { probe.close() }
        let observer = TestTokenObserver()
        _ = probe.invokeToken(source: TestTokenSource(token: nil, errorCode: "TOKEN_FAILED"), completion: observer)
        XCTAssertNil(observer.token)
        XCTAssertEqual(observer.errorCode, "TOKEN_FAILED")
        XCTAssertEqual(observer.completions, 1)
    }
}

private final class TestTokenSource: NSObject, PlatformTokenSource {
    let token: String?
    let errorCode: String?
    let request = TestTokenRequest()
    var forceRefresh: Bool?
    init(token: String?, errorCode: String?) { self.token = token; self.errorCode = errorCode }
    func fetchToken(forceRefresh: Bool, completion: any TokenCallback) -> any TokenRequest {
        self.forceRefresh = forceRefresh
        completion.complete(token: token, errorCode: errorCode)
        return request
    }
}
private final class TestTokenRequest: NSObject, TokenRequest { func cancel() {} }
private final class TestTokenObserver: NSObject, TokenCallback {
    var token: String?
    var errorCode: String?
    var completions = 0
    func complete(token: String?, errorCode: String?) {
        self.token = token; self.errorCode = errorCode; completions += 1
    }
}
