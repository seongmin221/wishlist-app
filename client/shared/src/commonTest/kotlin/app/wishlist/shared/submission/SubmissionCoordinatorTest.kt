@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.wishlist.shared.submission

import app.wishlist.shared.core.AuthProvider
import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.Clock
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.IdGenerator
import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.data.fake.AnalysisOutcome
import app.wishlist.shared.data.fake.FakeAuthFacade
import app.wishlist.shared.data.fake.FakeItemRepository
import app.wishlist.shared.data.fake.FakeStore
import app.wishlist.shared.data.fake.error
import app.wishlist.shared.data.fake.successValue
import app.wishlist.shared.data.local.CachedGetItemRepository
import app.wishlist.shared.data.local.LazyDriver
import app.wishlist.shared.data.local.SqlLocalStore
import app.wishlist.shared.repository.SUBMISSION_IN_FLIGHT
import app.wishlist.shared.data.local.StoreHarness
import app.wishlist.shared.data.local.UUID_A
import app.wishlist.shared.data.local.UUID_B
import app.wishlist.shared.data.local.UUID_C
import app.wishlist.shared.data.local.submission as storedSubmission
import app.wishlist.shared.data.local.submissionId as storedSubmissionId
import app.wishlist.shared.data.local.withHarness
import app.wishlist.shared.model.AnalysisStatus
import app.wishlist.shared.model.CategoryMissingReason
import app.wishlist.shared.model.LocalSubmission
import app.wishlist.shared.model.SubmissionStatus
import app.wishlist.shared.model.WishlistItem
import app.wishlist.shared.model.itemFixture
import app.wishlist.shared.repository.CreateItemCommand
import app.wishlist.shared.repository.GetItemRepository
import app.wishlist.shared.repository.SnapshotCreateItemRepository
import app.wishlist.shared.repository.LocalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val GOOGLE_ID = "fake-google-0001"
private const val APPLE_ID = "fake-apple-0001"
private const val LINK = "https://shop.example/p/1"
private const val SHARED_ISO = "2026-10-06T23:00:00.123456Z"
private val baseTime = Instant.parse("2026-10-07T00:00:00Z")

// LocalStore fixtures deliberately contain surrounding whitespace to test raw persistence.
// Coordinator fixtures go through creation validation, so their default URL must be admissible.
private fun submission(
    id: String = storedSubmissionId,
    binding: String? = null,
    status: SubmissionStatus = SubmissionStatus.PENDING,
    url: String = LINK,
    sharedAt: Instant = baseTime,
) = storedSubmission(id, binding, status, url, sharedAt)

/** One ITEM-01 call: its key and the account it was made for (the expected snapshot's). */
internal data class CreateCall(val key: String, val account: String?)

/** Scripted ITEM-01 over the real Fake backend: records calls and concurrency, replays scripted steps in order. */
internal class ScriptedCreate(
    private val real: SnapshotCreateItemRepository,
    private val session: AuthSession,
) : SnapshotCreateItemRepository {
    val calls = mutableListOf<CreateCall>()
    val results = mutableListOf<ClientResult<WishlistItem>>()
    var maxActive = 0
        private set
    private var active = 0

    /** Runs at the start of every call, before the scripted step (e.g. to inspect the store). */
    var onCall: suspend (CreateItemCommand) -> Unit = {}
    private val script = ArrayDeque<suspend (suspend () -> ClientResult<WishlistItem>) -> ClientResult<WishlistItem>>()

    /** The next call runs [step]; `real()` sends to the Fake backend. Unscripted calls go straight to it. */
    fun then(step: suspend (real: suspend () -> ClientResult<WishlistItem>) -> ClientResult<WishlistItem>) {
        script.addLast(step)
    }

    fun fail(error: ClientError) = then { ClientResult.Failure(error) }

    /** Drops the scripted steps not run yet: later calls go to the Fake backend. */
    fun clearScript() = script.clear()

    override suspend fun create(command: CreateItemCommand): ClientResult<WishlistItem> =
        create(command, session.state.value)

    override suspend fun create(command: CreateItemCommand, expected: SessionSnapshot): ClientResult<WishlistItem> {
        calls += CreateCall(command.submissionId, expected.accountId)
        active++
        maxActive = maxOf(maxActive, active)
        try {
            onCall(command)
            val step = script.removeFirstOrNull()
            val result = if (step == null) real.create(command, expected) else step { real.create(command, expected) }
            results += result
            return result
        } finally {
            active--
        }
    }
}

/** Counts flush runs (every flush with an account starts with exactly one prepareFlush); hooks marks. */
internal class CountingStore(private val delegate: LocalStore) : LocalStore by delegate {
    var prepareFlushCalls = 0
        private set

    /** View reads: one pending() and (signed in) one processingItems() per view computation. */
    var pendingCalls = 0
    var processingItemsCalls = 0

    /** Runs right after a markSubmission has committed (outside the session gate). */
    var afterMark: suspend (SubmissionStatus) -> Unit = {}

    /** Runs right after prepareFlush has read the queue, before the flush sees it. */
    var afterPrepareFlush: suspend (SessionSnapshot) -> Unit = {}

    override suspend fun prepareFlush(snapshot: SessionSnapshot): ClientResult<List<LocalSubmission>> {
        prepareFlushCalls++
        return delegate.prepareFlush(snapshot).also { afterPrepareFlush(snapshot) }
    }

    /** Runs right after every pending() read (a view computation's first read), before it returns. */
    var afterPending: suspend () -> Unit = {}

    override suspend fun pending(): ClientResult<List<LocalSubmission>> {
        pendingCalls++
        return delegate.pending().also { afterPending() }
    }

    override suspend fun processingItems(snapshot: SessionSnapshot): ClientResult<List<WishlistItem>> {
        processingItemsCalls++
        return delegate.processingItems(snapshot)
    }

    override suspend fun markSubmission(
        snapshot: SessionSnapshot, id: String, status: SubmissionStatus, error: ClientError?, retryAfter: Instant?,
    ): ClientResult<Unit> = delegate.markSubmission(snapshot, id, status, error, retryAfter).also { afterMark(status) }
}

