package app.wishlist.shared.data.remote

import app.wishlist.shared.core.ApiId
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.MutableAuthSession
import io.ktor.client.request.url
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Real OkHttp engine (the production [platformHttpEngine]) against two local MockWebServers on
 * dynamic ports: one answers 302, the other is the redirect target. localhost is a fixture only.
 * runBlocking (not runTest) because real socket IO must not run under virtual time.
 */
class OkHttpRedirectTest {
    private fun redirectScenario(status: Int, location: (MockWebServer) -> String) = runBlocking {
        val target = MockWebServer().apply { enqueue(MockResponse().setBody("{}")); start() }
        val origin = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(status).setHeader("Location", location(target)))
            start()
        }
        try {
            val session = MutableAuthSession().apply { changeAccount("a") }
            val tokens = object : AuthTokenProvider {
                override suspend fun getToken(snapshot: app.wishlist.shared.core.SessionSnapshot, forceRefresh: Boolean) =
                    app.wishlist.shared.core.ClientResult.Success("token")
            }
            val client = createWishlistHttpClient(platformHttpEngine())
            val transport = AuthenticatedTransport(session, tokens, client, origin.url("/").toString().trimEnd('/'))
            val result = transport.execute(session.state.value, ApiId.ITEM_03) { url("/v1/wishlist-items/x") }
            client.close()
            assertEquals(ErrorKind.INVALID_RESPONSE, (result as app.wishlist.shared.core.ClientResult.Failure).error.kind)
            assertEquals(1, origin.requestCount)
            assertEquals(0, target.requestCount, "redirect target must never be contacted")
            // Bearer was only ever sent to the origin, never forwarded.
            assertEquals("Bearer token", origin.takeRequest().getHeader("Authorization"))
        } finally {
            origin.shutdown()
            target.shutdown()
        }
    }

    @Test
    fun http302ToAnotherServerIsNotFollowed() = redirectScenario(302) { it.url("/v1/wishlist-items/x").toString() }

    @Test
    fun http307ToAnotherServerIsNotFollowed() = redirectScenario(307) { it.url("/v1/wishlist-items/x").toString() }

    @Test
    fun redirectToHttpsSchemeChangeIsNotFollowed() = redirectScenario(301) {
        it.url("/x").toString().replace("http://", "https://")
    }
}
