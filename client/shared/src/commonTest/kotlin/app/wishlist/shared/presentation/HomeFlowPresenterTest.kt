@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package app.wishlist.shared.presentation

import app.wishlist.shared.core.ApiId
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.Clock
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.IdGenerator
import app.wishlist.shared.core.MutableAuthSession
import app.wishlist.shared.data.fake.FakeHomeRepository
import app.wishlist.shared.data.fake.FakeStore
import app.wishlist.shared.data.fake.fakeTime
import app.wishlist.shared.data.fake.successValue
import app.wishlist.shared.model.HomeActionGroup
import app.wishlist.shared.model.ItemPage
import app.wishlist.shared.repository.HomeKind
import app.wishlist.shared.repository.HomeRepository
import app.wishlist.shared.repository.homeItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

private const val OWNER_A = "00000000-0000-4000-8000-00000000000a"
private const val OWNER_B = "00000000-0000-4000-8000-00000000000b"
private val REVIEW = HomeActionGroup.CLASSIFICATION_REVIEW

/**
 * The Fake HOME-02 behind a recorder: every call is logged. [gate] holds a call before it reaches the
 * Fake (a failNext set meanwhile still applies); [hold] keeps a ready answer back until completed.
 */
private class RecordingHome(private val fake: HomeRepository) : HomeRepository {
    val calls = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    var hold: CompletableDeferred<Unit>? = null
    override suspend fun summary() = error("not used")
    override suspend fun group(group: HomeActionGroup, cursor: String?, limit: Int): ClientResult<ItemPage> {
        calls += "group:${group.name}:${if (cursor == null) "first" else "next"}:$limit"
        gate?.await()
        return fake.group(group, cursor, limit).also { hold?.await() }
    }
    override suspend fun groupAround(group: HomeActionGroup, anchor: String, before: Int, after: Int): ClientResult<ItemPage> {
        calls += "around:${group.name}:$before:$after"
        gate?.await()
        return fake.groupAround(group, anchor, before, after).also { hold?.await() }
    }
}

class HomeFlowPresenterTest {
    private val session = MutableAuthSession()
    private val store = FakeStore(session, Clock { fakeTime }, IdGenerator { Uuid.random().toString() })
    private val home = RecordingHome(FakeHomeRepository(store))
    private var seq = 0

    private suspend fun signIn(owner: String = OWNER_A) = session.changeAccount(owner)

    /** Places [count] REVIEW items; returns their ids newest first (the group order). */
    private suspend fun placeReview(count: Int): List<String> =
        (1..count).map { store.putItem(homeItem(++seq, HomeKind.REVIEW)).successValue().id }.reversed()

    private fun TestScope.presenter() = HomeFlowPresenter(home, session, StandardTestDispatcher(testScheduler))

    private val HomeFlowPresenter.ids get() = state.value.let { s -> listOfNotNull(s.current?.item?.id) + s.upcoming.map { it.item.id } }
    private val HomeFlowPresenter.counter get() = state.value.position to state.value.total

    @Test
    fun startShowsFirstWithStackOfTwo() = runTest {
        signIn()
        val items = placeReview(3)
        val p = presenter()
        p.start(REVIEW, null)
        advanceUntilIdle()
        val s = p.state.value
        assertEquals(REVIEW, s.group)
        assertEquals(HomeFlowPhase.ACTIVE, s.phase)
        assertEquals(items, p.ids)
        assertEquals(items.drop(1), s.upcoming.map { it.item.id })
        assertEquals(1 to 3, p.counter)
        assertEquals(0, s.passed)
        assertNull(s.error)
        assertEquals(false, s.refreshing)
        assertEquals(listOf("group:CLASSIFICATION_REVIEW:first:20"), home.calls)
        p.close()
    }

    @Test
    fun passAdvancesAndCounts() = runTest {
        signIn()
        val items = placeReview(3)
        val p = presenter()
        p.start(REVIEW, null)
        advanceUntilIdle()
        p.pass()
        advanceUntilIdle()
        assertEquals(items.drop(1), p.ids)
        assertEquals(2 to 3, p.counter)
        assertEquals(1, p.state.value.passed)
        assertEquals(HomeFlowPhase.ACTIVE, p.state.value.phase)
        // A temporary pass saves nothing and reads nothing.
        assertEquals(1, home.calls.size)
        p.close()
    }

