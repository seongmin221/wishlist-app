@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.wishlist.shared.di

import app.wishlist.shared.core.ApiId
import app.wishlist.shared.core.AuthFacade
import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.Clock
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.IdGenerator
import app.wishlist.shared.core.MutableAuthSession
import app.wishlist.shared.core.RuntimeDispatchers
import app.wishlist.shared.data.fake.BoardSeeds
import app.wishlist.shared.data.fake.DebugAnalysisDriver
import app.wishlist.shared.data.fake.FakeAuthFacade
import app.wishlist.shared.data.fake.FakeStore
import app.wishlist.shared.presentation.AccountPresenter
import app.wishlist.shared.presentation.HomePresenter
import app.wishlist.shared.presentation.ItemDetailPresenter
import app.wishlist.shared.repository.CatalogRepository
import app.wishlist.shared.repository.CreateItemRepository
import app.wishlist.shared.repository.GetItemRepository
import app.wishlist.shared.repository.LocalStore
import app.wishlist.shared.repository.SnapshotCreateItemRepository
import app.wishlist.shared.submission.SubmissionCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import kotlin.uuid.Uuid

/** The debug bootstrap threw instead of returning a typed failure. */
internal const val BOOTSTRAP_FAILURE = "BOOTSTRAP_FAILURE"

internal val systemClock = Clock { kotlin.time.Clock.System.now() }
internal val randomIds = IdGenerator { Uuid.random().toString() }

/**
 * Validates the configuration before anything is opened: REMOTE config is required if and only
 * if some API is REMOTE. Platform factories call this; tests call it with test seams.
 */
internal fun assembleSharedRuntime(
    bindings: RepositoryBindings,
    remote: RemoteConfig?,
    platform: PlatformResources,
    clock: Clock,
    ids: IdGenerator,
    dispatchers: RuntimeDispatchers,
    /** Test seam: replaces the debug board seed in [SharedRuntime.startDebugSession]. */
    seedOverride: (suspend () -> ClientResult<Unit>)? = null,
): SharedRuntime {
    require(bindings.usesRemote == (remote != null)) {
        if (remote == null) "A REMOTE binding requires RemoteConfig" else "RemoteConfig given but no API is REMOTE"
    }
    return SharedRuntime(RuntimeEnvironment(bindings, remote, platform, clock, ids, dispatchers), seedOverride)
}

/**
 * One isolated shared graph (its own DI container, never a global one) per app process.
 * Every component uses the same [session]. Facades are gated by [ready]: RELEASE is ready right
 * after assembly; DEBUG starts signed out and becomes ready only after [startDebugSession] has
 * restored the saved fake account (if any) and seeded that account's namespace. Before ready and after [close], requests return
 * UNAVAILABLE/RUNTIME_NOT_READY. Apps see only these facades, never the DB, HTTP or DI library
 * types (kept Kotlin-internal so the ObjC header stays free of them).
 */
