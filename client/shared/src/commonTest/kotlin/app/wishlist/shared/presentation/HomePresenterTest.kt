@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.wishlist.shared.presentation

import app.cash.turbine.test
import app.wishlist.shared.core.AuthAccount
import app.wishlist.shared.core.AuthProvider
import app.wishlist.shared.core.Clock
import app.wishlist.shared.di.TEST_UTC_OFFSET_SECONDS
import app.wishlist.shared.domain.RelativeTime
import app.wishlist.shared.model.AnalysisStatus
import app.wishlist.shared.model.LocalSubmission
import app.wishlist.shared.model.SubmissionStatus
import app.wishlist.shared.model.WishlistItem
import app.wishlist.shared.model.itemFixture
import app.wishlist.shared.submission.FlushTrigger
import app.wishlist.shared.submission.SubmissionView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val T0 = Instant.parse("2026-10-07T03:00:00Z")
private val accountA = AuthAccount("A", "a@x", AuthProvider.GOOGLE)
private val accountB = AuthAccount("B", "b@x", AuthProvider.GOOGLE)

private fun local(id: String, at: Instant, status: SubmissionStatus = SubmissionStatus.PENDING, binding: String? = null) =
    LocalSubmission(id, "https://www.Shop.example/$id", at, binding, status)

private fun processing(id: String, at: Instant): WishlistItem =
    itemFixture(analysis = AnalysisStatus.PROCESSING, id = id, clientSubmissionId = "cs-$id").copy(createdAt = at)

class HomePresenterTest {
    private val auth = ScriptedAuth(seen = true)
    private val view = MutableStateFlow(SubmissionView(null, emptyList(), emptyList(), flushing = false))
    private val refreshRuns = MutableStateFlow(0L)
    private var now = T0 + 5.minutes
    private val refreshes = mutableListOf<FlushTrigger>()
    private var refreshGate: CompletableDeferred<Unit>? = null

