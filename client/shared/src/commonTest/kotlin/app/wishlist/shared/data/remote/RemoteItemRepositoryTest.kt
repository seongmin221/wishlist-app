@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package app.wishlist.shared.data.remote

import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.MutableAuthSession
import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.data.fake.error
import app.wishlist.shared.data.fake.successValue
import app.wishlist.shared.model.AnalysisStatus
import app.wishlist.shared.model.LifecycleStatus
import app.wishlist.shared.repository.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Instant

private const val OWNER_A = "00000000-0000-4000-8000-000000000001"
private const val OWNER_B = "00000000-0000-4000-8000-000000000002"
private const val SUBMISSION = "00000000-0000-4000-8000-000000000003"
private val shareTime = Instant.parse("2026-10-07T00:00:00Z")

private class RemoteHarness(val server: ScriptedItemServer = ScriptedItemServer()) {
    val session = MutableAuthSession()
    private val tokens = object : AuthTokenProvider {
        override suspend fun getToken(snapshot: SessionSnapshot, forceRefresh: Boolean): ClientResult<String> =
            ClientResult.Success("token-${snapshot.accountId}")
    }
    val repository = RemoteItemRepository(session, transportOf(session, tokens, server.engine))
    fun command(url: String = "https://shop.example/headphone", at: Instant? = shareTime) =
        CreateItemCommand(SUBMISSION, url, at)
}

/** Task 4's identical scenarios, run against the scripted MockEngine server. */
class RemoteItemRepositoryContractTest : RepositoryContractTest() {
    override suspend fun createFixture(): RepositoryContractFixture = object : RepositoryContractFixture {
        val harness = RemoteHarness()
        override suspend fun repositoriesFor(ownerId: String): ContractRepositories {
            harness.session.changeAccount(ownerId)
            return ContractRepositories(harness.repository, harness.repository)
        }
        override suspend fun completeAnalysis(ownerId: String, itemId: String) = harness.server.complete(ownerId, itemId)
        override suspend fun deleteItem(ownerId: String, itemId: String) = harness.server.delete(ownerId, itemId)
        override suspend fun close() = harness.server.engine.close()
    }
}

class RemoteItemRepositoryTest {
    @Test fun create_posts_key_bearer_and_exact_original_url_then_replay_is_200() = runTest {
        val h = RemoteHarness(); h.session.changeAccount(OWNER_A)
        val url = " https://shop.example/headphone?x=1 "
        val first = h.repository.create(h.command(url)).successValue()
        val replay = h.repository.create(h.command(url)).successValue()
        assertEquals(listOf(201, 200), h.server.statuses)
        assertEquals(first.id, replay.id)
        val request = h.server.engine.requestHistory.first()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("$TEST_BASE_URL/v1/wishlist-items", request.url.toString())
        assertEquals("Bearer token-$OWNER_A", request.authorization())
        assertEquals(SUBMISSION, request.headers["Idempotency-Key"])
        assertEquals(url, first.sourceUrl)
        assertTrue(request.bodyText().contains("\"clientCreatedAt\":\"2026-10-07T00:00:00Z\""))
    }

    @Test fun create_for_a_stale_expected_snapshot_sends_no_request() = runTest {
        val h = RemoteHarness(); h.session.changeAccount(OWNER_A)
        val expected = h.session.state.value
        h.session.changeAccount(OWNER_B)
        assertEquals(ErrorKind.SESSION_CHANGED, h.repository.create(h.command(), expected).error().kind)
        h.session.changeAccount(null)
        h.session.changeAccount(OWNER_B) // same account again: a new generation is still stale
        assertEquals(ErrorKind.SESSION_CHANGED, h.repository.create(h.command(), expected).error().kind)
        assertEquals(ErrorKind.UNAUTHENTICATED, h.repository.create(h.command(), SessionSnapshot(null, 0)).error().kind)
        assertTrue(h.server.engine.requestHistory.isEmpty())

        // The current snapshot sends exactly once, as that account.
        h.repository.create(h.command(), h.session.state.value).successValue()
        assertEquals("Bearer token-$OWNER_B", h.server.engine.requestHistory.single().authorization())
    }

    @Test fun absent_client_created_at_is_omitted_from_the_body() = runTest {
        val h = RemoteHarness(); h.session.changeAccount(OWNER_A)
        val item = h.repository.create(h.command(at = null)).successValue()
        assertNull(item.clientCreatedAt)
        assertFalse(h.server.engine.requestHistory.single().bodyText().contains("clientCreatedAt"))
    }

    @Test fun get_encodes_the_id_as_one_path_segment() = runTest {
        val h = RemoteHarness(); h.session.changeAccount(OWNER_A)
        assertEquals(ErrorKind.NOT_FOUND, h.repository.get("a b/c?d").error().kind)
        assertEquals("/v1/wishlist-items/a%20b%2Fc%3Fd", h.server.engine.requestHistory.single().url.encodedPath)
    }

