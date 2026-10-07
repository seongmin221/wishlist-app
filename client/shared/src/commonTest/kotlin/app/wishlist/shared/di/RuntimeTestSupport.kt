@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, io.ktor.utils.io.InternalAPI::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.wishlist.shared.di

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.wishlist.shared.core.ApiId
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.Clock
import app.wishlist.shared.core.IdGenerator
import app.wishlist.shared.core.RuntimeDispatchers
import app.wishlist.shared.data.local.WishlistDatabase
import app.wishlist.shared.data.local.deleteTestDb
import app.wishlist.shared.data.local.newTestDbPath
import app.wishlist.shared.data.local.openTestDriver
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

internal val runtimeTime = Instant.parse("2026-10-07T00:00:00Z")

/** Counts closes on top of a real file-backed SQLite driver; the DB file is removed on close. */
internal class CountingDriver(private val delegate: SqlDriver, private val path: String) : SqlDriver by delegate {
    // Written by whichever thread runs the teardown (possibly the io thread).
    @Volatile var closes = 0
        private set

    /** Test seam: runs on the querying thread right before each SELECT reaches SQLite. */
    @Volatile var beforeQuery: (sql: String) -> Unit = {}

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        beforeQuery(sql)
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    override fun close() {
        closes++
        delegate.close()
        deleteTestDb(path)
    }
}

/**
 * Parks one real thread (e.g. the io thread inside a blocking SQLite call) until [release]. The
 * blocked side spins instead of suspending because the SQL driver API is blocking; it gives up
 * after [limit] so a failing test never hangs the run.
 */
internal class ThreadGate(private val limit: Duration = 10.seconds) {
    private val entered = MutableStateFlow(false)
    private val released = MutableStateFlow(false)

    fun block() {
        entered.value = true
        val start = TimeSource.Monotonic.markNow()
        while (!released.value) check(start.elapsedNow() < limit) { "ThreadGate was never released" }
    }

    /** Waits in real time (not the test scheduler's virtual time) until a thread is parked. */
    suspend fun awaitEntered() = withContext(Dispatchers.Default) { withTimeout(limit) { entered.first { it } } }

    fun release() {
        released.value = true
    }
}

/** Counts closes of the engine the runtime owns (HttpClient never closes an engine it was handed). */
internal class CountingEngine(private val delegate: HttpClientEngine) : HttpClientEngine by delegate {
    var closes = 0
        private set

    override fun close() {
        closes++
        delegate.close()
    }
}

/** Test seams for one runtime: real SQLite file, scripted MockEngine, counted opens/closes. */
internal class RuntimeResourcesProbe(
    private val engineFactory: () -> HttpClientEngine = { MockEngine { respondError(HttpStatusCode.NotFound) } },
) {
    val drivers = mutableListOf<CountingDriver>()
    val engines = mutableListOf<CountingEngine>()

    /** app_state rows written into the database before the graph first uses it (e.g. a saved login). */
    var appState: Map<String, String> = emptyMap()

    /** Runs inside the graph's driver creation, i.e. in the middle of a facade resolution. */
    var onDriverOpen: () -> Unit = {}

    val platform = PlatformResources(
        openDriver = {
            onDriverOpen()
            newTestDbPath().let { path ->
                CountingDriver(openTestDriver(path), path).also { driver ->
                    drivers += driver
                    appState.forEach { (key, value) -> WishlistDatabase(driver).wishlistQueries.upsertAppState(key, value) }
                }
            }
        },
        createEngine = { CountingEngine(engineFactory()).also { engines += it } },
        utcOffsetSeconds = { TEST_UTC_OFFSET_SECONDS },
    )
}

internal fun allBackends(backend: Backend): Map<ApiId, Backend> = ApiId.entries.associateWith { backend }

/** DEBUG with C2's Fake APIs (ITEM-01, ITEM-03); every other API explicitly UNAVAILABLE. */
internal fun debugBindings(vararg overrides: Pair<ApiId, Backend>) = RepositoryBindings(
    ClientBuildMode.DEBUG,
    allBackends(Backend.UNAVAILABLE) + mapOf(ApiId.ITEM_01 to Backend.FAKE, ApiId.ITEM_03 to Backend.FAKE) + overrides,
)

/** RELEASE with every API explicitly UNAVAILABLE (the only valid all-local RELEASE). */
internal fun releaseBindings() = RepositoryBindings(ClientBuildMode.RELEASE, allBackends(Backend.UNAVAILABLE))

/** DI test helper: an isolated runtime over test seams. Always [SharedRuntime.close] it. */
internal fun createRuntime(
    bindings: RepositoryBindings,
    remote: RemoteConfig? = null,
    probe: RuntimeResourcesProbe = RuntimeResourcesProbe(),
    dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
    seedOverride: (suspend () -> ClientResult<Unit>)? = null,
    clock: Clock = Clock { runtimeTime },
    ids: IdGenerator = IdGenerator { Uuid.random().toString() },
): SharedRuntime = assembleSharedRuntime(
    bindings = bindings,
    remote = remote,
    platform = probe.platform,
    clock = clock,
    ids = ids,
    dispatchers = RuntimeDispatchers(default = dispatcher, io = dispatcher),
    seedOverride = seedOverride,
)

/** Fixed device offset (KST) so relative-date tests do not depend on the host zone. */
internal const val TEST_UTC_OFFSET_SECONDS = 9 * 3600
