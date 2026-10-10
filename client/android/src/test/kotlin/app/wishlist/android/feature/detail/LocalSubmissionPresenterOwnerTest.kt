@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.wishlist.android.feature.detail

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.Clock
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.model.LocalSubmission
import app.wishlist.shared.model.WishlistItem
import app.wishlist.shared.presentation.LocalDetailState
import app.wishlist.shared.presentation.LocalSubmissionDetailPresenter
import app.wishlist.shared.presentation.RowStatus
import app.wishlist.shared.submission.SubmissionView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/** The ViewModel owns the local detail Presenter's lifetime: clearing its store closes it (JVM only). */
class LocalSubmissionPresenterOwnerTest {
    private val submissionId = "00000000-0000-4000-8000-0000000000aa"
    private val time = Instant.parse("2026-10-07T00:00:00Z")
    private val link = LocalSubmission(submissionId, "https://www.shop.example/item", time)
    private val view = MutableStateFlow<SubmissionView?>(null)
    private val session = OwnerTestSession()
    private val deletes = mutableListOf<String>()
    private var lookups = 0

    /** The Presenter constructor is internal to :shared; the runtime builds it in the app. */
    private fun TestScope.presenter(): LocalSubmissionDetailPresenter {
        val lookup: suspend (SessionSnapshot, String) -> ClientResult<WishlistItem?> = { _, _ ->
            lookups++
            ClientResult.Success(null)
        }
        val delete: suspend (String) -> ClientResult<Unit> = { id ->
            deletes += id
            ClientResult.Success(Unit)
        }
        val offset: (Instant) -> Int = { 0 }
        val constructor = LocalSubmissionDetailPresenter::class.java.declaredConstructors.single { it.parameterCount == 7 }
        constructor.isAccessible = true
        return constructor.newInstance(
            view, lookup, delete, session, Clock { time }, offset, StandardTestDispatcher(testScheduler),
        ) as LocalSubmissionDetailPresenter
    }

    private fun TestScope.owner(store: ViewModelStore): Pair<LocalSubmissionPresenterOwner, LocalSubmissionDetailPresenter> {
        val presenter = presenter()
        val factory = viewModelFactory { initializer { LocalSubmissionPresenterOwner(presenter) } }
        return ViewModelProvider.create(store, factory)[LocalSubmissionPresenterOwner::class] to presenter
    }

    @Test fun owner_exposes_the_presenter_state_and_forwards_intents() = runTest {
        val store = ViewModelStore()
        val (owner, presenter) = owner(store)
        assertSame(presenter.state, owner.state)

        owner.load(submissionId)
        view.value = SubmissionView(accountId = null, local = listOf(link), processing = emptyList())
        advanceUntilIdle()
        assertEquals("shop.example", owner.state.value.row?.host)
        assertEquals(RowStatus.LOCAL_ONLY, owner.state.value.row?.status)
        assertTrue(owner.state.value.canDelete)

        owner.tick()
        owner.delete()
        advanceUntilIdle()
        assertEquals(listOf(submissionId), deletes)
        store.clear()
    }

    @Test fun clearing_the_view_model_store_closes_the_presenter() = runTest {
        val store = ViewModelStore()
        val (owner, _) = owner(store)
        owner.load(submissionId)
        view.value = SubmissionView(accountId = null, local = listOf(link), processing = emptyList())
        advanceUntilIdle()
        val shown = owner.state.value

        store.clear() // onCleared -> presenter.close()
        view.value = SubmissionView(accountId = null, local = emptyList(), processing = emptyList())
        owner.delete()
        owner.load(submissionId)
        advanceUntilIdle()

        assertEquals(shown, owner.state.value) // no Gone outcome, no reset: it no longer follows the view
        assertEquals(emptyList<String>(), deletes)
        assertEquals(0, lookups)
    }

    @Test fun the_same_store_returns_the_same_owner_until_cleared() = runTest {
        val store = ViewModelStore()
        val (first, _) = owner(store)
        val (second, unused) = owner(store)
        assertSame(first, second)
        unused.close()
        store.clear()
        assertEquals(LocalDetailState.Initial, first.state.value)
    }
}

private class OwnerTestSession : AuthSession {
    private val mutableState = MutableStateFlow(SessionSnapshot(null, 0))
    override val state: StateFlow<SessionSnapshot> = mutableState

    override suspend fun <T> withCurrent(
        snapshot: SessionSnapshot,
        operation: suspend () -> ClientResult<T>,
    ): ClientResult<T> =
        if (mutableState.value != snapshot) ClientResult.Failure(ClientError(kind = ErrorKind.SESSION_CHANGED)) else operation()
}
