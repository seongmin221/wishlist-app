@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.wishlist.shared.submission

import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.Clock
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.IdGenerator
import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.data.local.SqlLocalStore.Companion.ACCOUNT_BINDING_MISMATCH
import app.wishlist.shared.domain.ParsedShare
import app.wishlist.shared.domain.ShareTextParser
import app.wishlist.shared.model.LocalSubmission
import app.wishlist.shared.model.SubmissionStatus
import app.wishlist.shared.model.WishlistItem
import app.wishlist.shared.repository.CreateItemCommand
import app.wishlist.shared.repository.GetItemRepository
import app.wishlist.shared.repository.LocalStore
import app.wishlist.shared.repository.SnapshotCreateItemRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** A send or refresh step threw instead of returning a typed result. */
internal const val SUBMISSION_STEP_FAILURE = "SUBMISSION_STEP_FAILURE"

/**
 * Owns sending local shares (C3-D6). Platforms only signal; every trigger lands in one consumer
 * coroutine, so at most one flush runs at a time and requests arriving meanwhile are coalesced
 * into a single rerun. A flush reads the session once and passes that snapshot to every store
 * call: binding unbound rows is committed (prepareFlush) before the first POST, each row keeps its
 * key across resends, and the C3-D8 error table decides what a failure does. Every
 * failure becomes a typed outcome; nothing thrown here escapes into the runtime scope.
 * Obtained from `SharedRuntime.submissions()`; after the runtime closes it is inert.
 */
