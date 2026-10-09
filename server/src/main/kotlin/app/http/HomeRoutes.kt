package app.http

import app.wishlist.WishlistReadService
import app.home.HomeReadService
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.*
import java.util.UUID

fun Route.homeActionRoutes(service: WishlistReadService, ownerResolver: suspend (ApplicationCall) -> UUID?) {
    get("/v1/home/action-items") {
        val owner=ownerResolver(call) ?: return@get call.respondApiError(HttpStatusCode.Unauthorized,"UNAUTHORIZED")
        call.respondReadQuery(owner,ReadEndpoint.HOME_ACTION_ITEMS,WishlistReadQueryParser.action(owner,call.request.queryParameters),service)
    }
}

fun Route.homeSummaryRoutes(service: HomeReadService, ownerResolver: suspend (ApplicationCall) -> UUID?) {
    get("/v1/home") {
        val owner = ownerResolver(call) ?: return@get call.respondApiError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
        if (!call.request.queryParameters.isEmpty()) return@get call.respondApiError(HttpStatusCode.BadRequest, "INVALID_HOME_QUERY")
        val summary = withContext(Dispatchers.IO) { service.get(owner) }
        call.respondText(ApiJson.encodeToString(HomeViewMapper.map(owner, summary)), ContentType.Application.Json, HttpStatusCode.OK)
    }
}
