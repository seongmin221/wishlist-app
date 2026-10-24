import XCTest
import Shared
import SwiftUI
@testable import Wishlist

final class PurposePresentationTests: XCTestCase {
    func testExpandedCardSurvivesRefreshAndFallsBackWhenRemoved() {
        XCTAssertEqual(PurposePresentation.expanded(previous: "b", ids: ["a", "b"]), "b")
        XCTAssertEqual(PurposePresentation.expanded(previous: "b", ids: ["c", "a"]), "c")
        XCTAssertNil(PurposePresentation.expanded(previous: "b", ids: []))
    }

    func testHeaderChangesContinuouslyAndClamps() {
        XCTAssertEqual(PurposePresentation.iconSize(progress: 0), 44)
        XCTAssertEqual(PurposePresentation.iconSize(progress: 0.5), 40)
        XCTAssertEqual(PurposePresentation.iconSize(progress: 1), 36)
        XCTAssertEqual(PurposePresentation.nameSize(progress: 0.5), 24)
        XCTAssertEqual(PurposePresentation.detailOpacity(progress: 0.25), 0.5)
        XCTAssertEqual(PurposePresentation.detailOpacity(progress: 1), 0)
    }

    func testDismissBlocksSubmittingAndConfirmsDirty() {
        XCTAssertEqual(PurposePresentation.dismiss(submitting: true, dirty: true), .block)
        XCTAssertEqual(PurposePresentation.dismiss(submitting: false, dirty: true), .confirm)
        XCTAssertEqual(PurposePresentation.dismiss(submitting: false, dirty: false), .allow)
    }

    func testPurposeRouteKeepsArbitraryIDAndIsAccountScoped() {
        let destination = AppDestination.purpose("id /?한글%")
        XCTAssertTrue(destination.accountScoped)
        XCTAssertTrue(destination.showsTabBar)
        XCTAssertEqual(destination.route.destination.base as? AppDestination, destination)
    }
    func testBackGuardProtectsButtonAndEdgeAndAccountResetBypassesIt() {
        let nav = WLNavigator(initialTab: .purpose)
        XCTAssertTrue(nav.push(AppDestination.purpose("one").route, sourceKey: "test"))
        nav.finishTransition()
        let id = nav.entries(.purpose).last!.id
        var attempts = 0
        nav.setBackGuard(id) { attempts += 1; return false }
        XCTAssertFalse(nav.pop())
        XCTAssertFalse(nav.beginBackGesture())
        XCTAssertEqual(attempts, 2)
        XCTAssertEqual(nav.dropAccountScoped(), [id])
        XCTAssertFalse(nav.canPop)
        XCTAssertEqual(attempts, 2)
    }

    @MainActor
    func testPurposePresentersCrossSKIEAndRefreshAndCreate() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let home = PurposeHomeOwner(presenter: runtime.purposeHomePresenter())
        defer { home.close() }
        home.loadOnce()
        await SharedTestRuntime.eventually { home.state.loaded && !home.state.loading }
        let purpose = try XCTUnwrap(home.state.purposes.first)
        await home.refreshAndWait()
        await SharedTestRuntime.eventually { !home.state.loading }
        XCTAssertFalse(home.state.loading)
        let detail = PurposeDetailOwner(presenter: runtime.purposeDetailPresenter())
        defer { detail.close() }
        detail.loadOnce(purpose.id)
        await SharedTestRuntime.eventually { detail.state.detail != nil && !detail.state.itemsLoading }
        XCTAssertEqual(detail.state.detail?.id, purpose.id)
        await detail.refreshAndWait()
        await SharedTestRuntime.eventually { !detail.state.itemsLoading }
        XCTAssertFalse(detail.state.itemsLoading)

