import XCTest
@testable import Wishlist

/// Android `WLNavigatorTest`와 같은 12개 사례 + iOS 쪽 화면 유지(`exiting`)·전환 식별 사례.
final class WLNavigatorTests: XCTestCase {
    private let detail = WLRoute(destination: "p1", pushStyle: .photo)

    func testStartsOnHomeWithOneRootPerTab() {
        let nav = WLNavigator()
        XCTAssertEqual(nav.currentTab, .home)
        for tab in WLTab.allCases { XCTAssertEqual(nav.stack(tab), [.tabRoot(tab)]) }
        XCTAssertFalse(nav.isTransitioning)
    }

    func testTabStacksAreIndependent() {
        let nav = WLNavigator()
        XCTAssertTrue(nav.push(detail, sourceKey: "home/p1"))
        nav.finishTransition()

        XCTAssertTrue(nav.selectTab(.category))
        nav.finishTransition()
        XCTAssertEqual(nav.currentTab, .category)
        XCTAssertEqual(nav.stack(.category), [.tabRoot(.category)])

        XCTAssertTrue(nav.selectTab(.home))
        nav.finishTransition()
        XCTAssertEqual(nav.stack(.home), [.tabRoot(.home), detail])
    }

    func testPopAtRootReturnsFalseAndStartsNothing() {
        let nav = WLNavigator()
        XCTAssertFalse(nav.pop())
        XCTAssertFalse(nav.isTransitioning)
        XCTAssertEqual(nav.stack(.home), [.tabRoot(.home)])
    }

    func testPopRemovesTopAndStartsTransition() {
        let nav = WLNavigator()
        nav.push(detail, sourceKey: "home/p1")
        nav.finishTransition()

        XCTAssertTrue(nav.pop())
        XCTAssertTrue(nav.isTransitioning)
        XCTAssertEqual(nav.stack(.home), [.tabRoot(.home)])
    }

    func testPushKeepsSourceKeyForSharedTransition() {
        let nav = WLNavigator()
        nav.push(detail, sourceKey: "home/p1")
        XCTAssertEqual(nav.entries(.home).last?.sourceKey, "home/p1")
    }

    func testReselectingCurrentTabRequestsScrollToTopWithoutTransition() {
        let nav = WLNavigator()
        XCTAssertNil(nav.scrollToTopRequest)

        XCTAssertTrue(nav.selectTab(.home))

        XCTAssertEqual(nav.scrollToTopRequest, .home)
        XCTAssertFalse(nav.isTransitioning)
        XCTAssertEqual(nav.currentTab, .home)

        nav.consumeScrollToTop(.home)
        XCTAssertNil(nav.scrollToTopRequest)
    }

    func testPushSelectTabAndPopAreIgnoredWhileTransitioning() {
        let nav = WLNavigator()
        XCTAssertTrue(nav.push(detail, sourceKey: "home/p1"))
        XCTAssertTrue(nav.isTransitioning)

        XCTAssertFalse(nav.push(.init(destination: "p2", pushStyle: .slide), sourceKey: "home/p2"))
        XCTAssertFalse(nav.selectTab(.purpose))
        XCTAssertFalse(nav.pop())

        XCTAssertEqual(nav.currentTab, .home)
        XCTAssertEqual(nav.stack(.home), [.tabRoot(.home), detail])
    }

    func testTabSwitchBlocksInputUntilFinished() {
        let nav = WLNavigator()
        XCTAssertTrue(nav.selectTab(.category))
        XCTAssertTrue(nav.isTransitioning)
        XCTAssertFalse(nav.selectTab(.purpose))
        XCTAssertFalse(nav.push(detail, sourceKey: "category/p1"))

        nav.finishTransition()
        XCTAssertFalse(nav.isTransitioning)
        XCTAssertTrue(nav.push(detail, sourceKey: "category/p1"))
        XCTAssertEqual(nav.stack(.category), [.tabRoot(.category), detail])
    }

    func testBackGestureIsRefusedAtRootAndWhileTransitioning() {
        let nav = WLNavigator()
        XCTAssertFalse(nav.beginBackGesture())

        nav.push(detail, sourceKey: "home/p1")
        XCTAssertFalse(nav.beginBackGesture())
    }

