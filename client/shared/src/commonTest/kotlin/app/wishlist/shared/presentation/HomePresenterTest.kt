@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.wishlist.shared.presentation

import app.cash.turbine.test
import app.wishlist.shared.core.AuthAccount
import app.wishlist.shared.core.AuthProvider
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.Clock
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.MutableAuthSession
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.di.TEST_UTC_OFFSET_SECONDS
import app.wishlist.shared.domain.RelativeTime
import app.wishlist.shared.model.AnalysisStatus
import app.wishlist.shared.model.HomeActionGroup
import app.wishlist.shared.model.HomeGroupSummary
import app.wishlist.shared.model.HomePreview
import app.wishlist.shared.model.HomeSummary
import app.wishlist.shared.model.ItemPage
import app.wishlist.shared.model.LocalSubmission
import app.wishlist.shared.model.SubmissionStatus
import app.wishlist.shared.model.WishlistItem
import app.wishlist.shared.model.itemFixture
import app.wishlist.shared.repository.HomeRepository
import app.wishlist.shared.submission.SubmissionView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
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


private fun preview(id: String) = HomePreview(itemFixture(id = id), "anchor-$id")

/** HOME-01 with the given counts (previews up to four), in [HomeActionGroup.entries] order like the repository. */
private fun summaryOf(review: Int = 0, completion: Int = 0, analysis: Int = 0, purposes: List<app.wishlist.shared.model.PurposeSummary> = emptyList()) = HomeSummary(
    HomeActionGroup.entries.map { g ->
        val count = when (g) {
            HomeActionGroup.CLASSIFICATION_REVIEW -> review
            HomeActionGroup.INFORMATION_COMPLETION -> completion
            HomeActionGroup.ANALYSIS_IN_PROGRESS -> analysis
        }
        HomeGroupSummary(g, count, (1..minOf(count, 4)).map { preview("${g.name}-$it") })
    },
    purposes,
)

private class ScriptedHome : HomeRepository {
    val summaries = Script<HomeSummary> { ok(summaryOf()) }
    var running = 0
    var maxRunning = 0
    override suspend fun summary(): ClientResult<HomeSummary> {
        running++
        maxRunning = maxOf(maxRunning, running)
        try { return summaries.next(Unit) } finally { running-- }
    }
    override suspend fun group(group: HomeActionGroup, cursor: String?, limit: Int): ClientResult<ItemPage> = error("not used")
    override suspend fun groupAround(group: HomeActionGroup, anchor: String, before: Int, after: Int): ClientResult<ItemPage> = error("not used")
}

class HomePresenterTest {
    private val auth = ScriptedAuth(seen = true)
    private val session = MutableAuthSession()
    private val home = ScriptedHome()
    private val order = mutableListOf<String>()
    private val view = MutableStateFlow<SubmissionView?>(SubmissionView(null, emptyList(), emptyList()))
    private val refreshRuns = MutableStateFlow(0L)
    private var now = T0 + 5.minutes
    private var refreshes = 0
    private var refreshGate: CompletableDeferred<Unit>? = null

