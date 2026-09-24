package app.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class FirebaseOwnerResolverTest {
    @Test
    fun `only a verified bearer token resolves an owner`() = testApplication {
        val resolver = FirebaseOwnerResolver("project-a") { token -> if (token == "valid") "uid-1" else null }
        application {
            routing {
                get("/owner") {
                    val owner = resolver.resolve(call)
                    if (owner == null) call.respondText("missing", status = HttpStatusCode.Unauthorized)
                    else call.respondText(owner.toString())
                }
            }
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/owner") { header("X-Owner-Id", "claimed") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/owner") { header("Authorization", "Bearer bad") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/owner") { header("Authorization", "Bearer valid") }.status)
    }
}
