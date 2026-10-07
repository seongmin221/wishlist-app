package app.http

import app.module
import app.DatabaseFactory
import app.category.*
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

class WishlistReadRoutesTest {
    @Test fun authentication_and_invalid_query_precede_database_access() = testApplication {
        val source=DatabaseFactory.dataSource("jdbc:postgresql://127.0.0.1:1/not_used","test","test")
        val owner=UUID.randomUUID()
        application { installApiHttpSupport(); routing {
            wishlistRoutes(CreateWishlistItemService(source),GetWishlistItemService(source)) { call -> if(call.request.headers["Test-Auth"]=="true") owner else null }
            wishlistReadRoutes(WishlistReadService(source)) { call -> if(call.request.headers["Test-Auth"]=="true") owner else null }
        } }
        assertEquals(HttpStatusCode.Unauthorized,client.get("/v1/wishlist-items?purposeUnassigned=true").status)
        for ((query,code) in listOf("" to "INVALID_WISHLIST_QUERY", "categoryId=C999" to "INVALID_WISHLIST_QUERY", "purposeUnassigned=true&limit=101" to "INVALID_WISHLIST_QUERY",
            "purposeUnassigned=true&purposeUnassigned=true" to "INVALID_WISHLIST_QUERY", "purposeUnassigned=true&anchorItemId=x" to "INVALID_WISHLIST_QUERY",
            "purposeUnassigned=true&cursor=broken" to "INVALID_WISHLIST_CURSOR")) {
            val response=client.get("/v1/wishlist-items?$query") { header("Test-Auth","true") }
            assertEquals(HttpStatusCode.BadRequest,response.status,query)
            val error=Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
            assertEquals(code,error.getValue("code").jsonPrimitive.content)
            assertEquals(response.headers["X-Request-ID"],error.getValue("requestId").jsonPrimitive.content)
        }
    }
    @Test fun list_http_exposes_scope_bound_cards_and_anchor_contract() = withAnalysisDatabase { source ->
        val owner=UUID.randomUUID();val other=UUID.randomUUID();val purpose=insertPurpose(source,owner,"p",null)
        val foreignPurpose=insertPurpose(source,other,"foreign",null)
        val foreignCustom=CategoryService(source).create(other,UUID.randomUUID(),"G003",CategoryInput("foreign",null,emptyList())).category.id
        val time=Instant.parse("2026-10-07T10:00:00Z")
        val first=ReadFixture(ReadPosition(time,UUID.randomUUID()),WishlistItemState(AnalysisStatus.READY,ReviewStatus.PENDING,LifecycleStatus.ACTIVE,"name","C026",null,null),purposeId=purpose,purposeSource=ValueSource.USER)
        val second=first.copy(position=ReadPosition(time.minusSeconds(1),UUID.randomUUID()))
        val nameless=first.copy(position=ReadPosition(time.minusSeconds(2),UUID.randomUUID()),state=first.state.copy(productName=null))
        source.connection.use { c -> insertReadFixtures(c,owner,listOf(first,second,nameless)) }
        testApplication {
            application { installApiHttpSupport(); routing {
                wishlistRoutes(CreateWishlistItemService(source),GetWishlistItemService(source)) { owner }
                wishlistReadRoutes(WishlistReadService(source)) { owner }
            } }
            val response=client.get("/v1/wishlist-items?categoryId=C026&limit=1")
            assertEquals(HttpStatusCode.OK,response.status)
            val body=Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(2L,body.getValue("totalCount").jsonPrimitive.long)
            assertEquals(JsonNull,body.getValue("requestedAnchorItemId"));assertEquals(JsonNull,body.getValue("anchorResolved"))
            val card=body.getValue("items").jsonArray.single().jsonObject
            val detail=Json.parseToJsonElement(client.get("/v1/wishlist-items/${first.position.id}").bodyAsText())
            assertEquals(detail,card.getValue("item"))
            val product=card.getValue("item").jsonObject.getValue("product").jsonObject
            for (field in listOf("brand","price","currency","merchant","metadataCheckedAt")) assertEquals(JsonNull,product.getValue(field))
            val anchor=card.getValue("anchorCursor").jsonPrimitive.content
            val next=body.getValue("nextCursor").jsonPrimitive.content
            val nextBody=Json.parseToJsonElement(client.get("/v1/wishlist-items?categoryId=C026&cursor=$next&limit=1").bodyAsText()).jsonObject
            assertEquals(second.position.id.toString(),nextBody.getValue("items").jsonArray.single().jsonObject.getValue("item").jsonObject.getValue("id").jsonPrimitive.content)
            assertNotEquals(JsonNull,nextBody.getValue("previousCursor"))
            assertEquals(HttpStatusCode.BadRequest,client.get("/v1/wishlist-items?purposeId=$purpose&anchor=$anchor").status)
            val purposeBody=Json.parseToJsonElement(client.get("/v1/wishlist-items?purposeId=$purpose").bodyAsText()).jsonObject
            assertEquals(3L,purposeBody.getValue("totalCount").jsonPrimitive.long)
            analysisSql(source,"update wishlist_items set lifecycle_status='DELETED' where id='${first.position.id}'")
            val recovered=client.get("/v1/wishlist-items?categoryId=C026&anchor=$anchor&before=0&after=0")
            assertEquals(HttpStatusCode.OK,recovered.status)
            val window=Json.parseToJsonElement(recovered.bodyAsText()).jsonObject
            assertEquals(false,window.getValue("anchorResolved").jsonPrimitive.boolean)
            assertEquals(first.position.id.toString(),window.getValue("requestedAnchorItemId").jsonPrimitive.content)
            assertEquals(second.position.id.toString(),window.getValue("resolvedAnchorItemId").jsonPrimitive.content)
            for (query in listOf("purposeId=$foreignPurpose","purposeId=${UUID.randomUUID()}","categoryId=$foreignCustom","categoryId=${UUID.randomUUID()}"))
                assertEquals(HttpStatusCode.NotFound,client.get("/v1/wishlist-items?$query").status)
            val created=client.post("/v1/wishlist-items") { header("Idempotency-Key",UUID.randomUUID().toString());setBody("""{"sourceUrl":"https://example.com/new"}""") }
            assertEquals(HttpStatusCode.Created,created.status)
        }
    }
    @Test fun health_runtime_does_not_expose_read_routes() = testApplication {
        application { module(emptyMap()) }
        assertEquals(HttpStatusCode.OK,client.get("/health").status)
        assertEquals(HttpStatusCode.NotFound,client.get("/v1/wishlist-items?purposeUnassigned=true").status)
        assertEquals(HttpStatusCode.NotFound,client.get("/v1/home").status)
        assertEquals(HttpStatusCode.NotFound,client.get("/v1/home/action-items?group=INFORMATION_COMPLETION").status)
    }
}
