package app.wishlist.shared.presentation

import app.wishlist.shared.core.AuthAccount
import app.wishlist.shared.core.AuthFacade
import app.wishlist.shared.core.Clock
import app.wishlist.shared.domain.DisplayFormat
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.model.LocalSubmission
import app.wishlist.shared.model.SubmissionStatus
import app.wishlist.shared.submission.SubmissionView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Instant

/**
 * Home list over the login state and the [SubmissionView]. Only a view that belongs to the
 * current account is shown: while the account and the view disagree (an account switch passes
 * through signed out, and the view follows later) the state is Loading or LoggedOut, never rows
 * of the other account; before the coordinator's first view (store not ready) it is Loading too.
 * Relative times are recomputed on every state update, after every coordinator refresh run
 * ([refreshes], any trigger, also when no data changed) and on [tick] (the platform calls it every
 * minute while the home screen is shown).
 *
 * Foreground refresh is app-wide (the platform asks the coordinator directly); this Presenter
 * only reacts to the view. [close] cancels everything, is idempotent, and later intents are ignored.
 */
class HomePresenter internal constructor(
    private val auth: AuthFacade,
    view: StateFlow<SubmissionView?>,
    refreshes: StateFlow<Long>,
    private val runRefresh: suspend () -> Unit,
    private val clock: Clock,
    private val utcOffsetSeconds: (Instant) -> Int,
    dispatcher: CoroutineDispatcher,
) : Presenter {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher.limitedParallelism(1))
    private val refreshing = MutableStateFlow(false)
    private val recompute = MutableStateFlow(0)
    private val mutableState = MutableStateFlow<HomeState>(HomeState.Loading)

    /** Read-only and thread-safe; platform owners collect it and render on the main thread. */
    val state: StateFlow<HomeState> = mutableState.asStateFlow()

    init {
        scope.launch {
            // Either revision changing only re-reads the clock (equal views are dropped by the StateFlow).
            val ticks = combine(refreshes, recompute) { _, _ -> }
            combine(auth.restored, auth.account, view, refreshing, ticks) { restored, account, current, busy, _ ->
                compose(restored, account, current, busy)
            }.collect { mutableState.value = it }
        }
    }

    /** Recomputes relative times ("방금" → "1분 전") without new data. Returns at once. */
    fun tick() {
        recompute.update { it + 1 }
    }

    /** Pull-to-refresh: shows [HomeState.LoggedIn.refreshing] until the refresh finished. Returns at once. */
    fun refresh() {
        scope.launch { userRefresh() }
    }

    /**
     * [refresh], awaited: returns once that refresh finished (iOS `.refreshable` awaits it). Returns
     * at once while another refresh is running or after [close]. Cancelling the caller stops only the
     * wait; the refresh itself runs on in the Presenter.
     */
    suspend fun refreshNow() {
        scope.launch { userRefresh() }.join()
    }

    // On the Presenter's single lane, so the running check and the flag change are atomic.
    private suspend fun userRefresh() {
        if (refreshing.value) return
        refreshing.value = true
        try {
            runRefresh()
        } finally {
            refreshing.value = false
            recompute.update { it + 1 }
        }
    }

    override fun close() {
        scope.cancel()
    }

    private fun compose(restored: Boolean, account: AuthAccount?, view: SubmissionView?, busy: Boolean): HomeState {
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
        // The view is already in display order (SubmissionView): local rows, then processing items.
        val local = view.local.map { row("local-${it.clientSubmissionId}", HomeRowTarget.Local(it.clientSubmissionId), it.sourceUrl, it.sharedAt, it.rowStatus()) }
        val processing = view.processing.map { row("item-${it.id}", HomeRowTarget.Item(it.id), it.sourceUrl, it.savedAt, RowStatus.PROCESSING) }
        return HomeState.LoggedIn(local + processing, busy)
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
