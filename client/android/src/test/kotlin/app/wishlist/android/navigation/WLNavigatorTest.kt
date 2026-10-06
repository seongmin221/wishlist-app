package app.wishlist.android.navigation

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WLNavigatorTest {
    private data class TestDetail(val id: String, val hasPhoto: Boolean) : WLRoute {
        override val showsTabBar = false
        override val pushStyle get() = if (hasPhoto) WLPushStyle.Photo else WLPushStyle.Surface
    }

    private val codec = object : WLRouteCodec {
        override fun encode(route: WLRoute): List<String>? = (route as? TestDetail)?.let { listOf(it.id, it.hasPhoto.toString()) }
        override fun decode(tokens: List<String>): WLRoute? = TestDetail(tokens[0], tokens[1].toBooleanStrict())
    }

    @Test fun restorationPreservesTabsEntriesAndNextIdWithoutTransitionLock() {
        val original = WLNavigator()
        original.push(TestDetail("removed", true), "home/removed")
        val removedId = original.entries(WLTab.Home).last().id
        original.finishTransition()
        original.pop()
        original.finishTransition()
        original.selectTab(WLTab.Purpose)
        original.finishTransition()
        original.push(TestDetail("saved", false), "purpose/saved")
        val savedId = original.entries(WLTab.Purpose).last().id

        val restored = WLNavigator.restore(original.save(codec), codec)
        assertEquals(WLTab.Purpose, restored.currentTab)
        assertEquals(original.entries(WLTab.Purpose).toList(), restored.entries(WLTab.Purpose).toList())
        assertFalse(restored.isTransitioning)
        restored.push(TestDetail("new", true), "purpose/new")
        assertTrue(restored.entries(WLTab.Purpose).last().id > maxOf(savedId, removedId))
    }

    @Test fun poppedIdsAreNeverReusedAfterRestoringRootsOnly() {
        val original = WLNavigator()
        original.push(TestDetail("removed", true), "home/removed")
        val removedId = original.entries(WLTab.Home).last().id
        original.finishTransition()
        original.pop()
        original.finishTransition()
        val restored = WLNavigator.restore(original.save(codec), codec)
        restored.push(TestDetail("new", true), "home/new")
        assertTrue(restored.entries(WLTab.Home).last().id > removedId)
    }

    private val detail = TestDetail(id = "p1", hasPhoto = true)

    @Test
    fun tabBarVisibilityFollowsCurrentTabAfterSwitchAndPush() {
        val nav = WLNavigator()
        val alpha = mutableStateOf<State<Float>?>(null)
        val visible = tabBarVisibility(nav, alpha)
        assertTrue(visible.value)

        nav.selectTab(WLTab.Purpose)
        nav.finishTransition()
        nav.push(detail, "purpose/p1")
        nav.finishTransition()
        alpha.value = mutableStateOf(0f)
        assertFalse(visible.value)

        nav.selectTab(WLTab.Home)
        nav.finishTransition()
        assertTrue(visible.value)
    }

    @Test
    fun startsOnHomeWithOneRootPerTab() {
        val nav = WLNavigator()
        assertEquals(WLTab.Home, nav.currentTab)
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
        assertEquals(WLTab.Category, nav.currentTab)
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
        assertEquals(WLTab.Home, nav.currentTab)
    }

    @Test
    fun pushSelectTabAndPopAreIgnoredWhileTransitioning() {
        val nav = WLNavigator()
        assertTrue(nav.push(detail, "home/p1"))
        assertTrue(nav.isTransitioning)

        assertFalse(nav.push(TestDetail("p2", hasPhoto = false), "home/p2"))
        assertFalse(nav.selectTab(WLTab.Purpose))
        assertFalse(nav.pop())

        assertEquals(WLTab.Home, nav.currentTab)
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

    @Test
    fun navBackIsDisabledWhileOverlayIsShowing() {
        assertTrue(navBackEnabled(isCurrent = true, overlayShowing = false, canPop = true, transitioning = false))
        assertTrue(navBackEnabled(isCurrent = true, overlayShowing = false, canPop = false, transitioning = true))
        // overlay가 있으면 등록 순서와 상관없이 라우터는 뒤로를 받지 않는다(overlay의 BackHandler가 받는다).
        assertFalse(navBackEnabled(isCurrent = true, overlayShowing = true, canPop = true, transitioning = false))
        assertFalse(navBackEnabled(isCurrent = true, overlayShowing = true, canPop = true, transitioning = true))
        assertFalse(navBackEnabled(isCurrent = false, overlayShowing = false, canPop = true, transitioning = false))
        assertFalse(navBackEnabled(isCurrent = true, overlayShowing = false, canPop = false, transitioning = false))
    }
}