    private fun TestScope.presenter() = HomePresenter(
        auth = auth,
        view = view,
        refreshes = refreshRuns,
        runRefresh = {
            refreshes++
            order += "coordinator"
            refreshGate?.await()
        },
        home = object : HomeRepository by home {
            override suspend fun summary(): ClientResult<HomeSummary> {
                order += "summary"
                return home.summary()
            }
        },
        session = session,
        clock = Clock { now },
        utcOffsetSeconds = { TEST_UTC_OFFSET_SECONDS },
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    /** Facade and session change together; the coordinator view follows at once unless [lagView]. */
    private suspend fun signIn(account: AuthAccount?, lagView: Boolean = false) {
        auth.mutableAccount.value = account
        session.changeAccount(account?.accountId)
        if (!lagView) view.value = SubmissionView(account?.accountId, emptyList(), emptyList())
    }

    @Test
    fun loadingUntilRestored() = runTest {
        val a = ScriptedAuth(restored = false)
        val p = HomePresenter(a, view, refreshRuns, {}, home, session, { now }, { 0 }, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(HomeState.Loading, p.state.value)
        a.mutableRestored.value = true
        advanceUntilIdle()
        assertIs<HomeState.LoggedOut>(p.state.value)
        p.close()
    }

    @Test
    fun loggedOutShowsUnboundInViewOrderWithLocalOnly() = runTest {
        // The coordinator view is the one ordering point; the presenter keeps its order as given.
        view.value = SubmissionView(
            null,
            listOf(local("new", T0 + 4.minutes), local("old", T0), local("mid", T0 + 2.minutes)),
            emptyList(),
        )
        val p = presenter()
        advanceUntilIdle()
        val state = assertIs<HomeState.LoggedOut>(p.state.value)
        assertEquals(listOf("new", "old", "mid"), state.pending.map { it.sourceUrl.substringAfterLast('/') })
        assertTrue(state.pending.all { it.status == RowStatus.LOCAL_ONLY })
        assertEquals("shop.example", state.pending.first().host)
        assertEquals(RelativeTime.Minutes(5), state.pending[1].savedAt)
        p.close()
    }

    @Test
    fun loggedInPendingIsLocalRowsOnlyInViewOrder() = runTest {
        signIn(accountA)
        view.value = SubmissionView(
            "A",
            listOf(
                local("f", T0 + 3.minutes, SubmissionStatus.FAILED, "A"),
                local("s", T0 + 1.minutes, SubmissionStatus.SUBMITTING, "A"),
                local("p", T0, SubmissionStatus.PENDING, "A"),
            ),
            // Server-side processing items come from HOME-01 ANALYSIS_IN_PROGRESS, not from the view.
            listOf(processing("i1", T0 + 1.minutes)),
        )
        val p = presenter()
        advanceUntilIdle()
        val state = assertIs<HomeState.LoggedIn>(p.state.value)
        assertEquals(listOf(RowStatus.FAILED, RowStatus.SENDING, RowStatus.WAITING_NETWORK), state.pending.map { it.status })
        assertEquals(listOf("local-f", "local-s", "local-p"), state.pending.map { it.key })
        assertEquals(listOf(HomeRowTarget.Local("f"), HomeRowTarget.Local("s"), HomeRowTarget.Local("p")), state.pending.map { it.target })
        assertFalse(state.refreshing)
        p.close()
    }

    @Test
    fun loadingUntilTheCoordinatorHasAView() = runTest {
        signIn(accountA)
        view.value = null
        val p = presenter()
        advanceUntilIdle()
        assertEquals(HomeState.Loading, p.state.value)
        view.value = SubmissionView("A", emptyList(), emptyList())
        advanceUntilIdle()
        assertIs<HomeState.LoggedIn>(p.state.value)
        p.close()
    }

    @Test
    fun tickRecomputesRelativeTimes() = runTest {
        view.value = SubmissionView(null, listOf(local("p", T0)), emptyList())
        val p = presenter()
        advanceUntilIdle()
        assertEquals(RelativeTime.Minutes(5), assertIs<HomeState.LoggedOut>(p.state.value).pending.single().savedAt)
        now = T0 + 65.minutes
        p.tick()
        advanceUntilIdle()
        assertEquals(RelativeTime.Hours(1), assertIs<HomeState.LoggedOut>(p.state.value).pending.single().savedAt)
        p.close()
    }

    @Test
    fun pendingRowsSayWhyTheyWait() = runTest {
        signIn(accountA)
        // Each recorded error kind of a PENDING row, and the status its row shows.
        val expected = mapOf<ErrorKind?, RowStatus>(
            null to RowStatus.WAITING_NETWORK,
            ErrorKind.NETWORK to RowStatus.WAITING_NETWORK,
            ErrorKind.TIMEOUT to RowStatus.WAITING_NETWORK,
            ErrorKind.SESSION_CHANGED to RowStatus.WAITING_NETWORK,
            ErrorKind.SERVER to RowStatus.RETRYING,
            ErrorKind.INVALID_RESPONSE to RowStatus.RETRYING,
            ErrorKind.UNAVAILABLE to RowStatus.RETRYING,
            ErrorKind.NOT_FOUND to RowStatus.RETRYING,
            ErrorKind.RATE_LIMITED to RowStatus.RETRYING,
            ErrorKind.UNAUTHENTICATED to RowStatus.NEEDS_SIGN_IN,
        )
        view.value = SubmissionView(
            "A",
            expected.keys.mapIndexed { i, kind ->
                local("r$i", T0, binding = "A").copy(lastSubmissionError = kind?.let { ClientError(it) })
            },
            emptyList(),
        )
        val p = presenter()
        advanceUntilIdle()
        val state = assertIs<HomeState.LoggedIn>(p.state.value)
        assertEquals(expected.values.toList(), state.pending.map { it.status })
        p.close()
    }

    @Test
    fun refreshTogglesRefreshingAndResendsPending() = runTest {
        signIn(accountA)
        view.value = SubmissionView("A", listOf(local("p", T0, binding = "A")), emptyList())
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
            advanceUntilIdle() // the HOME-01 reload after the coordinator run may publish first
            assertFalse(assertIs<HomeState.LoggedIn>(p.state.value).refreshing)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, refreshes)
        p.close()
    }

    @Test
    fun refreshNowReturnsOnceTheRefreshFinished() = runTest {
        signIn(accountA)
        view.value = SubmissionView("A", listOf(local("p", T0, binding = "A")), emptyList())
        val gate = CompletableDeferred<Unit>()
        refreshGate = gate
        val p = presenter()
        advanceUntilIdle()

        val call = async { p.refreshNow() }
        advanceUntilIdle()
        assertFalse(call.isCompleted)
        assertTrue(assertIs<HomeState.LoggedIn>(p.state.value).refreshing)
        // While one runs, another is ignored and returns at once.
        val second = async { p.refreshNow() }
        advanceUntilIdle()
        assertTrue(second.isCompleted)

        now = T0 + 20.minutes
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(call.isCompleted)
        val state = assertIs<HomeState.LoggedIn>(p.state.value)
        assertFalse(state.refreshing)
        assertEquals(RelativeTime.Minutes(20), state.pending.single().savedAt)
        assertEquals(1, refreshes)

        p.close()
        p.refreshNow() // closed: returns without refreshing
        assertEquals(1, refreshes)
    }

    @Test
    fun accountSwitchNeverEmitsPreviousAccountRows() = runTest {
        signIn(accountA)
        view.value = SubmissionView("A", listOf(local("a-row", T0, binding = "A")), emptyList())
        val p = presenter()
        advanceUntilIdle()
        val seen = mutableListOf<HomeState>()
        p.state.test {
            seen += awaitItem()
            // Facade order: signed out first, then B, while the coordinator view lags behind.
            signIn(null, lagView = true)
            advanceUntilIdle()
            signIn(accountB, lagView = true)
            advanceUntilIdle()
            view.value = SubmissionView("B", listOf(local("b-row", T0, binding = "B")), emptyList())
            advanceUntilIdle()
            seen += cancelAndConsumeRemainingEvents().filterIsInstance<app.cash.turbine.Event.Item<HomeState>>().map { it.value }
        }
        val afterSwitch = seen.drop(1)
        assertTrue(afterSwitch.isNotEmpty())
        for (state in afterSwitch) {
            if (state is HomeState.LoggedIn) {
                assertTrue(state.pending.none { it.sourceUrl.endsWith("a-row") }, "previous account row leaked: $state")
            }
            if (state is HomeState.LoggedOut) {
                assertTrue(state.pending.none { it.sourceUrl.endsWith("a-row") }, "previous account row leaked: $state")
            }
        }
        val last = assertIs<HomeState.LoggedIn>(p.state.value)
        assertEquals(listOf("b-row"), last.pending.map { it.sourceUrl.substringAfterLast('/') })
        p.close()
    }

    @Test
    fun relativeTimeRecomputedOnRefresh() = runTest {
        signIn(accountA)
        view.value = SubmissionView("A", listOf(local("p", T0, binding = "A")), emptyList())
        val p = presenter()
        advanceUntilIdle()
        assertEquals(RelativeTime.Minutes(5), assertIs<HomeState.LoggedIn>(p.state.value).pending.single().savedAt)
        now = T0 + 20.minutes
        p.refresh()
        advanceUntilIdle()
        assertEquals(RelativeTime.Minutes(20), assertIs<HomeState.LoggedIn>(p.state.value).pending.single().savedAt)
        now = T0 + 30.minutes
        view.value = view.value!!.copy(local = view.value!!.local.map { it.copy(submissionStatus = SubmissionStatus.SUBMITTING) })
        advanceUntilIdle()
        assertEquals(RelativeTime.Minutes(30), assertIs<HomeState.LoggedIn>(p.state.value).pending.single().savedAt)
        p.close()
    }

    @Test
    fun anyCoordinatorRefreshRunRecomputesRelativeTimeWithoutViewChange() = runTest {
        signIn(accountA)
        view.value = SubmissionView("A", listOf(local("p", T0, binding = "A")), emptyList())
        val p = presenter()
        advanceUntilIdle()
        assertEquals(RelativeTime.Minutes(5), assertIs<HomeState.LoggedIn>(p.state.value).pending.single().savedAt)
        now = T0 + 3.hours
        refreshRuns.value++ // e.g. a FOREGROUND run that changed nothing
        advanceUntilIdle()
        assertEquals(RelativeTime.Hours(3), assertIs<HomeState.LoggedIn>(p.state.value).pending.single().savedAt)
        p.close()
    }

    @Test
    fun closedPresenterStopsCollecting() = runTest {
        signIn(accountA)
        view.value = SubmissionView("A", listOf(local("p", T0, binding = "A")), emptyList())
        val p = presenter()
        advanceUntilIdle()
        val before = p.state.value
        p.close()
        p.close()
        view.value = SubmissionView("A", emptyList(), emptyList())
        p.refresh()
        advanceUntilIdle()
        assertEquals(before, p.state.value)
        assertEquals(0, refreshes)
    }

    // C7 HOME-01: action groups and recent purposes.

    private fun HomeState.loggedIn() = assertIs<HomeState.LoggedIn>(this)

    @Test
    fun todoCountSumsPendingAndGroups() = runTest {
        signIn(accountA)
        view.value = SubmissionView("A", listOf(local("p", T0, binding = "A")), emptyList())
        home.summaries.answer(ok(summaryOf(review = 3, completion = 3, analysis = 1)))
        val p = presenter()
        advanceUntilIdle()
        assertFalse(p.state.value.loggedIn().summaryLoaded)
        assertEquals(1, p.state.value.loggedIn().todoCount)
        p.appeared()
        advanceUntilIdle()
        val state = p.state.value.loggedIn()
        assertTrue(state.summaryLoaded)
        assertEquals(8, state.todoCount)
        p.close()
    }

    @Test
    fun groupsHideZeroAndKeepBoardOrder() = runTest {
        signIn(accountA)
        view.value = SubmissionView("A", emptyList(), emptyList())
        home.summaries.answer(ok(summaryOf(review = 2, completion = 0, analysis = 5)))
        val p = presenter()
        p.appeared()
        advanceUntilIdle()
        val groups = p.state.value.loggedIn().groups
        assertEquals(listOf(HomeActionGroup.CLASSIFICATION_REVIEW, HomeActionGroup.ANALYSIS_IN_PROGRESS), groups.map { it.group })
        assertEquals(listOf(2, 5), groups.map { it.count })
        assertEquals(listOf(2, 4), groups.map { it.previews.size })
        assertEquals("anchor-CLASSIFICATION_REVIEW-1", groups.first().previews.first().anchorCursor)

        home.summaries.answer(ok(summaryOf(review = 1, completion = 4, analysis = 2)))
        p.appeared()
        advanceUntilIdle()
        assertEquals(
            listOf(HomeActionGroup.CLASSIFICATION_REVIEW, HomeActionGroup.INFORMATION_COMPLETION, HomeActionGroup.ANALYSIS_IN_PROGRESS),
            p.state.value.loggedIn().groups.map { it.group },
        )
        p.close()
    }

    @Test
    fun purposesComeFromSummary() = runTest {
        signIn(accountA)
        val purposes = listOf(
            purposeSummaryFixture(purposeDetail("출퇴근")),
            purposeSummaryFixture(purposeDetail("빈 목적").copy(id = "22222222-2222-4222-8222-222222222222")),
            purposeSummaryFixture(purposeDetail("캠핑").copy(id = "33333333-3333-4333-8333-333333333333")),
        )
        home.summaries.answer(ok(summaryOf(purposes = purposes)))
        val p = presenter()
        p.appeared()
        advanceUntilIdle()
        val state = p.state.value.loggedIn()
        assertEquals(purposes, state.purposes)
        // No action items: the purposes still show, the todo area is empty.
        assertTrue(state.groups.isEmpty())
        assertEquals(0, state.todoCount)
        p.close()
    }

    @Test
    fun refreshRunsCoordinatorThenSummary() = runTest {
        signIn(accountA)
        val p = presenter()
        p.appeared()
        advanceUntilIdle()
        order.clear()
        val gate = CompletableDeferred<Unit>()
        refreshGate = gate
        val held = home.summaries.hold()
        val call = async { p.refreshNow() }
        advanceUntilIdle()
        assertEquals(listOf("coordinator"), order)
        assertTrue(p.state.value.loggedIn().refreshing)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("coordinator", "summary"), order)
        // Both have to finish before the indicator goes away.
        assertTrue(p.state.value.loggedIn().refreshing)
        assertFalse(call.isCompleted)
        held.complete(ok(summaryOf(review = 1)))
        advanceUntilIdle()
        assertTrue(call.isCompleted)
        val state = p.state.value.loggedIn()
        assertFalse(state.refreshing)
        assertEquals(1, state.todoCount)

        // Foreground: the same order, without the pull indicator.
        order.clear()
        refreshGate = null
        p.foreground()
        advanceUntilIdle()
        assertEquals(listOf("coordinator", "summary"), order)
        p.close()
    }

