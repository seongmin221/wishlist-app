package app.wishlist.android.ui

import app.wishlist.android.feature.flow.HomeFlowRoute
import app.wishlist.android.navigation.WLNavigator
import app.wishlist.android.navigation.WLPushStyle
import app.wishlist.android.navigation.WLTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import app.wishlist.shared.model.HomeActionGroup
import org.junit.Test

class AppRouteCodecTest {
    @Test
    fun itemAndLocalRoutesRoundTripThroughTheCodec() {
        val item = ItemDetailRoute("item-1")
        val local = LocalSubmissionRoute("sub-1")
        assertEquals(listOf("item", "item-1"), ProductionRouteCodec.encode(item))
        assertEquals(listOf("local", "sub-1"), ProductionRouteCodec.encode(local))
        assertEquals(item, AppRouteCodec.decode(AppRouteCodec.encode(item)!!))
        assertEquals(local, AppRouteCodec.decode(AppRouteCodec.encode(local)!!))

        val nav = WLNavigator()
        nav.push(item, "home/item-1")
        nav.finishTransition()
        nav.push(local, "home/sub-1")
        val restored = WLNavigator.restore(nav.save(AppRouteCodec), AppRouteCodec)
        assertEquals(nav.entries(WLTab.Home).toList(), restored.entries(WLTab.Home).toList())
    }

    @Test
    fun detailRoutesAreAccountScopedSlidesWithoutTabBar() {
        listOf(ItemDetailRoute("i"), LocalSubmissionRoute("s")).forEach {
            assertTrue(it.accountScoped)
            assertFalse(it.showsTabBar)
            assertEquals(WLPushStyle.Slide, it.pushStyle)
        }
        assertFalse(SettingsRoute.accountScoped)
        assertFalse(LoginRoute.accountScoped)
    }

    @Test
    fun signingInKeepsAccountScopedRoutes() {
        assertFalse(shouldDropAccountScoped(previous = null, next = "a"))
        assertFalse(shouldDropAccountScoped(previous = null, next = null))
        assertFalse(shouldDropAccountScoped(previous = "a", next = "a"))
        assertTrue(shouldDropAccountScoped(previous = "a", next = null))
        assertTrue(shouldDropAccountScoped(previous = "a", next = "b"))
    }
    @Test
    fun homeFlowRoutesRoundTripWithAndWithoutAStartAnchor() {
        val fromCard = HomeFlowRoute(HomeActionGroup.CLASSIFICATION_REVIEW, startAnchor = null)
        val fromPreview = HomeFlowRoute(HomeActionGroup.INFORMATION_COMPLETION, startAnchor = "opaque/anchor+token=")
        listOf(fromCard, fromPreview).forEach { route ->
            assertEquals(route, AppRouteCodec.decode(AppRouteCodec.encode(route)!!))
            assertTrue(route.accountScoped)
            assertFalse(route.showsTabBar)
            assertEquals(WLPushStyle.Slide, route.pushStyle)
        }
        assertEquals("flow", ProductionRouteCodec.encode(fromCard)!!.first())
        // The analysis group has no flow; a broken token decodes to nothing.
        assertNull(AppRouteCodec.decode(listOf("flow", "ANALYSIS_IN_PROGRESS", "")))
        assertNull(AppRouteCodec.decode(listOf("flow", "CLASSIFICATION_REVIEW", "%%%")))
        assertNull(AppRouteCodec.decode(listOf("flow", "NOPE", "")))
    }
}