class SharedRuntime internal constructor(
    private val env: RuntimeEnvironment,
    private val seedOverride: (suspend () -> ClientResult<Unit>)? = null,
) {
    // Every graph lookup, the ready publication and every store DB operation run inside the guard;
    // teardown waits for them. Created first: the graph's store holds it as its lease.
    private val guard = CloseGuard(onClosed = ::tearDown)
    private val koinApplication = koinApplication { modules(sharedModules(env, guard)) }
    internal val koin: Koin = koinApplication.koin
    private val mutableSession: MutableAuthSession = koin.get()
    private val debugStarted = MutableStateFlow(false)
    private val mutableReady = MutableStateFlow(env.bindings.buildMode == ClientBuildMode.RELEASE)
    private val mutableBootstrapFailure = MutableStateFlow<ClientError?>(null)

    // Backstop for anything escaping the bootstrap's own handling: still ready, never a crash.
    // Installed on the bootstrap launch only, so other jobs in [scope] are never reported as bootstrap failures.
    private val bootstrapExceptionHandler = CoroutineExceptionHandler { _, _ ->
        guard.use {
            mutableBootstrapFailure.value = ClientError(ErrorKind.UNAVAILABLE, BOOTSTRAP_FAILURE)
            mutableReady.value = true
            fakeAuth?.publishRestored()
            Unit
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + env.dispatchers.default)

    /** Test seam: runs in the bootstrap right before ready is published (close-race tests). */
    internal var beforeReadyPublished: () -> Unit = {}

    // DEBUG: the fake login seeds the namespace of whichever account signs in or is restored.
    private val fakeAuth: FakeAuthFacade? =
        if (env.bindings.buildMode == ClientBuildMode.DEBUG) {
            val fakeStore = koin.get<FakeStore>()
            FakeAuthFacade(
                session = mutableSession,
                store = koin.get<LocalStore>(),
                seed = seedOverride ?: { fakeStore.seed(BoardSeeds.create(env.clock, env.ids)) },
                onSignedIn = { submissions().requestFlush() },
            )
        } else {
            null
        }

    val session: AuthSession get() = mutableSession
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()

    /**
     * Why the DEBUG bootstrap did not complete cleanly (seed failure, or UNAVAILABLE/BOOTSTRAP_FAILURE
     * for an unexpected exception). The runtime still becomes [ready]; null when it succeeded.
     */
    val bootstrapFailure: StateFlow<ClientError?> = mutableBootstrapFailure.asStateFlow()

    /**
     * DEBUG only, idempotent: starts signed out, restores the saved fake account (changing the
     * session to it and seeding that namespace), then ready=true. A failed restore/seed or an
     * exception still publishes ready, with the cause in [bootstrapFailure].
     * Calling it in RELEASE is a programming error.
     */
    fun startDebugSession() {
        check(env.bindings.buildMode == ClientBuildMode.DEBUG) { "startDebugSession is DEBUG only" }
        if (!debugStarted.compareAndSet(expect = false, update = true)) return
        val auth = fakeAuth ?: return
        // Refused once closing began, so a closed graph never starts a bootstrap.
        if (guard.use { } == null) return
        scope.launch(bootstrapExceptionHandler) {
            val failure = try {
                auth.restore()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ClientError(ErrorKind.UNAVAILABLE, BOOTSTRAP_FAILURE)
            }
            // Refused once closing began; a close() during the write defers teardown (and its
            // ready = false) until this block has left the guard.
            guard.use {
                mutableBootstrapFailure.value = failure
                beforeReadyPublished()
                mutableReady.value = true
                auth.publishRestored()
            }
        }
    }

    /** ITEM-01 facade over the explicitly bound backend. */
    fun createItemRepository(): CreateItemRepository = gatedCreate()

    private fun gatedCreate() =
        GatedCreateItemRepository(ready, resolveOr<SnapshotCreateItemRepository>(UnavailableItemRepository) { get(ITEM_01_DELEGATE) })

    /** ITEM-03 facade: the bound backend wrapped once by the local cache decorator. */
    fun getItemRepository(): GetItemRepository =
        GatedGetItemRepository(ready, resolveOr<GetItemRepository>(UnavailableItemRepository) { get() })

    /** Debug seed catalog (DEBUG); UNAVAILABLE in RELEASE. Not a CAT/PUR/ITEM-02 wire backend. */
    fun catalogRepository(): CatalogRepository =
        GatedCatalogRepository(ready, resolveOr<CatalogRepository>(UnavailableCatalogRepository) { get() })

    /** Login state and sign-in/out: the fake facade in DEBUG, UNAVAILABLE in RELEASE. */
    fun auth(): AuthFacade = when (val auth = fakeAuth) {
        null -> UnavailableAuthFacade()
        else -> if (guard.use { } == null) UnavailableAuthFacade() else GatedAuthFacade(ready, auth)
    }

    fun localStore(): LocalStore = GatedLocalStore(ready, resolveOr<LocalStore>(ClosedLocalStore) { get() })

    // Created on first use (never opens the driver by itself) over the gated facades; repository
    // work runs on the io dispatcher. DEBUG with the Fake ITEM-03 advances fake analysis before refresh.
    private val coordinator: SubmissionCoordinator by lazy {
        val analysis = if (env.bindings.backendOf(ApiId.ITEM_03) == Backend.FAKE) {
            guard.use { koin.get<DebugAnalysisDriver>() }
        } else {
            null
        }
        SubmissionCoordinator(
            store = localStore(),
            create = gatedCreate(),
            get = getItemRepository(),
            session = session,
            clock = env.clock,
            ids = env.ids,
            scope = scope,
            ready = ready,
            beforeRefresh = { analysis?.advance() },
            dispatcher = env.dispatchers.io,
        )
    }

    /**
     * The one share receiver and sender of this runtime (single-flight flush, view of the local
     * queue). After [close] it is inert: shares report STORE_FAILED and flush requests are ignored.
     */
    fun submissions(): SubmissionCoordinator = coordinator

    /**
     * A new item detail Presenter over the gated ITEM-03 facade and this runtime's one [session].
     * Repository work runs on the runtime's background (I/O) dispatcher, never on the caller's UI
     * thread. Each successful load asks [submissions] to republish its view, so the home list picks up
     * the refreshed cache. The platform owner that requested it calls [ItemDetailPresenter.close].
     */
    fun itemDetailPresenter(): ItemDetailPresenter =
        ItemDetailPresenter(
            repository = getItemRepository(),
            session = session,
            dispatcher = env.dispatchers.io,
            onLoaded = { submissions().requestViewPublish() },
        )

    /** A new login Presenter over [auth]; the platform owner calls [AccountPresenter.close]. */
    fun accountPresenter(): AccountPresenter = AccountPresenter(auth = auth(), dispatcher = env.dispatchers.io)

    /**
     * A new home list Presenter over [auth] and the one [submissions] coordinator. Foreground
     * refresh stays app-wide (the platform calls `submissions().refresh()`). The
     * platform owner calls [HomePresenter.close].
     */
    fun homePresenter(): HomePresenter {
        val submissions = submissions()
        return HomePresenter(
            auth = auth(),
            view = submissions.view,
            refreshes = submissions.refreshes,
            runRefresh = submissions::refresh,
            clock = env.clock,
            utcOffsetSeconds = env.platform.utcOffsetSeconds,
            dispatcher = env.dispatchers.io,
        )
    }

    /**
     * **DEBUG only** (null in RELEASE and after [close]): demo hooks for on-device checks — delaying
     * the next Fake ITEM-01 and creating unbound pending rows. Apps call it only from debug code paths.
     */
    fun debugControls(): DebugControls? {
        if (env.bindings.buildMode != ClientBuildMode.DEBUG) return null
        val fake = guard.use { koin.get<FakeStore>() } ?: return null
        return DebugControls(
            fake = fake,
            store = localStore(),
            ready = ready,
            clock = env.clock,
            ids = env.ids,
            io = env.dispatchers.io,
            onPendingCreated = { submissions().requestFlush() },
        )
    }

    /**
     * Releases the HTTP client, its engine and the SQL driver once (if created). Idempotent,
     * non-blocking and safe from any thread. At once: ready = false, new graph lookups and new
     * local-store DB operations are refused (UNAVAILABLE/RUNTIME_NOT_READY, the DB untouched), and
     * the runtime's background jobs are cancelled. The teardown itself is deferred until the last
     * in-flight graph lookup, ready publication or local-store DB operation (including a first
     * driver open) has returned, and then runs on that caller's thread (possibly the io thread),
     * ending with ready = false. It does not wait for in-flight HTTP requests (the HTTP client and
     * engine are closed with the rest of the teardown) or for caller jobs outside the store.
     */
    fun close() {
        if (!guard.close()) return
        mutableReady.value = false
        scope.cancel()
    }

    private fun tearDown() {
        koin.get<ResourceRegistry>().closeAll()
        koinApplication.close()
        mutableReady.value = false
    }

    /** The backend the graph actually connected for [apiId]; APIs without a facade stay UNAVAILABLE. */
    internal fun resolvedBackend(apiId: ApiId): Backend = when (apiId) {
        ApiId.ITEM_01 -> backendOf(koin.get<SnapshotCreateItemRepository>(ITEM_01_DELEGATE))
        ApiId.ITEM_03 -> backendOf(koin.get<GetItemRepository>(ITEM_03_DELEGATE))
        else -> Backend.UNAVAILABLE
    }

    // Facades are resolved on request; the first one that needs the driver/engine opens it.
    private inline fun <T : Any> resolveOr(closedFallback: T, resolve: Koin.() -> T): T =
        guard.use { koin.resolve() } ?: closedFallback
}
