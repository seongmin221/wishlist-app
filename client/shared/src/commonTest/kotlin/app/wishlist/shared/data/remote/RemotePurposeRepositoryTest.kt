@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package app.wishlist.shared.data.remote

import app.wishlist.shared.core.*
import app.wishlist.shared.data.fake.*
import app.wishlist.shared.model.*
import app.wishlist.shared.repository.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import kotlin.test.*

internal class RemotePurposeRepositoryContractTest : PurposeRepositoryContractTest() {
    private val server by lazy { ScriptedPurposeServer(store) }
    private val transport by lazy { purposeTransport(session, server.engine) }
    override fun repository(session: AuthSession, store: FakeStore): PurposeRepository = RemotePurposeRepository(session, transport)
    override fun lists(session: AuthSession, store: FakeStore): ItemListRepository = RemoteItemListRepository(session, transport)
}

internal fun purposeTransport(session: AuthSession, engine: MockEngine): AuthenticatedTransport = transportOf(session, object : AuthTokenProvider {
    override suspend fun getToken(snapshot: SessionSnapshot, forceRefresh: Boolean): ClientResult<String> = ClientResult.Success("token-${snapshot.accountId}")
}, engine)

class RemotePurposeRepositoryTest {
    @Test fun summaryOmitsPaginationCreateReplayAndPatchUseExactWire() = runTest {
        val session = MutableAuthSession(); session.changeAccount("A")
        val store = FakeStore(session, Clock { fakeTime }, IdGenerator { kotlin.uuid.Uuid.random().toString() })
        val server = ScriptedPurposeServer(store)
        val transport = purposeTransport(session, server.engine)
        val repo = RemotePurposeRepository(session, transport)
        val command = PurposeCommand("원문 ", null, "periwinkle", "plane")
        val key = "22222222-2222-4222-8222-222222222222"
        val p = repo.create(command, key).successValue().purpose
        repo.create(command, key).successValue()
        repo.summary().successValue()
        repo.update(p.id, 1, PurposePatch(description = DescriptionChange.Set(null), iconKey = "book")).successValue()
        val list = RemoteItemListRepository(session, transport)
        list.byPurpose(p.id, cursor = "c/?#", limit = 40)
        val (create, replay, summary, patch, page) = server.engine.requestHistory
        assertEquals(HttpMethod.Post, create.method)
        assertEquals("/v1/purposes", create.url.encodedPath)
        assertEquals(key, create.headers["Idempotency-Key"])
        assertEquals("Bearer token-A", create.authorization())
        assertEquals("""{"name":"원문 ","colorKey":"PERIWINKLE","iconKey":"PLANE"}""", create.bodyText())
        assertEquals(create.bodyText(), replay.bodyText())
        assertEquals(setOf("projection"), summary.url.parameters.names())
        assertEquals("SUMMARY", summary.url.parameters["projection"])
        assertEquals("""{"expectedVersion":1,"description":null,"iconKey":"BOOK"}""", patch.bodyText())
        assertEquals("/v1/purposes/${p.id}", patch.url.encodedPath)
        assertEquals(setOf("purposeId", "cursor", "limit"), page.url.parameters.names())
        assertEquals(p.id, page.url.parameters["purposeId"])
        assertEquals("c/?#", page.url.parameters["cursor"])
        assertEquals(listOf(201, 200, 200, 200, 400), server.statuses)
    }

    @Test fun switchedSnapshotAndSignedOutCallsNeverPublishOldOwner() = runTest {
        val session = MutableAuthSession(); session.changeAccount("A")
        val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val engine = MockEngine { entered.complete(Unit); resume.await(); json(HttpStatusCode.OK, PURPOSE_DETAIL_JSON) }
        val repo = RemotePurposeRepository(session, purposeTransport(session, engine))
        val pending = async { repo.detail(PURPOSE_ID) }
        entered.await(); session.changeAccount("B"); resume.complete(Unit)
        assertEquals(ErrorKind.SESSION_CHANGED, pending.await().error().kind)
        session.changeAccount(null)
        assertEquals(ErrorKind.UNAUTHENTICATED, repo.summary().error().kind)
        assertEquals(1, engine.requestHistory.size)
    }

    @Test fun invalidStyleInputReturnsFieldsRatherThanThrowingOrSending() = runTest {
        val session = MutableAuthSession(); session.changeAccount("A")
        val engine = MockEngine { error("invalid input must not send") }
        val repo = RemotePurposeRepository(session, purposeTransport(session, engine))
        assertEquals(setOf("colorKey"), repo.create(PurposeCommand("목적", null, "CORAL", "heart"), "key").error().fields)
        assertEquals(setOf("iconKey"), repo.update(PURPOSE_ID, 1, PurposePatch(iconKey = "unknown")).error().fields)
        assertTrue(engine.requestHistory.isEmpty())
    }
}
