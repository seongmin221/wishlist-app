@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.wishlist.android.feature.detail

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.SessionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import app.wishlist.shared.model.AnalysisStatus
import app.wishlist.shared.model.ItemAnalysis
import app.wishlist.shared.model.ItemCategory
import app.wishlist.shared.model.ItemPurpose
import app.wishlist.shared.model.LifecycleStatus
import app.wishlist.shared.model.ProductSnapshot
import app.wishlist.shared.model.RequiredAction
import app.wishlist.shared.model.ReviewStatus
import app.wishlist.shared.model.WishlistItem
import app.wishlist.shared.presentation.ItemDetailPresenter
import app.wishlist.shared.presentation.ItemDetailState
import app.wishlist.shared.repository.GetItemRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/** The ViewModel owns the Presenter's lifetime: clearing its store closes the Presenter (JVM only). */
class ItemDetailPresenterOwnerTest {
    private val itemId = "00000000-0000-4000-8000-000000000001"
    private val time = Instant.parse("2026-10-07T00:00:00Z")
    private val item = WishlistItem(
        id = itemId, clientSubmissionId = "00000000-0000-4000-8000-000000000002", version = 1,
        sourceUrl = "https://shop.example/item", product = ProductSnapshot(name = "헤드폰"),
        category = ItemCategory(id = "C026"), purpose = ItemPurpose(), analysis = ItemAnalysis(AnalysisStatus.READY),
        reviewStatus = ReviewStatus.CONFIRMED, lifecycleStatus = LifecycleStatus.ACTIVE,
        requiredAction = RequiredAction.NONE, createdAt = time, updatedAt = time,
    )

    private class GatedRepository : GetItemRepository {
        val answers = mutableListOf<CompletableDeferred<ClientResult<WishlistItem>>>()
        var cancellations = 0

        override suspend fun get(id: String): ClientResult<WishlistItem> {
            val answer = CompletableDeferred<ClientResult<WishlistItem>>().also { answers += it }
            return try {
                answer.await()
            } catch (e: CancellationException) {
                cancellations++
                throw e
            }
        }
    }

    private val repository = GatedRepository()
    private val session = TestAuthSession()

    private fun TestScope.owner(store: ViewModelStore): Pair<ItemDetailPresenterOwner, ItemDetailPresenter> {
        val presenter = ItemDetailPresenter(repository, session, StandardTestDispatcher(testScheduler))
        val factory = viewModelFactory { initializer { ItemDetailPresenterOwner(presenter) } }
        return ViewModelProvider.create(store, factory)[ItemDetailPresenterOwner::class] to presenter
    }

    @Test fun owner_exposes_the_presenter_state_and_forwards_intents() = runTest {
        session.changeAccount("account-a")
        val store = ViewModelStore()
        val (owner, presenter) = owner(store)
        assertSame(presenter.state, owner.state)

        owner.load(itemId)
        advanceUntilIdle()
        repository.answers.single().complete(ClientResult.Success(item))
        advanceUntilIdle()
        assertEquals(ItemDetailState(item = item, loading = false, error = null), owner.state.value)

        owner.retry()
        advanceUntilIdle()
        assertEquals(2, repository.answers.size)
        store.clear()
    }

    @Test fun load_once_loads_a_single_time_and_refresh_repeats_the_last_id() = runTest {
        session.changeAccount("account-a")
        val store = ViewModelStore()
        val (owner, _) = owner(store)
        owner.loadOnce(itemId)
        advanceUntilIdle()
        repository.answers.single().complete(ClientResult.Success(item))
        advanceUntilIdle()
        owner.loadOnce(itemId) // a recomposition after a configuration change
        advanceUntilIdle()
        assertEquals(1, repository.answers.size)

        owner.refresh()
        advanceUntilIdle()
        assertEquals(2, repository.answers.size)
        assertEquals(ItemDetailState(item = item, loading = true, error = null), owner.state.value) // keeps the item
        store.clear()
    }

    @Test fun a_failed_refresh_is_announced_once_per_error() {
        val owner = ItemDetailPresenterOwner(ItemDetailPresenter(repository, session, StandardTestDispatcher()))
        val failure = ItemDetailState(item = item, loading = false, error = ClientError(ErrorKind.NETWORK))
        assertTrue(owner.takeRefreshNotice(failure))
        assertFalse(owner.takeRefreshNotice(failure)) // same state read again (configuration change)
        assertFalse(owner.takeRefreshNotice(failure.copy(loading = true)))
        assertFalse(owner.takeRefreshNotice(failure.copy(item = null))) // no item: the error screen shows it
        assertTrue(owner.takeRefreshNotice(failure.copy(error = ClientError(ErrorKind.NETWORK)))) // the next failure
    }

    @Test fun clearing_the_view_model_store_closes_the_presenter() = runTest {
        session.changeAccount("account-a")
        val store = ViewModelStore()
        val (owner, _) = owner(store)
        owner.load(itemId)
        advanceUntilIdle()
        val loading = owner.state.value

        store.clear() // onCleared -> presenter.close()
        advanceUntilIdle()
        assertEquals(1, repository.cancellations)

        owner.load(itemId)
        owner.retry()
        advanceUntilIdle()
        assertEquals(1, repository.answers.size)
        assertEquals(loading, owner.state.value)
        assertTrue(owner.state.value.loading)
    }

    @Test fun the_same_store_returns_the_same_owner_until_cleared() = runTest {
        val store = ViewModelStore()
        val (first, _) = owner(store)
        val (second, unused) = owner(store)
        assertSame(first, second)
        unused.close()
        store.clear()
    }
}

/** The shared session's account changes are internal to the runtime, so this test owns a minimal one. */
private class TestAuthSession : AuthSession {
    private val mutableState = MutableStateFlow(SessionSnapshot(null, 0))
    override val state: StateFlow<SessionSnapshot> = mutableState

    fun changeAccount(accountId: String?) {
        mutableState.value = SessionSnapshot(accountId, mutableState.value.generation + 1)
    }

    override suspend fun <T> withCurrent(
        snapshot: SessionSnapshot,
        operation: suspend () -> ClientResult<T>,
    ): ClientResult<T> =
        if (mutableState.value != snapshot) ClientResult.Failure(ClientError(kind = ErrorKind.SESSION_CHANGED)) else operation()
}
