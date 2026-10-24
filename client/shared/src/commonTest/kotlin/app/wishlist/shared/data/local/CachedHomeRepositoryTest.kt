package app.wishlist.shared.data.local

import app.wishlist.shared.core.*
import app.wishlist.shared.data.fake.successValue
import app.wishlist.shared.model.*
import app.wishlist.shared.repository.HomeRepository
import app.wishlist.shared.repository.LocalStore
import kotlinx.coroutines.test.runTest
import kotlin.test.*

private class HomeDelegate(
    var summary: ClientResult<HomeSummary>,
    var page: ClientResult<ItemPage>,
    val during: suspend () -> Unit = {},
) : HomeRepository {
    override suspend fun summary(): ClientResult<HomeSummary> { during(); return summary }
    override suspend fun group(group: HomeActionGroup, cursor: String?, limit: Int): ClientResult<ItemPage> { during(); return page }
    override suspend fun groupAround(group: HomeActionGroup, anchor: String, before: Int, after: Int): ClientResult<ItemPage> { during(); return page }
}

private class UpsertFailingStore(inner: LocalStore) : LocalStore by inner {
    override suspend fun upsertItems(snapshot: SessionSnapshot, items: List<WishlistItem>) =
        ClientResult.Failure(ClientError(ErrorKind.UNAVAILABLE, "LOCAL_STORE_FAILURE"))
}

private fun summaryOf(vararg previews: WishlistItem) = HomeSummary(
    HomeActionGroup.entries.map { group ->
        HomeGroupSummary(group, if (group == HomeActionGroup.CLASSIFICATION_REVIEW) previews.size else 0,
            if (group == HomeActionGroup.CLASSIFICATION_REVIEW) previews.map { HomePreview(it, "anchor-${it.id}") } else emptyList())
    },
    emptyList(),
)

class CachedHomeRepositoryTest {
    @Test fun summaryPreviewsRefreshTheCache() = runTest {
        withHarness { h ->
            h.login("A"); val a = h.snapshot()
            h.store.upsertItem(a, item(3, name = "old name")).successValue()
            val summary = summaryOf(item(3, name = "home preview"))
            val repo = CachedHomeRepository(HomeDelegate(ClientResult.Success(summary), ClientResult.Success(ItemPage(emptyList(), 0, null))), h.store, h.session)
            assertEquals(summary, repo.summary().successValue())
            assertEquals("home preview", h.store.cachedItem(a, itemId).successValue()!!.product.name)
        }
    }

    @Test fun groupPagesAndWindowsRefreshTheCache() = runTest {
        withHarness { h ->
            h.login("A"); val a = h.snapshot()
            val delegate = HomeDelegate(ClientResult.Success(summaryOf()), ClientResult.Success(ItemPage(listOf(item(3, name = "group page")), 1, null)))
            val repo = CachedHomeRepository(delegate, h.store, h.session)
            repo.group(HomeActionGroup.CLASSIFICATION_REVIEW).successValue()
            assertEquals("group page", h.store.cachedItem(a, itemId).successValue()!!.product.name)
            delegate.page = ClientResult.Success(ItemPage(listOf(item(3, name = "group window")), 1, null, anchor = ItemAnchor(itemId, itemId, true)))
            repo.groupAround(HomeActionGroup.CLASSIFICATION_REVIEW, "anchor").successValue()
            assertEquals("group window", h.store.cachedItem(a, itemId).successValue()!!.product.name)
        }
    }

    @Test fun answersAfterAnAccountSwitchAreNotCachedAndCacheFailureKeepsTheResult() = runTest {
        withHarness { h ->
            h.login("A")
            val summary = summaryOf(item(1))
            val page = ItemPage(listOf(item(1)), 1, null)
            val switched = CachedHomeRepository(HomeDelegate(ClientResult.Success(summary), ClientResult.Success(page)) { h.login("B") }, h.store, h.session)
            switched.summary()
            switched.group(HomeActionGroup.CLASSIFICATION_REVIEW)
            assertNull(h.store.cachedItem(h.session.state.value, itemId).successValue())
            val failing = CachedHomeRepository(HomeDelegate(ClientResult.Success(summary), ClientResult.Success(page)), UpsertFailingStore(h.store), h.session)
            assertEquals(summary, failing.summary().successValue())
            assertEquals(page, failing.groupAround(HomeActionGroup.CLASSIFICATION_REVIEW, "anchor").successValue())
        }
    }
}