    @Test
    fun lastPassEndsDone() = runTest {
        signIn()
        placeReview(3)
        val p = presenter()
        p.start(REVIEW, null)
        advanceUntilIdle()
        repeat(3) { p.pass(); advanceUntilIdle() }
        val s = p.state.value
        assertEquals(HomeFlowPhase.DONE, s.phase)
        assertNull(s.current)
        assertEquals(emptyList(), s.upcoming)
        assertEquals(3, s.passed)
        assertEquals(3 to 3, p.counter)
        // Passing on the summary does nothing.
        p.pass()
        advanceUntilIdle()
        assertEquals(3, p.state.value.passed)
        p.close()
    }

    @Test
    fun startFromAnchorCountsFromThere() = runTest {
        signIn()
        val items = placeReview(5)
        val anchor = store.homeGroup(REVIEW, null, 20).successValue().anchorCursors.getValue(items[1])
        val p = presenter()
        p.start(REVIEW, anchor)
        advanceUntilIdle()
        assertEquals(items[1], p.state.value.current?.item?.id)
        assertEquals(items.subList(2, 4), p.state.value.upcoming.map { it.item.id })
        assertEquals(1 to 4, p.counter)
        assertEquals(0, p.state.value.passed)
        p.pass()
        advanceUntilIdle()
        assertEquals(2 to 4, p.counter)
        p.restart()
        advanceUntilIdle()
        assertEquals(items[0], p.state.value.current?.item?.id)
        assertEquals(1 to 5, p.counter)
        assertEquals(0, p.state.value.passed)
        assertEquals(listOf("around:CLASSIFICATION_REVIEW:20:20", "group:CLASSIFICATION_REVIEW:first:20"), home.calls)
        p.close()
    }

    @Test
    fun restartClearsOnlySession() = runTest {
        signIn()
        val items = placeReview(3)
        val p = presenter()
        p.start(REVIEW, null)
        advanceUntilIdle()
        p.pass(); advanceUntilIdle()
        p.pass(); advanceUntilIdle()
        assertEquals(3 to 3, p.counter)
        p.restart()
        advanceUntilIdle()
        assertEquals(items, p.ids)
        assertEquals(1 to 3, p.counter)
        assertEquals(0, p.state.value.passed)
        // Only reads reached the backend, and the group is as it was.
        assertEquals(listOf("group:CLASSIFICATION_REVIEW:first:20", "group:CLASSIFICATION_REVIEW:first:20"), home.calls)
        assertEquals(items, store.homeGroup(REVIEW, null, 20).successValue().items.map { it.id })
        p.close()
    }

    @Test
    fun foregroundKeepsCurrentByAnchor() = runTest {
        signIn()
        val items = placeReview(3)
        val p = presenter()
        p.start(REVIEW, null)
        advanceUntilIdle()
        p.pass(); advanceUntilIdle()
        // Saved elsewhere meanwhile: newer than every card, so it lands in front.
        val newer = placeReview(1).single()
        p.foreground()
        advanceUntilIdle()
        assertEquals(items.drop(1), p.ids)
        assertEquals(2 to 3, p.counter)
        assertEquals(1, p.state.value.passed)
        assertEquals(false, p.state.value.refreshing)
        assertEquals(false, newer in p.ids)
        assertEquals(listOf("group:CLASSIFICATION_REVIEW:first:20", "around:CLASSIFICATION_REVIEW:20:20"), home.calls)
        p.close()
    }

    @Test
    fun foregroundFallbackSkipsPassed() = runTest {
        signIn()
        val items = placeReview(5)
        val p = presenter()
        p.start(REVIEW, null)
        advanceUntilIdle()
        p.pass(); advanceUntilIdle()
        p.pass(); advanceUntilIdle()
        // The current card was handled elsewhere: the window falls back to the next older one.
        store.delete(items[2]).successValue()
        p.foreground()
        advanceUntilIdle()
        assertEquals(HomeFlowPhase.ACTIVE, p.state.value.phase)
        assertNull(p.state.value.error)
        assertEquals(items.drop(3), p.ids)
        assertEquals(3 to 4, p.counter)
        assertEquals(2, p.state.value.passed)
        // Nothing older is left: the fallback is a newer card this session passed, so the flow ends.
        store.delete(items[3]).successValue()
        store.delete(items[4]).successValue()
        p.foreground()
        advanceUntilIdle()
        assertEquals(HomeFlowPhase.DONE, p.state.value.phase)
        assertEquals(emptyList(), p.ids)
        assertEquals(2 to 2, p.counter)
        assertEquals(2, p.state.value.passed)
        p.close()
    }