class SubmissionCoordinator internal constructor(
    private val store: LocalStore,
    private val create: SnapshotCreateItemRepository,
    private val get: GetItemRepository,
    private val session: AuthSession,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val scope: CoroutineScope,
    private val ready: StateFlow<Boolean>,
    private val beforeRefresh: suspend () -> Unit = {},
    private val dispatcher: CoroutineDispatcher,
) {
    private class Request(val refresh: Boolean, val done: CompletableDeferred<Unit>?)

    private val requests = Channel<Request>(Channel.UNLIMITED)

    // Intermediate view updates (a row marked SUBMITTING, a session change) only ask for a publish;
    // one publisher runs them, and requests arriving during a run collapse into one more run.
    private val publishRequests = Channel<Unit>(Channel.CONFLATED)
    private val viewLock = Mutex()
    private val mutableView = MutableStateFlow(SubmissionView(null, emptyList(), emptyList(), flushing = false))
    val view: StateFlow<SubmissionView> = mutableView.asStateFlow()
    private val refreshRuns = MutableStateFlow(0L)

    /**
     * How many refresh runs (any trigger, signed out too) have finished. A run that changed no data
     * republishes an equal [view], which a StateFlow drops; the home list also follows this
     * revision so its relative times are recomputed after every refresh.
     */
    internal val refreshes: StateFlow<Long> = refreshRuns.asStateFlow()

    init {
        scope.launch(dispatcher) { consumeRequests() }.invokeOnCompletion {
            // Closed runtime: refuse new requests and release anyone still waiting.
            requests.close()
            while (true) requests.tryReceive().getOrNull()?.done?.complete(Unit) ?: break
        }
        scope.launch(dispatcher) {
            for (signal in publishRequests) guarded(Unit) { publishView() }
        }
        scope.launch(dispatcher) {
            combine(session.state, ready) { _, _ -> }.collect { requestPublish() }
        }
    }

    /** Android share: saves the link, then returns the card. Waits for ready at most 1500ms. */
    suspend fun receiveShared(text: String?, online: Boolean): ShareCardKind = withContext(dispatcher) {
        val parsed = ShareTextParser.parse(text) as? ParsedShare.Link ?: return@withContext ShareCardKind.INVALID
        if (!awaitReady()) return@withContext ShareCardKind.STORE_FAILED
        val saved = guarded<ClientResult<String?>>(STEP_FAILURE) { saveShare(parsed.url) }
        if (saved !is ClientResult.Success) return@withContext ShareCardKind.STORE_FAILED
        val binding = saved.value
        guarded(Unit) { publishView() }
        when {
            binding == null -> ShareCardKind.LOCAL
            online -> ShareCardKind.SAVED.also { requestFlush(FlushTrigger.SHARE_RECEIVED) }
            else -> ShareCardKind.OFFLINE
        }
    }

    /**
     * iOS inbox: imports each record with its share-time binding. Idempotent per key. Waits for
     * ready without a time limit (a slow DEBUG cold start must not defer the whole inbox); if the
     * runtime closes first, every readable record is retained.
     */
    suspend fun importInbox(records: List<InboxRecord>): InboxImportResult = withContext(dispatcher) {
        val deletable = mutableListOf<String>()
        val retained = mutableListOf<String>()
        val storeReady = awaitReady(Duration.INFINITE)
        for (record in records) {
            val submission = record.toSubmissionOrNull()
            val keep = when {
                submission == null -> false // permanently unreadable
                !storeReady -> true
                else -> when (val imported = guarded(STEP_FAILURE) { store.importSubmission(submission) }) {
                    is ClientResult.Success -> false
                    // Same key, another URL: the original row stays; this record is a stale duplicate.
                    is ClientResult.Failure -> imported.error.kind != ErrorKind.CONFLICT
                }
            }
            if (keep) retained += record.clientSubmissionId else deletable += record.clientSubmissionId
        }
        guarded(Unit) { publishView() }
        InboxImportResult(deletable, retained)
    }

    /** Fire-and-forget. While a flush runs, this only marks one rerun. */
    fun requestFlush(trigger: FlushTrigger) {
        requests.trySend(Request(refresh = false, done = null))
    }

    /**
     * flush → (DEBUG analysis step) → ITEM-03 for each cached PROCESSING item → view. Pull to refresh
     * awaits it. Coalesced like [requestFlush]: concurrent calls share one run started after them.
     */
    suspend fun refresh(trigger: FlushTrigger) {
        val done = CompletableDeferred<Unit>()
        if (requests.trySend(Request(refresh = true, done = done)).isFailure) return
        done.await()
    }

    private suspend fun consumeRequests() {
        while (true) {
            // Wait before taking a request: until then requests stay in the channel, where a close
            // releases them (a request held here would never be completed).
            ready.first { it }
            val batch = mutableListOf(requests.receive())
            while (true) batch += requests.tryReceive().getOrNull() ?: break
            val refresh = batch.any { it.refresh }
            try {
                guarded(Unit) { if (refresh) refreshOnce() else flushOnce() }
            } finally {
                if (refresh) refreshRuns.update { it + 1 }
                batch.forEach { it.done?.complete(Unit) }
            }
        }
    }

    private suspend fun flushOnce() {
        val snapshot = session.state.value
        if (snapshot.accountId == null) return publishView()
        mutableView.update { it.copy(flushing = true) }
        try {
            // Resets stale SUBMITTING rows and binds unbound ones in one commit, before any POST.
            val queue = (store.prepareFlush(snapshot) as? ClientResult.Success)?.value ?: return
            requestPublish()
            val now = clock.now()
            for (row in queue) {
                if (row.submissionStatus != SubmissionStatus.PENDING) continue
                if (row.retryAfter?.let { it > now } == true) continue
                if (session.state.value != snapshot || !send(snapshot, row)) return
            }
        } finally {
            mutableView.update { it.copy(flushing = false) }
            publishView() // the final state, before the flush (and a refresh awaiting it) returns
        }
    }

    /** Sends one row with its own key; false stops this flush. */
    private suspend fun send(snapshot: SessionSnapshot, row: LocalSubmission): Boolean {
        val id = row.clientSubmissionId
        if (store.markSubmission(snapshot, id, SubmissionStatus.SUBMITTING, null, null) is ClientResult.Failure) return false
        // Shown as sending without suspending here (a coalesced publish); ITEM-01 itself is bound to the
        // flush snapshot, so an account change at any point is SESSION_CHANGED and never a POST for the other account.
        requestPublish()
        val command = CreateItemCommand(id, row.sourceUrl, row.sharedAt)
        return when (val result = guarded(STEP_FAILURE) { create.create(command, snapshot) }) {
            is ClientResult.Success -> accept(snapshot, id, result)
            is ClientResult.Failure -> recordFailure(snapshot, id, result.error)
        }
    }

    private suspend fun accept(snapshot: SessionSnapshot, id: String, result: ClientResult.Success<WishlistItem>): Boolean {
        val failed = store.accept(snapshot, id, result.value) as? ClientResult.Failure ?: return true
        // SESSION_CHANGED: the response is dropped and the row stays SUBMITTING with its binding;
        // that account's next prepareFlush resets it. Otherwise the store is suspect: record and stop.
        if (failed.error.kind != ErrorKind.SESSION_CHANGED) recordFailure(snapshot, id, failed.error)
        return false
    }

    /** Applies C3-D8 to the row; false stops this flush. */
    private suspend fun recordFailure(snapshot: SessionSnapshot, id: String, error: ClientError): Boolean {
        val decision = SubmissionErrorPolicy.decide(error, clock.now())
        val marked = store.markSubmission(snapshot, id, decision.status, error, decision.retryAfter)
        return marked is ClientResult.Success && !decision.stopFlush
    }

    private suspend fun refreshOnce() {
        flushOnce()
        guarded(Unit) { beforeRefresh() }
        val snapshot = session.state.value
        if (snapshot.accountId != null) {
            val items = (store.processingItems(snapshot) as? ClientResult.Success)?.value.orEmpty()
            for (item in items) {
                if (session.state.value != snapshot) break
                guarded(STEP_FAILURE) { get.get(item.id) } // the cache decorator stores the result
            }
        }
        publishView()
    }

    private fun requestPublish() {
        publishRequests.trySend(Unit)
    }

    /**
     * Recomputes the view for the current session; the one place that orders it (see [SubmissionView]):
     * local rows keep the store's (sharedAt, key) order, processing items are sorted by (createdAt, id).
     * Lock order: viewLock → session gate (held only to publish).
     */
    private suspend fun publishView(): Unit = viewLock.withLock {
        val snapshot = session.state.value
        val previous = mutableView.value
        val sameAccount = previous.accountId == snapshot.accountId
        val local = if (ready.value) (store.pending() as? ClientResult.Success)?.value else null
        val processing = when {
            snapshot.accountId == null -> emptyList()
            !ready.value -> null
            else -> (store.processingItems(snapshot) as? ClientResult.Success)?.value
                ?.sortedWith(compareBy({ it.createdAt }, { it.id }))
        }
        // Published inside the session gate, so it cannot land after a newer account is current;
        // a stale snapshot publishes nothing (the newer session publishes its own view).
        session.withCurrent(snapshot) {
            mutableView.update {
                it.copy(
                    accountId = snapshot.accountId,
                    local = local ?: if (sameAccount) it.local else emptyList(),
                    processing = processing ?: if (sameAccount) it.processing else emptyList(),
                )
            }
            ClientResult.Success(Unit)
        }
        Unit
    }

    /** Saves a new key under the current account; the value is the binding used. */
    private suspend fun saveShare(url: String): ClientResult<String?> {
        val key = ids.newId()
        val sharedAt = clock.now()
        var saved: ClientResult<Unit>
        var binding: String?
        var attempts = 0
        do {
            // An account change between reading the binding and committing is retried once.
            binding = session.state.value.accountId
            saved = store.saveSubmission(LocalSubmission(key, url, sharedAt, binding))
        } while (++attempts < 2 && saved is ClientResult.Failure && saved.error.isAccountRace())
        return if (saved is ClientResult.Failure) saved else ClientResult.Success(binding)
    }

    /**
     * True once ready within [limit] (no limit when infinite); false at the limit or as soon as the
     * coordinator's scope closes. Waited in that scope, so closing the runtime ends the wait.
     */
    private suspend fun awaitReady(limit: Duration = READY_WAIT): Boolean {
        if (!scope.isActive) return false
        if (ready.value) return true
        val waiter = scope.async { ready.first { it } }
        return try {
            when {
                limit.isInfinite() -> waiter.await()
                else -> withTimeoutOrNull(limit) { waiter.await() }
            } != null
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            false // the scope closed
        } finally {
            waiter.cancel()
        }
    }

    private fun ClientError.isAccountRace() =
        kind == ErrorKind.SESSION_CHANGED || code == ACCOUNT_BINDING_MISMATCH

    private fun InboxRecord.toSubmissionOrNull(): LocalSubmission? {
        if (runCatching { Uuid.parse(clientSubmissionId) }.isFailure) return null
        val sharedAt = runCatching { Instant.parse(sharedAtIso) }.getOrNull() ?: return null
        val link = ShareTextParser.parse(sourceUrl) as? ParsedShare.Link ?: return null
        return LocalSubmission(clientSubmissionId, link.url, sharedAt, accountBinding?.takeIf { it.isNotBlank() })
    }

    /**
     * Runs [block]; an exception becomes [fallback] instead of escaping. Cancellation of this
     * coroutine propagates; a stray CancellationException while still active is just a failure.
     */
    private suspend inline fun <T> guarded(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        fallback
    } catch (e: Exception) {
        fallback
    }

    private companion object {
        val READY_WAIT = 1500.milliseconds
        val STEP_FAILURE = ClientResult.Failure(ClientError(ErrorKind.UNAVAILABLE, SUBMISSION_STEP_FAILURE))
    }
}
