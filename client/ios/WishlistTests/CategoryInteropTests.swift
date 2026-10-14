import XCTest
import Shared
@testable import Wishlist

/// C5 category Presenters seen from Swift: SKIE state flows, plain-value intents, the suspend
/// `refreshNow`, close, and `ClientError.fields` as a Swift `Set<String>`.
final class CategoryInteropTests: XCTestCase {
    @MainActor
    func testHomeAndListPresentersLoadTheSeededFake() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }

        let home = runtime.categoryHomePresenter()
        defer { home.close() }
        home.load()
        await SharedTestRuntime.eventually { home.state.value.loaded && !home.state.value.loading }
        XCTAssertEqual(home.state.value.totalCount, 69)
        XCTAssertEqual(home.state.value.groups.map(\.id), ["G001", "G002", "G003", "G004", "G005", "G006", "G007", "G011"])
        try await home.refreshNow()
        XCTAssertFalse(home.state.value.loading)

        let list = runtime.categoryListPresenter()
        defer { list.close() }
        list.load(hint: CategoryListHint(categoryId: "C026", name: "헤드폰", parentId: "G003", parentName: "디지털·IT", kind: .`public`))
        await SharedTestRuntime.eventually { !list.state.value.loading && !list.state.value.items.isEmpty }
        XCTAssertEqual(list.state.value.items.count, 8)
        XCTAssertEqual(list.state.value.header?.count?.intValue, 8)
    }

    @MainActor
    func testFormFieldErrorsCrossAsASwiftSet() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let error = ClientError(kind: .validation, code: "INVALID_CATEGORY_INPUT", requestId: nil, currentVersion: nil,
                                retryAfterSeconds: nil, fields: ["name", "examples"])
        let fields: Set<String> = error.fields
        XCTAssertEqual(fields, ["name", "examples"])

        let form = runtime.categoryFormPresenter()
        defer { form.close() }
        form.startCreate(parentId: "G003", source: .tab, select: nil)
        await SharedTestRuntime.eventually { form.state.value.phase == .ready }
        XCTAssertEqual(form.state.value.parents.count, 11)
        form.setName(text: "책상 장비")
        await SharedTestRuntime.eventually { form.state.value.canSubmit }
        form.submit()
        await SharedTestRuntime.eventually { form.state.value.result != nil }
        XCTAssertTrue(form.state.value.result is FormResultCreated)
    }
}
