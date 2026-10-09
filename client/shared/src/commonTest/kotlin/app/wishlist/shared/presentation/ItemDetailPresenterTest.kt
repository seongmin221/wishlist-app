@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.wishlist.shared.presentation

import app.cash.turbine.test
import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.MutableAuthSession
import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.model.WishlistItem
import app.wishlist.shared.model.itemFixture
import app.wishlist.shared.repository.GetItemRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Scripted ITEM-03 repository: every call waits until the test answers it. */
private class ScriptedGetRepository : GetItemRepository {
    inner class Call(val id: String) {
        val response = CompletableDeferred<ClientResult<WishlistItem>>()
        var cancelled = false
        var dispatcher: CoroutineDispatcher? = null
        fun succeed(item: WishlistItem) = response.complete(ClientResult.Success(item))
        fun fail(kind: ErrorKind) = response.complete(ClientResult.Failure(ClientError(kind)))
    }

    val calls = mutableListOf<Call>()

    /** A repository that ignores cancellation and answers late (exercises the publish guards). */
    var ignoreCancellation = false

    /** Runs inside the call before it waits for the scripted response. */
    var onCall: suspend (Call) -> Unit = {}

    override suspend fun get(id: String): ClientResult<WishlistItem> {
        val call = Call(id).also { calls += it }
        call.dispatcher = currentCoroutineContext()[CoroutineDispatcher]
        onCall(call)
        return try {
            if (ignoreCancellation) withContext(NonCancellable) { call.response.await() } else call.response.await()
        } catch (e: CancellationException) {
            call.cancelled = true
            throw e
        }
    }
}

/**
 * Models a cross-thread race deterministically: [value] (what withCurrent and the Presenter's
 * checks read) changes at once, but collectors observe the change only when the test calls
 * [deliver]. Commit gating is the real [MutableAuthSession]'s.
 */
private class LaggingSession : AuthSession {
    val real = MutableAuthSession()
    private val observed = MutableStateFlow(real.state.value)

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    override val state: StateFlow<SessionSnapshot> = object : StateFlow<SessionSnapshot> {
        override val value: SessionSnapshot get() = real.state.value
        override val replayCache: List<SessionSnapshot> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<SessionSnapshot>): Nothing = observed.collect(collector)
    }

    fun deliver() {
        observed.value = real.state.value
    }

    override suspend fun <T> withCurrent(
        snapshot: SessionSnapshot,
        operation: suspend () -> ClientResult<T>,
    ): ClientResult<T> = real.withCurrent(snapshot, operation)
}

class ItemDetailPresenterTest {
    private val itemId = "00000000-0000-4000-8000-000000000001"
    private val otherId = "00000000-0000-4000-8000-000000000002"
    private val item = itemFixture().copy(id = itemId)
    private val refreshed = item.copy(version = 2)
    private val other = itemFixture(name = "다른 상품").copy(id = otherId)

    private val repository = ScriptedGetRepository()
    private val session = MutableAuthSession()
    private val presenters = mutableListOf<ItemDetailPresenter>()

    private fun TestScope.newPresenter(): ItemDetailPresenter =
        ItemDetailPresenter(repository, session, StandardTestDispatcher(testScheduler)).also { presenters += it }

    private fun TestScope.signedIn(account: String = "account-a"): ItemDetailPresenter {
        launch { session.changeAccount(account) }
        advanceUntilIdle()
        return newPresenter()
    }

    @AfterTest fun closePresenters() = presenters.forEach { it.close() }

    private fun loaded(item: WishlistItem) = ItemDetailState(item = item, loading = false, error = null)
    private fun failed(kind: ErrorKind, item: WishlistItem? = null) =
        ItemDetailState(item = item, loading = false, error = ClientError(kind))

    // --- Load and retry ---------------------------------------------------------------------------

    @Test fun initial_then_loading_then_item() = runTest {
        val presenter = signedIn()
        presenter.state.test {
            assertEquals(ItemDetailState.Initial, awaitItem())
            presenter.load(itemId)
            assertEquals(ItemDetailState(item = null, loading = true, error = null), awaitItem())
            repository.calls.single().succeed(item)
            assertEquals(loaded(item), awaitItem())
        }
        assertEquals(listOf(itemId), repository.calls.map { it.id })
    }

    @Test fun error_then_retry_then_item() = runTest {
        val presenter = signedIn()
        presenter.load(itemId)
        advanceUntilIdle()
        repository.calls[0].fail(ErrorKind.NETWORK)
        advanceUntilIdle()
        assertEquals(failed(ErrorKind.NETWORK), presenter.state.value)

        presenter.retry()
        advanceUntilIdle()
        assertEquals(ItemDetailState(item = null, loading = true, error = null), presenter.state.value)
        assertEquals(listOf(itemId, itemId), repository.calls.map { it.id })
        repository.calls[1].succeed(item)
        advanceUntilIdle()
        assertEquals(loaded(item), presenter.state.value)
    }