/** Real SQLite store + scripted ITEM-01 over FakeStore + fake login, on virtual time. */
internal class CoordinatorHarness(
    private val test: TestScope,
    val storeHarness: StoreHarness,
    localStore: LocalStore,
    initiallyReady: Boolean,
) {
    val session = storeHarness.session
    val clock = Clock { baseTime + test.testScheduler.currentTime.milliseconds }
    private var idCount = 0
    val ids = IdGenerator { "00000000-0000-4000-8000-" + (++idCount).toString().padStart(12, '0') }
    val fakeStore = FakeStore(session, clock, ids)
    private val backend = FakeItemRepository(fakeStore)
    val create = ScriptedCreate(backend, session)
    val store = CountingStore(localStore)
    val ready = MutableStateFlow(initiallyReady)
    private val dispatcher = StandardTestDispatcher(test.testScheduler)
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    var beforeRefreshCalls = 0

    /** Runs before every ITEM-03 lookup of a refresh. */
    var beforeGet: suspend (String) -> Unit = {}
    private val lookups = object : GetItemRepository {
        override suspend fun get(id: String): ClientResult<WishlistItem> {
            beforeGet(id)
            return backend.get(id)
        }
    }
    val coordinator = newCoordinator()
    val auth = FakeAuthFacade(
        session = session,
        store = store,
        seed = { ClientResult.Success(Unit) },
        onSignedIn = { coordinator.requestFlush() },
    )

    /** A second coordinator over the same DB, like the next process after a crash. */
    fun newCoordinator() = SubmissionCoordinator(
        store = store,
        create = create,
        get = CachedGetItemRepository(lookups, store, session),
        session = session,
        clock = clock,
        ids = ids,
        scope = scope,
        ready = ready,
        beforeRefresh = { beforeRefreshCalls++ },
        dispatcher = dispatcher,
    )

    val view: SubmissionView get() = checkNotNull(coordinator.view.value) { "no view before ready" }

    suspend fun signIn(provider: AuthProvider = AuthProvider.GOOGLE) {
        auth.signIn(provider).successValue()
        test.advanceUntilIdle()
    }

    suspend fun signOut() {
        auth.signOut().successValue()
        test.advanceUntilIdle()
    }

    suspend fun share(text: String? = LINK, online: Boolean = true): ShareCardKind =
        coordinator.receiveShared(text, online).also { test.advanceUntilIdle() }

    suspend fun flush() {
        coordinator.requestFlush()
        test.advanceUntilIdle()
    }

    suspend fun pending(): List<LocalSubmission> = store.pending().successValue()
    fun keysSent(): List<String> = create.calls.map { it.key }
}

/** A store whose driver never opens: every call is UNAVAILABLE/LOCAL_STORE_FAILURE. */
private fun failingStore(h: StoreHarness): LocalStore =
    SqlLocalStore(h.session, LazyDriver(open = { throw IllegalStateException("disk gone") }, io = Dispatchers.Unconfined))

private fun runCoordinatorTest(
    ready: Boolean = true,
    store: (StoreHarness) -> LocalStore = { it.store },
    block: suspend TestScope.(CoordinatorHarness) -> Unit,
): TestResult = runTest {
    withHarness { storeHarness ->
        val h = CoordinatorHarness(this, storeHarness, store(storeHarness), ready)
        try {
            advanceUntilIdle()
            block(h)
        } finally {
            h.scope.cancel()
        }
    }
}

private fun record(key: String = UUID_A, url: String = LINK, sharedAtIso: String = SHARED_ISO, binding: String? = null) =
    InboxRecord(clientSubmissionId = key, sourceUrl = url, sharedAtIso = sharedAtIso, accountBinding = binding)

private fun Instant.micros(): Long = epochSeconds * 1_000_000 + nanosecondsOfSecond / 1_000

class SubmissionCoordinatorTest {
    // --- receiveShared: card kinds -----------------------------------------------------------

    @Test fun loggedOutShareIsLocalUnboundAndNotSent() = runCoordinatorTest { h ->
        val sharedAt = h.clock.now()
        assertEquals(ShareCardKind.LOCAL, h.share(online = true))
        val row = h.pending().single()
        assertEquals(LINK, row.sourceUrl)
        assertNull(row.accountBinding)
        assertEquals(sharedAt, row.sharedAt)
        assertEquals(SubmissionStatus.PENDING, row.submissionStatus)
        assertTrue(h.create.calls.isEmpty())
        assertNull(h.view.accountId)
        assertEquals(listOf(row), h.view.local)
    }

    @Test fun loggedInOnlineShareIsSavedAndSent() = runCoordinatorTest { h ->
        h.signIn()
        assertEquals(ShareCardKind.SAVED, h.share(online = true))
        assertEquals(1, h.create.calls.size)
        assertTrue(h.pending().isEmpty())
        val view = h.view
        assertEquals(GOOGLE_ID, view.accountId)
        assertTrue(view.local.isEmpty())
        assertEquals(LINK, view.processing.single().sourceUrl)
        assertEquals(AnalysisStatus.PROCESSING, view.processing.single().analysis.status)
    }

    @Test fun loggedInOfflineShareIsOfflineAndKeptPending() = runCoordinatorTest { h ->
        h.signIn()
        assertEquals(ShareCardKind.OFFLINE, h.share(online = false))
        val row = h.pending().single()
        assertEquals(GOOGLE_ID, row.accountBinding)
        assertEquals(SubmissionStatus.PENDING, row.submissionStatus)
        assertTrue(h.create.calls.isEmpty())
        assertEquals(listOf(row), h.view.local)
    }

    @Test fun invalidAndTooLongShareSaveNothing() = runCoordinatorTest { h ->
        h.signIn()
        assertEquals(ShareCardKind.INVALID, h.share("링크 없는 글이에요"))
        assertEquals(ShareCardKind.INVALID, h.share("https://a.example/" + "a".repeat(2048 - 17)))
        assertEquals(ShareCardKind.INVALID, h.share(null))
        assertTrue(h.pending().isEmpty())
        assertTrue(h.create.calls.isEmpty())
    }

