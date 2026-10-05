import XCTest
@testable import Wishlist

/// Android `WLNavigatorTest`와 같은 12개 사례 + iOS 쪽 화면 유지(`exiting`)·전환 식별 사례.
final class WLNavigatorTests: XCTestCase {
    private let detail = WLRoute.demoDetail(id: "p1", hasPhoto: true)

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

        XCTAssertFalse(nav.push(.demoDetail(id: "p2", hasPhoto: false), sourceKey: "home/p2"))
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
        let list = WLRoute.demoDetail(id: "chip:headphone", hasPhoto: false)
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
}
