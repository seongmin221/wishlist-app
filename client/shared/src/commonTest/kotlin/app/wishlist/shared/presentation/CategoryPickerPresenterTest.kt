@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.wishlist.shared.presentation

import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.MutableAuthSession
import app.wishlist.shared.data.local.StoreHarness
import app.wishlist.shared.model.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CategoryPickerPresenterTest {
    private val customId = "11111111-1111-4111-8111-111111111111"
    private val select = catalog(fashion, digital, scope = CategoryScope.SELECT)
    private val made = CustomCategoryDetail(customId, "책상 장비", "G003", null, emptyList(), 0, 1)

    private class H(scope: TestScope) {
        val session = MutableAuthSession()
        val harness = StoreHarness(session)
        val categories = ScriptedCategories()
        val presenter = CategoryPickerPresenter(categories, harness.store, session, StandardTestDispatcher(scope.testScheduler))
        val state get() = presenter.state.value
    }

    private fun scenario(block: suspend TestScope.(H) -> Unit) = runTest {
        val h = H(this)
        h.session.changeAccount("A")
        try { block(h) } finally { h.presenter.close(); h.harness.close() }
    }

    @Test fun opensOnTheSelectedChipsGroup() = scenario { h ->
        h.categories.catalog.answer(ok(select))
        h.presenter.open("C026"); advanceUntilIdle()
        assertEquals(listOf<Any?>(CategoryScope.SELECT to null), h.categories.catalog.calls)
        assertEquals("G003", h.state.currentGroupId)
        assertEquals("C026", h.state.selectedId)
    }

    @Test fun withoutASelectionOpensOnTheLastGroupThenTheFirst() = scenario { h ->
        h.harness.store.writeAppState(lastCategoryGroupKey("A"), "G003")
        h.categories.catalog.answer(ok(select))
        h.presenter.open(null); advanceUntilIdle()
        assertEquals("G003", h.state.currentGroupId)
        h.harness.store.writeAppState(lastCategoryGroupKey("A"), null)
        h.categories.catalog.answer(ok(select))
        h.presenter.open(null); advanceUntilIdle()
        assertEquals("G001", h.state.currentGroupId)
    }

    @Test fun selectPicks() = scenario { h ->
        h.categories.catalog.answer(ok(select))
        h.presenter.open(null); advanceUntilIdle()
        h.presenter.showGroup("G003")
        h.presenter.select("C024"); advanceUntilIdle()
        assertEquals("C024", h.state.result?.id)
        assertEquals("C024", h.state.selectedId)
        h.presenter.consumeResult(); advanceUntilIdle()
        assertNull(h.state.result)
        h.presenter.select("C999"); advanceUntilIdle()
        assertNull(h.state.result)
    }

    @Test fun createdCategoryIsPickedAfterReadingSelect() = scenario { h ->
        h.categories.catalog.answer(ok(select))
        h.presenter.open(null); advanceUntilIdle()
        val withNew = catalog(fashion, group("G003", "디지털·IT", entry("C026", "헤드폰", "G003"),
            entry(customId, "책상 장비", "G003", 0, CategoryKind.CUSTOM)), scope = CategoryScope.SELECT)
        h.categories.catalog.answer(ok(withNew))
        h.presenter.onCreated(made); advanceUntilIdle()
        assertEquals(customId, h.state.result?.id)
        assertEquals("G003", h.state.currentGroupId)
        assertEquals(withNew, h.state.catalog)
    }

    @Test fun createdCategoryIsPickedEvenIfSelectFails() = scenario { h ->
        h.categories.catalog.answer(ok(select))
        h.presenter.open(null); advanceUntilIdle()
        h.categories.catalog.answer(fail(ErrorKind.NETWORK))
        h.presenter.onCreated(made); advanceUntilIdle()
        assertEquals(customId, h.state.result?.id)
        assertEquals(CategoryKind.CUSTOM, h.state.result?.kind)
        assertNull(h.state.error)
    }

    @Test fun loadFailureAndRetry() = scenario { h ->
        h.categories.catalog.answer(fail(ErrorKind.NETWORK))
        h.presenter.open(null); advanceUntilIdle()
        assertEquals(ErrorKind.NETWORK, h.state.error?.kind)
        h.categories.catalog.answer(ok(select))
        h.presenter.retry(); advanceUntilIdle()
        assertNull(h.state.error)
        assertEquals(select, h.state.catalog)
    }

    @Test fun accountChangeResets() = scenario { h ->
        val held = h.categories.catalog.hold()
        h.presenter.open(null); advanceUntilIdle()
        h.session.changeAccount("B"); advanceUntilIdle()
        held.complete(ok(select)); advanceUntilIdle()
        assertEquals(CategoryPickerState.Initial, h.state)
    }
}
