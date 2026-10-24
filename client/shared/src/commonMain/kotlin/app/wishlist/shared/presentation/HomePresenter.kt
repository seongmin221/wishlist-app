package app.wishlist.shared.presentation

import app.wishlist.shared.core.AuthAccount
import app.wishlist.shared.core.AuthFacade
import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.Clock
import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.domain.DisplayFormat
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.model.HomeActionGroup
import app.wishlist.shared.model.HomeSummary
import app.wishlist.shared.model.LocalSubmission
import app.wishlist.shared.model.SubmissionStatus
import app.wishlist.shared.repository.HomeRepository
import app.wishlist.shared.submission.SubmissionView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Instant

/** Board order of the home todo cards, top to bottom (not [HomeActionGroup.entries] order). */
private val BOARD_ORDER = listOf(
    HomeActionGroup.CLASSIFICATION_REVIEW,
    HomeActionGroup.INFORMATION_COMPLETION,
    HomeActionGroup.ANALYSIS_IN_PROGRESS,
)

/** The last HOME-01 answer of [accountId]'s session; shown only while that account is the signed-in one. */
private data class SummarySlot(
    val accountId: String?,
    val summary: HomeSummary? = null,
    val error: ClientError? = null,
    val refreshFailed: Boolean = false,
)

/**
 * Home over the login state, the [SubmissionView] and HOME-01. Only a view that belongs to the
 * current account is shown: while the account and the view disagree (an account switch passes
 * through signed out, and the view follows later) the state is Loading or LoggedOut, never rows
 * of the other account; before the coordinator's first view (store not ready) it is Loading too.
 * Relative times are recomputed on every state update, after every coordinator refresh run
 * ([refreshes], any trigger, also when no data changed) and on [tick] (the platform calls it every
 * minute while the home screen is shown).
 *
 * HOME-01 (C7) runs in the [SessionLane] model: a summary of a previous session never reaches the
 * state, and an account change drops the shown summary. It is fetched only on [appeared], and once
 * the home has appeared, on [foreground], [refresh]/[refreshNow] (after their coordinator run),
 * [retry] and when [refreshes] advances. Single-flight: a trigger while one runs schedules one
 * more run. Never on a timer; [tick] only recomputes times.
 */