    @Test fun retry_before_any_load_does_nothing() = runTest {
        val presenter = signedIn()
        presenter.retry()
        advanceUntilIdle()
        assertTrue(repository.calls.isEmpty())
        assertEquals(ItemDetailState.Initial, presenter.state.value)
    }

    @Test fun refresh_general_error_keeps_the_existing_item() = runTest {
        val presenter = signedIn()
        presenter.load(itemId)
        advanceUntilIdle()
        repository.calls[0].succeed(item)
        advanceUntilIdle()

        presenter.retry()
        advanceUntilIdle()
        assertEquals(ItemDetailState(item = item, loading = true, error = null), presenter.state.value)
        repository.calls[1].fail(ErrorKind.SERVER)
        advanceUntilIdle()
        assertEquals(failed(ErrorKind.SERVER, item), presenter.state.value)

        presenter.load(itemId)
        advanceUntilIdle()
        repository.calls[2].succeed(refreshed)
        advanceUntilIdle()
        assertEquals(loaded(refreshed), presenter.state.value)
    }

    @Test fun refresh_not_found_removes_the_item() = runTest {
        val presenter = signedIn()
        presenter.load(itemId)
        advanceUntilIdle()
        repository.calls[0].succeed(item)
        advanceUntilIdle()

        presenter.retry()
        advanceUntilIdle()
        repository.calls[1].fail(ErrorKind.NOT_FOUND)
        advanceUntilIdle()
        assertEquals(failed(ErrorKind.NOT_FOUND), presenter.state.value)
    }

    @Test fun loading_another_id_does_not_show_the_previous_item() = runTest {
        val presenter = signedIn()
        presenter.load(itemId)
        advanceUntilIdle()
        repository.calls[0].succeed(item)
        advanceUntilIdle()

        presenter.load(otherId)
        advanceUntilIdle()
        assertEquals(ItemDetailState(item = null, loading = true, error = null), presenter.state.value)
        repository.calls[1].fail(ErrorKind.SERVER)
        advanceUntilIdle()
        assertEquals(failed(ErrorKind.SERVER), presenter.state.value)
    }

    // --- Account change, refresh, normalization and the success hook ------------------------------

    @Test fun retryAfterAnAccountChangeDoesNothing() = runTest {
        val p = signedIn()
        p.load(itemId); runCurrent(); repository.calls.last().succeed(item); runCurrent()
        launch { session.changeAccount("account-b") }; runCurrent()
        p.retry(); p.refresh(); runCurrent()
        assertEquals(1, repository.calls.size)
        assertEquals(ItemDetailState.Initial, p.state.value)
    }

    @Test fun refreshKeepsTheShownItemAndRepeatsTheLastId() = runTest {
        val presenter = signedIn()
        presenter.load(itemId)
        runCurrent()
        repository.calls[0].succeed(item)
        runCurrent()

        presenter.refresh()
        runCurrent()
        assertEquals(listOf(itemId, itemId), repository.calls.map { it.id })
        assertEquals(ItemDetailState(item = item, loading = true, error = null), presenter.state.value)
        repository.calls[1].succeed(refreshed)
        runCurrent()
        assertEquals(loaded(refreshed), presenter.state.value)
    }

    @Test fun refreshBeforeTheFirstLoadDoesNothing() = runTest {
        val presenter = signedIn()
        presenter.refresh()
        runCurrent()
        assertEquals(0, repository.calls.size)
        assertEquals(ItemDetailState.Initial, presenter.state.value)
    }

    @Test fun uppercaseIdRefreshKeepsTheShownItem() = runTest {
        val lowerId = "abcdef00-0000-4000-8000-00000000000a"
        val lettered = item.copy(id = lowerId)
        val presenter = signedIn()
        presenter.load(lowerId.uppercase())
        runCurrent()
        assertEquals(lowerId, repository.calls.single().id)
        repository.calls[0].succeed(lettered)
        runCurrent()

        presenter.refresh()
        runCurrent()
        assertEquals(ItemDetailState(item = lettered, loading = true, error = null), presenter.state.value)
        presenter.load(lowerId.uppercase())
        runCurrent()
        assertEquals(ItemDetailState(item = lettered, loading = true, error = null), presenter.state.value)
    }