    @Test fun storeFailureReturnsStoreFailed() = runCoordinatorTest(store = ::failingStore) { h ->
        assertEquals(ShareCardKind.STORE_FAILED, h.share())
        assertTrue(h.view.local.isEmpty())
    }

    @Test fun notReadyWaitsThenStoreFailedAfter1500ms() = runCoordinatorTest(ready = false) { h ->
        val result = async { h.coordinator.receiveShared(LINK, online = true) }
        advanceTimeBy(1_499)
        runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(ShareCardKind.STORE_FAILED, result.await())
        assertTrue(h.storeHarness.store.pending().successValue().isEmpty())
    }

    @Test fun readyWithinTheWaitStillSavesTheShare() = runCoordinatorTest(ready = false) { h ->
        val result = async { h.coordinator.receiveShared(LINK, online = true) }
        advanceTimeBy(1_000)
        h.ready.value = true
        advanceUntilIdle()
        assertEquals(ShareCardKind.LOCAL, result.await())
        assertEquals(1, h.pending().size)
    }

    // --- Review Focus 4: one key per share, single flight -------------------------------------

    @Test fun sameTextSharedTwiceCreatesTwoKeys() = runCoordinatorTest { h ->
        assertEquals(ShareCardKind.LOCAL, h.share())
        assertEquals(ShareCardKind.LOCAL, h.share())
        val keys = h.pending().map { it.clientSubmissionId }
        assertEquals(2, keys.toSet().size)
        h.signIn()
        assertEquals(keys.toSet(), h.keysSent().toSet())
        assertEquals(2, h.view.processing.map { it.id }.toSet().size)
    }

