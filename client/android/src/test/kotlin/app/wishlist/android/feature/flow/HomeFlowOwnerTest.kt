@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.wishlist.android.feature.flow

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.wishlist.android.testing.TestItems
import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.model.HomeActionGroup
import app.wishlist.shared.model.HomeSummary
import app.wishlist.shared.model.ItemPage
import app.wishlist.shared.presentation.HomeFlowPhase
import app.wishlist.shared.presentation.HomeFlowPresenter
import app.wishlist.shared.repository.HomeRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The entry-scoped owner starts the flow once per screen lifetime and closes the Presenter with its store (JVM only). */
class HomeFlowOwnerTest {
    private class Session : AuthSession {
        override val state: StateFlow<SessionSnapshot> = MutableStateFlow(SessionSnapshot("account-a", 1))
        override suspend fun <T> withCurrent(snapshot: SessionSnapshot, operation: suspend () -> ClientResult<T>): ClientResult<T> =
            if (snapshot == state.value) operation() else ClientResult.Failure(ClientError(ErrorKind.SESSION_CHANGED))
    }

    private class RecordingHome : HomeRepository {
        val reads = mutableListOf<Pair<HomeActionGroup, String?>>()
        private val items = listOf(TestItems.item("1"), TestItems.item("2"), TestItems.item("3"))

        override suspend fun summary(): ClientResult<HomeSummary> = error("not used")
        private fun page() = ClientResult.Success(ItemPage(items, totalCount = items.size, nextCursor = null, anchorCursors = items.associate { it.id to "a-${it.id}" }))

        override suspend fun group(group: HomeActionGroup, cursor: String?, limit: Int): ClientResult<ItemPage> {
            reads += group to null
            return page()
        }
        override suspend fun groupAround(group: HomeActionGroup, anchor: String, before: Int, after: Int): ClientResult<ItemPage> {
            reads += group to anchor
            return page()
        }
    }

    private val home = RecordingHome()

    /** The Presenter constructor is internal to :shared; the runtime builds it in the app. */
    private fun TestScope.presenter(): HomeFlowPresenter =
        HomeFlowPresenter::class.java
            .getDeclaredConstructor(HomeRepository::class.java, AuthSession::class.java, CoroutineDispatcher::class.java)
            .apply { isAccessible = true }
            .newInstance(home, Session(), StandardTestDispatcher(testScheduler))

    private fun TestScope.owner(store: ViewModelStore): HomeFlowOwner {
        val presenter = presenter()
        val factory = viewModelFactory { initializer { HomeFlowOwner(presenter) } }
        return ViewModelProvider.create(store, factory)[HomeFlowOwner::class]
    }

    @Test fun the_flow_starts_once_even_when_the_screen_composes_again() = runTest {
        val store = ViewModelStore()
        val owner = owner(store)
        owner.startOnce(HomeActionGroup.CLASSIFICATION_REVIEW, startAnchor = null)
        advanceUntilIdle()
        // Rotation or the screen coming back: the same owner, no second start (passes are kept).
        owner.presenter.pass()
        advanceUntilIdle()
        owner.startOnce(HomeActionGroup.CLASSIFICATION_REVIEW, startAnchor = null)
        advanceUntilIdle()
        assertEquals(1, home.reads.size)
        assertEquals(1, owner.presenter.state.value.passed)
        assertEquals("2", owner.presenter.state.value.current?.item?.id)
    }

    @Test fun a_carousel_start_reads_the_window_at_its_anchor() = runTest {
        val owner = owner(ViewModelStore())
        owner.startOnce(HomeActionGroup.INFORMATION_COMPLETION, startAnchor = "a-2")
        advanceUntilIdle()
        assertEquals(listOf(HomeActionGroup.INFORMATION_COMPLETION to "a-2"), home.reads)
        assertEquals(HomeFlowPhase.ACTIVE, owner.presenter.state.value.phase)
    }

    @Test fun clearing_the_entry_store_closes_the_presenter() = runTest {
        val store = ViewModelStore()
        val owner = owner(store)
        owner.startOnce(HomeActionGroup.CLASSIFICATION_REVIEW, startAnchor = null)
        advanceUntilIdle()
        store.clear() // the route was popped or the account left
        owner.presenter.restart()
        owner.presenter.foreground()
        advanceUntilIdle()
        assertEquals(1, home.reads.size)
    }
}
