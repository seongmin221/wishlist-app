package app.wishlist.shared.presentation

import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.Clock
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.core.canonicalUuidOrNull
import app.wishlist.shared.data.local.SqlLocalStore
import app.wishlist.shared.domain.DisplayFormat
import app.wishlist.shared.model.LocalSubmission
import app.wishlist.shared.model.SubmissionStatus
import app.wishlist.shared.model.WishlistItem
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
 * Detail of one local link not sent yet (C4 spec §2), over the coordinator's [SubmissionView].
 * Every view, intent and state change is handled on one serial lane of [dispatcher].
 *
 * While the row (compared as canonical UUIDs) is in the view, [LocalDetailState.row] follows it;
 * a signed-out view shows it as [RowStatus.LOCAL_ONLY], and only a SUBMITTING row cannot be deleted.
 * Once the row is gone and no outcome is set:
 * - after a view of one account was followed by a view of another (signed out included), nothing is
 *   decided any more for this screen (the shell closes it on the account change). Signing in from
 *   signed out (null → A) is not such a change;
 * - a signed-out view: [LocalDetailOutcome.Gone];
 * - otherwise [lookup] under the session snapshot of the view's account (if the session already
 *   moved on, the next view decides): an item → MovedTo, none → RemovedOnServer (D19), a failure → Gone.
 * An outcome is final: later views are ignored. While a delete runs, a view without the row decides
 * nothing (the delete's own answer does).
 *
 * [close] cancels everything, is idempotent, and later intents are ignored.
 */
class LocalSubmissionDetailPresenter internal constructor(
    private val view: StateFlow<SubmissionView?>,
    private val lookup: suspend (SessionSnapshot, String) -> ClientResult<WishlistItem?>,
    private val delete: suspend (String) -> ClientResult<Unit>,
    private val session: AuthSession,
    private val clock: Clock,
    private val utcOffsetSeconds: (Instant) -> Int,
    dispatcher: CoroutineDispatcher,
) : Presenter {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher.limitedParallelism(1))
    private val mutableState = MutableStateFlow(LocalDetailState.Initial)

    /** Read-only and thread-safe; platform owners collect it and render on the main thread. */
    val state: StateFlow<LocalDetailState> = mutableState.asStateFlow()

    // Bumped by load, tick and the end of a delete: re-evaluates the current view.
    private val recompute = MutableStateFlow(0)

    // Confined to the lane.
    private var submissionId: String? = null
    private var lastView: SubmissionView? = null
    private var leftAccount = false

    init {
        scope.launch {
            combine(view, recompute) { current, _ -> current }.collect { evaluate(it) }
        }
    }

    /** Shows the local link [submissionId]; a new id starts over. Returns at once. */
    fun load(submissionId: String) {
        scope.launch {
            this@LocalSubmissionDetailPresenter.submissionId = canonical(submissionId)
            lastView = null
            leftAccount = false
            mutableState.value = LocalDetailState.Initial
            recompute.update { it + 1 }
        }
    }

    /**
     * Deletes the link (signed out too). Ignored unless [LocalDetailState.canDelete] and no delete is
     * running. A row that started sending meanwhile only clears [LocalDetailState.deleting] (its
     * MovedTo follows); any other failure also sets [LocalDetailState.deleteFailed].
     */
    fun delete() {
        scope.launch {
            val id = submissionId ?: return@launch
            val current = mutableState.value
            if (!current.canDelete || current.deleting || current.outcome != null) return@launch
            mutableState.value = current.copy(deleting = true, deleteFailed = false)
            val result = delete(id)
            if (submissionId != id) return@launch
            mutableState.update { state ->
                when {
                    result is ClientResult.Success -> state.copy(deleting = false, outcome = state.outcome ?: LocalDetailOutcome.Deleted)
                    result is ClientResult.Failure && result.error.kind == ErrorKind.CONFLICT &&
                        result.error.code == SqlLocalStore.SUBMISSION_IN_FLIGHT -> state.copy(deleting = false)
                    else -> state.copy(deleting = false, deleteFailed = true)
                }
            }
            // A view that arrived during the delete may still need a decision.
            recompute.update { it + 1 }
        }
    }

    /** Recomputes the relative saved time without new data. Returns at once. */
    fun tick() {
        recompute.update { it + 1 }
    }

    override fun close() {
        scope.cancel()
    }

    private suspend fun evaluate(current: SubmissionView?) {
        val id = submissionId ?: return
        // Before ready the store's rows are unknown: no row, no outcome.
        if (current == null || mutableState.value.outcome != null) return
        val previous = lastView
        val isNewView = current != previous
        lastView = current
        if (previous?.accountId != null && previous.accountId != current.accountId) leftAccount = true

        val shown = current.local.firstOrNull { canonical(it.clientSubmissionId) == id }
        mutableState.update { state ->
            val failed = state.deleteFailed && !isNewView
            if (shown != null) {
                state.copy(
                    row = shown.toRow(signedIn = current.accountId != null),
                    canDelete = shown.submissionStatus != SubmissionStatus.SUBMITTING,
                    deleteFailed = failed,
                )
            } else {
                state.copy(canDelete = false, deleteFailed = failed)
            }
        }
        if (shown != null || leftAccount || mutableState.value.deleting) return

        if (current.accountId == null) {
            decide(LocalDetailOutcome.Gone)
            return
        }
        val snapshot = session.state.value
        if (snapshot.accountId != current.accountId) return // the next view decides
        val outcome = when (val found = lookup(snapshot, id)) {
            is ClientResult.Success -> found.value?.let { LocalDetailOutcome.MovedTo(it.id) } ?: LocalDetailOutcome.RemovedOnServer
            is ClientResult.Failure -> LocalDetailOutcome.Gone
        }
        if (submissionId == id) decide(outcome)
    }

    private fun decide(outcome: LocalDetailOutcome) {
        mutableState.update { if (it.outcome == null) it.copy(outcome = outcome, canDelete = false) else it }
    }

    private fun LocalSubmission.toRow(signedIn: Boolean) = LocalDetailRow(
        submissionId = clientSubmissionId,
        host = DisplayFormat.host(sourceUrl),
        sourceUrl = sourceUrl,
        savedAt = DisplayFormat.relative(sharedAt, clock.now(), utcOffsetSeconds),
        status = if (signedIn) rowStatus() else RowStatus.LOCAL_ONLY,
    )

    private fun canonical(id: String) = canonicalUuidOrNull(id) ?: id
}
