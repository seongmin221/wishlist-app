@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.wishlist.shared.di

import app.wishlist.shared.core.ApiId
import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.Clock
import app.wishlist.shared.core.IdGenerator
import app.wishlist.shared.core.MutableAuthSession
import app.wishlist.shared.core.RuntimeDispatchers
import app.wishlist.shared.data.fake.BoardSeeds
import app.wishlist.shared.data.fake.FakeStore
import app.wishlist.shared.repository.CatalogRepository
import app.wishlist.shared.repository.CreateItemRepository
import app.wishlist.shared.repository.GetItemRepository
import app.wishlist.shared.repository.LocalStore
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

/** Owner namespace the debug bootstrap logs into before seeding the board. */
internal const val DEBUG_ACCOUNT_ID = "debug-board-owner"

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
): SharedRuntime {
    require(bindings.usesRemote == (remote != null)) {
        if (remote == null) "A REMOTE binding requires RemoteConfig" else "RemoteConfig given but no API is REMOTE"
    }
    return SharedRuntime(RuntimeEnvironment(bindings, remote, platform, clock, ids, dispatchers))
}

/**
 * One isolated shared graph (its own DI container, never a global one) per app process.
 * Every component uses the same [session]. Facades are gated by [ready]: RELEASE is ready right
 * after assembly; DEBUG becomes ready only after [startDebugSession] has changed the account to
 * the debug owner and seeded that namespace. Before ready and after [close], requests return
 * UNAVAILABLE/RUNTIME_NOT_READY. Apps see only these facades, never the DB, HTTP or DI library
 * types (kept Kotlin-internal so the ObjC header stays free of them).
 */
class SharedRuntime internal constructor(private val env: RuntimeEnvironment) {
    private val koinApplication = koinApplication { modules(sharedModules(env)) }
    internal val koin: Koin = koinApplication.koin
    private val mutableSession: MutableAuthSession = koin.get()
    private val scope = CoroutineScope(SupervisorJob() + env.dispatchers.default)
    private val debugStarted = MutableStateFlow(false)
    private val closed = MutableStateFlow(false)
    private val mutableReady = MutableStateFlow(env.bindings.buildMode == ClientBuildMode.RELEASE)

    val session: AuthSession get() = mutableSession
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()

    /**
     * DEBUG only, idempotent: account → debug owner, seed that same namespace, then ready=true.
     * Calling it in RELEASE is a programming error.
     */
    fun startDebugSession() {
        check(env.bindings.buildMode == ClientBuildMode.DEBUG) { "startDebugSession is DEBUG only" }
        if (closed.value || !debugStarted.compareAndSet(expect = false, update = true)) return
        // Resolved before launching so a concurrent close never meets a closed Koin mid-bootstrap.
        val store = koin.get<FakeStore>()
        scope.launch {
            mutableSession.changeAccount(DEBUG_ACCOUNT_ID)
            val seeded = store.seed(BoardSeeds.create(env.clock, env.ids))
            if (seeded is ClientResult.Success && !closed.value) mutableReady.value = true
        }
    }

    /** ITEM-01 facade over the explicitly bound backend. */
    fun createItemRepository(): CreateItemRepository =
        GatedCreateItemRepository(ready, resolveOr<CreateItemRepository>(UnavailableItemRepository) { get(ITEM_01_DELEGATE) })

    /** ITEM-03 facade: the bound backend wrapped once by the local cache decorator. */
    fun getItemRepository(): GetItemRepository =
        GatedGetItemRepository(ready, resolveOr<GetItemRepository>(UnavailableItemRepository) { get() })

    /** Debug seed catalog (DEBUG); UNAVAILABLE in RELEASE. Not a CAT/PUR/ITEM-02 wire backend. */
    fun catalogRepository(): CatalogRepository =
        GatedCatalogRepository(ready, resolveOr<CatalogRepository>(UnavailableCatalogRepository) { get() })

    fun localStore(): LocalStore = GatedLocalStore(ready, resolveOr<LocalStore>(ClosedLocalStore) { get() })

    /** Releases the HTTP client, its engine and the SQL driver once (if created). Idempotent. */
    fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        mutableReady.value = false
        scope.cancel()
        koin.get<ResourceRegistry>().closeAll()
        koinApplication.close()
    }

    /** The backend the graph actually connected for [apiId]; APIs without a facade stay UNAVAILABLE. */
    internal fun resolvedBackend(apiId: ApiId): Backend = when (apiId) {
        ApiId.ITEM_01 -> backendOf(koin.get<CreateItemRepository>(ITEM_01_DELEGATE))
        ApiId.ITEM_03 -> backendOf(koin.get<GetItemRepository>(ITEM_03_DELEGATE))
        else -> Backend.UNAVAILABLE
    }

    // Facades are resolved on request; the first one that needs the driver/engine opens it.
    private inline fun <T> resolveOr(closedFallback: T, resolve: Koin.() -> T): T =
        if (closed.value) closedFallback else koin.resolve()
}
