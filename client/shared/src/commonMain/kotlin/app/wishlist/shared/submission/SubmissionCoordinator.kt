package app.wishlist.shared.submission

import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.Clock
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.IdGenerator
import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.core.canonicalUuidOrNull
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/** A send or refresh step threw instead of returning a typed result. */
internal const val SUBMISSION_STEP_FAILURE = "SUBMISSION_STEP_FAILURE"

/**
 * Owns sending local shares (C3-D6). Platforms only signal; every trigger lands in one consumer
 * coroutine, so at most one flush runs at a time and requests arriving meanwhile are coalesced
 * into a single rerun. A flush reads the session once and passes that snapshot to every store
 * call: binding unbound rows is committed (prepareFlush) before the first POST, each row keeps its
 * key across resends, and the C3-D8 error table decides what a failure does. A 429 holds every row
 * of that account until its Retry-After; a server-side failure is resent by any trigger. A timer
 * resends once the earliest recorded wait has passed, so a row never waits for a trigger that may not come.
 * Every failure becomes a typed outcome; nothing thrown here escapes into the runtime scope.
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
    // one publisher runs them at most every PUBLISH_INTERVAL, and requests arriving meanwhile
    // collapse into one more run, so a long flush recomputes the view a bounded number of times.
    private val publishRequests = Channel<Unit>(Channel.CONFLATED)
    private val viewLock = Mutex()
    private val mutableView = MutableStateFlow<SubmissionView?>(null)

    /** Null until the first view after ready (the store's rows are not known before it). */
    val view: StateFlow<SubmissionView?> = mutableView.asStateFlow()
    private val refreshRuns = MutableStateFlow(0L)

    /**
     * How many refresh runs (any trigger, signed out too) have finished. A run that changed no data
     * republishes an equal [view], which a StateFlow drops; the home list also follows this
     * revision so its relative times are recomputed after every refresh.
     */
    internal val refreshes: StateFlow<Long> = refreshRuns.asStateFlow()

    // Set by requestFlush, cleared when a flush starts: a refresh's ITEM-03 lookups step aside for it.
    private val flushWanted = MutableStateFlow(false)

    // The wake-up for the earliest future retryAfter; replaced after every flush (consumer only).
    private var retryTimer: Job? = null

    // Server-side failures in a row (consumer only); any accepted send resets it. Drives the backoff.
    private var serverFailures = 0
    private var backoffAccount: String? = null

    init {
        scope.launch(dispatcher) { consumeRequests() }.invokeOnCompletion {
            // Closed runtime: refuse new requests and release anyone still waiting.
            requests.close()
            while (true) requests.tryReceive().getOrNull()?.done?.complete(Unit) ?: break
        }
        scope.launch(dispatcher) {
            for (signal in publishRequests) {
                guarded(Unit) { publishView() }
                delay(PUBLISH_INTERVAL)
            }
        }
        scope.launch(dispatcher) {
            combine(session.state, ready) { _, _ -> }.collect { requestPublish() }
        }
    }

    /**
     * Android share: saves the link, then returns the card. Waits for ready at most 1500ms (the card's
     * moment). When the store is not ready by then or the save fails, the share goes to [defer] as an
     * unbound inbox record the app imports later ([DEFERRED]); only if that fails too is it lost.
     */
    suspend fun receiveShared(
        text: String?,
        online: Boolean,
        defer: (InboxRecord) -> Boolean = { false },
    ): ShareCardKind = withContext(dispatcher) {
        val parsed = ShareTextParser.parse(text) as? ParsedShare.Link ?: return@withContext ShareCardKind.INVALID
        // One key and time for the share, whichever way it is kept: a save that committed and then
        // reported a failure re-imports as the same row (a no-op), never a second one.
        val key = guarded<String?>(null) { ids.newId() } ?: return@withContext ShareCardKind.STORE_FAILED
        val sharedAt = clock.now()
        suspend fun deferred(): ShareCardKind {
            val record = InboxRecord(key, parsed.url, sharedAt.toString(), null)
            return if (guarded(false) { defer(record) }) ShareCardKind.DEFERRED else ShareCardKind.STORE_FAILED
        }
        if (!awaitReady()) return@withContext deferred()
        val saved = guarded<ClientResult<String?>>(STEP_FAILURE) { saveShare(key, parsed.url, sharedAt) }
        if (saved !is ClientResult.Success) return@withContext deferred()
        val binding = saved.value
        guarded(Unit) { publishView() }
        when {
            binding == null -> ShareCardKind.LOCAL
            online -> ShareCardKind.SAVED.also { requestFlush() }
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

    /**
     * Deletes a local row that has not been sent (signed out too) and publishes the view at once.
     * A row already SUBMITTING is CONFLICT/SUBMISSION_IN_FLIGHT; its send goes on.
     */
    suspend fun deleteLocal(submissionId: String): ClientResult<Unit> = withContext(dispatcher) {
        val deleted = guarded(STEP_FAILURE) { store.deleteSubmission(session.state.value, submissionId) }
        if (deleted is ClientResult.Success) guarded(Unit) { publishView() }
        deleted
    }

    /**
     * Fire-and-forget (launch, network restored, sign-in, a share, a retry timer). While a flush runs,
     * this only marks one rerun; during a refresh's lookups it runs before the next lookup.
     */
    fun requestFlush() {
        flushWanted.value = true
        requests.trySend(Request(refresh = false, done = null))
    }

    /**
     * flush → (DEBUG analysis step) → ITEM-03 for each cached PROCESSING item → view. Launch,
     * foreground and pull to refresh use it; pull to refresh awaits it. Coalesced like
     * [requestFlush]: concurrent calls share one run started after them.
     */
    suspend fun refresh() {
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
            // Only flush requests, and a flush already ran after they were made (a refresh's lookups
            // step aside for them): running again would resend every row just tried.
            if (!refresh && !flushWanted.value) {
                batch.forEach { it.done?.complete(Unit) }
                continue
            }
            try {
                guarded(Unit) { if (refresh) refreshOnce() else flushOnce() }
            } finally {
                if (refresh) refreshRuns.update { it + 1 }
                batch.forEach { it.done?.complete(Unit) }
            }
        }
    }

    /** [finalPublish] false: a refresh publishes once at its own end, so its flushes only ask for one. */
    private suspend fun flushOnce(finalPublish: Boolean = true) {
        flushWanted.value = false
        val snapshot = session.state.value
        if (snapshot.accountId == null) return if (finalPublish) publishView() else requestPublish()
        if (snapshot.accountId != backoffAccount) {
            backoffAccount = snapshot.accountId
            serverFailures = 0 // another account's failures say nothing about this one's
        }
        val retry = RetryWindow(clock)
        // A flush that could not read the queue knows no waits: it leaves the current timer as it is.
        var queueRead = false
        try {
            // Resets stale SUBMITTING rows and binds unbound ones in one commit, before any POST.
            val queue = (store.prepareFlush(snapshot) as? ClientResult.Success)?.value ?: return
            queueRead = true
            requestPublish()
            val now = clock.now()
            // Every recorded wait counts, also of rows this flush stops before (a NETWORK stop).
            queue.filter { it.submissionStatus == SubmissionStatus.PENDING }.mapNotNull { it.retryAfter }.forEach(retry::note)
            // 429 is the server pausing this account, not one row: nothing is sent until it ends.
            val throttledUntil = queue
                .filter { it.submissionStatus == SubmissionStatus.PENDING && it.lastSubmissionError?.kind == ErrorKind.RATE_LIMITED }
                .mapNotNull { it.retryAfter }
                .filter { it > now }
                .maxOrNull()
            if (throttledUntil != null) return retry.note(throttledUntil)
            // Any other retryAfter (a server-side failure) only sets the timer: every trigger still resends.
            for (row in queue) {
                if (row.submissionStatus != SubmissionStatus.PENDING) continue
                if (session.state.value != snapshot || !send(snapshot, row, retry)) return
            }
        } finally {
            if (queueRead) scheduleRetry(retry.earliest)
            // The final state, before the flush (and a refresh awaiting it) returns.
            if (finalPublish) publishView() else requestPublish()
        }
    }

    /** The earliest future retryAfter met during one flush; a time not after now is ignored (no busy loop). */
    private class RetryWindow(private val clock: Clock) {
        var earliest: Instant? = null
            private set

        /** Server-side failures of ITEM-01 in this flush: the second one stops it (see recordFailure). */
        var serverFailures = 0

        fun note(at: Instant) {
            if (at <= clock.now()) return
            earliest = earliest?.let { minOf(it, at) } ?: at
        }
    }

    /** Replaces the wake-up: a flush at [at], so a wait that passed while the app stays open still resends. */
    private fun scheduleRetry(at: Instant?) {
        retryTimer?.cancel()
        retryTimer = at?.let {
            scope.launch(dispatcher) {
                delay(it - clock.now())
                requestFlush()
            }
        }
    }

    /** Sends one row with its own key; false stops this flush. A row deleted since the queue was read is skipped. */
    private suspend fun send(snapshot: SessionSnapshot, row: LocalSubmission, retry: RetryWindow): Boolean {
        val id = row.clientSubmissionId
        when (val marked = store.markSubmission(snapshot, id, SubmissionStatus.SUBMITTING, null, null)) {
            is ClientResult.Failure -> return marked.error.kind == ErrorKind.NOT_FOUND // deleted since the queue was read
            is ClientResult.Success -> Unit
        }
        // Shown as sending without suspending here (a coalesced publish); ITEM-01 itself is bound to the
        // flush snapshot, so an account change at any point is SESSION_CHANGED and never a POST for the other account.
        requestPublish()
        val command = CreateItemCommand(id, row.sourceUrl, row.sharedAt)
        return when (val result = guarded(STEP_FAILURE) { create.create(command, snapshot) }) {
            is ClientResult.Success -> accept(snapshot, id, result, retry)
            is ClientResult.Failure -> recordFailure(snapshot, id, result.error, retry, fromServer = true)
        }
    }

    private suspend fun accept(
        snapshot: SessionSnapshot, id: String, result: ClientResult.Success<WishlistItem>, retry: RetryWindow,
    ): Boolean {
        // Under viewLock: accept moves the row from the queue to the cache in one commit, and a view
        // reads both, so a view never sees the row in both (or in neither).
        val failed = viewLock.withLock { store.accept(snapshot, id, result.value) } as? ClientResult.Failure
        if (failed == null) {
            serverFailures = 0
            return true
        }
        // SESSION_CHANGED: the response is dropped and the row stays SUBMITTING with its binding;
        // that account's next prepareFlush resets it. Otherwise the store is suspect: record and stop.
        if (failed.error.kind != ErrorKind.SESSION_CHANGED) recordFailure(snapshot, id, failed.error, retry, fromServer = false)
        return false
    }

    /**
     * Applies C3-D8 to the row; false stops this flush. [fromServer]: an ITEM-01 failure (a local
     * accept failure neither counts toward the backoff nor takes part in the stop rule). A server-side
     * failure stops the flush only when it is the second in this flush: one bad row (a URL the server
     * always fails on) does not hold back the rows after it, and a server that is down still costs at
     * most two requests per attempt.
     */
    private suspend fun recordFailure(
        snapshot: SessionSnapshot, id: String, error: ClientError, retry: RetryWindow, fromServer: Boolean,
    ): Boolean {
        val serverSide = fromServer && SubmissionErrorPolicy.isServerSide(error.kind)
        if (serverSide) {
            serverFailures++
            retry.serverFailures++
        }
        val decision = SubmissionErrorPolicy.decide(error, clock.now(), serverFailures.coerceAtLeast(1))
        val marked = store.markSubmission(snapshot, id, decision.status, error, decision.retryAfter)
        if (marked is ClientResult.Success) decision.retryAfter?.let(retry::note)
        val stop = if (serverSide) retry.serverFailures >= 2 else decision.stopFlush
        return marked is ClientResult.Success && !stop
    }

    private suspend fun refreshOnce() {
        flushOnce(finalPublish = false)
        guarded(Unit) { beforeRefresh() }
        val snapshot = session.state.value
        if (snapshot.accountId != null) {
            val items = (store.processingItems(snapshot) as? ClientResult.Success)?.value.orEmpty()
            for (item in items) {
                // A share or a network return waits at most one lookup, not the whole list.
                if (flushWanted.value) flushOnce(finalPublish = false)
                if (session.state.value != snapshot) break
                guarded(STEP_FAILURE) { get.get(item.id) } // the cache decorator stores the result
            }
        }
        publishView()
    }

    private fun requestPublish() {
        publishRequests.trySend(Unit)
    }

    /** Asks for a view republish (the detail Presenter calls it after each successful load). */
    internal fun requestViewPublish() = requestPublish()

    /**
     * Recomputes the view for the current session; the one place that orders it (see [SubmissionView]):
     * local rows keep the store's (sharedAt, key) order, processing items are sorted by (createdAt, id).
     * Lock order: viewLock → session gate (here only to publish; [accept] takes the same order).
     */
    private suspend fun publishView(): Unit = viewLock.withLock {
        // Before ready the store's rows are unknown: an empty view then would read as "nothing saved".
        if (!ready.value) return@withLock
        val snapshot = session.state.value
        val previous = mutableView.value?.takeIf { it.accountId == snapshot.accountId }
        val local = (store.pending() as? ClientResult.Success)?.value
        val processing = when (snapshot.accountId) {
            null -> emptyList()
            else -> (store.processingItems(snapshot) as? ClientResult.Success)?.value
                ?.sortedWith(compareBy({ it.createdAt }, { it.id }))
        }
        // Published inside the session gate, so it cannot land after a newer account is current;
        // a stale snapshot publishes nothing (the newer session publishes its own view). A failed read
        // keeps the same account's previous list (another account's is never shown).
        session.withCurrent(snapshot) {
            mutableView.value = SubmissionView(
                accountId = snapshot.accountId,
                local = local ?: previous?.local.orEmpty(),
                processing = processing ?: previous?.processing.orEmpty(),
            )
            ClientResult.Success(Unit)
        }
        Unit
    }

    /** Saves [key] under the current account; the value is the binding used. */
    private suspend fun saveShare(key: String, url: String, sharedAt: Instant): ClientResult<String?> {
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
        if (canonicalUuidOrNull(clientSubmissionId) == null) return null
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
        val PUBLISH_INTERVAL = 200.milliseconds
        val STEP_FAILURE = ClientResult.Failure(ClientError(ErrorKind.UNAVAILABLE, SUBMISSION_STEP_FAILURE))
    }
}