    func testCancelledBackGestureKeepsStackAndUnblocks() {
        let nav = WLNavigator()
        nav.push(detail, sourceKey: "home/p1")
        nav.finishTransition()

        XCTAssertTrue(nav.beginBackGesture())
        XCTAssertTrue(nav.isTransitioning)
        XCTAssertFalse(nav.selectTab(.category))

        nav.cancelBackGesture()
        XCTAssertFalse(nav.isTransitioning)
        XCTAssertEqual(nav.stack(.home), [.tabRoot(.home), detail])
    }

    func testCommittedBackGesturePopsAndStaysTransitioningUntilFinished() {
        let nav = WLNavigator()
        nav.push(detail, sourceKey: "home/p1")
        nav.finishTransition()

        XCTAssertTrue(nav.beginBackGesture())
        nav.commitBackGesture()
        XCTAssertEqual(nav.stack(.home), [.tabRoot(.home)])
        XCTAssertTrue(nav.isTransitioning)
        XCTAssertFalse(nav.push(detail, sourceKey: "home/p1"))

        nav.finishTransition()
        XCTAssertFalse(nav.isTransitioning)
    }

    func testRoutesDeclareTabBarVisibility() {
        XCTAssertTrue(WLRoute.tabRoot(.home).showsTabBar)
        XCTAssertFalse(detail.showsTabBar)
    }

    // MARK: iOS 추가 사례

    /// pop된 칸은 뒤로 모션이 끝날 때까지 `exiting`으로 남아 화면이 계속 그린다(같은 갱신에서 사라지면 한 프레임 비어 보인다).
    func testPoppedEntryStaysExitingUntilTransitionFinishes() {
        let nav = WLNavigator()
        nav.push(detail, sourceKey: "home/p1")
        nav.finishTransition()
        let top = nav.entries(.home).last

        XCTAssertTrue(nav.pop())
        XCTAssertEqual(nav.exiting?.tab, .home)
        XCTAssertEqual(nav.exiting?.entry, top)

        nav.finishTransition()
        XCTAssertNil(nav.exiting)
    }

    func testCommittedBackGestureAlsoLeavesExitingEntry() {
        let nav = WLNavigator()
        nav.push(detail, sourceKey: "home/p1")
        nav.finishTransition()
        let top = nav.entries(.home).last

        XCTAssertTrue(nav.beginBackGesture())
        XCTAssertNil(nav.exiting)
        nav.commitBackGesture()
        XCTAssertEqual(nav.exiting?.entry, top)
    }

    /// 화면이 자기 전환만 끝내도록(Android `activeTransition === mine`) 전환마다 다른 값을 준다.
    func testEachTransitionIsDistinct() {
        let nav = WLNavigator()
        nav.push(detail, sourceKey: "home/p1")
        let first = nav.activeTransition
        nav.finishTransition()
        nav.pop()
        nav.finishTransition()
        nav.push(detail, sourceKey: "home/p1")
        XCTAssertNotNil(first)
        XCTAssertNotEqual(nav.activeTransition, first)
        XCTAssertEqual(nav.activeTransition?.kind, .push)
    }

    func testDepthTwoPushAndPopKeepLowerEntries() {
        let nav = WLNavigator()
        let list = WLRoute.init(destination: "chip:headphone", pushStyle: .slide)
        nav.push(list, sourceKey: "home/chip/headphone")
        nav.finishTransition()
        nav.push(detail, sourceKey: "home/chiplist/headphone/p1")
        nav.finishTransition()
        XCTAssertEqual(nav.stack(.home), [.tabRoot(.home), list, detail])

        XCTAssertTrue(nav.beginBackGesture())
        nav.commitBackGesture()
        nav.finishTransition()
        XCTAssertEqual(nav.stack(.home), [.tabRoot(.home), list])
        XCTAssertEqual(nav.entries(.home).last?.sourceKey, "home/chip/headphone")
    }

    // MARK: C4 계정 범위 화면 정리 (Android Task 7과 같은 사례)

    private let settings = AppDestination.settings.route
    private let web = WLRoute(destination: "web", pushStyle: .slide)

    /// push 후 전환을 끝낸다.
    private func open(_ nav: WLNavigator, _ route: WLRoute, _ key: String = "k") {
        XCTAssertTrue(nav.push(route, sourceKey: key))
        nav.finishTransition()
    }

