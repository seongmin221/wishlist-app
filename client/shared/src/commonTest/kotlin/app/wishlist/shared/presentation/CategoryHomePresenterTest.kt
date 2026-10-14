@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.wishlist.shared.presentation

import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.MutableAuthSession
import app.wishlist.shared.data.local.StoreHarness
import app.wishlist.shared.model.CategoryScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CategoryHomePresenterTest {
    private class H(scope: TestScope) {
        val session = MutableAuthSession()
        val harness = StoreHarness(session)
        val categories = ScriptedCategories()
        val presenter = CategoryHomePresenter(categories, harness.store, session, StandardTestDispatcher(scope.testScheduler))
        val state get() = presenter.state.value
        fun close() { presenter.close(); harness.close() }
    }

    private fun scenario(block: suspend TestScope.(H) -> Unit) = runTest {
        val h = H(this)
        try { block(h) } finally { h.close() }
    }

    @Test fun signedOutSendsNoRequest() = scenario { h ->
        h.presenter.load(); advanceUntilIdle()
        assertFalse(h.state.signedIn)
        assertTrue(h.state.loaded)
        assertTrue(h.categories.catalog.calls.isEmpty())
    }

    @Test fun signedInLoadsBrowse() = scenario { h ->
        h.session.changeAccount("A")
        h.presenter.load(); advanceUntilIdle()
        assertEquals(listOf<Any?>(CategoryScope.BROWSE to null), h.categories.catalog.calls)
        assertEquals(listOf("G001", "G003"), h.state.groups.map { it.id })
        assertEquals(16, h.state.totalCount)
        assertEquals("G001", h.state.currentGroupId)
        assertNull(h.state.error)
    }

    @Test fun emptyBrowseIsLoadedAndEmpty() = scenario { h ->
        h.session.changeAccount("A")
        h.categories.catalog.answer(ok(catalog()))
        h.presenter.load(); advanceUntilIdle()
        assertTrue(h.state.loaded)
        assertEquals(emptyList(), h.state.groups)
        assertNull(h.state.currentGroupId)
    }

    @Test fun restoresLastGroup() = scenario { h ->
        h.session.changeAccount("A")
        h.presenter.load(); advanceUntilIdle()
        h.presenter.showGroup("G003"); advanceUntilIdle()
        assertEquals("G003", h.state.currentGroupId)
        // A new screen for the same account starts on G003; another account does not see it.
        val again = CategoryHomePresenter(h.categories, h.harness.store, h.session, StandardTestDispatcher(testScheduler))
        again.load(); advanceUntilIdle()
        assertEquals("G003", again.state.value.currentGroupId)
        again.close()
        h.session.changeAccount("B")
        val other = CategoryHomePresenter(h.categories, h.harness.store, h.session, StandardTestDispatcher(testScheduler))
        other.load(); advanceUntilIdle()
        assertEquals("G001", other.state.value.currentGroupId)
        other.close()
    }

    @Test fun savedGroupThatIsGoneFallsBackToTheFirst() = scenario { h ->
        h.session.changeAccount("A")
        h.harness.store.writeAppState(lastCategoryGroupKey("A"), "G009")
        h.presenter.load(); advanceUntilIdle()
        assertEquals("G001", h.state.currentGroupId)
    }

    @Test fun showGroupIgnoresUnknownAndRepeats() = scenario { h ->
        h.session.changeAccount("A")
        h.presenter.load(); advanceUntilIdle()
        h.presenter.showGroup("G999"); advanceUntilIdle()
        assertEquals("G001", h.state.currentGroupId)
    }

    @Test fun refreshKeepsGroupsAndFlagsFailure() = scenario { h ->
        h.session.changeAccount("A")
        h.presenter.load(); advanceUntilIdle()
        h.presenter.showGroup("G003"); advanceUntilIdle()
        h.categories.catalog.answer(fail(ErrorKind.NETWORK))
        h.presenter.refresh(); advanceUntilIdle()
        assertEquals(listOf("G001", "G003"), h.state.groups.map { it.id })
        assertTrue(h.state.refreshFailed)
        assertNull(h.state.error)
        assertEquals("G003", h.state.currentGroupId)
        h.presenter.refresh(); advanceUntilIdle()
        assertFalse(h.state.refreshFailed)
        assertEquals("G003", h.state.currentGroupId) // the shown page survives a refresh
    }

    @Test fun firstLoadFailureIsAnErrorAndRetryRecovers() = scenario { h ->
        h.session.changeAccount("A")
        h.categories.catalog.answer(fail(ErrorKind.NETWORK))
        h.presenter.load(); advanceUntilIdle()
        assertEquals(ErrorKind.NETWORK, h.state.error?.kind)
        h.presenter.retry(); advanceUntilIdle()
        assertNull(h.state.error)
        assertEquals(2, h.state.groups.size)
    }

    @Test fun lastRequestWins() = scenario { h ->
        h.session.changeAccount("A")
        val slow = h.categories.catalog.hold()
        h.presenter.load(); advanceUntilIdle()
        h.categories.catalog.answer(ok(catalog(digital)))
        h.presenter.refresh(); advanceUntilIdle()
        slow.complete(ok(catalog(fashion)))
        advanceUntilIdle()
        assertEquals(listOf("G003"), h.state.groups.map { it.id })
    }

    @Test fun accountChangeResetsAndReloadsForTheNewAccount() = scenario { h ->
        h.session.changeAccount("A")
        val stale = h.categories.catalog.hold()
        h.presenter.load(); advanceUntilIdle()
        h.categories.catalog.answer(ok(catalog(digital)))
        h.session.changeAccount("B"); advanceUntilIdle()
        stale.complete(ok(catalog(fashion))); advanceUntilIdle()
        assertEquals(listOf("G003"), h.state.groups.map { it.id })
        h.session.changeAccount(null); advanceUntilIdle()
        assertFalse(h.state.signedIn)
        assertEquals(emptyList(), h.state.groups)
    }

    @Test fun signInWhileOpenLoads() = scenario { h ->
        h.presenter.load(); advanceUntilIdle()
        h.session.changeAccount("A"); advanceUntilIdle()
        assertTrue(h.state.signedIn)
        assertEquals(2, h.state.groups.size)
    }

    @Test fun refreshNowWaitsForTheAnswer() = scenario { h ->
        h.session.changeAccount("A")
        h.presenter.load(); advanceUntilIdle()
        h.categories.catalog.answer(ok(catalog(digital)))
        h.presenter.refreshNow()
        assertEquals(listOf("G003"), h.state.groups.map { it.id })
    }

    @Test fun closeIgnoresLaterIntents() = scenario { h ->
        h.session.changeAccount("A")
        h.presenter.close()
        h.presenter.load(); advanceUntilIdle()
        assertTrue(h.categories.catalog.calls.isEmpty())
    }
}
