@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.uuid.ExperimentalUuidApi::class)
package app.wishlist.shared.data.fake

import app.wishlist.shared.core.*
import app.wishlist.shared.model.*
import app.wishlist.shared.repository.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal val fakeTime = Instant.parse("2026-10-07T00:00:00Z")
internal fun <T> ClientResult<T>.successValue(): T = when(this) {
    is ClientResult.Success -> value
    is ClientResult.Failure -> fail("Expected success: $error")
}
internal fun ClientResult<*>.error(): ClientError = when(this) {
    is ClientResult.Success -> fail("Expected failure: $value")
    is ClientResult.Failure -> error
}
internal class FakeFixture {
    val session = MutableAuthSession()
    val generatedItemIds = mutableListOf<String>()
    val store = FakeStore(session, Clock { fakeTime }, IdGenerator {
        Uuid.random().toString().also { generatedItemIds.add(it) }
    })
    val repository = FakeItemRepository(store)
    val controls = FakeControls(store)
    val catalog = FakeCatalogRepository(store)
    suspend fun login(owner: String? = "A") = session.changeAccount(owner)
    fun command(url: String = "https://shop.example/product") = CreateItemCommand(Uuid.random().toString(), url, fakeTime)
    suspend fun create() = repository.create(command()).successValue()
    suspend fun finish(item: WishlistItem, status: AnalysisStatus = AnalysisStatus.READY) {
        controls.completeAnalysis(item.id, 1, AnalysisOutcome(status, "Headphones", "C026", null, null)).successValue()
    }
}

class FakeItemRepositoryContractTest : RepositoryContractTest() {
    override suspend fun createFixture(): RepositoryContractFixture = object : RepositoryContractFixture {
        val fixture = FakeFixture()
        override suspend fun repositoriesFor(ownerId: String): ContractRepositories {
            fixture.login(ownerId)
            return ContractRepositories(fixture.repository, fixture.repository)
        }
        override suspend fun completeAnalysis(ownerId: String, itemId: String) {
            fixture.controls.completeAnalysis(itemId, 1,
                AnalysisOutcome(AnalysisStatus.READY, "Headphones", "C026", null, null)).successValue()
        }
        override suspend fun deleteItem(ownerId: String, itemId: String) { fixture.controls.delete(itemId).successValue() }
    }
}

