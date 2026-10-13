package app.http

import app.wishlist.WishlistReadService
import app.home.HomeReadService
import app.media.MediaUrlSigner
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.*
import java.util.UUID

fun Route.homeActionRoutes(service: WishlistReadService, signer: MediaUrlSigner, ownerResolver: suspend (ApplicationCall) -> UUID?) {
    get("/v1/home/action-items") {
        val owner=ownerResolver(call) ?: return@get call.respondApiError(HttpStatusCode.Unauthorized,"UNAUTHORIZED")
        call.respondReadQuery(owner,ReadEndpoint.HOME_ACTION_ITEMS,WishlistReadQueryParser.action(owner,call.request.queryParameters),service,signer)
    }
}

fun Route.homeSummaryRoutes(service: HomeReadService, signer: MediaUrlSigner, ownerResolver: suspend (ApplicationCall) -> UUID?) {
    get("/v1/home") {
        val owner = ownerResolver(call) ?: return@get call.respondApiError(HttpStatusCode.Unauthorized, "UNAUTHORIZED")
        if (!call.request.queryParameters.isEmpty()) return@get call.respondApiError(HttpStatusCode.BadRequest, "INVALID_HOME_QUERY")
        val (summary, urls) = withContext(Dispatchers.IO) {
            val summary = service.get(owner)
            summary to signer.signAll(summary.actionGroups.flatMap { it.items }.mediaKeys() + summary.recentPurposes.previewMediaKeys())
        }
        call.respondText(ApiJson.encodeToString(HomeViewMapper.map(owner, summary, urls)), ContentType.Application.Json, HttpStatusCode.OK)
    }
}