    func testLeavingAnAccountDropsAccountScopedRoutesInEveryTab() {
        let nav = WLNavigator()
        open(nav, AppDestination.item("i1").route)
        XCTAssertTrue(nav.selectTab(.category))
        nav.finishTransition()
        open(nav, AppDestination.local("l1").route)
        let homeDetail = nav.entries(.home)[1].id
        let categoryDetail = nav.entries(.category)[1].id

        let dropped = nav.dropAccountScoped()

        XCTAssertEqual(Set(dropped), [homeDetail, categoryDetail])
        XCTAssertEqual(nav.stack(.home), [.tabRoot(.home)])
        XCTAssertEqual(nav.stack(.category), [.tabRoot(.category)])
        XCTAssertEqual(nav.stack(.purpose), [.tabRoot(.purpose)])
        XCTAssertFalse(nav.isTransitioning)
        XCTAssertNil(nav.exiting)
    }

    /// 로그인(null → 계정)은 떠남이 아니다. 로그아웃·다른 계정만 떠남이다.
    func testSigningInKeepsAccountScopedRoutes() {
        XCTAssertFalse(ContentView.shouldDropAccountScoped(previous: nil, next: "a"))
        XCTAssertFalse(ContentView.shouldDropAccountScoped(previous: nil, next: nil))
        XCTAssertFalse(ContentView.shouldDropAccountScoped(previous: "a", next: "a"))
        XCTAssertTrue(ContentView.shouldDropAccountScoped(previous: "a", next: nil))
        XCTAssertTrue(ContentView.shouldDropAccountScoped(previous: "a", next: "b"))
    }

    func testNonScopedRoutesBelowStay() {
        let nav = WLNavigator()
        open(nav, settings)
        open(nav, AppDestination.item("i1").route)
        open(nav, web)
        let ids = nav.entries(.home).map(\.id)

        XCTAssertEqual(nav.dropAccountScoped(), [ids[2], ids[3]])
        XCTAssertEqual(nav.stack(.home), [.tabRoot(.home), settings])
        XCTAssertEqual(nav.dropAccountScoped(), [])
    }

    func testReplaceTopSwapsTheTopAndReportsTheOldId() {
        let nav = WLNavigator()
        XCTAssertFalse(nav.replaceTop(AppDestination.item("i1").route)) // tab root
        open(nav, AppDestination.local("l1").route, "home/row/l1")
        let old = nav.entries(.home)[1]
        _ = nav.drainRemoved()

        let item = AppDestination.item("i1").route
        XCTAssertTrue(nav.replaceTop(item))

        XCTAssertEqual(nav.stack(.home), [.tabRoot(.home), item])
        let top = nav.entries(.home)[1]
        XCTAssertNotEqual(top.id, old.id)
        XCTAssertEqual(top.sourceKey, "home/row/l1")
        XCTAssertEqual(nav.activeTransition?.kind, .replace)
        XCTAssertEqual(nav.exiting?.entry, old)
        // 전환 중에는 거절한다.
        XCTAssertFalse(nav.replaceTop(web))
        nav.finishTransition()
        XCTAssertNil(nav.exiting)
        XCTAssertEqual(nav.drainRemoved(), [old.id])
    }

    func testRemovedIdsCoverPopGestureReplaceAndDrop() {
        let nav = WLNavigator()
        XCTAssertEqual(nav.drainRemoved(), [])

        open(nav, detail)
        let popped = nav.entries(.home)[1].id
        XCTAssertTrue(nav.pop())
        nav.finishTransition()

        open(nav, detail)
        let gestured = nav.entries(.home)[1].id
        XCTAssertTrue(nav.beginBackGesture())
        nav.commitBackGesture()
        nav.finishTransition()

        open(nav, AppDestination.local("l1").route)
        let replaced = nav.entries(.home)[1].id
        XCTAssertTrue(nav.replaceTop(AppDestination.item("i1").route))
        nav.finishTransition()
        let dropped = nav.entries(.home)[1].id
        XCTAssertEqual(nav.dropAccountScoped(), [dropped])

        XCTAssertEqual(nav.drainRemoved(), [popped, gestured, replaced, dropped])
        XCTAssertEqual(nav.drainRemoved(), [])
        XCTAssertFalse(nav.hasRemoved)
    }