class FakeItemRepositoryTest {
    @Test fun unauthenticated_calls_do_not_assign_a_session() = runTest {
        val f = FakeFixture()
        assertEquals(ErrorKind.UNAUTHENTICATED, f.repository.create(f.command()).error().kind)
        assertEquals(ErrorKind.UNAUTHENTICATED, f.catalog.categories().error().kind)
        assertEquals(null, f.session.state.value.accountId)
    }
    @Test fun original_url_reuse_reports_confirmed_code_without_version() = runTest {
        val f = FakeFixture(); f.login()
        val command = f.command(); f.repository.create(command).successValue()
        val error = f.repository.create(command.copy(sourceUrl = command.sourceUrl + "?other")).error()
        assertEquals(ErrorKind.CONFLICT, error.kind)
        assertEquals("IDEMPOTENCY_KEY_REUSED", error.code)
        assertNull(error.currentVersion)
    }
    @Test fun concurrent_replay_commits_one_id() = runTest {
        val f = FakeFixture(); f.login(); val command = f.command()
        val first = async { f.repository.create(command).successValue() }
        val second = async { f.repository.create(command).successValue() }
        assertEquals(first.await().id, second.await().id)
    }
    @Test fun delayed_create_cannot_write_after_account_switch() = runTest {
        val f = FakeFixture(); f.login(); val command = f.command()
        f.controls.delayNext(ApiId.ITEM_01, 100)
        val pending = async { f.repository.create(command) }; runCurrent()
        f.login("B"); advanceUntilIdle()
        assertEquals(ErrorKind.SESSION_CHANGED, pending.await().error().kind)
        assertTrue(f.generatedItemIds.isEmpty())
        f.login(); assertEquals(1, f.repository.create(command).successValue().version)
        assertEquals(1, f.generatedItemIds.size)
    }
    @Test fun delayed_get_is_discarded_after_logout_and_same_account_login() = runTest {
        val f = FakeFixture(); f.login(); val item = f.create()
        f.controls.delayNext(ApiId.ITEM_03, 100)
        val pending = async { f.repository.get(item.id) }; runCurrent()
        f.login(null); f.login(); advanceUntilIdle()
        assertEquals(ErrorKind.SESSION_CHANGED, pending.await().error().kind)
        assertEquals(item, f.repository.get(item.id).successValue())
    }
    @Test fun delayed_create_cannot_write_after_logout_and_same_account_login() = runTest {
        val f = FakeFixture(); f.login(); val command = f.command()
        f.controls.delayNext(ApiId.ITEM_01, 100)
        val pending = async { f.repository.create(command) }; runCurrent()
        f.login(null); f.login(); advanceUntilIdle()
        assertEquals(ErrorKind.SESSION_CHANGED, pending.await().error().kind)
        assertTrue(f.generatedItemIds.isEmpty())
        f.repository.create(command).successValue()
        assertEquals(1, f.generatedItemIds.size)
    }
    @Test fun delayed_get_is_discarded_after_account_switch() = runTest {
        val f = FakeFixture(); f.login(); val item = f.create()
        f.controls.delayNext(ApiId.ITEM_03, 100)
        val pending = async { f.repository.get(item.id) }; runCurrent()
        f.login("B"); advanceUntilIdle()
        assertEquals(ErrorKind.SESSION_CHANGED, pending.await().error().kind)
        assertEquals(ErrorKind.NOT_FOUND, f.repository.get(item.id).error().kind)
    }
    @Test fun injected_failure_is_delayed_once_and_has_no_creation_effect() = runTest {
        val f = FakeFixture(); f.login(); val command = f.command()
        val injected = ClientError(ErrorKind.NETWORK)
        f.controls.failNext(ApiId.ITEM_01, injected); f.controls.delayNext(ApiId.ITEM_01, 250)
        val pending = async { f.repository.create(command) }; runCurrent()
        assertFalse(pending.isCompleted); advanceTimeBy(250); runCurrent()
        assertEquals(injected, pending.await().error())
        assertTrue(f.generatedItemIds.isEmpty())
        assertEquals(1, f.repository.create(command).successValue().version)
        assertEquals(250, testScheduler.currentTime)
    }
    @Test fun session_change_also_discards_injected_error() = runTest {
        val f = FakeFixture(); f.login()
        f.controls.failNext(ApiId.ITEM_01, ClientError(ErrorKind.NETWORK))
        f.controls.delayNext(ApiId.ITEM_01, 100)
        val pending = async { f.repository.create(f.command()) }; runCurrent()
        f.login(null); f.login(); advanceUntilIdle()
        assertEquals(ErrorKind.SESSION_CHANGED, pending.await().error().kind)
    }
    @Test fun explicit_seed_is_owner_scoped_and_counts_membership() = runTest {
        val f = FakeFixture()
        val seeds = BoardSeeds.create(Clock { fakeTime }, IdGenerator { Uuid.random().toString() })
        assertEquals(ErrorKind.UNAUTHENTICATED, f.store.seed(seeds).error().kind)
        f.login(); f.store.seed(seeds).successValue()
        assertEquals(8, f.catalog.items("C026", null).successValue().size)
        assertEquals(4, f.catalog.items("C026", seeds.commuteId).successValue().size)
        assertEquals(0, f.catalog.items(null, seeds.carrierId).successValue().size)
        f.login("B"); assertEquals(0, f.catalog.items(null, null).successValue().size)
        assertTrue(f.catalog.purposes().successValue().isEmpty())
    }
    @Test fun scheduler_advancement_never_auto_completes_analysis() = runTest {
        val f = FakeFixture(); f.login(); val item = f.create()
        advanceTimeBy(1_000_000)
        assertEquals(AnalysisStatus.PROCESSING, f.repository.get(item.id).successValue().analysis.status)
    }
}
