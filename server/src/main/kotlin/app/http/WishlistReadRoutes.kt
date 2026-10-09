package app.http

import app.wishlist.*
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.util.UUID

fun Route.wishlistReadRoutes(service: WishlistReadService, ownerResolver: suspend (ApplicationCall) -> UUID?) {
    get("/v1/wishlist-items") {
        val owner=ownerResolver(call) ?: return@get call.respondApiError(HttpStatusCode.Unauthorized,"UNAUTHORIZED")
        call.respondReadQuery(owner,ReadEndpoint.WISHLIST_ITEMS,WishlistReadQueryParser.wishlist(owner,call.request.queryParameters),service)
    }
}

internal suspend fun ApplicationCall.respondReadQuery(owner: UUID, endpoint: ReadEndpoint, parsed: ReadQueryParseResult, service: WishlistReadService) {
    val prefix=if(endpoint==ReadEndpoint.WISHLIST_ITEMS) "WISHLIST" else "HOME"
    val query=when(parsed) {
        ReadQueryParseResult.InvalidQuery -> return respondApiError(HttpStatusCode.BadRequest,"INVALID_${prefix}_QUERY")
        ReadQueryParseResult.InvalidCursor -> return respondApiError(HttpStatusCode.BadRequest,"INVALID_${prefix}_CURSOR")
        is ReadQueryParseResult.Valid -> parsed.query
    }
    when(val result=withContext(Dispatchers.IO) { service.read(owner,query) }) {
        ReadResult.CategoryNotFound -> respondApiError(HttpStatusCode.NotFound,"CATEGORY_NOT_FOUND")
        ReadResult.PurposeNotFound -> respondApiError(HttpStatusCode.NotFound,"PURPOSE_NOT_FOUND")
        is ReadResult.Success -> respondText(ApiJson.encodeToString(ReadWindowViewMapper.map(owner,endpoint,query.scope,result.page)),ContentType.Application.Json,HttpStatusCode.OK)
    }
}