    @Test fun onLoadedRunsAfterEverySuccessfulLoad() = runTest {
        launch { session.changeAccount("account-a") }
        advanceUntilIdle()
        var hooks = 0
        val seen = mutableListOf<ItemDetailState>()
        lateinit var presenter: ItemDetailPresenter
        presenter = ItemDetailPresenter(repository, session, StandardTestDispatcher(testScheduler)) {
            hooks++
            seen += presenter.state.value
        }.also { presenters += it }

        presenter.load(itemId)
        runCurrent()
        repository.calls[0].fail(ErrorKind.SERVER)
        runCurrent()
        assertEquals(0, hooks)

        presenter.retry()
        runCurrent()
        repository.calls[1].succeed(item)
        runCurrent()
        presenter.refresh()
        runCurrent()
        repository.calls[2].succeed(refreshed)
        runCurrent()
        assertEquals(2, hooks)
        // The hook runs after the success state is published.
        assertEquals(listOf(loaded(item), loaded(refreshed)), seen)
    }

    // --- Last request wins ------------------------------------------------------------------------

    @Test fun a_new_load_cancels_the_previous_request() = runTest {
        val presenter = signedIn()
        presenter.load(itemId)
        advanceUntilIdle()
        presenter.load(otherId)
        advanceUntilIdle()
        assertTrue(repository.calls[0].cancelled)
        assertFalse(repository.calls[1].cancelled)
        repository.calls[1].succeed(other)
        advanceUntilIdle()
        assertEquals(loaded(other), presenter.state.value)
    }

    @Test fun when_b_completes_first_a_late_answer_is_ignored() = runTest {
        repository.ignoreCancellation = true
        val presenter = signedIn()
        presenter.load(itemId)
        advanceUntilIdle()
        presenter.load(otherId)
        advanceUntilIdle()

        repository.calls[1].succeed(other)
        advanceUntilIdle()
        assertEquals(loaded(other), presenter.state.value)
        repository.calls[0].succeed(item)
        advanceUntilIdle()
        assertEquals(loaded(other), presenter.state.value)
    }

    @Test fun a_late_error_of_a_replaced_request_is_ignored() = runTest {
        repository.ignoreCancellation = true
        val presenter = signedIn()
        presenter.load(itemId)
        advanceUntilIdle()
        presenter.retry()
        advanceUntilIdle()
        repository.calls[1].succeed(item)
        advanceUntilIdle()
        repository.calls[0].fail(ErrorKind.NOT_FOUND)
        advanceUntilIdle()
        assertEquals(loaded(item), presenter.state.value)
    }

    // --- Close ------------------------------------------------------------------------------------

    @Test fun close_cancels_in_flight_work_and_ignores_later_intents() = runTest {
        val presenter = signedIn()
        presenter.load(itemId)
        advanceUntilIdle()
        val before = presenter.state.value

        presenter.close()
        advanceUntilIdle()
        assertTrue(repository.calls.single().cancelled)

        presenter.load(otherId)
        presenter.retry()
        presenter.close() // idempotent
        advanceUntilIdle()
        assertEquals(1, repository.calls.size)
        assertEquals(before, presenter.state.value)
    }

    @Test fun close_drops_a_late_answer() = runTest {
        repository.ignoreCancellation = true
        val presenter = signedIn()
        presenter.load(itemId)
        advanceUntilIdle()
        val before = presenter.state.value
        presenter.close()
        repository.calls.single().succeed(item)
        advanceUntilIdle()
        assertEquals(before, presenter.state.value)
        assertNull(presenter.state.value.item)
    }

    @Test fun close_stops_following_the_session() = runTest {
        val presenter = signedIn()
        presenter.load(itemId)
        advanceUntilIdle()
        repository.calls.single().succeed(item)
        advanceUntilIdle()
        presenter.close()
        session.changeAccount("account-b")
        advanceUntilIdle()
        assertEquals(loaded(item), presenter.state.value)
    }

    // --- Session ----------------------------------------------------------------------------------

    @Test fun account_change_clears_previous_item() = runTest {
        val presenter = signedIn()
        presenter.load(itemId)
        advanceUntilIdle()
        repository.calls.single().succeed(item)
        advanceUntilIdle()
        assertNotNull(presenter.state.value.item)
        session.changeAccount("account-b")
        advanceUntilIdle()
        assertNull(presenter.state.value.item)
        assertNull(presenter.state.value.error)
    }

    @Test fun account_change_clears_the_error_and_cancels_the_request() = runTest {
        val presenter = signedIn()
        presenter.load(itemId)
        advanceUntilIdle()
        repository.calls[0].fail(ErrorKind.SERVER)
        advanceUntilIdle()
        presenter.retry()
        advanceUntilIdle()

        session.changeAccount("account-b")
        advanceUntilIdle()
        assertTrue(repository.calls[1].cancelled)
        assertEquals(ItemDetailState.Initial, presenter.state.value)
    }