    private fun TestScope.presenter() = HomePresenter(
        auth = auth,
        view = view,
        refreshes = refreshRuns,
        refresh = { trigger ->
            refreshes += trigger
            refreshGate?.await()
        },
        clock = Clock { now },
        utcOffsetSeconds = { TEST_UTC_OFFSET_SECONDS },
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun signIn(account: AuthAccount) {
        auth.mutableAccount.value = account
    }

    @Test
    fun loadingUntilRestored() = runTest {
        val a = ScriptedAuth(restored = false)
        val p = HomePresenter(a, view, refreshRuns, {}, { now }, { 0 }, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(HomeState.Loading, p.state.value)
        a.mutableRestored.value = true
        advanceUntilIdle()
        assertIs<HomeState.LoggedOut>(p.state.value)
        p.close()
    }

    @Test
    fun loggedOutShowsUnboundOldestFirstWithLocalOnly() = runTest {
        view.value = SubmissionView(
            null,
            listOf(local("new", T0 + 4.minutes), local("old", T0), local("mid", T0 + 2.minutes)),
            emptyList(),
            flushing = false,
        )
        val p = presenter()
        advanceUntilIdle()
        val state = assertIs<HomeState.LoggedOut>(p.state.value)
        assertEquals(listOf("old", "mid", "new"), state.pending.map { it.sourceUrl.substringAfterLast('/') })
        assertTrue(state.pending.all { it.status == RowStatus.LOCAL_ONLY })
        assertEquals("shop.example", state.pending.first().host)
        assertEquals(RelativeTime.Minutes(5), state.pending.first().savedAt)
        p.close()
    }

    @Test
    fun loggedInMapsLocalAndProcessingStatuses() = runTest {
        signIn(accountA)
        view.value = SubmissionView(
            "A",
            listOf(
                local("f", T0 + 3.minutes, SubmissionStatus.FAILED, "A"),
                local("s", T0 + 1.minutes, SubmissionStatus.SUBMITTING, "A"),
                local("p", T0, SubmissionStatus.PENDING, "A"),
            ),
            listOf(
                processing("i3", T0 + 2.minutes),
                processing("i2", T0 + 2.minutes),
                processing("i1", T0 + 1.minutes),
            ),
            flushing = false,
        )
        val p = presenter()
        advanceUntilIdle()
        val state = assertIs<HomeState.LoggedIn>(p.state.value)
        assertEquals(
            listOf(RowStatus.WAITING_NETWORK, RowStatus.SENDING, RowStatus.FAILED, RowStatus.PROCESSING, RowStatus.PROCESSING, RowStatus.PROCESSING),
            state.processing.map { it.status },
        )
        // Local rows (oldest first) then processing rows by createdAt, ties by id; input order is deliberately unsorted.
        assertEquals(
            listOf("local-p", "local-s", "local-f", "item-i1", "item-i2", "item-i3"),
            state.processing.map { it.key },
        )
        assertEquals(state.processing.size, state.processing.map { it.key }.toSet().size)
        assertFalse(state.refreshing)
        p.close()
    }

    @Test
    fun refreshTogglesRefreshingAndResendsPending() = runTest {
        signIn(accountA)
        view.value = SubmissionView("A", listOf(local("p", T0, binding = "A")), emptyList(), flushing = false)
        val gate = CompletableDeferred<Unit>()
        refreshGate = gate
        val p = presenter()
        advanceUntilIdle()
        p.state.test {
            assertFalse(assertIs<HomeState.LoggedIn>(awaitItem()).refreshing)
            p.refresh()
            p.refresh() // ignored while one is running
            assertTrue(assertIs<HomeState.LoggedIn>(awaitItem()).refreshing)
            gate.complete(Unit)
            assertFalse(assertIs<HomeState.LoggedIn>(awaitItem()).refreshing)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(FlushTrigger.USER_REFRESH), refreshes)
        p.close()
    }

    @Test
    fun accountSwitchNeverEmitsPreviousAccountRows() = runTest {
        signIn(accountA)
        view.value = SubmissionView("A", listOf(local("a-row", T0, binding = "A")), emptyList(), flushing = false)
        val p = presenter()
        advanceUntilIdle()
        val seen = mutableListOf<HomeState>()
        p.state.test {
            seen += awaitItem()
            // Facade order: signed out first, then B, while the coordinator view lags behind.
            auth.mutableAccount.value = null
            advanceUntilIdle()
            auth.mutableAccount.value = accountB
            advanceUntilIdle()
            view.value = SubmissionView("B", listOf(local("b-row", T0, binding = "B")), emptyList(), flushing = false)
            advanceUntilIdle()
            seen += cancelAndConsumeRemainingEvents().filterIsInstance<app.cash.turbine.Event.Item<HomeState>>().map { it.value }
        }
        val afterSwitch = seen.drop(1)
        assertTrue(afterSwitch.isNotEmpty())
        for (state in afterSwitch) {
            if (state is HomeState.LoggedIn) {
                assertTrue(state.processing.none { it.sourceUrl.endsWith("a-row") }, "previous account row leaked: $state")
            }
            if (state is HomeState.LoggedOut) {
                assertTrue(state.pending.none { it.sourceUrl.endsWith("a-row") }, "previous account row leaked: $state")
            }
        }
        val last = assertIs<HomeState.LoggedIn>(p.state.value)
        assertEquals(listOf("b-row"), last.processing.map { it.sourceUrl.substringAfterLast('/') })
        p.close()
    }

    @Test
    fun relativeTimeRecomputedOnRefresh() = runTest {
        signIn(accountA)
        view.value = SubmissionView("A", listOf(local("p", T0, binding = "A")), emptyList(), flushing = false)
        val p = presenter()
        advanceUntilIdle()
        assertEquals(RelativeTime.Minutes(5), assertIs<HomeState.LoggedIn>(p.state.value).processing.single().savedAt)
        now = T0 + 20.minutes
        p.refresh()
        advanceUntilIdle()
        assertEquals(RelativeTime.Minutes(20), assertIs<HomeState.LoggedIn>(p.state.value).processing.single().savedAt)
        now = T0 + 30.minutes
        view.value = view.value.copy(flushing = true)
        advanceUntilIdle()
        assertEquals(RelativeTime.Minutes(30), assertIs<HomeState.LoggedIn>(p.state.value).processing.single().savedAt)
        p.close()
    }

    @Test
    fun anyCoordinatorRefreshRunRecomputesRelativeTimeWithoutViewChange() = runTest {
        signIn(accountA)
        view.value = SubmissionView("A", listOf(local("p", T0, binding = "A")), emptyList(), flushing = false)
        val p = presenter()
        advanceUntilIdle()
        assertEquals(RelativeTime.Minutes(5), assertIs<HomeState.LoggedIn>(p.state.value).processing.single().savedAt)
        now = T0 + 3.hours
        refreshRuns.value++ // e.g. a FOREGROUND run that changed nothing
        advanceUntilIdle()
        assertEquals(RelativeTime.Hours(3), assertIs<HomeState.LoggedIn>(p.state.value).processing.single().savedAt)
        p.close()
    }

    @Test
    fun closedPresenterStopsCollecting() = runTest {
        signIn(accountA)
        view.value = SubmissionView("A", listOf(local("p", T0, binding = "A")), emptyList(), flushing = false)
        val p = presenter()
        advanceUntilIdle()
        val before = p.state.value
        p.close()
        p.close()
        view.value = SubmissionView("A", emptyList(), emptyList(), flushing = false)
        p.refresh()
        advanceUntilIdle()
        assertEquals(before, p.state.value)
        assertTrue(refreshes.isEmpty())
    }
}
