package app.wishlist.android.feature.web

import app.wishlist.android.navigation.WLNavigator
import app.wishlist.android.navigation.WLPushStyle
import app.wishlist.android.navigation.WLTab
import app.wishlist.android.ui.AppRouteCodec
import app.wishlist.android.ui.ProductionRouteCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewRouteCodecTest {
    private val urls = listOf(
        "https://shop.com/a/b?x=1&y=%20#frag",
        "https://shop.com/상품/가방?색=검정",
        "http://shop.com/p?q=🎁&r=a/b?c#d=e",
        "HTTPS://user@Shop.com:8443/a%2Fb;c=d",
    )

    @Test
    fun webUrlsRoundTripThroughTheCodec() {
        for (url in urls) {
            val route = WebViewRoute.of(url) ?: error("rejected $url")
            assertEquals(url, route.url)
            val tokens = ProductionRouteCodec.encode(route)!!
            assertEquals(2, tokens.size)
            assertEquals("web", tokens[0])
            assertTrue(url, tokens[1].all { it.isLetterOrDigit() || it == '-' || it == '_' })
            assertEquals(route, ProductionRouteCodec.decode(tokens))
            assertEquals(route, AppRouteCodec.decode(AppRouteCodec.encode(route)!!))
        }
    }

    @Test
    fun navigatorRestoresAWebRoute() {
        val nav = WLNavigator()
        nav.push(WebViewRoute.of(urls[0])!!, "home/row/1")
        val restored = WLNavigator.restore(nav.save(AppRouteCodec), AppRouteCodec)
        assertEquals(nav.entries(WLTab.Home).toList(), restored.entries(WLTab.Home).toList())
    }

    @Test
    fun onlyHttpUrlsWithAHostAreAccepted() {
        listOf(
            "javascript:alert(1)",
            "file:///etc",
            "data:text/html,hi",
            "intent://x#Intent;end",
            "https://",
            "https:///path",
            "https://:443/a",
            "https://user@/a",
            "https:shop.com",
            "https:\\\\shop.com",
            "shop.com",
            "",
            "https://shop .com",
            "https://shop.com/a b",
            "https://sh\\op.com/",
        ).forEach { assertNull(it, WebViewRoute.of(it)) }
    }

    @Test
    fun decodeRejectsBadTokens() {
        val js = WebViewRoute.encodeToken("javascript:alert(1)")
        val file = WebViewRoute.encodeToken("file:///etc")
        listOf(
            listOf("web", ""),
            listOf("web", "!!!"),
            listOf("web", "aGk="), // padding is not produced, and "hi" is not a URL anyway
            listOf("web", "%%%"),
            listOf("web", js),
            listOf("web", file),
            listOf("web"),
            listOf("web", WebViewRoute.encodeToken(urls[0]), "extra"),
        ).forEach { assertNull(it.toString(), ProductionRouteCodec.decode(it)) }
    }

    @Test
    fun webRouteIsAnAccountScopedSlideWithoutTabBar() {
        val route = WebViewRoute.of(urls[0])!!
        assertTrue(route.accountScoped)
        assertFalse(route.showsTabBar)
        assertEquals(WLPushStyle.Slide, route.pushStyle)
    }
}
