package app.wishlist.android.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WLEntryViewModelStoresTest {
    private class Probe : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }

    private data object Detail : WLRoute {
        override val showsTabBar = false
        override val pushStyle = WLPushStyle.Slide
    }

    private val factory = viewModelFactory { initializer { Probe() } }

    private fun probe(store: ViewModelStore): Probe =
        ViewModelProvider(object : ViewModelStoreOwner { override val viewModelStore = store }, factory)[Probe::class.java]

    @Test
    fun entryStoreIsClearedWhenItsEntryIsRemoved() {
        val stores = WLEntryViewModelStores()
        val nav = WLNavigator()
        nav.push(Detail, "k")
        nav.finishTransition()
        val entryId = nav.entries(WLTab.Home).last().id
        val rootId = nav.entries(WLTab.Home).first().id
        val detail = probe(stores.storeFor(entryId))
        val root = probe(stores.storeFor(rootId))
        assertSame(stores.storeFor(entryId), stores.storeFor(entryId))

        nav.pop()
        stores.clearRemoved(nav) // still animating: keep the leaving screen's ViewModels
        assertFalse(detail.cleared)

        nav.finishTransition()
        stores.clearRemoved(nav)
        assertTrue(detail.cleared)
        assertFalse(root.cleared)
        assertNotSame(detail, probe(stores.storeFor(entryId)))
    }

    @Test
    fun clearingTheHolderClearsEveryEntryStore() {
        val activityStore = ViewModelStore()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = activityStore }
        val stores = ViewModelProvider(owner, viewModelFactory { initializer { WLEntryViewModelStores() } })[WLEntryViewModelStores::class.java]
        val a = probe(stores.storeFor(1))
        val b = probe(stores.storeFor(2))

        activityStore.clear()

        assertTrue(a.cleared)
        assertTrue(b.cleared)
    }
}
