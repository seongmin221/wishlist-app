package app.wishlist.android.feature.purpose

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import app.wishlist.android.feature.category.CategoryFormSlot
import app.wishlist.android.navigation.WLNavigator
import app.wishlist.android.navigation.WLPushStyle
import app.wishlist.android.navigation.WLTab
import app.wishlist.android.ui.AppRouteCodec
import app.wishlist.shared.presentation.Presenter
import org.junit.Assert.*
import org.junit.Test

class PurposeRouteTest {
    @Test fun detailSurvivesRestoreAndLeavesWithTheAccount() {
        val route = PurposeDetailRoute("목적 /?#% 🎧")
        assertEquals(route, AppRouteCodec.decode(AppRouteCodec.encode(route)!!))
        assertTrue(route.accountScoped)
        assertTrue(route.showsTabBar)
        assertEquals(WLPushStyle.Slide, route.pushStyle)
        val nav = WLNavigator()
        nav.selectTab(WLTab.Purpose); nav.finishTransition()
        nav.push(route, "purpose/card"); nav.finishTransition()
        val restored = WLNavigator.restore(nav.save(AppRouteCodec), AppRouteCodec)
        assertEquals(route, restored.stack(WLTab.Purpose).last())
        assertEquals(1, restored.dropAccountScoped().size)
        assertEquals(1, restored.stack(WLTab.Purpose).size)
    }
    @Test fun malformedAndEmptyIdsAreRejected() {
        listOf(listOf("purpose"), listOf("purpose", ""), listOf("purpose", "!!"), listOf("purpose", "YQ", "extra"))
            .forEach { assertNull(PurposeDetailRoute.decode(it)) }
    }
    @Test fun refreshPreservesExpandedIdentityAndFallsBackWhenDeleted() {
        assertEquals("b", expandedPurposeId("b", listOf("a", "b")))
        assertEquals("a", expandedPurposeId("removed", listOf("a", "b")))
        assertNull(expandedPurposeId("a", emptyList()))
    }
    @Test fun entryAndFormLifetimesCloseEachPresenterExactlyOnce() {
        val store = ViewModelStore()
        val entry = object : ViewModelStoreOwner { override val viewModelStore = store }
        val owner = PurposePresenterOwner.of(entry, "purpose") { FakePresenter() }
        val slot = CategoryFormSlot.of(entry, "purpose.form") { FakePresenter() }
        val first = slot.fresh()
        val second = slot.fresh()
        assertEquals(1, first.closed)
        var loads = 0
        owner.once { loads++ }; owner.once { loads++ }
        assertEquals(1, loads)
        store.clear()
        assertEquals(1, owner.presenter.closed)
        assertEquals(1, second.closed)
    }
    @Test fun homeSeesEditsSavedWhileItsCompositionWasAway() {
        val store = ViewModelStore()
        val entry = object : ViewModelStoreOwner { override val viewModelStore = store }
        val owner = PurposePresenterOwner.of(entry, "purpose") { FakePresenter() }
        assertFalse(owner.markSeen(PurposeChanges.revision.value))
        PurposeChanges.changed()
        assertTrue(owner.markSeen(PurposeChanges.revision.value))
        assertFalse(owner.markSeen(PurposeChanges.revision.value))
        store.clear()
    }
    @Test fun returningToHomeFetchesSummaryAgainWithoutRecreatingPresenter() {
        val store = ViewModelStore()
        val entry = object : ViewModelStoreOwner { override val viewModelStore = store }
        val owner = PurposePresenterOwner.of(entry, "purpose") { FakePresenter() }
        var loads = 0
        var refreshes = 0
        owner.enter({ loads++ }, { refreshes++ })
        owner.enter({ loads++ }, { refreshes++ })
        assertEquals(1, loads)
        assertEquals(1, refreshes)
        store.clear()
    }
    private class FakePresenter : Presenter {
        var closed = 0
        override fun close() { closed++ }
    }
}
