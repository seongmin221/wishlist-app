package app.wishlist.shared.data.remote

import app.wishlist.shared.core.*
import app.wishlist.shared.data.fake.contractItem
import app.wishlist.shared.data.fake.error
import app.wishlist.shared.data.fake.fakeTime
import app.wishlist.shared.data.fake.successValue
import app.wishlist.shared.model.*
import app.wishlist.shared.repository.*
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The client session plus a Remote stack whose MockEngine serves [ScriptedCategoryServer]. */
private class RemoteCategoryHarness : CategoryContractFixture {
    var now: Instant = fakeTime
    private var itemSeq = 0
    val server = ScriptedCategoryServer(Clock { now })
    val session = MutableAuthSession()
    private val tokens = object : AuthTokenProvider {
        override suspend fun getToken(snapshot: SessionSnapshot, forceRefresh: Boolean): ClientResult<String> =
            ClientResult.Success("token-${snapshot.accountId}")
    }
    private val transport = transportOf(session, tokens, server.engine)
    val categories = RemoteCategoryRepository(session, transport)
    val items = RemoteItemListRepository(session, transport)

    override suspend fun repositoriesFor(ownerId: String): CategoryContractRepositories {
        if (session.state.value.accountId != ownerId) session.changeAccount(ownerId)
        return CategoryContractRepositories(categories, items)
    }

    override suspend fun placeItem(ownerId: String, categoryId: String, name: String?, lifecycle: LifecycleStatus): String =
        server.asOwner(ownerId) { server.store.putItem(contractItem(++itemSeq, categoryId, name, lifecycle)).successValue().id }

    override fun advanceSeconds(seconds: Long) { now += seconds.seconds }
    override suspend fun close() = server.engine.close()
}

class RemoteCategoryRepositoryContractTest : CategoryRepositoryContractTest() {
    override suspend fun createFixture(): CategoryContractFixture = RemoteCategoryHarness()
}

class RemoteItemListRepositoryContractTest : ItemListRepositoryContractTest() {
    override suspend fun createFixture(): CategoryContractFixture = RemoteCategoryHarness()
}

class RemoteCategoryRepositoryTest {
    private val owner = "00000000-0000-4000-8000-00000000000a"
    private val key = "00000000-0000-4000-9000-000000000001"

    @Test fun createSendsTheKeyAndReplayIs200() = runTest {
        val h = RemoteCategoryHarness(); h.session.changeAccount(owner)
        val command = CategoryCommand("G003", "책상 장비", null, listOf("키보드"))
        h.categories.create(command, key).successValue()
        h.categories.create(command, key).successValue()
        assertEquals(listOf(201, 200), h.server.statuses)
        val request = h.server.engine.requestHistory.first()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("$TEST_BASE_URL/v1/custom-categories", request.url.toString())
        assertEquals(key, request.headers["Idempotency-Key"])
        assertEquals("Bearer token-$owner", request.authorization())
        // A null description is omitted; examples are always sent.
        assertEquals("""{"parentId":"G003","name":"책상 장비","examples":["키보드"]}""", request.bodyText())
    }

    @Test fun patchSendsOnlyChangedFieldsAndAnExplicitNull() = runTest {
        val h = RemoteCategoryHarness(); h.session.changeAccount(owner)
        val created = h.categories.create(CategoryCommand("G003", "책상", "설명", listOf("a")), key).successValue().category
        h.categories.update(created.id, 1, CategoryPatch(description = DescriptionChange.Set(null))).successValue()
        h.categories.update(created.id, 2, CategoryPatch(name = "책상 장비", examples = emptyList())).successValue()
        val patches = h.server.engine.requestHistory.filter { it.method == HttpMethod.Patch }
        assertEquals("""{"expectedVersion":1,"description":null}""", patches[0].bodyText())
        assertEquals("""{"expectedVersion":2,"name":"책상 장비","examples":[]}""", patches[1].bodyText())
        assertEquals("$TEST_BASE_URL/v1/custom-categories/${created.id}", patches[0].url.toString())
    }

    @Test fun catalogAndListQueries() = runTest {
        val h = RemoteCategoryHarness(); h.session.changeAccount(owner)
        h.categories.catalog(CategoryScope.BROWSE, "G003").successValue()
        // An unknown cursor is refused (400); only the request shape matters here.
        assertEquals(ErrorKind.VALIDATION, h.items.byCategory("C026", cursor = "c/1?#", limit = 40).error().kind)
        val (catalog, list) = h.server.engine.requestHistory
        assertEquals("BROWSE", catalog.url.parameters["scope"])
        assertEquals("G003", catalog.url.parameters["parentId"])
        assertEquals("/v1/wishlist-items", list.url.encodedPath)
        assertEquals("C026", list.url.parameters["categoryId"])
        assertEquals("c/1?#", list.url.parameters["cursor"])
        assertEquals("40", list.url.parameters["limit"])
    }

    @Test fun rateLimitReadsRetryAfterAndFieldsReachTheError() = runTest {
        val h = RemoteCategoryHarness(); h.session.changeAccount(owner)
        repeat(5) { h.categories.create(CategoryCommand("G003", "c$it", null, emptyList()), "00000000-0000-4000-9000-00000000010$it").successValue() }
        h.now += 23.seconds
        val limited = h.categories.create(CategoryCommand("G003", "c5", null, emptyList()), "00000000-0000-4000-9000-000000000200").error()
        assertEquals(429, h.server.statuses.last())
        assertEquals(37L, limited.retryAfterSeconds)
        val invalid = h.categories.create(CategoryCommand("G003", "x", null, listOf(" ")), "00000000-0000-4000-9000-000000000201").error()
        assertEquals(422, h.server.statuses.last())
        assertEquals(setOf("examples"), invalid.fields)
    }

    @Test fun anAccountSwitchWhileWaitingIsSessionChanged() = runTest {
        val h = RemoteCategoryHarness(); h.session.changeAccount(owner)
        assertEquals(ErrorKind.UNAUTHENTICATED, run { h.session.changeAccount(null); h.categories.catalog(CategoryScope.SELECT).error().kind })
        assertTrue(h.server.engine.requestHistory.isEmpty())
    }
}