        let form = PurposeFormOwner(presenter: runtime.purposeFormPresenter())
        defer { form.close() }
        form.presenter.startCreate()
        await SharedTestRuntime.eventually { form.state.phase == .ready }
        form.presenter.changeName(value: "출퇴근 장비")
        form.presenter.changeDescription(value: "🙂 설명")
        form.presenter.chooseColor(key: "mint")
        form.presenter.chooseIcon(key: "music")
        await SharedTestRuntime.eventually { form.state.canSubmit && form.state.iconKey == "music" }
        form.presenter.submit()
        await SharedTestRuntime.eventually { form.state.result != nil }
        XCTAssertEqual(form.state.result?.purposeDescription, "🙂 설명")
        XCTAssertEqual(form.state.result?.colorKey, "mint")
    }

    @MainActor
    func testRetainedDetailRefreshesOnTabAndItemReturnWithoutReloadingSameTop() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let home = PurposeHomeOwner(presenter: runtime.purposeHomePresenter())
        defer { home.close() }
        home.loadOnce()
        await SharedTestRuntime.eventually { home.state.loaded && !home.state.loading }
        let purpose = try XCTUnwrap(home.state.purposes.first)
        let owner = PurposeDetailOwner(presenter: runtime.purposeDetailPresenter())
        defer { owner.close() }
        let nav = WLNavigator(initialTab: .purpose)
        XCTAssertTrue(nav.push(AppDestination.purpose(purpose.id).route, sourceKey: "test"))
        nav.finishTransition()
        let entryID = nav.entries(.purpose).last!.id
        func observe(_ phase: ScenePhase = .active) {
            owner.navigationChanged(id: purpose.id, entryID: entryID, navigator: nav, phase: phase)
        }
        observe()
        await SharedTestRuntime.eventually { owner.state.detail != nil && !owner.state.itemsLoading }
        let original = try XCTUnwrap(owner.state.detail)
        let form = PurposeFormOwner(presenter: runtime.purposeFormPresenter())
        owner.form = form
        form.presenter.startEdit(detail: original)
        await SharedTestRuntime.eventually { form.state.phase == .ready }
        form.presenter.changeName(value: "보존할 초안")
        await SharedTestRuntime.eventually { form.state.name == "보존할 초안" }

        func update(_ name: String, version: Int32) async throws {
            let result = try await runtime.purposeRepository().update(id: purpose.id, expectedVersion: version,
                patch: PurposePatch(name: name, description: DescriptionChangeKeep.shared, colorKey: nil, iconKey: nil))
            XCTAssertNotNil(result as? ClientResultSuccess<PurposeDetail>)
        }
        try await update("탭 밖에서 변경", version: original.version)
        observe() // Remaining at the same top must not read again.
        await SharedTestRuntime.stays(for: 0.15) { owner.state.detail?.name == original.name && !owner.state.loading }
        XCTAssertTrue(nav.selectTab(.home)); nav.finishTransition()
        observe()
        await SharedTestRuntime.stays(for: 0.15) { owner.state.detail?.name == original.name }
        XCTAssertTrue(nav.selectTab(.purpose)); nav.finishTransition()
        observe()
        await SharedTestRuntime.eventually { owner.state.detail?.name == "탭 밖에서 변경" && !owner.state.itemsLoading }
        XCTAssertTrue(owner.form === form)
        XCTAssertEqual(form.state.name, "보존할 초안")
        XCTAssertTrue(form.state.dirty)

        XCTAssertTrue(nav.push(AppDestination.item("candidate").route, sourceKey: "test")); nav.finishTransition()
        observe()
        try await update("아이템에서 돌아온 제목", version: original.version + 1)
        observe() // Covered entries must not read.
        await SharedTestRuntime.stays(for: 0.15) { owner.state.detail?.name == "탭 밖에서 변경" }
        XCTAssertTrue(nav.pop()); nav.finishTransition()
        observe()
        await SharedTestRuntime.eventually { owner.state.detail?.name == "아이템에서 돌아온 제목" && !owner.state.itemsLoading }
        XCTAssertTrue(owner.form === form)
        XCTAssertEqual(form.state.name, "보존할 초안")

        observe(.background)
        try await update("활성화 뒤 제목", version: original.version + 2)
        observe(.inactive)
        await SharedTestRuntime.stays(for: 0.15) { owner.state.detail?.name == "아이템에서 돌아온 제목" }
        observe()
        await SharedTestRuntime.eventually { owner.state.detail?.name == "활성화 뒤 제목" && !owner.state.itemsLoading }
        XCTAssertTrue(owner.form === form)
        observe()
        await SharedTestRuntime.stays(for: 0.15) { !owner.state.loading && !owner.state.itemsLoading }
    }

    @MainActor
    func testCancellingConflictRecoveredEditUpdatesUnderlyingDetail() async throws {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let home = PurposeHomeOwner(presenter: runtime.purposeHomePresenter())
        defer { home.close() }
        home.loadOnce()
        await SharedTestRuntime.eventually { home.state.loaded && !home.state.loading }
        let purpose = try XCTUnwrap(home.state.purposes.first)
        let owner = PurposeDetailOwner(presenter: runtime.purposeDetailPresenter())
        defer { owner.close() }
        owner.loadOnce(purpose.id)
        await SharedTestRuntime.eventually { owner.state.detail != nil && !owner.state.loading }
        let original = try XCTUnwrap(owner.state.detail)
        let form = PurposeFormOwner(presenter: runtime.purposeFormPresenter())
        owner.form = form
        form.presenter.startEdit(detail: original)
        await SharedTestRuntime.eventually { form.state.phase == .ready }
        let result = try await runtime.purposeRepository().update(id: purpose.id, expectedVersion: original.version,
            patch: PurposePatch(name: "서버에서 복구한 제목", description: DescriptionChangeKeep.shared, colorKey: nil, iconKey: nil))
        XCTAssertNotNil(result as? ClientResultSuccess<PurposeDetail>)
        form.presenter.changeName(value: "내 오래된 초안")
        await SharedTestRuntime.eventually { form.state.dirty }
        form.presenter.submit()
        await SharedTestRuntime.eventually { form.state.message == .conflictLoaded }
        form.presenter.changeName(value: "복구 뒤 다시 편집")
        await SharedTestRuntime.eventually { form.state.name == "복구 뒤 다시 편집" }
        XCTAssertEqual(form.state.recoveredDetail?.name, "서버에서 복구한 제목")
        XCTAssertTrue(owner.finishEditing())
        await SharedTestRuntime.eventually { owner.state.detail?.name == "서버에서 복구한 제목" }
        XCTAssertEqual(owner.state.detail?.version, original.version + 1)
        XCTAssertNil(owner.form)
    }

    @MainActor
    func testDetailEntryClosesItsRetainedForm() async {
        let runtime = await SharedTestRuntime.readyDebug()
        defer { runtime.close() }
        let detail = PurposeDetailOwner(presenter: runtime.purposeDetailPresenter())
        let form = PurposeFormOwner(presenter: runtime.purposeFormPresenter())
        form.presenter.startCreate()
        await SharedTestRuntime.eventually { form.state.phase == .ready }
        detail.form = form
        detail.close()
        XCTAssertNil(detail.form)
        form.presenter.changeName(value: "closed form must ignore")
        try? await Task.sleep(for: .milliseconds(50))
        XCTAssertEqual(form.state.name, "")
    }

    func testPurposeCatalogIncludesEnglishAndKorean() throws {
        for language in ["ko", "en"] {
            let bundle = try XCTUnwrap(HomeRowText.bundle(for: language))
            for key in ["purpose.title", "purpose.form.title", "purpose.activity", "purpose.active.count", "purpose.form.note", "purpose.candidates.empty.title", "purpose.form.limit"] {
                XCTAssertNotEqual(bundle.localizedString(forKey: key, value: nil, table: nil), key)
            }
        }
    }

    func testUnnamedCandidatesUseAnalysisStateAndNamedCandidatesPreserveRawName() throws {
        let ko = try XCTUnwrap(HomeRowText.bundle(for: "ko"))
        XCTAssertEqual(PurposeCandidateText.title(name: nil, processing: true, bundle: ko), "분류 중")
        XCTAssertEqual(PurposeCandidateText.title(name: " \n", processing: false, bundle: ko), "정보 보완 필요")
        XCTAssertEqual(PurposeCandidateText.title(name: "  헤드폰  ", processing: true, bundle: ko), "  헤드폰  ")
    }

    func testPurposeIconsUseHandoffPaths() {
        XCTAssertEqual(PurposeStyle.icon("tent").path.boundingRect, CGRect(x: 3, y: 5, width: 18, height: 15))
        XCTAssertEqual(PurposeStyle.icon("book").path.boundingRect, CGRect(x: 5, y: 4, width: 13, height: 16))
        XCTAssertEqual(PurposeStyle.icon("music").path.boundingRect, CGRect(x: 5, y: 4, width: 14, height: 16))
        XCTAssertEqual(PurposeStyle.icon("unknown"), .sorting)
    }

}
