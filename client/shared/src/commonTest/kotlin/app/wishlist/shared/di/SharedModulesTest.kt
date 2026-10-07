@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.wishlist.shared.di

import app.wishlist.shared.core.ApiId
import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.Clock
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.IdGenerator
import app.wishlist.shared.core.RuntimeDispatchers
import app.wishlist.shared.core.MutableAuthSession
import app.wishlist.shared.data.fake.FakeItemRepository
import app.wishlist.shared.data.fake.FakeStore
import app.wishlist.shared.data.fake.error
import app.wishlist.shared.data.fake.successValue
import app.wishlist.shared.data.local.CachedGetItemRepository
import app.wishlist.shared.data.local.item
import app.wishlist.shared.data.local.itemId
import app.wishlist.shared.data.remote.BASE_ITEM_JSON
import app.wishlist.shared.data.remote.FakeTokenSource
import app.wishlist.shared.data.remote.RemoteItemRepository
import app.wishlist.shared.data.remote.TEST_BASE_URL
import app.wishlist.shared.presentation.ItemDetailState
import app.wishlist.shared.di.ClientBuildMode.DEBUG
import app.wishlist.shared.di.ClientBuildMode.RELEASE
import app.wishlist.shared.repository.CreateItemCommand
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SharedModulesTest {
    private val remoteItemId = "00000000-0000-4000-8000-000000000101"
    private val boardOrder = listOf(
        "소니 WH-1000XM6", "보스 QuietComfort Ultra", "젠하이저 MOMENTUM 4", "애플 AirPods Max",
        "마샬 MAJOR V", "뱅앤올룹슨 Beoplay H95", "소니 ULT WEAR", "오디오테크니카 ATH-M50x",
    )

    // --- Explicit bindings -------------------------------------------------------------------

    @Test fun bindings_must_list_all_37_apis() {
        assertEquals(37, ApiId.entries.size)
        assertFailsWith<IllegalArgumentException> {
            createRuntime(RepositoryBindings(RELEASE, allBackends(Backend.UNAVAILABLE) - ApiId.ITEM_02))
        }
        assertFailsWith<IllegalArgumentException> {
            createRuntime(RepositoryBindings(DEBUG, debugBindings().backends - ApiId.MEDIA_02))
        }
    }

    @Test fun release_rejects_any_fake_binding() {
        val bindings = ApiId.entries.associateWith { Backend.UNAVAILABLE } +
            (ApiId.ITEM_03 to Backend.FAKE)
        assertFailsWith<IllegalArgumentException> {
            createRuntime(RepositoryBindings(RELEASE, bindings))
        }
    }

    @Test fun release_all_unavailable_is_valid() {
        val bindings = ApiId.entries.associateWith { Backend.UNAVAILABLE }
        val runtime = createRuntime(RepositoryBindings(RELEASE, bindings))
        assertEquals(Backend.UNAVAILABLE, runtime.resolvedBackend(ApiId.ITEM_03))
        runtime.close()
    }

    @Test fun unimplemented_api_cannot_be_bound_to_remote_or_fake() {
        val remote = RemoteConfig(TEST_BASE_URL, FakeTokenSource())
        assertFailsWith<IllegalArgumentException> { createRuntime(debugBindings(ApiId.HOME_01 to Backend.REMOTE), remote) }
        assertFailsWith<IllegalArgumentException> { createRuntime(debugBindings(ApiId.CAT_01 to Backend.FAKE)) }
        // The seed catalog does not make ITEM-02/CAT-01/PUR-01 Fake wire APIs.
        assertFailsWith<IllegalArgumentException> { createRuntime(debugBindings(ApiId.ITEM_02 to Backend.FAKE)) }
    }

    @Test fun remote_config_is_required_if_and_only_if_some_api_is_remote() {
        assertFailsWith<IllegalArgumentException> { createRuntime(debugBindings(ApiId.ITEM_03 to Backend.REMOTE)) }
        assertFailsWith<IllegalArgumentException> {
            createRuntime(debugBindings(), RemoteConfig(TEST_BASE_URL, FakeTokenSource()))
        }
        val release = RepositoryBindings(RELEASE, allBackends(Backend.UNAVAILABLE) + (ApiId.ITEM_01 to Backend.REMOTE))
        val runtime = createRuntime(release, RemoteConfig(TEST_BASE_URL, FakeTokenSource()))
        assertEquals(Backend.REMOTE, runtime.resolvedBackend(ApiId.ITEM_01))
        assertEquals(Backend.UNAVAILABLE, runtime.resolvedBackend(ApiId.ITEM_03))
        runtime.close()
    }

    @Test fun each_api_resolves_its_own_backend() {
        val mixed = createRuntime(
            debugBindings(ApiId.ITEM_01 to Backend.UNAVAILABLE, ApiId.ITEM_03 to Backend.REMOTE),
            RemoteConfig(TEST_BASE_URL, FakeTokenSource()),
        )
        assertEquals(Backend.UNAVAILABLE, mixed.resolvedBackend(ApiId.ITEM_01))
        assertEquals(Backend.REMOTE, mixed.resolvedBackend(ApiId.ITEM_03))
        assertEquals(Backend.UNAVAILABLE, mixed.resolvedBackend(ApiId.ITEM_02))
        mixed.close()

        val debug = createRuntime(debugBindings())
        assertEquals(Backend.FAKE, debug.resolvedBackend(ApiId.ITEM_01))
        assertEquals(Backend.FAKE, debug.resolvedBackend(ApiId.ITEM_03))
        ApiId.entries.filter { it != ApiId.ITEM_01 && it != ApiId.ITEM_03 }
            .forEach { assertEquals(Backend.UNAVAILABLE, debug.resolvedBackend(it), it.wireId) }
        debug.close()
    }

    // --- RELEASE graph --------------------------------------------------------------------------

    @Test fun release_graph_has_no_fake_and_is_ready_but_every_request_is_unavailable() = runTest {
        val runtime = createRuntime(RepositoryBindings(RELEASE, allBackends(Backend.UNAVAILABLE)))
        assertNull(runtime.koin.getOrNull<FakeStore>())
        assertTrue(runtime.ready.value)
        assertFailsWith<IllegalStateException> { runtime.startDebugSession() }

        (runtime.session as MutableAuthSession).changeAccount("release-user")
        val get = runtime.getItemRepository().get(remoteItemId).error()
        assertEquals(ErrorKind.UNAVAILABLE, get.kind)
        assertEquals(API_UNAVAILABLE, get.code)
        val create = runtime.createItemRepository().create(CreateItemCommand(itemId, "https://shop.example/p", null))
        assertEquals(API_UNAVAILABLE, create.error().code)
        assertEquals(API_UNAVAILABLE, runtime.catalogRepository().items(null, null).error().code)
        assertEquals(emptyList(), runtime.localStore().pending().successValue())
        runtime.close()
    }

    // --- DEBUG bootstrap ------------------------------------------------------------------------

    @Test fun debug_requests_before_ready_are_unavailable() = runTest {
        val runtime = createRuntime(debugBindings(), dispatcher = StandardTestDispatcher(testScheduler))
        assertFalse(runtime.ready.value)
        assertEquals(RUNTIME_NOT_READY, runtime.getItemRepository().get(remoteItemId).error().code)
        assertEquals(RUNTIME_NOT_READY, runtime.catalogRepository().items(null, null).error().code)
        assertEquals(RUNTIME_NOT_READY, runtime.localStore().pending().error().code)

        runtime.startDebugSession()
        // The bootstrap is queued on the runtime dispatcher; until it runs the runtime is not ready.
        assertFalse(runtime.ready.value)
        assertEquals(ErrorKind.UNAVAILABLE, runtime.createItemRepository()
            .create(CreateItemCommand(itemId, "https://shop.example/p", null)).error().kind)
        advanceUntilIdle()
        assertTrue(runtime.ready.value)
        runtime.close()
    }

    @Test fun debug_bootstrap_changes_account_then_seeds_that_namespace_then_reports_ready() = runTest {
        val runtime = createRuntime(debugBindings(), dispatcher = StandardTestDispatcher(testScheduler))
        val accountWhenReady = mutableListOf<String?>()
        // A foreground collector: advanceUntilIdle does not drive background-only work.
        launch {
            runtime.ready.first { it }
            accountWhenReady += runtime.session.state.value.accountId
        }
        runtime.startDebugSession()
        runtime.startDebugSession() // idempotent: one account change, one seed
        advanceUntilIdle()

        assertEquals(listOf<String?>(DEBUG_ACCOUNT_ID), accountWhenReady)
        assertNull(runtime.bootstrapFailure.value)
        assertEquals(1L, runtime.session.state.value.generation)
        val items = runtime.catalogRepository().items(null, null).successValue()
        assertEquals(boardOrder, items.map { it.product.name })
        assertEquals(7, runtime.catalogRepository().purposes().successValue().size)
        runtime.close()
    }

    // --- Cached Get facade ----------------------------------------------------------------------

    @Test fun get_facade_wraps_the_selected_delegate_in_one_cache_layer_and_syncs_the_cache() = runTest {
        val runtime = createRuntime(debugBindings())
        runtime.startDebugSession()
        runtime.ready.first { it }

        val facade = assertIs<GatedGetItemRepository>(runtime.getItemRepository())
        val cached = assertIs<CachedGetItemRepository>(facade.delegate)
        assertIs<FakeItemRepository>(cached.delegate)

        val seeded = runtime.catalogRepository().items(null, null).successValue().first()
        val snapshot = runtime.session.state.value
        assertNull(runtime.localStore().cachedItem(snapshot, seeded.id).successValue())
        assertEquals(seeded, runtime.getItemRepository().get(seeded.id).successValue())
        assertEquals(seeded.version, runtime.localStore().cachedItem(snapshot, seeded.id).successValue()?.version)
        runtime.close()
    }

    @Test fun unavailable_get_returns_unavailable_and_keeps_the_cache() = runTest {
        val runtime = createRuntime(debugBindings(ApiId.ITEM_03 to Backend.UNAVAILABLE))
        runtime.startDebugSession()
        runtime.ready.first { it }
        val cached = assertIs<CachedGetItemRepository>(assertIs<GatedGetItemRepository>(runtime.getItemRepository()).delegate)
        assertSame(UnavailableItemRepository, cached.delegate)

        val snapshot = runtime.session.state.value
        runtime.localStore().upsertItem(snapshot, item(version = 3)).successValue()
        val error = runtime.getItemRepository().get(itemId).error()
        assertEquals(ErrorKind.UNAVAILABLE, error.kind)
        assertEquals(API_UNAVAILABLE, error.code)
        assertEquals(3, runtime.localStore().cachedItem(snapshot, itemId).successValue()?.version)
        runtime.close()
    }

    @Test fun remote_get_uses_the_runtime_session_token_source_and_cache() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val probe = RuntimeResourcesProbe {
            MockEngine { request ->
                requests += request
                respond(BASE_ITEM_JSON, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        val tokens = FakeTokenSource().apply { respondToken("debug-token") }
        val runtime = createRuntime(
            debugBindings(ApiId.ITEM_01 to Backend.REMOTE, ApiId.ITEM_03 to Backend.REMOTE),
            RemoteConfig(TEST_BASE_URL, tokens), probe,
        )
        val cached = assertIs<CachedGetItemRepository>(assertIs<GatedGetItemRepository>(runtime.getItemRepository()).delegate)
        assertIs<RemoteItemRepository>(cached.delegate)
        runtime.startDebugSession()
        runtime.ready.first { it }

        val item = runtime.getItemRepository().get(remoteItemId).successValue()
        assertEquals(2, item.version)
        assertEquals("Bearer debug-token", requests.single().headers[HttpHeaders.Authorization])
        assertEquals("$TEST_BASE_URL/v1/wishlist-items/$remoteItemId", requests.single().url.toString())
        assertEquals(2, runtime.localStore().cachedItem(runtime.session.state.value, remoteItemId).successValue()?.version)
        runtime.close()
    }

    // --- Item detail Presenter from the runtime ---------------------------------------------------

    @Test fun runtime_presenter_reads_the_gated_get_facade_and_follows_the_runtime_session() = runTest {
        val runtime = createRuntime(debugBindings(), dispatcher = StandardTestDispatcher(testScheduler))
        val presenter = runtime.itemDetailPresenter()

        // Before ready, the gated facade answers; the Presenter reports it as an error state.
        presenter.load(remoteItemId)
        advanceUntilIdle()
        assertEquals(RUNTIME_NOT_READY, presenter.state.value.error?.code)

        // The debug bootstrap changes the runtime session's account: the Presenter starts over.
        runtime.startDebugSession()
        advanceUntilIdle()
        assertEquals(ItemDetailState.Initial, presenter.state.value)

        val seeded = runtime.catalogRepository().items(null, null).successValue().first()
        presenter.load(seeded.id)
        advanceUntilIdle()
        assertEquals(ItemDetailState(item = seeded, loading = false, error = null), presenter.state.value)
        // Cache sync stays with the Get facade's decorator.
        val snapshot = runtime.session.state.value
        assertEquals(seeded.version, runtime.localStore().cachedItem(snapshot, seeded.id).successValue()?.version)

        (runtime.session as MutableAuthSession).changeAccount("account-b")
        advanceUntilIdle()
        assertEquals(ItemDetailState.Initial, presenter.state.value)
        presenter.retry()
        advanceUntilIdle()
        assertEquals(ErrorKind.NOT_FOUND, presenter.state.value.error?.kind)

        presenter.close()
        runtime.close()
        // A Presenter created after close reads the closed facade.
        val late = runtime.itemDetailPresenter()
        late.load(seeded.id)
        advanceUntilIdle()
        assertEquals(RUNTIME_NOT_READY, late.state.value.error?.code)
        late.close()
    }

    // --- Isolation and one session per runtime --------------------------------------------------

    @Test fun runtimes_are_isolated_and_every_component_shares_the_runtime_session() = runTest {
        val a = createRuntime(debugBindings())
        val b = createRuntime(debugBindings())
        assertNotSame(a.koin, b.koin)
        assertNotSame(a.session, b.session)
        assertSame(a.session, a.koin.get<AuthSession>())
        assertSame(a.session, a.koin.get<MutableAuthSession>())
        assertNotSame(a.koin.get<FakeStore>(), b.koin.get<FakeStore>())

        a.startDebugSession()
        a.ready.first { it }
        assertFalse(b.ready.value)
        assertNull(b.session.state.value.accountId)

        val seeded = a.catalogRepository().items(null, null).successValue().first()
        val before = a.session.state.value
        a.getItemRepository().get(seeded.id).successValue()
        // Switching A's one session moves Fake and the local cache together; B never sees A's data.
        (a.session as MutableAuthSession).changeAccount("someone-else")
        assertEquals(ErrorKind.SESSION_CHANGED, a.localStore().cachedItem(before, seeded.id).error().kind)
        assertEquals(ErrorKind.NOT_FOUND, a.getItemRepository().get(seeded.id).error().kind)
        assertNull(a.localStore().cachedItem(a.session.state.value, seeded.id).successValue())
        assertEquals(RUNTIME_NOT_READY, b.getItemRepository().get(seeded.id).error().code)
        a.close()
        b.close()
    }

    // --- Resource lifetime ----------------------------------------------------------------------

    @Test fun close_releases_http_client_engine_and_driver_exactly_once() = runTest {
        val probe = RuntimeResourcesProbe {
            MockEngine { respond(BASE_ITEM_JSON, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        }
        val runtime = createRuntime(
            debugBindings(ApiId.ITEM_03 to Backend.REMOTE),
            RemoteConfig(TEST_BASE_URL, FakeTokenSource().apply { respondToken("t") }), probe,
        )
        runtime.startDebugSession()
        runtime.ready.first { it }
        runtime.getItemRepository().get(remoteItemId).successValue()
        assertEquals(1, probe.drivers.size)
        assertEquals(1, probe.engines.size)

        runtime.close()
        runtime.close()
        assertEquals(1, probe.drivers.single().closes)
        assertEquals(1, probe.engines.single().closes)
        assertFalse(runtime.ready.value)
        assertEquals(RUNTIME_NOT_READY, runtime.getItemRepository().get(remoteItemId).error().code)
        assertEquals(1, probe.drivers.single().closes)
    }

    @Test fun runtime_without_remote_never_creates_an_engine_and_closes_its_driver_once() = runTest {
        val probe = RuntimeResourcesProbe()
        val runtime = createRuntime(debugBindings(), probe = probe)
        runtime.startDebugSession()
        runtime.ready.first { it }
        runtime.localStore().pending().successValue()
        runtime.close()
        runtime.close()
        assertTrue(probe.engines.isEmpty())
        assertEquals(1, probe.drivers.size)
        assertEquals(1, probe.drivers.single().closes)
    }

    @Test fun close_between_seed_and_ready_publication_leaves_the_runtime_not_ready() = runTest {
        val runtime = createRuntime(debugBindings(), dispatcher = StandardTestDispatcher(testScheduler))
        // close() lands after the bootstrap decided to publish but before it writes ready.
        runtime.beforeReadyPublished = { runtime.close() }
        runtime.startDebugSession()
        advanceUntilIdle()

        assertFalse(runtime.ready.value)
        assertEquals(RUNTIME_NOT_READY, runtime.getItemRepository().get(remoteItemId).error().code)
        assertEquals(RUNTIME_NOT_READY, runtime.catalogRepository().items(null, null).error().code)
    }

    @Test fun close_during_first_driver_open_does_not_throw_and_still_closes_the_driver_once() = runTest {
        val probe = RuntimeResourcesProbe()
        val runtime = createRuntime(debugBindings(), probe = probe)
        runtime.startDebugSession()
        runtime.ready.first { it }
        var closes = 0
        // close() arrives while the first real store use is opening the driver (lookups open nothing).
        probe.onDriverOpen = { if (closes++ == 0) runtime.close() }

        val facade = runtime.getItemRepository()
        assertTrue(probe.drivers.isEmpty())
        val failure = facade.get(remoteItemId).error()
        assertEquals(ErrorKind.UNAVAILABLE, failure.kind)
        assertFalse(runtime.ready.value)
        assertEquals(RUNTIME_NOT_READY, facade.get(remoteItemId).error().code)
        assertEquals(1, probe.drivers.size)
        assertEquals(1, probe.drivers.single().closes)
        // Everything after close stays a typed failure, never an exception.
        assertEquals(RUNTIME_NOT_READY, runtime.localStore().pending().error().code)
        runtime.startDebugSession()
        runtime.close()
        assertFalse(runtime.ready.value)
        assertEquals(1, probe.drivers.single().closes)
    }

    @Test fun facadeLookupDoesNotOpenDriverOnCallerThread() = runTest {
        val probe = RuntimeResourcesProbe()
        val runtime = createRuntime(releaseBindings(), probe = probe)
        runtime.getItemRepository(); runtime.localStore()
        assertEquals(0, probe.drivers.size)                    // a facade lookup alone opens nothing
        runtime.localStore().pending()
        assertEquals(1, probe.drivers.size)                    // the first real use opens it on the io dispatcher
        runtime.close()
    }

    @Test fun cancelledFirstOpenStillOpensTheDriverOnlyOnce() = runTest {
        val probe = RuntimeResourcesProbe()
        val runtime = createRuntime(releaseBindings(), probe = probe)
        lateinit var caller: Job
        // The caller is cancelled while the first open is running (the driver is already being created).
        probe.onDriverOpen = { caller.cancel() }
        caller = launch { runtime.localStore().pending() }
        caller.join()
        assertTrue(caller.isCancelled)
        probe.onDriverOpen = {}
        runtime.localStore().pending().successValue()
        assertEquals(1, probe.drivers.size)                    // the next use reuses the driver opened above
        runtime.close()
        assertEquals(1, probe.drivers.single().closes)
    }

    @Test fun driverIsOpenedOnTheIoDispatcher() = runTest {
        val io = RecordingDispatcher()
        val probe = RuntimeResourcesProbe()
        val runtime = assembleSharedRuntime(
            bindings = releaseBindings(), remote = null, platform = probe.platform,
            clock = Clock { runtimeTime }, ids = IdGenerator { "id" },
            dispatchers = RuntimeDispatchers(default = Dispatchers.Unconfined, io = io),
        )
        var openedOnIo = false
        probe.onDriverOpen = { openedOnIo = io.inside }
        runtime.localStore().pending().successValue()
        assertTrue(openedOnIo)
        assertEquals(1, io.dispatches)
        runtime.localStore().pending().successValue()
        assertEquals(1, io.dispatches)                         // opened once, never re-dispatched
        runtime.close()
    }

    @Test fun debugSeedFailureStillPublishesReadyAndReportsError() = runTest {
        val runtime = createRuntime(debugBindings(), seedOverride = { ClientResult.Failure(ClientError(ErrorKind.VALIDATION)) })
        runtime.startDebugSession()
        assertTrue(runtime.ready.value)
        assertEquals(ErrorKind.VALIDATION, runtime.bootstrapFailure.value?.kind)
        runtime.close()
    }

    @Test fun unexpectedBootstrapExceptionIsReportedNotThrown() = runTest {
        val runtime = createRuntime(debugBindings(), seedOverride = { throw IllegalStateException("seed exploded") })
        runtime.startDebugSession()
        assertTrue(runtime.ready.value)
        val failure = runtime.bootstrapFailure.value
        assertEquals(ErrorKind.UNAVAILABLE, failure?.kind)
        assertEquals(BOOTSTRAP_FAILURE, failure?.code)
        // The runtime still serves requests after a failed bootstrap.
        assertEquals(emptyList(), runtime.localStore().pending().successValue())
        runtime.close()
    }

    @Test fun closing_an_unused_runtime_opens_nothing() {
        val probe = RuntimeResourcesProbe()
        createRuntime(RepositoryBindings(RELEASE, allBackends(Backend.UNAVAILABLE)), probe = probe).close()
        assertTrue(probe.drivers.isEmpty())
        assertTrue(probe.engines.isEmpty())
    }
}

/** Runs blocks inline but records that they were dispatched to it (the runtime's io seam). */
private class RecordingDispatcher : CoroutineDispatcher() {
    var dispatches = 0
        private set
    var inside = false
        private set

    override fun isDispatchNeeded(context: kotlin.coroutines.CoroutineContext): Boolean = true

    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
        dispatches++
        inside = true
        try { block.run() } finally { inside = false }
    }
}