    @Test fun same_account_relogin_clears_and_drops_the_late_answer() = runTest {
        repository.ignoreCancellation = true
        val presenter = signedIn("account-a")
        presenter.load(itemId)
        advanceUntilIdle()
        repository.calls[0].succeed(item)
        advanceUntilIdle()
        presenter.retry()
        advanceUntilIdle()

        session.changeAccount("account-a") // relogin: same account, new generation
        advanceUntilIdle()
        assertEquals(ItemDetailState.Initial, presenter.state.value)
        repository.calls[1].succeed(refreshed)
        advanceUntilIdle()
        assertEquals(ItemDetailState.Initial, presenter.state.value)
    }

    @Test fun an_answer_for_a_stale_snapshot_is_never_published() = runTest {
        val presenter = signedIn("account-a")
        val history = mutableListOf<ItemDetailState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { presenter.state.toList(history) }
        // The account changes while the request runs, then the repository answers immediately.
        repository.onCall = { call ->
            session.changeAccount("account-b")
            call.succeed(item)
        }
        presenter.load(itemId)
        advanceUntilIdle()

        assertTrue(history.none { it.item != null }, "stale item was published: $history")
        assertEquals(ItemDetailState.Initial, presenter.state.value)
    }

    @Test fun an_answer_racing_an_unobserved_account_change_is_refused_at_publication() = runTest {
        val lagging = LaggingSession()
        lagging.real.changeAccount("account-a")
        lagging.deliver()
        val presenter = ItemDetailPresenter(repository, lagging, StandardTestDispatcher(testScheduler))
            .also { presenters += it }
        val history = mutableListOf<ItemDetailState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { presenter.state.toList(history) }
        presenter.load(itemId)
        advanceUntilIdle()

        // The account changes on "another thread"; the observer has not run when the answer lands.
        lagging.real.changeAccount("account-b")
        repository.calls.single().succeed(item)
        advanceUntilIdle()
        assertTrue(history.none { it.item != null }, "stale item was published: $history")

        lagging.deliver()
        advanceUntilIdle()
        assertEquals(ItemDetailState.Initial, presenter.state.value)
    }

    @Test fun a_load_right_after_an_account_change_belongs_to_the_new_account() = runTest {
        val lagging = LaggingSession()
        lagging.real.changeAccount("account-a")
        lagging.deliver()
        val presenter = ItemDetailPresenter(repository, lagging, StandardTestDispatcher(testScheduler))
            .also { presenters += it }
        advanceUntilIdle()

        // The load arrives before the Presenter's session observer has seen the change.
        lagging.real.changeAccount("account-b")
        presenter.load(itemId)
        advanceUntilIdle()
        repository.calls.single().succeed(item)
        advanceUntilIdle()
        assertEquals(loaded(item), presenter.state.value)

        // The late observation of that same change must not cancel or clear the new account's item.
        lagging.deliver()
        advanceUntilIdle()
        assertFalse(repository.calls.single().cancelled)
        assertEquals(loaded(item), presenter.state.value)
    }

    // --- Cancellation, dispatcher and state surface ----------------------------------------------

    @Test fun aStrayCancellationBecomesAnError() = runTest {
        val presenter = signedIn()
        repository.onCall = { throw CancellationException("stray") }
        presenter.load(itemId)
        runCurrent()
        assertEquals(
            ItemDetailState(item = null, loading = false, error = ClientError(ErrorKind.UNAVAILABLE, DETAIL_STEP_FAILURE)),
            presenter.state.value,
        )

        // The lane survives: the next load works.
        repository.onCall = {}
        presenter.retry()
        runCurrent()
        repository.calls[1].succeed(item)
        runCurrent()
        assertEquals(loaded(item), presenter.state.value)
    }

    @Test fun aRealCancellationStillPropagates() = runTest {
        val presenter = signedIn()
        val history = mutableListOf<ItemDetailState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { presenter.state.toList(history) }
        presenter.load(itemId)
        runCurrent()
        presenter.load(otherId)
        runCurrent()
        assertTrue(repository.calls[0].cancelled)
        assertTrue(history.none { it.error != null }, "a real cancellation became an error: $history")
        repository.calls[1].succeed(other)
        runCurrent()
        assertEquals(loaded(other), presenter.state.value)
    }

    @Test fun repository_runs_on_the_injected_dispatcher() = runTest {
        session.changeAccount("account-a")
        val dispatcher = StandardTestDispatcher(testScheduler, name = "injected")
        val presenter = ItemDetailPresenter(repository, session, dispatcher).also { presenters += it }
        presenter.load(itemId)
        advanceUntilIdle()
        assertSame(dispatcher, repository.calls.single().dispatcher)
    }

    @Test fun state_is_read_only() = runTest {
        val presenter = signedIn()
        assertFalse(presenter.state is MutableStateFlow<*>)
    }
}