    @Test
    fun coordinatorRefreshReloadsSummaryOnce() = runTest {
        signIn(accountA)
        val p = presenter()
        refreshRuns.value++ // before the home appeared: nothing to reload
        advanceUntilIdle()
        assertEquals(0, home.summaries.calls.size)
        p.appeared()
        advanceUntilIdle()
        assertEquals(1, home.summaries.calls.size)

        val held = home.summaries.hold()
        refreshRuns.value++
        advanceUntilIdle()
        refreshRuns.value++
        advanceUntilIdle()
        home.summaries.answer(ok(summaryOf(completion = 3))) // a re-run's answer
        held.complete(ok(summaryOf(completion = 2)))
        advanceUntilIdle()
        val reloads = home.summaries.calls.size - 1
        assertTrue(reloads in 1..2, "reloads=$reloads")
        assertEquals(1, home.maxRunning)
        // The reload that started after the last counter change is the one shown.
        assertEquals(if (reloads == 2) 3 else 2, p.state.value.loggedIn().todoCount)
        p.close()
    }

    @Test
    fun summaryFailureKeepsLast() = runTest {
        signIn(accountA)
        home.summaries.answer(ok(summaryOf(review = 2)))
        val p = presenter()
        p.appeared()
        advanceUntilIdle()
        home.summaries.answer(fail(ErrorKind.NETWORK))
        p.refresh()
        advanceUntilIdle()
        val kept = p.state.value.loggedIn()
        assertEquals(listOf(2), kept.groups.map { it.count })
        assertTrue(kept.refreshFailed)
        assertNull(kept.summaryError)
        assertTrue(kept.summaryLoaded)
        // The next success clears the notice.
        p.retry()
        advanceUntilIdle()
        assertFalse(p.state.value.loggedIn().refreshFailed)
        p.close()

        // First load fails: no summary, the error (retry instead of the todo area).
        home.summaries.answer(fail(ErrorKind.SERVER))
        val q = presenter()
        q.appeared()
        advanceUntilIdle()
        val failed = q.state.value.loggedIn()
        assertFalse(failed.summaryLoaded)
        assertEquals(ErrorKind.SERVER, failed.summaryError?.kind)
        assertFalse(failed.refreshFailed)
        home.summaries.answer(ok(summaryOf(analysis = 1)))
        q.retry()
        advanceUntilIdle()
        val retried = q.state.value.loggedIn()
        assertTrue(retried.summaryLoaded)
        assertNull(retried.summaryError)
        assertEquals(1, retried.todoCount)
        q.close()
    }