    @Test
    fun appendsNextPageNearEnd() = runTest {
        signIn()
        val items = placeReview(25)
        val p = presenter()
        p.start(REVIEW, null)
        advanceUntilIdle()
        assertEquals(1 to 25, p.counter)
        repeat(17) { p.pass(); advanceUntilIdle() }
        assertEquals(1, home.calls.size)
        // Two cards left on the page: the next one is fetched.
        p.pass(); advanceUntilIdle()
        assertEquals(listOf("group:CLASSIFICATION_REVIEW:first:20", "group:CLASSIFICATION_REVIEW:next:20"), home.calls)
        assertEquals(items.subList(18, 21), p.ids)
        assertEquals(19 to 25, p.counter)
        repeat(2) { p.pass(); advanceUntilIdle() }
        assertEquals(items[20], p.state.value.current?.item?.id)
        assertEquals(items.subList(21, 23), p.state.value.upcoming.map { it.item.id })
        assertEquals(21 to 25, p.counter)
        assertEquals(2, home.calls.size)
        p.close()
    }

    @Test
    fun foregroundFailureKeepsQueue() = runTest {
        signIn()
        val items = placeReview(3)
        val p = presenter()
        p.start(REVIEW, null)
        advanceUntilIdle()
        p.pass(); advanceUntilIdle()
        store.failNext(ApiId.HOME_02, ClientError(ErrorKind.NETWORK))
        val gate = CompletableDeferred<Unit>().also { home.gate = it }
        p.foreground()
        advanceUntilIdle()
        assertEquals(true, p.state.value.refreshing)
        gate.complete(Unit)
        advanceUntilIdle()
        val s = p.state.value
        assertEquals(HomeFlowPhase.ACTIVE, s.phase)
        assertEquals(items.drop(1), p.ids)
        assertEquals(2 to 3, p.counter)
        assertEquals(1, s.passed)
        assertNull(s.error)
        assertEquals(false, s.refreshing)
        p.close()
    }

    @Test
    fun appendFailureKeepsQueueAndRetriesOnNextPass() = runTest {
        signIn()
        val items = placeReview(25)
        val p = presenter()
        p.start(REVIEW, null)
        advanceUntilIdle()
        repeat(17) { p.pass(); advanceUntilIdle() }
        store.failNext(ApiId.HOME_02, ClientError(ErrorKind.NETWORK))
        p.pass(); advanceUntilIdle()
        assertEquals(HomeFlowPhase.ACTIVE, p.state.value.phase)
        assertNull(p.state.value.error)
        assertEquals(items.subList(18, 20), p.ids)
        assertEquals(19 to 25, p.counter)
        // The next signal (a pass near the end) reads the page again.
        p.pass(); advanceUntilIdle()
        assertEquals(items.subList(19, 22), p.ids)
        assertEquals(20 to 25, p.counter)
        assertEquals(listOf("group:CLASSIFICATION_REVIEW:first:20", "group:CLASSIFICATION_REVIEW:next:20", "group:CLASSIFICATION_REVIEW:next:20"), home.calls)
        p.close()
    }

