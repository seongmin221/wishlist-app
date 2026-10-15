package app.wishlist.shared.data.local

import app.wishlist.shared.core.*
import app.wishlist.shared.data.fake.successValue
import app.wishlist.shared.model.ItemPage
import app.wishlist.shared.model.WishlistItem
import app.wishlist.shared.repository.ItemListRepository
import app.wishlist.shared.repository.LocalStore
import kotlinx.coroutines.test.runTest
import kotlin.test.*

private class PageDelegate(var page: ClientResult<ItemPage>, val during: suspend () -> Unit = {}) : ItemListRepository {
    override suspend fun byCategory(categoryId: String, cursor: String?, limit: Int): ClientResult<ItemPage> { during(); return page }
}

private class WriteFailingStore(inner: LocalStore) : LocalStore by inner {
    override suspend fun upsertItems(snapshot: SessionSnapshot, items: List<WishlistItem>) =
        ClientResult.Failure(ClientError(ErrorKind.UNAVAILABLE, "LOCAL_STORE_FAILURE"))
}

class CachedItemListRepositoryTest {
    @Test fun sameVersionCardRefreshesDisplay() = runTest {
        withHarness { h ->
            h.login("A"); val a = h.snapshot()
            h.store.upsertItem(a, item(3, name = "old name")).successValue()
            val repo = CachedItemListRepository(PageDelegate(ClientResult.Success(ItemPage(listOf(item(3, name = "new name")), 1, null))), h.store, h.session)
            repo.byCategory("C026").successValue()
            assertEquals("new name", h.store.cachedItem(a, itemId).successValue()!!.product.name)
        }
    }

    @Test fun cacheFailureKeepsThePage() = runTest {
        withHarness { h ->
            h.login("A")
            val page = ItemPage(listOf(item(1)), 1, null)
            val repo = CachedItemListRepository(PageDelegate(ClientResult.Success(page)), WriteFailingStore(h.store), h.session)
            assertEquals(page, repo.byCategory("C026").successValue())
        }
    }

    @Test fun aPageThatArrivesAfterAnAccountSwitchIsNotCachedForTheNewAccount() = runTest {
        withHarness { h ->
            h.login("A")
            val repo = CachedItemListRepository(PageDelegate(ClientResult.Success(ItemPage(listOf(item(1)), 1, null))) { h.login("B") }, h.store, h.session)
            repo.byCategory("C026")
            assertNull(h.store.cachedItem(h.session.state.value, itemId).successValue())
        }
    }
}
