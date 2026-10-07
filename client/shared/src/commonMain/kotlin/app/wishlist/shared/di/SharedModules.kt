package app.wishlist.shared.di

import app.cash.sqldelight.db.SqlDriver
import app.wishlist.shared.core.ApiId
import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.Clock
import app.wishlist.shared.core.IdGenerator
import app.wishlist.shared.core.MutableAuthSession
import app.wishlist.shared.core.RuntimeDispatchers
import app.wishlist.shared.data.fake.FakeCatalogRepository
import app.wishlist.shared.data.fake.FakeItemRepository
import app.wishlist.shared.data.fake.FakeStore
import app.wishlist.shared.data.local.CachedGetItemRepository
import app.wishlist.shared.data.local.SqlLocalStore
import app.wishlist.shared.data.remote.AuthTokenProvider
import app.wishlist.shared.data.remote.AuthenticatedTransport
import app.wishlist.shared.data.remote.CallbackAuthTokenProvider
import app.wishlist.shared.data.remote.RemoteItemRepository
import app.wishlist.shared.data.remote.createWishlistHttpClient
import app.wishlist.shared.repository.CatalogRepository
import app.wishlist.shared.repository.CreateItemRepository
import app.wishlist.shared.repository.GetItemRepository
import app.wishlist.shared.repository.LocalStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.bind
import org.koin.dsl.module

/** Platform seams: the SQL driver and HTTP engine are opened lazily, on first use, by the graph. */
internal class PlatformResources(
    val openDriver: () -> SqlDriver,
    val createEngine: () -> HttpClientEngine,
)

internal class RuntimeEnvironment(
    val bindings: RepositoryBindings,
    val remote: RemoteConfig?,
    val platform: PlatformResources,
    val clock: Clock,
    val ids: IdGenerator,
    val dispatchers: RuntimeDispatchers,
)

/**
 * Closes every resource the graph created exactly once, newest first (HttpClient before its
 * engine). A resource created after [closeAll] (a request racing close) is closed immediately.
 * One failing close does not keep the others open.
 */
internal class ResourceRegistry {
    private val closers = MutableStateFlow<List<() -> Unit>?>(emptyList())

    fun <T> register(resource: T, close: (T) -> Unit): T {
        val closer = { close(resource) }
        while (true) {
            val current = closers.value ?: return resource.also { runClose(closer) }
            if (closers.compareAndSet(current, current + closer)) return resource
        }
    }

    fun closeAll() {
        closers.getAndUpdate { null }?.asReversed()?.forEach(::runClose)
    }

    private fun runClose(closer: () -> Unit) {
        try {
            closer()
        } catch (_: Exception) {
            // Best effort: the remaining resources must still be released.
        }
    }
}

/** Delegate qualifiers: the selected backend for one API, before any facade decoration. */
internal val ITEM_01_DELEGATE = named("ITEM_01")
internal val ITEM_03_DELEGATE = named("ITEM_03")

/**
 * The runtime graph. Every component gets the one [MutableAuthSession] of this graph. Fake parts
 * exist only in DEBUG (the seed catalog needs them even if no API is FAKE); Remote parts only when
 * some API is REMOTE. ITEM-01/03 are wired from their explicit binding, never from the build mode.
 */
internal fun sharedModules(env: RuntimeEnvironment): List<Module> = buildList {
    add(coreModule(env))
    if (env.bindings.buildMode == ClientBuildMode.DEBUG) add(fakeModule())
    env.remote?.let { add(remoteModule(env, it)) }
}

private fun coreModule(env: RuntimeEnvironment) = module {
    single { MutableAuthSession() } bind AuthSession::class
    single { env.clock }
    single { env.ids }
    single { ResourceRegistry() }
    single<SqlDriver> { get<ResourceRegistry>().register(env.platform.openDriver()) { it.close() } }
    single<LocalStore> { SqlLocalStore(get(), get()) }
    single<CreateItemRepository>(ITEM_01_DELEGATE) { itemBackend(env.bindings.backendOf(ApiId.ITEM_01)) }
    single<GetItemRepository>(ITEM_03_DELEGATE) { itemBackend(env.bindings.backendOf(ApiId.ITEM_03)) }
    // The Get facade owns cache sync: the selected delegate is wrapped exactly once.
    single<GetItemRepository> { CachedGetItemRepository(get(ITEM_03_DELEGATE), get(), get()) }
    single<CatalogRepository> {
        if (env.bindings.buildMode == ClientBuildMode.DEBUG) get<FakeCatalogRepository>() else UnavailableCatalogRepository
    }
}

private fun fakeModule() = module {
    single { FakeStore(get(), get(), get()) }
    single { FakeItemRepository(get()) }
    single { FakeCatalogRepository(get()) }
}

private fun remoteModule(env: RuntimeEnvironment, remote: RemoteConfig) = module {
    single<HttpClientEngine> { get<ResourceRegistry>().register(env.platform.createEngine()) { it.close() } }
    // HttpClient never closes an engine it was handed, so both are registered.
    single<HttpClient> { get<ResourceRegistry>().register(createWishlistHttpClient(get())) { it.close() } }
    single<AuthTokenProvider> { CallbackAuthTokenProvider(get(), remote.tokenSource) }
    single { AuthenticatedTransport(get(), get(), get(), remote.baseUrl) }
    single { RemoteItemRepository(get(), get()) }
}

/** Item API implementation for one explicit binding (FakeItemRepository/RemoteItemRepository implement both). */
private inline fun <reified T : Any> Scope.itemBackend(backend: Backend): T = when (backend) {
    Backend.FAKE -> get<FakeItemRepository>()
    Backend.REMOTE -> get<RemoteItemRepository>()
    Backend.UNAVAILABLE -> UnavailableItemRepository
} as T

/** The backend a resolved item delegate actually is (the verification target, not the bindings). */
internal fun backendOf(delegate: Any): Backend = when (delegate) {
    is FakeItemRepository -> Backend.FAKE
    is RemoteItemRepository -> Backend.REMOTE
    else -> Backend.UNAVAILABLE
}
