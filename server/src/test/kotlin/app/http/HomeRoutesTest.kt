package app.http

import app.home.*
import app.testutil.*
import app.wishlist.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class HomeRoutesTest {
    @Test fun summary_has_no_todo_aggregate_or_local_pending_and_previews_can_anchor_actions() = withAnalysisDatabase { source ->
        val owner=UUID.randomUUID();val time=Instant.parse("2026-10-07T10:00:00Z")
        val fixture=ReadFixture(ReadPosition(time,UUID.randomUUID()),WishlistItemState(AnalysisStatus.PROCESSING,ReviewStatus.NOT_REQUIRED,LifecycleStatus.ACTIVE,null,null,CategoryMissingReason.EXTRACTION_UNRESOLVED,null))
        source.connection.use { c -> insertReadFixtures(c,owner,listOf(fixture)) }
        testApplication {
            application { installApiHttpSupport();routing { homeSummaryRoutes(HomeReadService(source)) { call -> if(call.request.headers["Test-Auth"]=="true") owner else null };homeActionRoutes(WishlistReadService(source)) { owner } } }
            assertEquals(HttpStatusCode.Unauthorized,client.get("/v1/home").status)
            val response=client.get("/v1/home") { header("Test-Auth","true") }
            assertEquals(HttpStatusCode.OK,response.status)
            val body=Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(setOf("actionGroups","recentPurposes"),body.keys)
            val groups=body.getValue("actionGroups").jsonArray
            assertEquals(listOf(1L,0L,0L),groups.map { it.jsonObject.getValue("count").jsonPrimitive.long })
            val anchor=groups.first().jsonObject.getValue("previews").jsonArray.single().jsonObject.getValue("anchorCursor").jsonPrimitive.content
            val window=client.get("/v1/home/action-items?group=ANALYSIS_IN_PROGRESS&anchor=$anchor&before=0&after=0")
            assertEquals(HttpStatusCode.OK,window.status)
            assertEquals(true,Json.parseToJsonElement(window.bodyAsText()).jsonObject.getValue("anchorResolved").jsonPrimitive.boolean)
            assertEquals(HttpStatusCode.BadRequest,client.get("/v1/home?unknown=x") { header("Test-Auth","true") }.status)
        }
    }
}