    @Test fun concurrentTriggersRunSingleFlightWithRerun() = runCoordinatorTest { h ->
        h.signIn()
        h.share(online = false)
        val runsBefore = h.store.prepareFlushCalls
        val gate = CompletableDeferred<Unit>()
        h.create.then { real -> gate.await(); real() }

        h.coordinator.requestFlush()
        runCurrent()
        assertEquals(1, h.create.calls.size)
        h.coordinator.requestFlush()
        h.coordinator.requestFlush()
        h.coordinator.requestFlush()
        runCurrent()
        assertEquals(1, h.create.calls.size)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, h.create.maxActive)
        assertEquals(1, h.create.calls.size)
        assertEquals(2, h.store.prepareFlushCalls - runsBefore) // the running flush + one rerun
        assertTrue(h.pending().isEmpty())
    }

    @Test fun concurrentRefreshesShareOneRerunAndAllReturn() = runCoordinatorTest { h ->
        h.signIn()
        h.share(online = false)
        val runsBefore = h.store.prepareFlushCalls
        val gate = CompletableDeferred<Unit>()
        h.create.then { real -> gate.await(); real() }
        h.coordinator.requestFlush()
        runCurrent()

        val refreshes = List(3) { launch { h.coordinator.refresh() } }
        h.coordinator.requestFlush()
        runCurrent()
        assertTrue(refreshes.none { it.isCompleted })

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(refreshes.all { it.isCompleted })
        assertEquals(2, h.store.prepareFlushCalls - runsBefore)
        assertEquals(1, h.beforeRefreshCalls)
        assertEquals(1, h.create.maxActive)
    }

    // --- Binding and order --------------------------------------------------------------------

    @Test fun bindingIsCommittedBeforeFirstPost() = runCoordinatorTest { h ->
        h.share()
        var bindingAtPost: String? = null
        var statusAtPost: String? = null
        h.create.onCall = { command ->
            val row = assertNotNull(h.storeHarness.row(command.submissionId))
            bindingAtPost = row.account_binding
            statusAtPost = row.status
        }
        h.signIn()
        assertEquals(GOOGLE_ID, bindingAtPost)
        assertEquals(SubmissionStatus.SUBMITTING.name, statusAtPost)
    }

    @Test fun signInSendsAllUnboundOldestFirst() = runCoordinatorTest { h ->
        // Insertion order B, A, C; key order A, B, C; share-time order C, A, B.
        h.store.saveSubmission(submission(id = UUID_B, sharedAt = baseTime + 2.seconds)).successValue()
        h.store.saveSubmission(submission(id = UUID_A, sharedAt = baseTime + 1.seconds)).successValue()
        h.store.saveSubmission(submission(id = UUID_C, sharedAt = baseTime)).successValue()
        h.signIn()
        assertEquals(listOf(UUID_C, UUID_A, UUID_B), h.keysSent())
        assertTrue(h.create.calls.all { it.account == GOOGLE_ID })
        assertTrue(h.pending().isEmpty())
    }

    // --- Review Focus 1: account switch while a POST is in flight -----------------------------

    @Test fun accountSwitchDuringPostKeepsBindingAndNeverSendsFromOtherOwner() = runCoordinatorTest { h ->
        h.signIn(AuthProvider.GOOGLE)
        val gate = CompletableDeferred<Unit>()
        // The server creates the item for A, but the response arrives after the switch.
        h.create.then { real -> real().also { gate.await() } }
        assertEquals(ShareCardKind.SAVED, h.share())
        val key = h.keysSent().single()

        h.signOut()
        h.signIn(AuthProvider.APPLE)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(CreateCall(key, GOOGLE_ID)), h.create.calls)
        val row = assertNotNull(h.storeHarness.row(key))
        assertEquals(GOOGLE_ID, row.account_binding)
        assertEquals(SubmissionStatus.SUBMITTING.name, row.status)
        assertEquals(APPLE_ID, h.view.accountId)
        assertTrue(h.view.local.isEmpty())
        assertTrue(h.view.processing.isEmpty())

        h.signOut()
        h.signIn(AuthProvider.GOOGLE)
        assertEquals(listOf(CreateCall(key, GOOGLE_ID), CreateCall(key, GOOGLE_ID)), h.create.calls)
        val ids = h.create.results.map { it.successValue().id }
        assertEquals(1, ids.toSet().size) // the resend is a replay of A's one item
        assertTrue(h.pending().isEmpty())
        assertEquals(ids.toSet(), h.view.processing.map { it.id }.toSet())
    }

    @Test fun accountSwitchBetweenSubmittingCommitAndPostNeverSendsToOtherOwner() = runCoordinatorTest { h ->
        h.signIn(AuthProvider.GOOGLE)
        h.share(online = false)
        val key = h.pending().single().clientSubmissionId
        // The account changes right after SUBMITTING is committed, before ITEM-01 is called.
        h.store.afterMark = { status ->
            if (status == SubmissionStatus.SUBMITTING) {
                h.store.afterMark = {}
                h.session.changeAccount(APPLE_ID)
            }
        }
        h.flush()

        // Nothing was created in Apple's namespace (the probe counts its PROCESSING items).
        val probe = AnalysisOutcome(AnalysisStatus.PARTIAL, null, null, CategoryMissingReason.EXTRACTION_UNRESOLVED, null)
        assertEquals(0, h.fakeStore.completeDueAnalyses(h.clock.now(), Duration.ZERO) { probe }.successValue())
        val row = assertNotNull(h.storeHarness.row(key))
        assertEquals(GOOGLE_ID, row.account_binding)

        h.session.changeAccount(GOOGLE_ID)
        h.flush()
        assertTrue(h.create.calls.all { it == CreateCall(key, GOOGLE_ID) })
        assertEquals(1, h.create.results.count { it is ClientResult.Success })
        assertTrue(h.pending().isEmpty())
        assertEquals(1, h.view.processing.size)
    }

    // --- Review Focus 2: process death ------------------------------------------------------

    @Test fun staleSubmittingIsResentWithSameKey() = runCoordinatorTest { h ->
        h.session.changeAccount(GOOGLE_ID)
        h.store.saveSubmission(submission(id = UUID_A, binding = GOOGLE_ID, status = SubmissionStatus.SUBMITTING)).successValue()
        // The next process: a fresh coordinator over the same database.
        val restarted = h.newCoordinator()
        restarted.requestFlush()
        advanceUntilIdle()
        assertEquals(listOf(UUID_A), h.keysSent())
        assertTrue(h.pending().isEmpty())
        assertEquals(1, restarted.view.value!!.processing.size)
    }

    @Test fun responseLossThenResendDoesNotDuplicate() = runCoordinatorTest { h ->
        h.signIn()
        var created: WishlistItem? = null
        h.create.then { real -> created = real().successValue(); ClientResult.Failure(ClientError(ErrorKind.NETWORK)) }
        h.share()
        val row = h.pending().single()
        assertEquals(SubmissionStatus.PENDING, row.submissionStatus)

        h.coordinator.refresh()
        assertEquals(listOf(row.clientSubmissionId, row.clientSubmissionId), h.keysSent())
        val item = assertNotNull(created)
        assertEquals(item.id, h.create.results.last().successValue().id)
        assertTrue(h.pending().isEmpty())
        assertEquals(listOf(item.id), h.view.processing.map { it.id })
    }

    // --- C3-D8 error table --------------------------------------------------------------------

    @Test fun networkErrorKeepsPendingWithError() = runCoordinatorTest { h ->
        h.signIn()
        h.create.fail(ClientError(ErrorKind.NETWORK, code = "OFFLINE"))
        h.share()
        val row = h.pending().single()
        assertEquals(SubmissionStatus.PENDING, row.submissionStatus)
        assertEquals(ClientError(ErrorKind.NETWORK, code = "OFFLINE"), row.lastSubmissionError)
        assertNull(row.retryAfter)
        assertEquals(GOOGLE_ID, row.accountBinding)
        assertEquals(listOf(row), h.view.local)
    }

    @Test fun rateLimitedResendsByItselfOnceRetryAfterEnds() = runCoordinatorTest { h ->
        h.signIn()
        h.create.fail(ClientError(ErrorKind.RATE_LIMITED, retryAfterSeconds = 30))
        val at = h.clock.now()
        h.coordinator.receiveShared(LINK, online = true)
        runCurrent()
        val row = h.pending().single()
        assertEquals(SubmissionStatus.PENDING, row.submissionStatus)
        assertEquals(at + 30.seconds, row.retryAfter)

        advanceTimeBy(29_000)
        h.coordinator.requestFlush()
        runCurrent()
        assertEquals(1, h.create.calls.size)

        // No foreground, network or pull: the retry timer alone resends it.
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, h.create.calls.size)
        assertTrue(h.pending().isEmpty())
    }

    @Test fun retryAfterZeroWaitsASecondInsteadOfLooping() = runCoordinatorTest { h ->
        h.signIn()
        h.create.fail(ClientError(ErrorKind.RATE_LIMITED, retryAfterSeconds = 0))
        h.coordinator.receiveShared(LINK, online = true)
        runCurrent()
        assertEquals(1, h.create.calls.size) // no immediate resend
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, h.create.calls.size)
        assertTrue(h.pending().isEmpty())
    }

    @Test fun aFlushStoppedEarlyKeepsTheWaitOfRowsItDidNotReach() = runCoordinatorTest { h ->
        h.signIn()
        h.share(online = false)
        h.share(online = false)
        val (first, second) = h.pending()
        // The second row waits for a server-side retry recorded earlier (e.g. by a previous process).
        h.store.markSubmission(h.session.state.value, second.clientSubmissionId, SubmissionStatus.PENDING,
            ClientError(ErrorKind.SERVER), baseTime + 30.seconds).successValue()

        // The first row hits NETWORK and stops the flush before the second row.
        h.create.fail(ClientError(ErrorKind.NETWORK))
        h.coordinator.requestFlush()
        runCurrent()
        assertEquals(listOf(first.clientSubmissionId), h.keysSent())

        // The second row's 30s wait still resends both, with no other trigger.
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(3, h.create.calls.size)
        assertTrue(h.pending().isEmpty())
    }

    @Test fun aServerThatStaysDownCostsTwoRequestsPerAttemptWithGrowingWaits() = runCoordinatorTest { h ->
        h.signIn()
        repeat(3) { h.share(online = false) }
        repeat(1_000) { h.create.fail(ClientError(ErrorKind.SERVER)) }
        h.coordinator.requestFlush()
        runCurrent()
        assertEquals(2, h.create.calls.size) // stopped at the second server-side failure, not the whole queue

        // Ten minutes of a server that stays down: waits double from 30s, so a handful of attempts.
        advanceTimeBy(600_000)
        runCurrent()
        assertTrue(h.create.calls.size <= 14, "requests in 10 min: ${h.create.calls.size}") // 30s fixed, no stop: 60
        assertEquals(3, h.pending().size)

        h.create.clearScript() // the server is back: the next timer sends the whole queue
        advanceTimeBy(900_001)
        runCurrent()
        assertTrue(h.pending().isEmpty())
    }

    @Test fun oneRowTheServerAlwaysFailsDoesNotHoldBackTheRest() = runCoordinatorTest { h ->
        h.signIn()
        h.share(online = false)
        h.share(online = false)
        val (bad, good) = h.pending()
        h.create.fail(ClientError(ErrorKind.SERVER))
        h.coordinator.requestFlush()
        runCurrent()
        assertEquals(listOf(bad.clientSubmissionId, good.clientSubmissionId), h.keysSent())
        assertEquals(listOf(bad.clientSubmissionId), h.pending().map { it.clientSubmissionId })
        assertEquals(1, h.view.processing.size)
    }

    @Test fun backoffStartsOverForAnotherAccount() = runCoordinatorTest { h ->
        h.signIn(AuthProvider.GOOGLE)
        h.share(online = false)
        repeat(4) { h.create.fail(ClientError(ErrorKind.SERVER)) }
        h.coordinator.requestFlush(); runCurrent()
        advanceTimeBy(30_001); runCurrent() // second failure in a row: the next wait would be 120s
        h.signOut()
        h.signIn(AuthProvider.APPLE)
        h.share(online = false)
        val at = h.clock.now()
        h.coordinator.requestFlush(); runCurrent()
        assertEquals(at + 30.seconds, h.pending().single().retryAfter) // 30s again, not 120s
    }

    @Test fun rateLimitHoldsEveryRowOfTheAccount() = runCoordinatorTest { h ->
        h.signIn()
        h.share(online = false)
        h.share(online = false)
        h.create.fail(ClientError(ErrorKind.RATE_LIMITED, retryAfterSeconds = 60))
        h.coordinator.requestFlush()
        runCurrent()
        assertEquals(1, h.create.calls.size)

        // A later trigger inside the window sends nothing, not the next row.
        advanceTimeBy(5_000)
        h.coordinator.requestFlush()
        runCurrent()
        assertEquals(1, h.create.calls.size)

        advanceTimeBy(55_001)
        runCurrent()
        assertEquals(3, h.create.calls.size)
        assertTrue(h.pending().isEmpty())
    }

    @Test fun serverErrorResendsByItselfAfter30s() = runCoordinatorTest { h ->
        h.signIn()
        h.create.fail(ClientError(ErrorKind.SERVER))
        val at = h.clock.now()
        h.coordinator.receiveShared(LINK, online = true)
        runCurrent()
        val row = h.pending().single()
        assertEquals(ErrorKind.SERVER, row.lastSubmissionError?.kind)
        assertEquals(at + 30.seconds, row.retryAfter)

        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(2, h.create.calls.size)
        assertTrue(h.pending().isEmpty())
    }

    @Test fun serverErrorIsResentByAnEarlierTriggerToo() = runCoordinatorTest { h ->
        h.signIn()
        h.create.fail(ClientError(ErrorKind.SERVER))
        h.coordinator.receiveShared(LINK, online = true)
        runCurrent()
        assertEquals(1, h.create.calls.size)

        // Pull to refresh 5s later: the 30s is the timer's time, not a hold on the row.
        advanceTimeBy(5_000)
        h.coordinator.requestFlush()
        runCurrent()
        assertEquals(2, h.create.calls.size)
        assertTrue(h.pending().isEmpty())
    }

    @Test fun networkTimeoutAndRateLimitStopTheFlushLeavingLaterRowsUntouched() = runCoordinatorTest { h ->
        h.signIn()
        h.share(online = false)
        h.share(online = false)
        for (kind in listOf(ErrorKind.NETWORK, ErrorKind.TIMEOUT, ErrorKind.RATE_LIMITED)) {
            val before = h.create.calls.size
            h.create.fail(ClientError(kind))
            advanceTimeBy(120_000) // past any retryAfter from the previous round
            h.coordinator.requestFlush()
            runCurrent() // not until idle: the 429 retry timer would resend both rows
            assertEquals(before + 1, h.create.calls.size, "kind=$kind")
            val (first, second) = h.pending()
            assertEquals(kind, first.lastSubmissionError?.kind)
            assertEquals(SubmissionStatus.PENDING, second.submissionStatus)
            assertNull(second.lastSubmissionError)
        }
    }

    @Test fun strayCancellationFromCreateIsRecordedAndTheConsumerSurvives() = runCoordinatorTest { h ->
        h.signIn()
        h.create.then { throw CancellationException("stray") }
        h.coordinator.receiveShared(LINK, online = true)
        runCurrent() // not until idle: the 30s retry timer would resend it
        val row = h.pending().single()
        assertEquals(SubmissionStatus.PENDING, row.submissionStatus)
        assertEquals(ErrorKind.UNAVAILABLE, row.lastSubmissionError?.kind)
        assertEquals(SUBMISSION_STEP_FAILURE, row.lastSubmissionError?.code)
        h.flush()
        assertTrue(h.pending().isEmpty())
        assertEquals(1, h.view.processing.size)
    }

    @Test fun refreshBeforeReadyReturnsWhenTheScopeCloses() = runCoordinatorTest(ready = false) { h ->
        val waiter = launch { h.coordinator.refresh() }
        runCurrent()
        h.scope.cancel()
        advanceUntilIdle()
        val returned = waiter.isCompleted
        waiter.cancel()
        assertTrue(returned)
    }

    @Test fun validationAndConflictBecomeFailedAndAreNotResent() = runCoordinatorTest { h ->
        h.signIn()
        h.share(online = false)
        h.share(online = false)
        h.create.fail(ClientError(ErrorKind.VALIDATION))
        h.create.fail(ClientError(ErrorKind.CONFLICT))
        h.flush()
        assertEquals(2, h.create.calls.size)
        val rows = h.pending()
        assertEquals(listOf(SubmissionStatus.FAILED, SubmissionStatus.FAILED), rows.map { it.submissionStatus })
        assertEquals(listOf(ErrorKind.VALIDATION, ErrorKind.CONFLICT), rows.map { it.lastSubmissionError?.kind })

        h.flush()
        h.coordinator.refresh()
        assertEquals(2, h.create.calls.size)
        assertEquals(rows, h.view.local)
    }

    @Test fun unauthenticatedStopsFlush() = runCoordinatorTest { h ->
        h.signIn()
        h.share(online = false)
        h.share(online = false)
        h.create.fail(ClientError(ErrorKind.UNAUTHENTICATED))
        h.flush()
        assertEquals(1, h.create.calls.size)
        val rows = h.pending()
        assertEquals(listOf(SubmissionStatus.PENDING, SubmissionStatus.PENDING), rows.map { it.submissionStatus })
        assertEquals(ErrorKind.UNAUTHENTICATED, rows.first().lastSubmissionError?.kind)
        assertNull(rows.last().lastSubmissionError)
    }

    // --- iOS inbox import ---------------------------------------------------------------------

    @Test fun importInboxIsIdempotentAndKeepsShareTimeBinding() = runCoordinatorTest { h ->
        h.signIn(AuthProvider.GOOGLE)
        val inbox = listOf(record(binding = APPLE_ID))
        assertEquals(InboxImportResult(listOf(UUID_A), emptyList()), h.coordinator.importInbox(inbox))
        assertEquals(InboxImportResult(listOf(UUID_A), emptyList()), h.coordinator.importInbox(inbox))
        val row = assertNotNull(h.storeHarness.row(UUID_A))
        assertEquals(APPLE_ID, row.account_binding)
        assertEquals(LINK, row.source_url)
        assertEquals(Instant.parse(SHARED_ISO).micros(), row.shared_at_us)
        // Bound to another account at share time: Google neither sees nor sends it.
        h.coordinator.refresh()
        assertTrue(h.view.local.isEmpty())
        assertTrue(h.create.calls.isEmpty())
    }

    @Test fun reimportingSameRecordIsNoOp() = runCoordinatorTest { h ->
        assertEquals(listOf(UUID_A), h.coordinator.importInbox(listOf(record())).deletable)
        h.create.fail(ClientError(ErrorKind.NETWORK))
        h.signIn()
        val before = h.pending().single()
        assertEquals(GOOGLE_ID, before.accountBinding)
        assertEquals(ErrorKind.NETWORK, before.lastSubmissionError?.kind)

        // The app died after importing but before deleting the file: the same record comes again.
        assertEquals(InboxImportResult(listOf(UUID_A), emptyList()), h.coordinator.importInbox(listOf(record())))
        assertEquals(listOf(before), h.pending())
    }

    @Test fun reimportAfterAcceptReplaysWithoutDuplicate() = runCoordinatorTest { h ->
        h.signIn()
        h.coordinator.importInbox(listOf(record()))
        h.flush()
        assertTrue(h.pending().isEmpty())
        h.coordinator.importInbox(listOf(record()))
        h.flush()
        assertEquals(listOf(UUID_A, UUID_A), h.keysSent())
        assertEquals(1, h.create.results.map { it.successValue().id }.toSet().size)
        assertEquals(1, h.view.processing.size)
    }

    @Test fun importRejectsBadRecordsAsDeletable() = runCoordinatorTest { h ->
        val bad = listOf(
            record(key = "not-a-uuid"),
            record(key = UUID_A, sharedAtIso = "yesterday"),
            record(key = UUID_B, url = "그냥 글이에요"),
            record(key = UUID_C, url = "https://a.example/" + "a".repeat(2048 - 17)),
        )
        assertEquals(InboxImportResult(bad.map { it.clientSubmissionId }, emptyList()), h.coordinator.importInbox(bad))
        assertTrue(h.pending().isEmpty())
        assertNull(h.storeHarness.row(UUID_A))
    }

    @Test fun importSameKeyDifferentUrlIsDeletableAndOriginalKept() = runCoordinatorTest { h ->
        h.coordinator.importInbox(listOf(record()))
        val result = h.coordinator.importInbox(listOf(record(url = "https://other.example/2")))
        assertEquals(InboxImportResult(listOf(UUID_A), emptyList()), result)
        assertEquals(LINK, h.storeHarness.row(UUID_A)?.source_url)
    }

    @Test fun importInboxWaitsForReadyWithoutATimeout() = runCoordinatorTest(ready = false) { h ->
        // A slow DEBUG cold start (DB open, migration, restore, seed) takes longer than the share wait.
        val result = async { h.coordinator.importInbox(listOf(record())) }
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(result.isCompleted)
        h.ready.value = true
        advanceUntilIdle()
        assertEquals(InboxImportResult(listOf(UUID_A), emptyList()), result.await())
        assertEquals(listOf(UUID_A), h.pending().map { it.clientSubmissionId })
    }

    @Test fun importInboxBeforeReadyRetainsWhenTheCoordinatorCloses() = runCoordinatorTest(ready = false) { h ->
        val result = async { h.coordinator.importInbox(listOf(record(), record(key = "bad"))) }
        advanceTimeBy(5_000)
        runCurrent()
        h.scope.cancel()
        advanceUntilIdle()
        assertEquals(InboxImportResult(deletable = listOf("bad"), retained = listOf(UUID_A)), result.await())
    }

    @Test fun importStoreFailureIsRetained() = runCoordinatorTest(store = ::failingStore) { h ->
        val result = h.coordinator.importInbox(listOf(record(key = UUID_A), record(key = "bad")))
        assertEquals(InboxImportResult(deletable = listOf("bad"), retained = listOf(UUID_A)), result)
    }

    // --- refresh and view ---------------------------------------------------------------------

    @Test fun refreshUpdatesProcessingFromItem03() = runCoordinatorTest { h ->
        h.signIn()
        h.share()
        val item = h.view.processing.single()
        val outcome = AnalysisOutcome(AnalysisStatus.READY, "헤드폰", "C026", null, null)
        h.fakeStore.completeAnalysis(item.id, 1, outcome).successValue()
        assertEquals(1, h.view.processing.size)

        h.coordinator.refresh()
        assertTrue(h.view.processing.isEmpty())
        assertEquals(1, h.beforeRefreshCalls)
        val cached = h.store.cachedItem(h.session.state.value, item.id).successValue()
        assertEquals(AnalysisStatus.READY, cached?.analysis?.status)
    }

    @Test fun flushOfManyRowsCoalescesViewPublishes() = runCoordinatorTest { h ->
        h.signIn()
        val keys = List(100) { "00000000-0000-4000-9000-" + it.toString().padStart(12, '0') }
        keys.forEachIndexed { i, key ->
            h.store.saveSubmission(submission(id = key, binding = GOOGLE_ID, sharedAt = baseTime + i.seconds)).successValue()
        }
        h.store.pendingCalls = 0
        h.store.processingItemsCalls = 0

        h.flush()

        assertEquals(keys, h.keysSent())
        // The instant fake ITEM-01 never suspends the flush, so the sends' publish requests collapse
        // into one publisher run; with the publish after prepareFlush and the final one that is at most
        // 4 view computations (the old code ran one per row: >= 100 each).
        assertTrue(h.store.pendingCalls <= 4, "pending() calls: ${h.store.pendingCalls}")
        assertTrue(h.store.processingItemsCalls <= 4, "processingItems() calls: ${h.store.processingItemsCalls}")
        assertTrue(h.view.local.isEmpty())
        assertEquals(100, h.view.processing.size)
        assertEquals(h.create.results.map { it.successValue().id }, h.view.processing.map { it.id })
    }

    @Test fun viewNeverShowsARowBothQueuedAndProcessing() = runCoordinatorTest { h ->
        h.signIn()
        h.share(online = false)
        val seen = mutableListOf<SubmissionView>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.coordinator.view.collect { v -> v?.let { seen += it } } }
        // A view computation reads the queue, then (while it reads the cache) the send is accepted.
        val queueRead = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        h.store.afterPending = {
            h.store.afterPending = {}
            queueRead.complete(Unit)
            gate.await()
        }
        h.create.then { real -> queueRead.await(); real() }
        h.coordinator.requestFlush()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(h.pending().isEmpty())
        assertEquals(1, h.view.processing.size)
        val doubled = seen.filter { v -> v.local.any { l -> v.processing.any { it.clientSubmissionId == l.clientSubmissionId } } }
        assertTrue(doubled.isEmpty(), "views with a row in both lists: $doubled")
    }

    @Test fun refreshLookupsStepAsideForANewShare() = runCoordinatorTest { h ->
        h.signIn()
        repeat(3) { h.share() }
        assertEquals(3, h.view.processing.size)
        var lookups = 0
        val first = CompletableDeferred<Unit>()
        val rest = CompletableDeferred<Unit>()
        h.beforeGet = { if (++lookups == 1) first.await() else rest.await() }

        val refresh = launch { h.coordinator.refresh() }
        runCurrent()
        assertEquals(1, lookups)
        val flushesBefore = h.store.prepareFlushCalls
        launch { h.coordinator.receiveShared("https://shop.example/p/new", online = true) }
        runCurrent()
        assertEquals(3, h.create.calls.size)

        // The new share is sent before the second lookup, not after the whole list.
        first.complete(Unit)
        runCurrent()
        assertEquals(2, lookups)
        assertEquals(4, h.create.calls.size)

        rest.complete(Unit)
        advanceUntilIdle()
        assertTrue(refresh.isCompleted)
        assertTrue(h.pending().isEmpty())
        // The share's queued request is not a second flush once the lookups ran one for it.
        assertEquals(1, h.store.prepareFlushCalls - flushesBefore)
    }

    @Test fun slowSendsRecomputeTheViewABoundedNumberOfTimes() = runCoordinatorTest { h ->
        h.signIn()
        val keys = List(100) { "00000000-0000-4000-9000-" + it.toString().padStart(12, '0') }
        keys.forEachIndexed { i, key ->
            h.store.saveSubmission(submission(id = key, binding = GOOGLE_ID, sharedAt = baseTime + i.seconds)).successValue()
        }
        repeat(100) { h.create.then { real -> delay(50); real() } } // 5s of sends in all
        h.store.pendingCalls = 0

        h.flush()

        assertEquals(keys, h.keysSent())
        // One recompute per 200ms at most while sending (~25), not one per row (>= 100).
        assertTrue(h.store.pendingCalls <= 30, "pending() calls: ${h.store.pendingCalls}")
        assertTrue(h.view.local.isEmpty())
    }

    @Test fun noViewBeforeReadyEvenWhenTheAccountIsRestoredFirst() = runCoordinatorTest(ready = false) { h ->
        h.storeHarness.login(GOOGLE_ID) // the DEBUG restore signs in before ready
        advanceUntilIdle()
        assertNull(h.coordinator.view.value) // not an empty "0 to-dos" list
        h.ready.value = true
        advanceUntilIdle()
        assertEquals(GOOGLE_ID, h.view.accountId)
    }

    @Test fun shareNotStoredInTimeIsDeferredUnbound() = runCoordinatorTest(ready = false) { h ->
        val deferred = mutableListOf<InboxRecord>()
        val start = h.clock.now()
        val card = async { h.coordinator.receiveShared("보세요 $LINK", online = true) { deferred += it; true } }
        advanceTimeBy(1_501)
        runCurrent()
        assertEquals(ShareCardKind.DEFERRED, card.await())
        val record = deferred.single()
        assertEquals(LINK, record.sourceUrl)
        assertNull(record.accountBinding)
        assertEquals(start, Instant.parse(record.sharedAtIso)) // the share's own time, not the deferral's
        assertTrue(h.storeHarness.store.pending().successValue().isEmpty())

        // Once ready, importing it stores the share (as unbound).
        h.ready.value = true
        assertEquals(listOf(record.clientSubmissionId), h.coordinator.importInbox(deferred).deletable)
        assertEquals(LINK, h.pending().single().sourceUrl)
    }

    @Test fun storeFailureIsDeferredAndAFailedDeferIsStoreFailed() = runCoordinatorTest(store = ::failingStore) { h ->
        assertEquals(ShareCardKind.DEFERRED, h.coordinator.receiveShared(LINK, online = true) { true })
        assertEquals(ShareCardKind.STORE_FAILED, h.coordinator.receiveShared(LINK, online = true) { false })
        assertEquals(ShareCardKind.STORE_FAILED, h.coordinator.receiveShared(LINK, online = true) { error("disk full") })
    }

    @Test fun rowsWithValuesThisBuildDoesNotKnowStillReadAndSend() = runCoordinatorTest { h ->
        h.share(online = false)
        h.storeHarness.driver.execute(null, "UPDATE local_submission SET status = 'ARCHIVED_V9', error_kind = 'MYSTERY'", 0)
        val row = h.pending().single()
        assertEquals(SubmissionStatus.PENDING, row.submissionStatus)
        assertNull(row.lastSubmissionError)
        h.signIn()
        assertTrue(h.pending().isEmpty())
        assertEquals(1, h.view.processing.size)
    }

    @Test fun viewOrdersProcessingByCreatedAtThenId() = runCoordinatorTest { h ->
        h.signIn()
        val snapshot = h.session.state.value
        fun item(id: String, at: Instant) = itemFixture(
            analysis = AnalysisStatus.PROCESSING,
            id = "00000000-0000-4000-a000-00000000000$id",
            clientSubmissionId = "00000000-0000-4000-b000-00000000000$id",
        ).copy(createdAt = at)
        // Stored unsorted. Same createdAt ties by id; a fractional second sorts after the whole one
        // (as ISO text ".5Z" would sort before "Z").
        val stored = listOf(
            item("3", baseTime + 2.seconds),
            item("2", baseTime + 2.seconds),
            item("4", baseTime + 500.milliseconds),
            item("1", baseTime),
        )
        stored.forEach { h.store.upsertItem(snapshot, it).successValue() }
        h.flush() // republishes the view (a refresh would ask the fake ITEM-03, which knows none of them)
        assertEquals(listOf("1", "4", "2", "3"), h.view.processing.map { it.id.last().toString() })
    }

    @Test fun viewKeepsLocalRowsInSharedOrder() = runCoordinatorTest { h ->
        h.store.saveSubmission(submission(id = UUID_C, sharedAt = baseTime + 1.seconds)).successValue()
        h.store.saveSubmission(submission(id = UUID_B, sharedAt = baseTime)).successValue()
        h.store.saveSubmission(submission(id = UUID_A, sharedAt = baseTime + 1.seconds)).successValue()
        h.flush()
        assertEquals(listOf(UUID_B, UUID_A, UUID_C), h.view.local.map { it.clientSubmissionId })
    }

    @Test fun viewHidesOtherAccountsLocalItems() = runCoordinatorTest { h ->
        h.signIn(AuthProvider.GOOGLE)
        h.share(online = false)
        val googleKey = h.view.local.single().clientSubmissionId

        h.signOut()
        assertNull(h.view.accountId)
        assertTrue(h.view.local.isEmpty())

        h.share()
        val unboundKey = h.view.local.single().clientSubmissionId
        assertNull(h.view.local.single().accountBinding)

        h.create.fail(ClientError(ErrorKind.NETWORK))
        h.signIn(AuthProvider.APPLE)
        assertEquals(APPLE_ID, h.view.accountId)
        assertEquals(listOf(unboundKey), h.view.local.map { it.clientSubmissionId })
        assertFalse(googleKey in h.view.local.map { it.clientSubmissionId })
        assertTrue(h.create.calls.none { it.key == googleKey })
    }

    // --- C4 local delete ----------------------------------------------------------------------

    @Test fun deleteLocalPublishesAtOnce() = runCoordinatorTest { h ->
        h.share()
        val key = h.view.local.single().clientSubmissionId
        val deleted = async { h.coordinator.deleteLocal(key) }
        runCurrent()
        deleted.await().successValue()
        assertTrue(h.view.local.isEmpty())
        assertTrue(h.pending().isEmpty())
    }

    @Test fun deletedAfterQueueReadIsNeverPostedAndLaterRowsStillGo() = runCoordinatorTest { h ->
        h.signIn()
        h.store.saveSubmission(submission(id = UUID_A, binding = GOOGLE_ID, sharedAt = baseTime)).successValue()
        h.store.saveSubmission(submission(id = UUID_B, binding = GOOGLE_ID, sharedAt = baseTime + 1.seconds)).successValue()
        var deleted: ClientResult<Unit>? = null
        h.store.afterPrepareFlush = { snapshot ->
            h.store.afterPrepareFlush = {}
            deleted = h.store.deleteSubmission(snapshot, UUID_A) // the user deletes A after the queue was read
        }

        h.flush()
        assertEquals(ClientResult.Success(Unit), deleted)
        assertEquals(listOf(UUID_B), h.keysSent())
        assertTrue(h.create.results.single() is ClientResult.Success)
        assertTrue(h.pending().isEmpty())
        assertEquals(listOf(UUID_B), h.view.processing.map { it.clientSubmissionId })
    }

    @Test fun aSubmittingRowCannotBeDeleted() = runCoordinatorTest { h ->
        h.signIn()
        h.share(online = false)
        val key = h.pending().single().clientSubmissionId
        val gate = CompletableDeferred<Unit>()
        h.create.then { real -> gate.await(); real() }
        h.coordinator.requestFlush()
        runCurrent()
        assertEquals(listOf(key), h.keysSent())

        val deleted = async { h.coordinator.deleteLocal(key) }
        runCurrent()
        val failure = deleted.await().error()
        assertEquals(ErrorKind.CONFLICT, failure.kind)
        assertEquals(SUBMISSION_IN_FLIGHT, failure.code)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(key), h.keysSent())
        assertTrue(h.pending().isEmpty())
        assertEquals(listOf(key), h.view.processing.map { it.clientSubmissionId })
    }
}
