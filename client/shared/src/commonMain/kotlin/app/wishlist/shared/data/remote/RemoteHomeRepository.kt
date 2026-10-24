package app.wishlist.shared.data.remote

import app.wishlist.shared.core.ApiId
import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.model.HomeActionGroup
import app.wishlist.shared.model.HomeSummary
import app.wishlist.shared.model.ItemPage
import app.wishlist.shared.repository.HomeRepository
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import io.ktor.http.HttpMethod

/** B4 HOME-01 (no query) and HOME-02 over the authenticated transport. Kotlin-internal. */
internal class RemoteHomeRepository(
    private val session: AuthSession,
    private val transport: AuthenticatedTransport,
) : HomeRepository {
    override suspend fun summary(): ClientResult<HomeSummary> =
        transport.call(session, ApiId.HOME_01, ::parseHomeSummary) {
            method = HttpMethod.Get
            url("/v1/home")
        }

    override suspend fun group(group: HomeActionGroup, cursor: String?, limit: Int): ClientResult<ItemPage> =
        transport.call(session, ApiId.HOME_02, ::parseItemPage) {
            method = HttpMethod.Get
            url("/v1/home/action-items")
            parameter("group", group.name)
            cursor?.let { parameter("cursor", it) }
            parameter("limit", limit)
        }

    /** The anchor query carries position and requested id; cursor/limit are never sent with it. */
    override suspend fun groupAround(group: HomeActionGroup, anchor: String, before: Int, after: Int): ClientResult<ItemPage> =
        transport.call(session, ApiId.HOME_02, ::parseItemPage) {
            method = HttpMethod.Get
            url("/v1/home/action-items")
            parameter("group", group.name)
            parameter("anchor", anchor)
            parameter("before", before)
            parameter("after", after)
        }
}