    @Test
    fun retryAfterFailedNextPageOnEmptyQueue() = runTest {
        signIn()
        val items = placeReview(22)
        val p = presenter()
        p.start(REVIEW, null)
        advanceUntilIdle()
        repeat(17) { p.pass(); advanceUntilIdle() }
        val gate = CompletableDeferred<Unit>().also { home.gate = it }
        p.pass(); advanceUntilIdle() // the next page is read and held
        p.pass(); advanceUntilIdle()
        p.pass(); advanceUntilIdle()
        assertEquals(HomeFlowPhase.LOADING, p.state.value.phase)
        assertNull(p.state.value.current)
        store.failNext(ApiId.HOME_02, ClientError(ErrorKind.NETWORK))
        home.gate = null
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(HomeFlowPhase.ERROR, p.state.value.phase)
        assertEquals(ClientError(ErrorKind.NETWORK), p.state.value.error)
        p.retry()
        advanceUntilIdle()
        val s = p.state.value
        assertEquals(HomeFlowPhase.ACTIVE, s.phase)
        assertNull(s.error)
        assertEquals(items.drop(20), p.ids)
        assertEquals(21 to 22, p.counter)
        assertEquals(20, s.passed)
        p.close()
    }

    @Test
    fun foregroundWindowNextCursorAppendsPage() = runTest {
        signIn()
        val items = placeReview(45)
        val p = presenter()
        p.start(REVIEW, null)
        advanceUntilIdle()
        p.pass(); advanceUntilIdle()
        // The window around the 2nd card: 1 in front, 20 after, so it ends with a next cursor.
        p.foreground()
        advanceUntilIdle()
        assertEquals(2 to 45, p.counter)
        repeat(19) { p.pass(); advanceUntilIdle() }
        assertEquals(listOf("group:CLASSIFICATION_REVIEW:first:20", "around:CLASSIFICATION_REVIEW:20:20", "group:CLASSIFICATION_REVIEW:next:20"), home.calls)
        assertEquals(items.subList(20, 23), p.ids)
        repeat(2) { p.pass(); advanceUntilIdle() }
        assertEquals(items[22], p.state.value.current?.item?.id)
        assertEquals(items.subList(23, 25), p.state.value.upcoming.map { it.item.id })
        assertEquals(23 to 45, p.counter)
        assertEquals(3, home.calls.size)
        p.close()
    }

    @Test
    fun emptyGroupIsDone() = runTest {
        signIn()
        val p = presenter()
        p.start(REVIEW, null)
        advanceUntilIdle()
        val s = p.state.value
        assertEquals(HomeFlowPhase.DONE, s.phase)
        assertNull(s.current)
        assertEquals(0, s.passed)
        assertEquals(0 to 0, p.counter)
        p.close()
    }

    @Test
    fun startFailureThenRetry() = runTest {
        signIn()
        val items = placeReview(2)
        store.failNext(ApiId.HOME_02, ClientError(ErrorKind.NETWORK))
        val p = presenter()
        p.start(REVIEW, null)
        advanceUntilIdle()
        assertEquals(HomeFlowPhase.ERROR, p.state.value.phase)
        assertEquals(ClientError(ErrorKind.NETWORK), p.state.value.error)
        assertNull(p.state.value.current)
        p.retry()
        advanceUntilIdle()
        assertEquals(HomeFlowPhase.ACTIVE, p.state.value.phase)
        assertNull(p.state.value.error)
        assertEquals(items, p.ids)
        assertEquals(1 to 2, p.counter)
        assertEquals(2, home.calls.size)
        p.close()
    }

    @Test
    fun staleSessionFlowDropped() = runTest {
        signIn()
        placeReview(3)
        val p = presenter()
        p.pass() // nothing started yet
        val gate = CompletableDeferred<Unit>().also { home.hold = it }
        p.start(REVIEW, null)
        advanceUntilIdle()
        // A's answer is ready but held while the account changes to B.
        signIn(OWNER_B)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(HomeFlowState(), p.state.value)
        assertEquals(1, home.calls.size)
        p.close()
    }

    @Test
    fun ignoresOtherGroups() = runTest {
        signIn()
        val p = presenter()
        p.start(HomeActionGroup.ANALYSIS_IN_PROGRESS, null)
        advanceUntilIdle()
        assertEquals(HomeFlowState(), p.state.value)
        assertEquals(emptyList(), home.calls)
        p.close()
    }

    @Test
    fun noRequestWithoutSignal() = runTest {
        signIn()
        placeReview(3)
        val p = presenter()
        p.start(REVIEW, null)
        advanceUntilIdle()
        advanceTimeBy(5.hours)
        advanceUntilIdle()
        assertEquals(1, home.calls.size)
        p.close()
    }
}