class HomePresenter internal constructor(
    private val auth: AuthFacade,
    view: StateFlow<SubmissionView?>,
    private val refreshes: StateFlow<Long>,
    private val runRefresh: suspend () -> Unit,
    private val home: HomeRepository,
    session: AuthSession,
    private val clock: Clock,
    private val utcOffsetSeconds: (Instant) -> Int,
    dispatcher: CoroutineDispatcher,
) : SessionLane(session, dispatcher) {
    private val refreshing = MutableStateFlow(false)
    private val recompute = MutableStateFlow(0)
    private val summary = MutableStateFlow(SummarySlot(session.state.value.accountId))
    private val mutableState = MutableStateFlow<HomeState>(HomeState.Loading)

    // Confined to the lane.
    private var started = false
    private var ownRuns = 0
    private var handledRefreshes = refreshes.value
    private var summaryJob: Job? = null
    private var summaryCall: Job? = null
    private var again = false

    /** Read-only and thread-safe; platform owners collect it and render on the main thread. */
    val state: StateFlow<HomeState> = mutableState.asStateFlow()

    init {
        scope.launch {
            // Either revision changing only re-reads the clock (equal views are dropped by the StateFlow).
            val ticks = combine(refreshes, recompute) { _, _ -> }
            val extra = combine(refreshing, summary, ticks) { busy, slot, _ -> busy to slot }
            combine(auth.restored, auth.account, view, extra) { restored, account, current, (busy, slot) ->
                compose(restored, account, current, busy, slot)
            }.collect { mutableState.value = it }
        }
        scope.launch {
            refreshes.collect { run ->
                intent {
                    // A run this Presenter started reloads HOME-01 itself once it returns.
                    if (run != handledRefreshes && ownRuns == 0) {
                        handledRefreshes = run
                        if (started) fetch()
                    }
                }
            }
        }
    }

    /** The home screen became visible: fetches HOME-01. Returns at once. */
    fun appeared() {
        intent {
            started = true
            fetch()
        }
    }

    /** App foreground: a coordinator refresh, then HOME-01 (no pull indicator). Returns at once. */
    fun foreground() {
        intent { coordinatorThenSummary() }
    }

    /** Fetches HOME-01 again (the first-load retry or after a failure notice). Returns at once. */
    fun retry() {
        intent { if (started) fetch() }
    }

    /** Recomputes relative times ("방금" → "1분 전") without new data. Returns at once. */
    fun tick() {
        recompute.update { it + 1 }
    }

    /** Pull-to-refresh: shows [HomeState.LoggedIn.refreshing] until the refresh and HOME-01 finished. Returns at once. */
    fun refresh() {
        intent { userRefresh() }
    }

    /**
     * [refresh], awaited: returns once that refresh finished (iOS `.refreshable` awaits it). Returns
     * at once while another refresh is running or after [close]. Cancelling the caller stops only the
     * wait; the refresh itself runs on in the Presenter.
     */
    suspend fun refreshNow() {
        intent { userRefresh() }.join()
    }

    // On the Presenter's single lane, so the running check and the flag change are atomic.
    private suspend fun userRefresh() {
        if (refreshing.value) return
        refreshing.value = true
        try {
            coordinatorThenSummary()
        } finally {
            refreshing.value = false
            recompute.update { it + 1 }
        }
    }

    private suspend fun coordinatorThenSummary() {
        ownRuns++
        try {
            runRefresh()
        } finally {
            ownRuns--
            handledRefreshes = refreshes.value
        }
        if (started) fetch()?.join()
    }

    override fun onSessionChanged(current: SessionSnapshot) {
        summaryJob?.cancel(); summaryJob = null
        summaryCall?.cancel(); summaryCall = null
        again = false
        summary.value = SummarySlot(current.accountId)
        if (started) fetch()
    }

    /** Starts HOME-01, or schedules one more run when one is in flight. The job ends with the last run. */
    private fun fetch(): Job? {
        if (observed.accountId == null) return null
        summaryJob?.takeIf { it.isActive }?.let {
            again = true
            return it
        }
        summary.update { it.copy(error = null, refreshFailed = false) }
        return scope.launch {
            do {
                again = false
                val call = request({ home.summary() }) { publish(it) }
                summaryCall = call
                call.join()
            } while (again)
        }.also { summaryJob = it }
    }

    private fun publish(result: ClientResult<HomeSummary>) {
        summary.value = when (result) {
            is ClientResult.Success -> SummarySlot(observed.accountId, result.value)
            // Keep the last summary and say the refresh failed; without one, the error replaces the todo area.
            is ClientResult.Failure -> summary.value.let {
                if (it.summary != null) it.copy(refreshFailed = true) else it.copy(error = result.error)
            }
        }
    }

    private fun compose(restored: Boolean, account: AuthAccount?, view: SubmissionView?, busy: Boolean, slot: SummarySlot): HomeState {
        if (!restored || view == null) return HomeState.Loading
        val now = clock.now()
        fun row(key: String, target: HomeRowTarget, url: String, at: Instant, status: RowStatus) =
            HomeRow(key, target, DisplayFormat.host(url), url, DisplayFormat.relative(at, now, utcOffsetSeconds), status)
        if (account == null) {
            // A view still tied to an account is not this signed-out state's: show nothing of it.
            val unbound = if (view.accountId == null) view.local.filter { it.accountBinding == null } else emptyList()
            return HomeState.LoggedOut(
                unbound.map { row("local-${it.clientSubmissionId}", HomeRowTarget.Local(it.clientSubmissionId), it.sourceUrl, it.sharedAt, RowStatus.LOCAL_ONLY) },
            )
        }
        if (view.accountId != account.accountId) return HomeState.Loading
        // The view is already in display order (SubmissionView). Its processing items are not shown:
        // server-side items come from HOME-01 (ANALYSIS_IN_PROGRESS).
        val pending = view.local.map { row("local-${it.clientSubmissionId}", HomeRowTarget.Local(it.clientSubmissionId), it.sourceUrl, it.sharedAt, it.rowStatus()) }
        val own = slot.takeIf { it.accountId == account.accountId } ?: SummarySlot(account.accountId)
        val groups = own.summary?.groups.orEmpty()
        return HomeState.LoggedIn(
            pending = pending,
            groups = BOARD_ORDER.mapNotNull { g ->
                groups.firstOrNull { it.group == g }?.takeIf { it.count > 0 }?.let { HomeGroupCard(it.group, it.count, it.previews) }
            },
            purposes = own.summary?.recentPurposes.orEmpty(),
            todoCount = pending.size + groups.sumOf { it.count },
            summaryLoaded = own.summary != null,
            summaryError = own.error,
            refreshing = busy,
            refreshFailed = own.refreshFailed,
        )
    }
}

/** Why a signed-in local row waits (or that it is sending/failed); shared by the home and local detail Presenters. */
internal fun LocalSubmission.rowStatus(): RowStatus = when (submissionStatus) {
    SubmissionStatus.SUBMITTING -> RowStatus.SENDING
    SubmissionStatus.FAILED -> RowStatus.FAILED
    SubmissionStatus.PENDING -> when (lastSubmissionError?.kind) {
        // Not tried yet, offline, or cut off by an account change: the next connection sends it.
        null, ErrorKind.NETWORK, ErrorKind.TIMEOUT, ErrorKind.SESSION_CHANGED -> RowStatus.WAITING_NETWORK
        ErrorKind.SERVER, ErrorKind.INVALID_RESPONSE, ErrorKind.UNAVAILABLE, ErrorKind.NOT_FOUND,
        ErrorKind.RATE_LIMITED -> RowStatus.RETRYING
        ErrorKind.UNAUTHENTICATED -> RowStatus.NEEDS_SIGN_IN
        // Permanent kinds end FAILED; a PENDING row with one is still only waiting.
        ErrorKind.VALIDATION, ErrorKind.CONFLICT -> RowStatus.WAITING_NETWORK
    }
}