    @Test fun key_reuse_conflict_carries_code_and_no_version() = runTest {
        val h = RemoteHarness(); h.session.changeAccount(OWNER_A)
        h.repository.create(h.command()).successValue()
        val error = h.repository.create(h.command("https://shop.example/other")).error()
        assertEquals(ErrorKind.CONFLICT, error.kind)
        assertEquals("IDEMPOTENCY_KEY_REUSED", error.code)
        assertNull(error.currentVersion)
    }

    @Test fun without_an_account_nothing_is_sent() = runTest {
        val h = RemoteHarness()
        assertEquals(ErrorKind.UNAUTHENTICATED, h.repository.create(h.command()).error().kind)
        assertEquals(ErrorKind.UNAUTHENTICATED, h.repository.get("x").error().kind)
        assertTrue(h.server.engine.requestHistory.isEmpty())
    }

    @Test fun malformed_success_bodies_are_invalid_response() = runTest {
        val h = RemoteHarness(); h.session.changeAccount(OWNER_A)
        val item = h.repository.create(h.command()).successValue()
        listOf("not json", "[]", "{}", itemJson("lifecycleStatus" to "\"PAUSED\""), itemJson("version" to "0")).forEach {
            h.server.rawBodyOnce = it
            assertEquals(ErrorKind.INVALID_RESPONSE, h.repository.get(item.id).error().kind, it)
        }
    }

    @Test fun server_error_and_unknown_codes_are_typed() = runTest {
        val engine = MockEngine { json(HttpStatusCode.InternalServerError, """{"error":{"code":"WHATEVER"}}""") }
        val session = MutableAuthSession(); session.changeAccount(OWNER_A)
        val tokens = object : AuthTokenProvider {
            override suspend fun getToken(snapshot: SessionSnapshot, forceRefresh: Boolean) = ClientResult.Success("t")
        }
        val repo = RemoteItemRepository(session, transportOf(session, tokens, engine))
        val error = repo.get("x").error()
        assertEquals(ErrorKind.SERVER, error.kind)
        assertEquals("WHATEVER", error.code)
        assertEquals(1, engine.requestHistory.size)
    }

    @Test fun unknown_values_in_a_valid_response_do_not_fail_the_call() = runTest {
        val h = RemoteHarness(); h.session.changeAccount(OWNER_A)
        val item = h.repository.create(h.command()).successValue()
        h.server.rawBodyOnce = itemJson(
            "analysis" to """{"status":"FAILED_TERMINAL","failureCode":"BRAND_NEW_CODE"}""",
            "reviewStatus" to "\"SOMETHING_NEW\"",
        )
        val read = h.repository.get(item.id).successValue()
        assertEquals("BRAND_NEW_CODE", read.analysis.failureCode)
        assertEquals(AnalysisStatus.FAILED_TERMINAL, read.analysis.status)
    }

    @Test fun tombstone_replay_is_a_deleted_item() = runTest {
        val h = RemoteHarness(); h.session.changeAccount(OWNER_A)
        val item = h.repository.create(h.command()).successValue()
        h.server.delete(OWNER_A, item.id)
        assertEquals(LifecycleStatus.DELETED, h.repository.create(h.command()).successValue().lifecycleStatus)
    }

    private suspend fun staleScenario(
        h: RemoteHarness, call: suspend () -> ClientResult<*>, switch: suspend () -> Unit,
    ) = kotlinx.coroutines.coroutineScope {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        h.server.beforeRespond = { reached.complete(Unit); release.await() }
        val pending = async { call() }
        reached.await()
        switch()
        h.server.beforeRespond = null
        release.complete(Unit)
        assertEquals(ErrorKind.SESSION_CHANGED, pending.await().error().kind)
    }

    @Test fun delayed_create_response_is_discarded_after_account_switch() = runTest {
        val h = RemoteHarness(); h.session.changeAccount(OWNER_A)
        staleScenario(h, { h.repository.create(h.command()) }) { h.session.changeAccount("B") }
        h.session.changeAccount(OWNER_A)
        assertEquals(1, h.repository.create(h.command()).successValue().version)
    }

    @Test fun delayed_create_response_is_discarded_after_logout_and_same_account_login() = runTest {
        val h = RemoteHarness(); h.session.changeAccount(OWNER_A)
        staleScenario(h, { h.repository.create(h.command()) }) {
            h.session.changeAccount(null); h.session.changeAccount(OWNER_A)
        }
    }

    @Test fun delayed_get_response_is_discarded_after_account_switch() = runTest {
        val h = RemoteHarness(); h.session.changeAccount(OWNER_A)
        val item = h.repository.create(h.command()).successValue()
        staleScenario(h, { h.repository.get(item.id) }) { h.session.changeAccount("B") }
        assertEquals(ErrorKind.NOT_FOUND, h.repository.get(item.id).error().kind)
    }

    @Test fun delayed_get_response_is_discarded_after_logout_and_same_account_login() = runTest {
        val h = RemoteHarness(); h.session.changeAccount(OWNER_A)
        val item = h.repository.create(h.command()).successValue()
        staleScenario(h, { h.repository.get(item.id) }) {
            h.session.changeAccount(null); h.session.changeAccount(OWNER_A)
        }
        assertEquals(item, h.repository.get(item.id).successValue())
    }
}
