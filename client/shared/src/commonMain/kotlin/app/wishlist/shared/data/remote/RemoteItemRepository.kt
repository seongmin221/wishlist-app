package app.wishlist.shared.data.remote

import app.wishlist.shared.core.ApiId
import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.model.WishlistItem
import app.wishlist.shared.repository.CreateItemCommand
import app.wishlist.shared.repository.CreateItemRepository
import app.wishlist.shared.repository.GetItemRepository
import app.wishlist.shared.repository.SnapshotCreateItemRepository
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ITEM-01 Create and ITEM-03 Get over the session-guarded transport. Each call captures the
 * session snapshot first, hands it to the transport, and compares it again right before the
 * result is returned (SESSION_CHANGED otherwise). Kotlin-internal; the public facade is Task 8.
 */
internal class RemoteItemRepository(
    private val session: AuthSession,
    private val transport: AuthenticatedTransport,
) : SnapshotCreateItemRepository, GetItemRepository {

    override suspend fun create(command: CreateItemCommand): ClientResult<WishlistItem> = create(command, session.state.value)

    /** The transport rejects a stale [expected] before sending and again after the token. */
    override suspend fun create(command: CreateItemCommand, expected: SessionSnapshot): ClientResult<WishlistItem> {
        val snapshot = expected
        if (snapshot.accountId == null) return unauthenticated()
        // Everything is computed outside the request lambda; the transport also builds it only once.
        val body = buildJsonObject {
            put("sourceUrl", command.sourceUrl)
            command.clientCreatedAt?.let { put("clientCreatedAt", it.toString()) }
        }.toString()
        return call(snapshot, ApiId.ITEM_01) {
            method = HttpMethod.Post
            url("/v1/wishlist-items")
            header("Idempotency-Key", command.submissionId)
            setBody(TextContent(body, ContentType.Application.Json))
        }
    }

    override suspend fun get(id: String): ClientResult<WishlistItem> {
        val snapshot = session.state.value
        if (snapshot.accountId == null) return unauthenticated()
        val path = "/v1/wishlist-items/${id.encodeURLPathPart()}"
        return call(snapshot, ApiId.ITEM_03) {
            method = HttpMethod.Get
            url(path)
        }
    }

    private suspend fun call(
        snapshot: SessionSnapshot,
        apiId: ApiId,
        build: HttpRequestBuilder.() -> Unit,
    ): ClientResult<WishlistItem> {
        val response = when (val sent = transport.execute(snapshot, apiId, build)) {
            is ClientResult.Failure -> return sent
            is ClientResult.Success -> sent.value
        }
        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            return ClientResult.Failure(ApiErrorMapper.fromThrowable(e))
        }
        val result = parseItem(text)
        if (session.state.value != snapshot) return ClientResult.Failure(ClientError(ErrorKind.SESSION_CHANGED))
        return result
    }

    private fun unauthenticated() = ClientResult.Failure(ClientError(ErrorKind.UNAUTHENTICATED))
}