    func testItemAndLocalDestinationsAreAccountScopedSlidesWithoutTabBar() {
        for destination in [AppDestination.item("i1"), .local("l1")] {
            let route = destination.route
            XCTAssertTrue(route.accountScoped)
            XCTAssertFalse(route.showsTabBar)
            XCTAssertEqual(route.pushStyle, .slide)
            XCTAssertEqual(route.destination.base as? AppDestination, destination)
        }
        XCTAssertFalse(AppDestination.settings.route.accountScoped)
        XCTAssertFalse(AppDestination.login.route.accountScoped)
        XCTAssertFalse(WLRoute.tabRoot(.home).accountScoped)
    }

    /// 끌어서 뒤로 도중 정리되면 끌기가 끝나 뒤이은 확정·취소는 아무 일도 하지 않는다.
    func testDroppingDuringABackGestureEndsTheGesture() {
        let nav = WLNavigator()
        open(nav, AppDestination.item("i1").route)
        XCTAssertTrue(nav.beginBackGesture())

        XCTAssertEqual(nav.dropAccountScoped().count, 1)
        XCTAssertFalse(nav.isTransitioning)
        nav.commitBackGesture()
        nav.cancelBackGesture()
        XCTAssertFalse(nav.isTransitioning)
        XCTAssertNil(nav.exiting)
        XCTAssertEqual(nav.stack(.home), [.tabRoot(.home)])
    }

    /// 정리된 칸을 그리던 push·replace 전환도 끝난다(모션이 끝나기를 기다리지 않는다). 다른 탭의 전환은 그대로다.
    func testDroppingEndsAPushOrReplaceOfADroppedEntry() {
        let nav = WLNavigator()
        XCTAssertTrue(nav.push(AppDestination.item("i1").route, sourceKey: "k"))
        _ = nav.dropAccountScoped()
        XCTAssertFalse(nav.isTransitioning)

        open(nav, AppDestination.local("l1").route)
        XCTAssertTrue(nav.replaceTop(AppDestination.item("i1").route))
        _ = nav.dropAccountScoped()
        XCTAssertFalse(nav.isTransitioning)
        XCTAssertNil(nav.exiting)

        open(nav, AppDestination.item("i2").route)
        XCTAssertTrue(nav.selectTab(.category))
        XCTAssertEqual(nav.dropAccountScoped().count, 1)
        XCTAssertEqual(nav.activeTransition?.kind, .tab(from: .home))
    }

    /// A `MovedTo` that arrives while the local screen still slides in: the change waits for the transition, then applies.
    @MainActor
    func testWhenSettledRetriesAfterTheTransition() async throws {
        let nav = WLNavigator()
        XCTAssertTrue(nav.push(AppDestination.local("l1").route, sourceKey: "home/row/l1"))
        let localId = try XCTUnwrap(nav.entries(.home).last?.id)
        let item = AppDestination.item("i1").route
        let settled = Task { @MainActor in await nav.whenSettled(entryID: localId) { nav.replaceTop(item) } }
        try await Task.sleep(nanoseconds: 150_000_000)
        XCTAssertEqual(nav.stack(.home).last, AppDestination.local("l1").route) // refused mid-push: still waiting
        nav.finishTransition()
        await settled.value
        XCTAssertEqual(nav.stack(.home), [.tabRoot(.home), item])
    }

    /// The entry is no longer the top (the user left, or another screen came over it): nothing happens.
    @MainActor
    func testWhenSettledGivesUpWhenTheEntryIsNotTheTop() async throws {
        let nav = WLNavigator()
        XCTAssertTrue(nav.push(AppDestination.item("i1").route, sourceKey: "a"))
        nav.finishTransition()
        let itemId = try XCTUnwrap(nav.entries(.home).last?.id)
        XCTAssertTrue(nav.push(AppDestination.settings.route, sourceKey: "b"))
        let settled = Task { @MainActor in await nav.whenSettled(entryID: itemId) { nav.pop() } }
        nav.finishTransition()
        await settled.value
        XCTAssertEqual(nav.stack(.home), [.tabRoot(.home), AppDestination.item("i1").route, AppDestination.settings.route])
    }
}
