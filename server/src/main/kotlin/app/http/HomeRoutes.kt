package app.http

import app.wishlist.WishlistReadService
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
