package app.wishlist.shared.data.local

import app.wishlist.shared.core.*
import app.wishlist.shared.data.fake.successValue
import app.wishlist.shared.model.ItemAnchor
import app.wishlist.shared.model.ItemPage
import app.wishlist.shared.model.WishlistItem
import app.wishlist.shared.repository.ItemListRepository
import app.wishlist.shared.repository.LocalStore
import kotlinx.coroutines.test.runTest
import kotlin.test.*

private class PageDelegate(var page: ClientResult<ItemPage>, val during: suspend () -> Unit = {}) : ItemListRepository {
    override suspend fun byPurpose(purposeId: String, cursor: String?, limit: Int): ClientResult<ItemPage> { during(); return page }
    override suspend fun byCategory(categoryId: String, cursor: String?, limit: Int): ClientResult<ItemPage> { during(); return page }
    override suspend fun byCategoryAround(categoryId: String, anchor: String, before: Int, after: Int): ClientResult<ItemPage> { during(); return page }
    override suspend fun byPurposeAround(purposeId: String, anchor: String, before: Int, after: Int): ClientResult<ItemPage> { during(); return page }
}

private class WriteFailingStore(inner: LocalStore) : LocalStore by inner {
    override suspend fun upsertItems(snapshot: SessionSnapshot, items: List<WishlistItem>) =
        ClientResult.Failure(ClientError(ErrorKind.UNAVAILABLE, "LOCAL_STORE_FAILURE"))
}

class CachedItemListRepositoryTest {
    @Test fun purposePageRefreshesSameVersionPurposeAndCategoryDisplay() = runTest {
        withHarness { h ->
            h.login("A"); val a = h.snapshot()
            val old = item(3).copy(purpose = app.wishlist.shared.model.ItemPurpose("purpose", app.wishlist.shared.model.ValueSource.USER, "old", "CORAL", "HEART"))
            h.store.upsertItem(a, old).successValue()
            val fresh = old.copy(purpose = old.purpose.copy(name = "new", colorKey = "MINT"))
            val page = ItemPage(listOf(fresh), 1, null)
            val repo = CachedItemListRepository(PageDelegate(ClientResult.Success(page)), h.store, h.session)
            assertEquals(page, repo.byPurpose("purpose").successValue())
            assertEquals(fresh.purpose, h.store.cachedItem(a, itemId).successValue()!!.purpose)
        }
    }

    @Test fun purposePageNeverCachesUnderSwitchedAccountAndCacheFailureKeepsPage() = runTest {
        withHarness { h ->
            h.login("A")
            val page = ItemPage(listOf(item(1)), 1, null)
            val switched = CachedItemListRepository(PageDelegate(ClientResult.Success(page)) { h.login("B") }, h.store, h.session)
            switched.byPurpose("purpose")
            assertNull(h.store.cachedItem(h.session.state.value, itemId).successValue())
            val failed = CachedItemListRepository(PageDelegate(ClientResult.Success(page)), WriteFailingStore(h.store), h.session)
            assertEquals(page, failed.byPurpose("purpose").successValue())
        }
    }

    @Test fun sameVersionCardRefreshesDisplay() = runTest {
        withHarness { h ->
            h.login("A"); val a = h.snapshot()
            h.store.upsertItem(a, item(3, name = "old name")).successValue()
            val repo = CachedItemListRepository(PageDelegate(ClientResult.Success(ItemPage(listOf(item(3, name = "new name")), 1, null))), h.store, h.session)
            repo.byCategory("C026").successValue()
            assertEquals("new name", h.store.cachedItem(a, itemId).successValue()!!.product.name)
        }
    }

    @Test fun windowCardsRefreshTheCacheToo() = runTest {
        withHarness { h ->
            h.login("A"); val a = h.snapshot()
            h.store.upsertItem(a, item(3, name = "old name")).successValue()
            val window = ItemPage(listOf(item(3, name = "category window")), 1, null, anchor = ItemAnchor(itemId, itemId, true))
            val delegate = PageDelegate(ClientResult.Success(window))
            val repo = CachedItemListRepository(delegate, h.store, h.session)
            assertEquals(window, repo.byCategoryAround("C026", "anchor").successValue())
            assertEquals("category window", h.store.cachedItem(a, itemId).successValue()!!.product.name)
            delegate.page = ClientResult.Success(window.copy(items = listOf(item(3, name = "purpose window"))))
            repo.byPurposeAround("purpose", "anchor")
            assertEquals("purpose window", h.store.cachedItem(a, itemId).successValue()!!.product.name)
        }
    }

    @Test fun aWindowAfterAnAccountSwitchIsNotCached() = runTest {
        withHarness { h ->
            h.login("A")
            val repo = CachedItemListRepository(PageDelegate(ClientResult.Success(ItemPage(listOf(item(1)), 1, null))) { h.login("B") }, h.store, h.session)
            repo.byCategoryAround("C026", "anchor")
            assertNull(h.store.cachedItem(h.session.state.value, itemId).successValue())
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
