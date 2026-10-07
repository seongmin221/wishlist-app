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
import app.wishlist.shared.data.fake.successValue
import app.wishlist.shared.data.local.CachedGetItemRepository
import app.wishlist.shared.data.local.LazyDriver
import app.wishlist.shared.data.local.SqlLocalStore
import app.wishlist.shared.data.local.StoreHarness
import app.wishlist.shared.data.local.UUID_A
import app.wishlist.shared.data.local.UUID_B
import app.wishlist.shared.data.local.UUID_C
import app.wishlist.shared.data.local.submission
import app.wishlist.shared.data.local.withHarness
import app.wishlist.shared.model.AnalysisStatus
import app.wishlist.shared.model.CategoryMissingReason
import app.wishlist.shared.model.LocalSubmission
import app.wishlist.shared.model.SubmissionStatus
import app.wishlist.shared.model.WishlistItem
import app.wishlist.shared.repository.CreateItemCommand
import app.wishlist.shared.repository.SnapshotCreateItemRepository
import app.wishlist.shared.repository.LocalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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

    /** Runs right after a markSubmission has committed (outside the session gate). */
    var afterMark: suspend (SubmissionStatus) -> Unit = {}

    override suspend fun prepareFlush(snapshot: SessionSnapshot): ClientResult<List<LocalSubmission>> {
        prepareFlushCalls++
        return delegate.prepareFlush(snapshot)
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
    val coordinator = newCoordinator()
    val auth = FakeAuthFacade(
        session = session,
        store = store,
        seed = { ClientResult.Success(Unit) },
        onSignedIn = { coordinator.requestFlush(FlushTrigger.SIGNED_IN) },
    )

    /** A second coordinator over the same DB, like the next process after a crash. */
    fun newCoordinator() = SubmissionCoordinator(
        store = store,
        create = create,
        get = CachedGetItemRepository(backend, store, session),
        session = session,
        clock = clock,
        ids = ids,
        scope = scope,
        ready = ready,
        beforeRefresh = { beforeRefreshCalls++ },
        dispatcher = dispatcher,
    )

    val view: SubmissionView get() = coordinator.view.value

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
        coordinator.requestFlush(FlushTrigger.USER_REFRESH)
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
        assertEquals(ShareCardKind.LOCAL, h.share(online = true))
        val row = h.pending().single()
        assertEquals(LINK, row.sourceUrl)
        assertNull(row.accountBinding)
        assertEquals(baseTime, row.sharedAt)
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
        assertFalse(view.flushing)
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

        h.coordinator.requestFlush(FlushTrigger.USER_REFRESH)
        runCurrent()
        assertEquals(1, h.create.calls.size)
        assertTrue(h.view.flushing)
        h.coordinator.requestFlush(FlushTrigger.FOREGROUND)
        h.coordinator.requestFlush(FlushTrigger.NETWORK_RESTORED)
        h.coordinator.requestFlush(FlushTrigger.SHARE_RECEIVED)
        runCurrent()
        assertEquals(1, h.create.calls.size)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, h.create.maxActive)
        assertEquals(1, h.create.calls.size)
        assertEquals(2, h.store.prepareFlushCalls - runsBefore) // the running flush + one rerun
        assertTrue(h.pending().isEmpty())
        assertFalse(h.view.flushing)
    }

    @Test fun concurrentRefreshesShareOneRerunAndAllReturn() = runCoordinatorTest { h ->
        h.signIn()
        h.share(online = false)
        val runsBefore = h.store.prepareFlushCalls
        val gate = CompletableDeferred<Unit>()
        h.create.then { real -> gate.await(); real() }
        h.coordinator.requestFlush(FlushTrigger.FOREGROUND)
        runCurrent()

        val refreshes = List(3) { launch { h.coordinator.refresh(FlushTrigger.USER_REFRESH) } }
        h.coordinator.requestFlush(FlushTrigger.NETWORK_RESTORED)
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

        assertTrue(h.create.calls.none { it.account == APPLE_ID }, "calls=${h.create.calls}")
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
        restarted.requestFlush(FlushTrigger.LAUNCH)
        advanceUntilIdle()
        assertEquals(listOf(UUID_A), h.keysSent())
        assertTrue(h.pending().isEmpty())
        assertEquals(1, restarted.view.value.processing.size)
    }

    @Test fun responseLossThenResendDoesNotDuplicate() = runCoordinatorTest { h ->
        h.signIn()
        var created: WishlistItem? = null
        h.create.then { real -> created = real().successValue(); ClientResult.Failure(ClientError(ErrorKind.NETWORK)) }
        h.share()
        val row = h.pending().single()
        assertEquals(SubmissionStatus.PENDING, row.submissionStatus)

        h.coordinator.refresh(FlushTrigger.USER_REFRESH)
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

    @Test fun rateLimitedSkipsUntilRetryAfter() = runCoordinatorTest { h ->
        h.signIn()
        h.create.fail(ClientError(ErrorKind.RATE_LIMITED, retryAfterSeconds = 30))
        h.share()
        val row = h.pending().single()
        assertEquals(SubmissionStatus.PENDING, row.submissionStatus)
        assertEquals(baseTime + 30.seconds, row.retryAfter)

        advanceTimeBy(29_000)
        h.flush()
        assertEquals(1, h.create.calls.size)

        advanceTimeBy(2_000)
        h.flush()
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
            h.flush()
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
        h.share()
        val row = h.pending().single()
        assertEquals(SubmissionStatus.PENDING, row.submissionStatus)
        assertEquals(ErrorKind.UNAVAILABLE, row.lastSubmissionError?.kind)
        assertEquals(SUBMISSION_STEP_FAILURE, row.lastSubmissionError?.code)
        h.flush()
        assertTrue(h.pending().isEmpty())
        assertEquals(1, h.view.processing.size)
    }

    @Test fun refreshBeforeReadyReturnsWhenTheScopeCloses() = runCoordinatorTest(ready = false) { h ->
        val waiter = launch { h.coordinator.refresh(FlushTrigger.LAUNCH) }
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
        h.coordinator.refresh(FlushTrigger.USER_REFRESH)
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
        h.coordinator.refresh(FlushTrigger.FOREGROUND)
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

        h.coordinator.refresh(FlushTrigger.USER_REFRESH)
        assertTrue(h.view.processing.isEmpty())
        assertEquals(1, h.beforeRefreshCalls)
        val cached = h.store.cachedItem(h.session.state.value, item.id).successValue()
        assertEquals(AnalysisStatus.READY, cached?.analysis?.status)
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
}
