@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.wishlist.shared.presentation

import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.MutableAuthSession
import app.wishlist.shared.model.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CategoryListPresenterTest {
    private val customId = "11111111-1111-4111-8111-111111111111"
    private val headphones = CategoryListHint("C026", "헤드폰", "G003", "디지털·IT", CategoryKind.PUBLIC)
    private val dac = CategoryListHint(customId, "오디오 케이블·DAC", "G003", "디지털·IT", CategoryKind.CUSTOM)
    private val dacDetail = CustomCategoryDetail(customId, "오디오 케이블·DAC", "G003", "설명", listOf("포터블 DAC"), 2, 1)

    private fun id(n: Int) = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
    private fun items(range: IntRange) = range.map { itemFixture(id = id(it), name = "상품 $it") }
    private fun page(range: IntRange, total: Int, next: String?) = ok(ItemPage(items(range), total, next))
    /** A page or window whose cards carry anchors "a<n>". */
    private fun anchored(range: IntRange, total: Int, next: String?, previous: String? = null, anchor: ItemAnchor? = null) =
        ok(ItemPage(items(range), total, next, previous, range.associate { id(it) to "a$it" }, anchor))

    private class H(scope: TestScope) {
        val session = MutableAuthSession()
        val categories = ScriptedCategories()
        val list = ScriptedItemList()
        val presenter = CategoryListPresenter(categories, list, session, StandardTestDispatcher(scope.testScheduler))
        val state get() = presenter.state.value
    }

    private fun scenario(block: suspend TestScope.(H) -> Unit) = runTest {
        val h = H(this)
        h.session.changeAccount("A")
        try { block(h) } finally { h.presenter.close() }
    }

    @Test fun hintDrawsTheHeaderAtOnce() = scenario { h ->
        h.categories.catalog.hold()
        h.list.pages.hold()
        h.presenter.load(headphones); advanceUntilIdle()
        assertEquals(CategoryListHeader("헤드폰", "디지털·IT", CategoryKind.PUBLIC, null), h.state.header)
        assertTrue(h.state.loading)
    }

    @Test fun publicHeaderComesFromBrowseOfTheParent() = scenario { h ->
        h.list.pages.answer(page(1..3, 3, null))
        h.presenter.load(headphones); advanceUntilIdle()
        assertEquals(listOf<Any?>(CategoryScope.BROWSE to "G003"), h.categories.catalog.calls)
        assertEquals(CategoryListHeader("헤드폰", "디지털·IT", CategoryKind.PUBLIC, 8), h.state.header)
        assertEquals(3, h.state.items.size)
        assertFalse(h.state.hasMore)
        assertFalse(h.state.loading)
    }

    @Test fun customHeaderComesFromTheDetail() = scenario { h ->
        h.categories.custom.answer(ok(dacDetail))
        h.list.pages.answer(page(1..2, 2, null))
        h.presenter.load(dac); advanceUntilIdle()
        assertEquals(dacDetail, h.state.custom)
        assertEquals(2, h.state.header?.count)
        assertTrue(h.categories.catalog.calls.isEmpty())
    }

    @Test fun headerFailureAloneShowsNoErrorAndCountsFromItems() = scenario { h ->
        h.categories.catalog.answer(fail(ErrorKind.NETWORK))
        h.list.pages.answer(page(1..5, 5, null))
        h.presenter.load(headphones); advanceUntilIdle()
        assertEquals(CategoryListHeader("헤드폰", "디지털·IT", CategoryKind.PUBLIC, 5), h.state.header)
        assertNull(h.state.itemsError)
        assertFalse(h.state.partialFailed)
    }

    @Test fun itemsFailureAloneKeepsTheHeaderAndRetryAsksOnlyForItems() = scenario { h ->
        h.list.pages.answer(fail(ErrorKind.NETWORK))
        h.presenter.load(headphones); advanceUntilIdle()
        assertEquals(8, h.state.header?.count)
        assertEquals(ErrorKind.NETWORK, h.state.itemsError?.kind)
        h.list.pages.answer(page(1..2, 2, null))
        h.presenter.retry(); advanceUntilIdle()
        assertEquals(1, h.categories.catalog.calls.size)
        assertEquals(2, h.list.pages.calls.size)
        assertNull(h.state.itemsError)
        assertEquals(2, h.state.items.size)
    }

    @Test fun loadMoreAppendsAndIgnoresDuplicates() = scenario { h ->
        h.list.pages.answer(page(1..40, 45, "c1"))
        h.presenter.load(headphones); advanceUntilIdle()
        assertTrue(h.state.hasMore)
        val held = h.list.pages.hold()
        h.presenter.loadMore(); h.presenter.loadMore(); advanceUntilIdle()
        assertEquals(2, h.list.pages.calls.size) // the second loadMore was ignored while one was in flight
        assertEquals("C026" to "c1", h.list.pages.calls.last())
        held.complete(page(40..45, 45, null)); advanceUntilIdle()
        assertEquals(45, h.state.items.size)
        assertFalse(h.state.hasMore)
        h.presenter.loadMore(); advanceUntilIdle()
        assertEquals(2, h.list.pages.calls.size)
    }

    @Test fun loadMoreFailureKeepsTheListAndRetriesTheSameCursor() = scenario { h ->
        h.list.pages.answer(page(1..40, 80, "c1"))
        h.presenter.load(headphones); advanceUntilIdle()
        h.list.pages.answer(fail(ErrorKind.NETWORK))
        h.presenter.loadMore(); advanceUntilIdle()
        assertTrue(h.state.appendFailed)
        assertEquals(40, h.state.items.size)
        h.list.pages.answer(page(41..80, 80, null))
        h.presenter.retry(); advanceUntilIdle()
        assertEquals("C026" to "c1", h.list.pages.calls.last())
        assertEquals(80, h.state.items.size)
        assertFalse(h.state.appendFailed)
    }

    @Test fun refreshDropsAppendedPages() = scenario { h ->
        h.list.pages.answer(page(1..40, 80, "c1"))
        h.list.pages.answer(page(41..80, 80, null))
        h.presenter.load(headphones); advanceUntilIdle()
        h.presenter.loadMore(); advanceUntilIdle()
        assertEquals(80, h.state.items.size)
        h.list.pages.answer(page(1..40, 80, "c1"))
        h.presenter.refresh(); advanceUntilIdle()
        assertEquals(40, h.state.items.size)
        assertTrue(h.state.hasMore)
    }

    @Test fun refreshHeaderFailureIsAPartialNoticeOnlyForAPullRefresh() = scenario { h ->
        h.presenter.load(headphones); advanceUntilIdle()
        h.categories.catalog.answer(fail(ErrorKind.NETWORK))
        h.presenter.refresh(); advanceUntilIdle()
        assertTrue(h.state.partialFailed)
        h.categories.catalog.answer(fail(ErrorKind.NETWORK))
        h.presenter.refresh(); advanceUntilIdle()
        assertTrue(h.state.partialFailed)
        h.presenter.refresh(); advanceUntilIdle()
        assertFalse(h.state.partialFailed)
    }

    @Test fun refreshHeaderKeepsAppendedPages() = scenario { h ->
        h.categories.custom.answer(ok(dacDetail))
        h.list.pages.answer(page(1..40, 120, "c1"))
        h.list.pages.answer(page(41..80, 120, "c2"))
        h.list.pages.answer(page(81..120, 120, null))
        h.presenter.load(dac); advanceUntilIdle()
        h.presenter.loadMore(); advanceUntilIdle()
        h.presenter.loadMore(); advanceUntilIdle()
        val before = h.state.items
        h.categories.custom.answer(ok(dacDetail.copy(name = "DAC", itemCount = 120)))
        h.presenter.refreshHeader(); advanceUntilIdle()
        assertSame(before, h.state.items)
        assertEquals(CategoryListHeader("DAC", "디지털·IT", CategoryKind.CUSTOM, 120), h.state.header)
        assertEquals(3, h.list.pages.calls.size) // three pages, no new page request
        h.categories.custom.answer(fail(ErrorKind.NETWORK))
        h.presenter.refreshHeader(); advanceUntilIdle()
        assertEquals("DAC", h.state.header?.name)
        assertFalse(h.state.partialFailed)
    }

    @Test fun applyEditedShowsTheNameAtOnceAndKeepsItems() = scenario { h ->
        h.categories.custom.answer(ok(dacDetail))
        h.list.pages.answer(page(1..2, 2, null))
        h.presenter.load(dac); advanceUntilIdle()
        val held = h.categories.custom.hold()
        h.presenter.applyEdited(dacDetail.copy(name = "케이블", version = 2)); advanceUntilIdle()
        assertEquals("케이블", h.state.header?.name)
        assertEquals(2, h.state.custom?.version)
        assertEquals(2, h.state.items.size)
        held.complete(ok(dacDetail.copy(name = "케이블", version = 2))); advanceUntilIdle()
        assertEquals("케이블", h.state.header?.name)
    }

    @Test fun missingCustomIsGoneFromEitherRequest() = scenario { h ->
        h.categories.custom.answer(fail(ErrorKind.NOT_FOUND, "CATEGORY_NOT_FOUND"))
        h.presenter.load(dac); advanceUntilIdle()
        assertTrue(h.state.gone)
        val other = CategoryListPresenter(h.categories, h.list, h.session, StandardTestDispatcher(testScheduler))
        h.categories.custom.answer(ok(dacDetail))
        h.list.pages.answer(fail(ErrorKind.NOT_FOUND, "CATEGORY_NOT_FOUND"))
        other.load(dac); advanceUntilIdle()
        assertTrue(other.state.value.gone)
        other.close()
    }

    @Test fun publicLeafThatLeftBrowseKeepsTheHint() = scenario { h ->
        h.categories.catalog.answer(ok(catalog(group("G003", "디지털·IT", entry("C024", "키보드", "G003", 3)))))
        h.list.pages.answer(page(1..0, 0, null))
        h.presenter.load(headphones); advanceUntilIdle()
        assertEquals(CategoryListHeader("헤드폰", "디지털·IT", CategoryKind.PUBLIC, 0), h.state.header)
        assertEquals(emptyList(), h.state.items)
        assertFalse(h.state.gone)
    }

    @Test fun accountChangeResets() = scenario { h ->
        val held = h.list.pages.hold()
        h.presenter.load(headphones); advanceUntilIdle()
        h.session.changeAccount("B"); advanceUntilIdle()
        held.complete(page(1..3, 3, null)); advanceUntilIdle()
        assertEquals(CategoryListState.Initial, h.state)
        h.presenter.loadMore(); h.presenter.retry(); advanceUntilIdle()
        assertEquals(CategoryListState.Initial, h.state)
    }

    @Test fun refreshNowWaitsForBoth() = scenario { h ->
        h.presenter.load(headphones); advanceUntilIdle()
        h.list.pages.answer(page(1..4, 4, null))
        h.presenter.refreshNow()
        assertEquals(4, h.state.items.size)
        assertFalse(h.state.loading)
    }

    @Test fun refreshWithAnchorUsesWindow() = scenario { h ->
        h.list.pages.answer(anchored(1..40, 80, "c1"))
        h.list.pages.answer(anchored(41..80, 80, null))
        h.presenter.load(headphones); advanceUntilIdle()
        h.presenter.loadMore(); advanceUntilIdle()
        h.list.windows.answer(anchored(10..50, 80, "n50", "p10", ItemAnchor(id(30), id(30), true)))
        h.presenter.refresh(id(30)); advanceUntilIdle()
        assertEquals(listOf<Any?>(listOf("C026", "a30", 20, 20)), h.list.windows.calls)
        assertEquals(2, h.list.pages.calls.size)
        assertEquals(items(10..50), h.state.items)
        assertEquals(AnchorRestore(id(30), sameItem = true, token = 1), h.state.restore)
        assertTrue(h.state.hasNewer)
        assertTrue(h.state.hasMore)
        assertFalse(h.state.loading)
        h.list.pages.answer(anchored(51..60, 80, null))
        h.presenter.loadMore(); advanceUntilIdle()
        assertEquals("C026" to "n50", h.list.pages.calls.last()) // the window's own next cursor
        assertEquals(items(10..60), h.state.items)
    }

    @Test fun refreshWithRemovedAnchorRestoresFallback() = scenario { h ->
        h.list.pages.answer(anchored(1..40, 80, "c1"))
        h.presenter.load(headphones); advanceUntilIdle()
        // Card 1 was deleted elsewhere: the server falls back to the next older card.
        h.list.windows.answer(anchored(2..22, 79, "n22", null, ItemAnchor(id(1), id(2), false)))
        h.presenter.refresh(id(1)); advanceUntilIdle()
        assertEquals(AnchorRestore(id(2), sameItem = false, token = 1), h.state.restore)
        assertEquals(items(2..22), h.state.items)
        assertNull(h.state.itemsError)
        assertFalse(h.state.hasNewer)
    }

    @Test fun refreshWithoutAnchorUsesFirstPage() = scenario { h ->
        h.list.pages.answer(anchored(1..40, 80, "c1"))
        h.list.pages.answer(anchored(41..80, 80, null))
        h.presenter.load(headphones); advanceUntilIdle()
        h.presenter.loadMore(); advanceUntilIdle()
        assertNull(h.state.restore) // entry is the first page with no restore
        h.list.pages.answer(anchored(1..40, 80, "c1"))
        h.presenter.refresh(null); advanceUntilIdle()
        assertEquals(items(1..40), h.state.items)
        assertEquals(AnchorRestore(null, sameItem = false, token = 1), h.state.restore)
        h.list.pages.answer(anchored(1..40, 80, "c1"))
        h.presenter.refresh("99999999-9999-4999-8999-999999999999"); advanceUntilIdle() // no known anchor
        assertEquals(AnchorRestore(null, sameItem = false, token = 2), h.state.restore)
        assertEquals(listOf<Any?>("C026" to null, "C026" to "c1", "C026" to null, "C026" to null), h.list.pages.calls)
        assertTrue(h.list.windows.calls.isEmpty())
    }

    @Test fun loadNewerPrepends() = scenario { h ->
        h.list.pages.answer(anchored(1..40, 80, "c1"))
        h.presenter.load(headphones); advanceUntilIdle()
        h.list.windows.answer(anchored(10..50, 80, "n50", "p10", ItemAnchor(id(30), id(30), true)))
        h.presenter.refresh(id(30)); advanceUntilIdle()
        val held = h.list.pages.hold()
        h.presenter.loadNewer(); h.presenter.loadNewer(); advanceUntilIdle()
        assertTrue(h.state.prepending)
        assertEquals("C026" to "p10", h.list.pages.calls.last())
        assertEquals(2, h.list.pages.calls.size) // the second loadNewer was ignored while one was in flight
        held.complete(anchored(1..10, 80, "n10", null)); advanceUntilIdle()
        assertEquals(items(1..50), h.state.items)
        assertFalse(h.state.prepending)
        assertFalse(h.state.hasNewer)
        assertTrue(h.state.hasMore)
        h.presenter.loadNewer(); advanceUntilIdle()
        assertEquals(2, h.list.pages.calls.size)
    }

    @Test fun foregroundIgnoredWhileLoading() = scenario { h ->
        val held = h.list.pages.hold()
        h.presenter.load(headphones); advanceUntilIdle()
        h.presenter.foreground(null); advanceUntilIdle()
        assertEquals(1, h.list.pages.calls.size)
        assertEquals(1, h.categories.catalog.calls.size)
        held.complete(anchored(1..40, 80, "c1")); advanceUntilIdle()
        h.categories.catalog.answer(fail(ErrorKind.NETWORK))
        h.list.windows.answer(anchored(1..23, 80, "n23", null, ItemAnchor(id(3), id(3), true)))
        h.presenter.foreground(id(3)); advanceUntilIdle()
        assertEquals(2, h.categories.catalog.calls.size) // the header is read again, quietly
        assertFalse(h.state.partialFailed)
        assertEquals(listOf<Any?>(listOf("C026", "a3", 20, 20)), h.list.windows.calls)
        assertEquals(AnchorRestore(id(3), sameItem = true, token = 1), h.state.restore)
        assertFalse(h.state.loading)
    }

    @Test fun staleSessionWindowDropped() = scenario { h ->
        h.list.pages.answer(anchored(1..40, 80, "c1"))
        h.presenter.load(headphones); advanceUntilIdle()
        val held = h.list.windows.hold()
        h.presenter.refresh(id(3)); advanceUntilIdle()
        h.session.changeAccount("B"); advanceUntilIdle()
        held.complete(anchored(1..23, 80, "n23", null, ItemAnchor(id(3), id(3), true))); advanceUntilIdle()
        assertEquals(CategoryListState.Initial, h.state)
        h.presenter.loadNewer(); h.presenter.foreground(id(3)); advanceUntilIdle()
        assertEquals(CategoryListState.Initial, h.state)
        assertEquals(1, h.list.windows.calls.size)
    }
}
