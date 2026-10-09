package app.http

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

class HomeActionRoutesTest {
    @Test fun completion_group_combines_three_required_actions() = withAnalysisDatabase { source ->
        val owner=UUID.randomUUID();val other=UUID.randomUUID();val time=Instant.parse("2026-10-07T10:00:00Z")
        val states=listOf(
            WishlistItemState(AnalysisStatus.READY,ReviewStatus.PENDING,LifecycleStatus.ACTIVE,null,null,CategoryMissingReason.EXTRACTION_UNRESOLVED,null),
            WishlistItemState(AnalysisStatus.PARTIAL,ReviewStatus.PENDING,LifecycleStatus.ACTIVE,"assign",null,CategoryMissingReason.AI_ABSTAINED,null),
            WishlistItemState(AnalysisStatus.READY,ReviewStatus.PENDING,LifecycleStatus.ACTIVE,"reassign",null,CategoryMissingReason.CUSTOM_CATEGORY_DELETED,null),
            WishlistItemState(AnalysisStatus.PROCESSING,ReviewStatus.PENDING,LifecycleStatus.ACTIVE,null,null,CategoryMissingReason.EXTRACTION_UNRESOLVED,null),
            WishlistItemState(AnalysisStatus.READY,ReviewStatus.PENDING,LifecycleStatus.ACTIVE,"review","C026",null,null),
            WishlistItemState(AnalysisStatus.READY,ReviewStatus.CONFIRMED,LifecycleStatus.ACTIVE,"confirmed","C026",null,null),
            WishlistItemState(AnalysisStatus.READY,ReviewStatus.DEFERRED,LifecycleStatus.ACTIVE,"deferred","C026",null,null))
        val fixtures=states.mapIndexed { n,s -> ReadFixture(ReadPosition(time.minusSeconds(n.toLong()),UUID.randomUUID()),s) }
        source.connection.use { c -> insertReadFixtures(c,owner,fixtures);insertReadFixtures(c,other,listOf(fixtures[0].copy(position=ReadPosition(time,UUID.randomUUID())))) }
        testApplication {
            application { installApiHttpSupport();routing { homeActionRoutes(WishlistReadService(source)) { owner } } }
            suspend fun group(g:String):JsonObject {
                val response=client.get("/v1/home/action-items?group=$g")
                assertEquals(HttpStatusCode.OK,response.status)
                return Json.parseToJsonElement(response.bodyAsText()).jsonObject
            }
            val completion=group("INFORMATION_COMPLETION")
            assertEquals(3L,completion.getValue("totalCount").jsonPrimitive.long)
            val items=completion.getValue("items").jsonArray.map { it.jsonObject.getValue("item").jsonObject }
            assertEquals(setOf("INFORMATION_COMPLETION","CATEGORY_ASSIGNMENT","CATEGORY_REASSIGNMENT"),items.map { it.getValue("requiredAction").jsonPrimitive.content }.toSet())
            val analysis=group("ANALYSIS_IN_PROGRESS")
            assertEquals(1L,analysis.getValue("totalCount").jsonPrimitive.long)
            assertEquals(listOf("DELETE"),analysis.getValue("items").jsonArray.single().jsonObject.getValue("item").jsonObject.getValue("allowedActions").jsonArray.map { it.jsonPrimitive.content })
            val review=group("CLASSIFICATION_REVIEW")
            assertEquals(1L,review.getValue("totalCount").jsonPrimitive.long)
            assertEquals(fixtures[4].position.id.toString(),review.getValue("items").jsonArray.single().jsonObject.getValue("item").jsonObject.getValue("id").jsonPrimitive.content)
        }
    }
    @Test fun handled_card_is_normal_anchor_fallback() = withAnalysisDatabase { source ->
        val owner=UUID.randomUUID();val time=Instant.parse("2026-10-07T10:00:00Z")
        val state=WishlistItemState(AnalysisStatus.READY,ReviewStatus.PENDING,LifecycleStatus.ACTIVE,"review","C026",null,null)
        val fixtures=(0 until 5).map { ReadFixture(ReadPosition(time.minusSeconds(it.toLong()),UUID.randomUUID()),state) }
        source.connection.use { c -> insertReadFixtures(c,owner,fixtures) }
        testApplication {
            application { installApiHttpSupport();routing { homeActionRoutes(WishlistReadService(source)) { owner } } }
            val initialResponse=client.get("/v1/home/action-items?group=CLASSIFICATION_REVIEW&limit=1")
            assertEquals(HttpStatusCode.OK,initialResponse.status)
            val initial=Json.parseToJsonElement(initialResponse.bodyAsText()).jsonObject
            val anchor=initial.getValue("items").jsonArray.single().jsonObject.getValue("anchorCursor").jsonPrimitive.content
            analysisSql(source,"update wishlist_items set review_status='CONFIRMED' where id='${fixtures[0].position.id}'")
            analysisSql(source,"update wishlist_items set review_status='DEFERRED' where id='${fixtures[1].position.id}'")
            val response=client.get("/v1/home/action-items?group=CLASSIFICATION_REVIEW&anchor=$anchor&before=0&after=0")
            assertEquals(HttpStatusCode.OK,response.status)
            val body=Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(false,body.getValue("anchorResolved").jsonPrimitive.boolean)
            assertEquals(fixtures[0].position.id.toString(),body.getValue("requestedAnchorItemId").jsonPrimitive.content)
            assertEquals(fixtures[2].position.id.toString(),body.getValue("resolvedAnchorItemId").jsonPrimitive.content)
            val restart=Json.parseToJsonElement(client.get("/v1/home/action-items?group=CLASSIFICATION_REVIEW").bodyAsText()).jsonObject
            assertEquals(3L,restart.getValue("totalCount").jsonPrimitive.long)
            assertEquals(fixtures.drop(2).map { it.position.id.toString() },restart.getValue("items").jsonArray.map { it.jsonObject.getValue("item").jsonObject.getValue("id").jsonPrimitive.content })
            assertEquals("CONFIRMED",analysisScalar(source,"select review_status from wishlist_items where id='${fixtures[0].position.id}'"))
            assertEquals("DEFERRED",analysisScalar(source,"select review_status from wishlist_items where id='${fixtures[1].position.id}'"))
        }
    }
    @Test fun home_cursor_rejects_foreign_scope_and_bad_input() = testApplication {
        val source=app.DatabaseFactory.dataSource("jdbc:postgresql://127.0.0.1:1/not_used","test","test");val owner=UUID.randomUUID()
        application { installApiHttpSupport();routing { homeActionRoutes(WishlistReadService(source)) { owner } } }
        val scope=ReadScope.Action(HomeActionGroup.INFORMATION_COMPLETION);val pos=ReadPosition(Instant.parse("2026-10-07T10:00:00Z"),UUID.randomUUID())
        val tokens=listOf("broken",WishlistReadCursorCodec.forOwner(UUID.randomUUID()).encode(ReadEndpoint.HOME_ACTION_ITEMS,scope,ReadCursorUse.NEXT,pos),
            WishlistReadCursorCodec.forOwner(owner).encode(ReadEndpoint.HOME_ACTION_ITEMS,ReadScope.Action(HomeActionGroup.CLASSIFICATION_REVIEW),ReadCursorUse.NEXT,pos),
            WishlistReadCursorCodec.forOwner(owner).encode(ReadEndpoint.WISHLIST_ITEMS,ReadScope.PurposeUnassigned,ReadCursorUse.NEXT,pos))
        for (t in tokens) {
            val r=client.get("/v1/home/action-items?group=INFORMATION_COMPLETION&cursor=$t")
            assertEquals(HttpStatusCode.BadRequest,r.status)
            val error=Json.parseToJsonElement(r.bodyAsText()).jsonObject.getValue("error").jsonObject
            assertEquals("INVALID_HOME_CURSOR",error.getValue("code").jsonPrimitive.content)
            assertEquals(r.headers["X-Request-ID"],error.getValue("requestId").jsonPrimitive.content)
        }
        for(q in listOf("group=NONE","group=INFORMATION_COMPLETION&limit=101","group=INFORMATION_COMPLETION&action=CATEGORY_ASSIGNMENT","group=INFORMATION_COMPLETION&group=INFORMATION_COMPLETION"))
            assertEquals(HttpStatusCode.BadRequest,client.get("/v1/home/action-items?$q").status)
    }
    @Test fun action_list_requires_authentication() = testApplication {
        val source=app.DatabaseFactory.dataSource("jdbc:postgresql://127.0.0.1:1/not_used","test","test")
        application { installApiHttpSupport();routing { homeActionRoutes(WishlistReadService(source)) { null } } }
        assertEquals(HttpStatusCode.Unauthorized,client.get("/v1/home/action-items?group=INFORMATION_COMPLETION").status)
    }
}
