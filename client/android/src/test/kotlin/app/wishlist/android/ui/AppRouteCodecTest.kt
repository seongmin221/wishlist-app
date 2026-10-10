package app.wishlist.android.ui

import app.wishlist.android.navigation.WLNavigator
import app.wishlist.android.navigation.WLPushStyle
import app.wishlist.android.navigation.WLTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
