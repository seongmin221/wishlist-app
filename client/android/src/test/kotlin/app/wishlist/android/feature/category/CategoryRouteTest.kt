package app.wishlist.android.feature.category

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import app.wishlist.android.navigation.WLNavigator
import app.wishlist.android.navigation.WLPushStyle
import app.wishlist.android.navigation.WLTab
import app.wishlist.android.ui.AppRouteCodec
import app.wishlist.shared.model.CategoryKind
import app.wishlist.shared.presentation.CategoryListHint
import app.wishlist.shared.presentation.Presenter
import org.junit.Assert.*
import org.junit.Test

class CategoryRouteTest {
    private val hint = CategoryListHint("11111111-1111-4111-8111-111111111111", "케이블/DAC ?#%&= 🎧", "G003", "디지털·IT", CategoryKind.CUSTOM)

    @Test fun listRouteRoundTripsAwkwardNames() {
        val route = CategoryListRoute(hint)
        val tokens = AppRouteCodec.encode(route)!!
        assertEquals("category", tokens[0])
        assertTrue(tokens.none { '/' in it || '?' in it || '#' in it || '%' in it })
        assertEquals(route, AppRouteCodec.decode(tokens))
        val public = CategoryListRoute(CategoryListHint("C026", "헤드폰", "G003", "디지털·IT", CategoryKind.PUBLIC))
        assertEquals(public, AppRouteCodec.decode(AppRouteCodec.encode(public)!!))
    }

    @Test fun badTokensDecodeToNull() {
        val good = CategoryListRoute.encode(CategoryListRoute(hint))
        listOf(
            good.dropLast(1),
            good.toMutableList().also { it[2] = "!!" },
            good.toMutableList().also { it[5] = "SHARED" },
            good.toMutableList().also { it[1] = "" },
            listOf("category"),
        ).forEach { assertNull(it.toString(), CategoryListRoute.decode(it)) }
    }

    @Test fun listRouteIsAnAccountScopedSlideWithTheTabBar() {
        val route = CategoryListRoute(hint)
        assertTrue(route.accountScoped)
        assertTrue(route.showsTabBar)
        assertEquals(WLPushStyle.Slide, route.pushStyle)
        val nav = WLNavigator()
        nav.selectTab(WLTab.Category); nav.finishTransition()
        nav.push(route, "category/chip"); nav.finishTransition()
        assertEquals(1, nav.dropAccountScoped().size)
        assertEquals(1, nav.stack(WLTab.Category).size)
        val restored = WLNavigator.restore(WLNavigator().also { it.push(route, "k") }.save(AppRouteCodec), AppRouteCodec)
        assertEquals(route, restored.stack(WLTab.Home).last())
    }

    private class FakePresenter : Presenter {
        var closed = 0
        override fun close() { closed++ }
    }

    @Test fun ownerClosesItsPresenterWhenTheEntryStoreIsCleared() {
        val store = ViewModelStore()
        val entry = object : ViewModelStoreOwner { override val viewModelStore = store }
        var made = 0
        val first = CategoryPresenterOwner.of(entry, "home") { made++; FakePresenter() }
        val again = CategoryPresenterOwner.of(entry, "home") { made++; FakePresenter() }
        val form = CategoryPresenterOwner.of(entry, "form") { made++; FakePresenter() }
        assertSame(first, again)
        assertNotSame(first, form)
        assertEquals(2, made)
        var runs = 0
        first.once { runs++ }; first.once { runs++ }
        assertEquals(1, runs)
        store.clear()
        assertEquals(1, first.presenter.closed)
        assertEquals(1, form.presenter.closed)
    }
}