    @Test
    fun staleSessionSummaryDropped() = runTest {
        signIn(accountA)
        view.value = SubmissionView("A", emptyList(), emptyList())
        home.summaries.answer(ok(summaryOf(review = 9)))
        val p = presenter()
        p.appeared()
        advanceUntilIdle()
        assertEquals(9, p.state.value.loggedIn().todoCount)
        val held = home.summaries.hold()
        p.refresh()
        advanceUntilIdle()
        val seen = mutableListOf<HomeState>()
        p.state.test {
            awaitItem()
            signIn(accountB)
            view.value = SubmissionView("B", emptyList(), emptyList())
            advanceUntilIdle()
            held.complete(ok(summaryOf(review = 7))) // A's late answer
            advanceUntilIdle()
            seen += cancelAndConsumeRemainingEvents().filterIsInstance<app.cash.turbine.Event.Item<HomeState>>().map { it.value }
        }
        for (state in seen.filterIsInstance<HomeState.LoggedIn>()) {
            assertTrue(state.groups.none { it.count == 9 || it.count == 7 }, "previous account summary leaked: $state")
        }
        // B's own HOME-01 (the default empty summary) is shown.
        val last = p.state.value.loggedIn()
        assertTrue(last.summaryLoaded)
        assertTrue(last.groups.isEmpty())
        p.close()
    }

    @Test
    fun noTimerRequests() = runTest {
        signIn(accountA)
        view.value = SubmissionView("A", listOf(local("p", T0, binding = "A")), emptyList())
        val p = presenter()
        p.appeared()
        advanceUntilIdle()
        assertEquals(1, home.summaries.calls.size)
        repeat(3) {
            advanceTimeBy(1.hours)
            now += 1.hours
            p.tick()
            advanceUntilIdle()
        }
        assertEquals(1, home.summaries.calls.size)
        assertEquals(0, refreshes)
        assertEquals(RelativeTime.Hours(3), p.state.value.loggedIn().pending.single().savedAt)
        p.close()
    }
}
