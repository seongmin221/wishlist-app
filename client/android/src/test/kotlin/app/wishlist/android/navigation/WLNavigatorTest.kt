package app.wishlist.android.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WLNavigatorTest {
    private val detail = WLRoute.DemoDetail(id = "p1", hasPhoto = true)

    @Test
    fun startsOnHomeWithOneRootPerTab() {
        val nav = WLNavigator()
        assertEquals(WLTab.Home, nav.currentTab.value)
        WLTab.entries.forEach { assertEquals(listOf(WLRoute.TabRoot(it)), nav.stack(it)) }
        assertFalse(nav.isTransitioning)
    }

    @Test
    fun tabStacksAreIndependent() {
        val nav = WLNavigator()
        assertTrue(nav.push(detail, sourceKey = "home/p1"))
        nav.finishTransition()

        assertTrue(nav.selectTab(WLTab.Category))
        nav.finishTransition()
        assertEquals(WLTab.Category, nav.currentTab.value)
        assertEquals(listOf(WLRoute.TabRoot(WLTab.Category)), nav.stack(WLTab.Category))

        assertTrue(nav.selectTab(WLTab.Home))
        nav.finishTransition()
        assertEquals(listOf(WLRoute.TabRoot(WLTab.Home), detail), nav.stack(WLTab.Home))
    }

    @Test
    fun popAtRootReturnsFalseAndStartsNothing() {
        val nav = WLNavigator()
        assertFalse(nav.pop())
        assertFalse(nav.isTransitioning)
        assertEquals(listOf(WLRoute.TabRoot(WLTab.Home)), nav.stack(WLTab.Home))
    }

    @Test
    fun popRemovesTopAndStartsTransition() {
        val nav = WLNavigator()
        nav.push(detail, "home/p1")
        nav.finishTransition()

        assertTrue(nav.pop())
        assertTrue(nav.isTransitioning)
        assertEquals(listOf(WLRoute.TabRoot(WLTab.Home)), nav.stack(WLTab.Home))
    }

    @Test
    fun pushKeepsSourceKeyForSharedTransition() {
        val nav = WLNavigator()
        nav.push(detail, "home/p1")
        assertEquals("home/p1", nav.entries(WLTab.Home).last().sourceKey)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun reselectingCurrentTabRequestsScrollToTopWithoutTransition() = runTest {
        val nav = WLNavigator()
        val events = mutableListOf<WLTab>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            nav.scrollToTopRequests.collect { events += it }
        }

        nav.selectTab(WLTab.Home)

        assertEquals(listOf(WLTab.Home), events)
        assertFalse(nav.isTransitioning)
        assertEquals(WLTab.Home, nav.currentTab.value)
    }

    @Test
    fun pushSelectTabAndPopAreIgnoredWhileTransitioning() {
        val nav = WLNavigator()
        assertTrue(nav.push(detail, "home/p1"))
        assertTrue(nav.isTransitioning)

        assertFalse(nav.push(WLRoute.DemoDetail("p2", hasPhoto = false), "home/p2"))
        assertFalse(nav.selectTab(WLTab.Purpose))
        assertFalse(nav.pop())

        assertEquals(WLTab.Home, nav.currentTab.value)
        assertEquals(listOf(WLRoute.TabRoot(WLTab.Home), detail), nav.stack(WLTab.Home))
    }

    @Test
    fun tabSwitchBlocksInputUntilFinished() {
        val nav = WLNavigator()
        assertTrue(nav.selectTab(WLTab.Category))
        assertTrue(nav.isTransitioning)
        assertFalse(nav.selectTab(WLTab.Purpose))
        assertFalse(nav.push(detail, "category/p1"))

        nav.finishTransition()
        assertFalse(nav.isTransitioning)
        assertTrue(nav.push(detail, "category/p1"))
        assertEquals(listOf(WLRoute.TabRoot(WLTab.Category), detail), nav.stack(WLTab.Category))
    }

    @Test
    fun backGestureIsRefusedAtRootAndWhileTransitioning() {
        val nav = WLNavigator()
        assertFalse(nav.beginBackGesture())

        nav.push(detail, "home/p1")
        assertFalse(nav.beginBackGesture())
    }

    @Test
    fun cancelledBackGestureKeepsStackAndUnblocks() {
        val nav = WLNavigator()
        nav.push(detail, "home/p1")
        nav.finishTransition()

        assertTrue(nav.beginBackGesture())
        assertTrue(nav.isTransitioning)
        assertFalse(nav.selectTab(WLTab.Category))

        nav.cancelBackGesture()
        assertFalse(nav.isTransitioning)
        assertEquals(listOf(WLRoute.TabRoot(WLTab.Home), detail), nav.stack(WLTab.Home))
    }

    @Test
    fun committedBackGesturePopsAndStaysTransitioningUntilFinished() {
        val nav = WLNavigator()
        nav.push(detail, "home/p1")
        nav.finishTransition()

        assertTrue(nav.beginBackGesture())
        nav.commitBackGesture()
        assertEquals(listOf(WLRoute.TabRoot(WLTab.Home)), nav.stack(WLTab.Home))
        assertTrue(nav.isTransitioning)
        assertFalse(nav.push(detail, "home/p1"))

        nav.finishTransition()
        assertFalse(nav.isTransitioning)
    }

    @Test
    fun routesDeclareTabBarVisibility() {
        assertTrue(WLRoute.TabRoot(WLTab.Home).showsTabBar)
        assertFalse(detail.showsTabBar)
    }
}
