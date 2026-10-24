package app.wishlist.shared.data.remote

import app.wishlist.shared.core.ApiId
import app.wishlist.shared.core.AuthSession
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.model.*
import app.wishlist.shared.repository.CategoryRepository
import app.wishlist.shared.repository.ItemListRepository
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
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

/**
 * Sends one request for the snapshot taken at the call and parses the body. Like
 * [RemoteItemRepository]: a session change at any point (before sending, while waiting, before
 * returning) is SESSION_CHANGED, so one account's answer never reaches another's screen.
 */
internal suspend fun <T> AuthenticatedTransport.call(
    session: AuthSession,
    apiId: ApiId,
    parse: (String) -> ClientResult<T>,
    build: HttpRequestBuilder.() -> Unit,
): ClientResult<T> {
    val snapshot: SessionSnapshot = session.state.value
    if (snapshot.accountId == null) return ClientResult.Failure(ClientError(ErrorKind.UNAUTHENTICATED))
    val changed = ClientResult.Failure(ClientError(ErrorKind.SESSION_CHANGED))
    val response = when (val sent = execute(snapshot, apiId, build)) {
        is ClientResult.Failure -> return if (session.state.value != snapshot) changed else sent
        is ClientResult.Success -> sent.value
    }
    val text = try {
        response.bodyAsText()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        if (session.state.value != snapshot) return changed
        return ClientResult.Failure(ApiErrorMapper.fromThrowable(e))
    }
    val result = parse(text)
    return if (session.state.value != snapshot) changed else result
}

/** B2 CAT-01~04 over the authenticated transport. Kotlin-internal. */
internal class RemoteCategoryRepository(
    private val session: AuthSession,
    private val transport: AuthenticatedTransport,
) : CategoryRepository {
    override suspend fun catalog(scope: CategoryScope, parentId: String?): ClientResult<CategoryCatalog> =
        transport.call(session, ApiId.CAT_01, ::parseCatalog) {
            method = HttpMethod.Get
            url("/v1/categories")
            parameter("scope", scope.name)
            parentId?.let { parameter("parentId", it) }
        }

    override suspend fun custom(id: String): ClientResult<CustomCategoryDetail> =
        transport.call(session, ApiId.CAT_02, ::parseCustomDetail) {
            method = HttpMethod.Get
            url("/v1/custom-categories/${id.encodeURLPathPart()}")
        }

    override suspend fun create(command: CategoryCommand, idempotencyKey: String): ClientResult<CategoryCreated> {
        val body = createBody(command)
        return transport.call(session, ApiId.CAT_03, ::parseCreated) {
            method = HttpMethod.Post
            url("/v1/custom-categories")
            header("Idempotency-Key", idempotencyKey)
            setBody(TextContent(body, ContentType.Application.Json))
        }
    }

    override suspend fun update(id: String, expectedVersion: Int, patch: CategoryPatch): ClientResult<CustomCategoryDetail> {
        val body = patchBody(expectedVersion, patch)
        return transport.call(session, ApiId.CAT_04, ::parseCustomDetail) {
            method = HttpMethod.Patch
            url("/v1/custom-categories/${id.encodeURLPathPart()}")
            setBody(TextContent(body, ContentType.Application.Json))
        }
    }
}

/** B4 ITEM-02, category scope. Kotlin-internal. */
internal class RemoteItemListRepository(
    private val session: AuthSession,
    private val transport: AuthenticatedTransport,
) : ItemListRepository {
    override suspend fun byPurpose(purposeId: String, cursor: String?, limit: Int): ClientResult<ItemPage> =
        transport.call(session, ApiId.ITEM_02, ::parseItemPage) {
            method = HttpMethod.Get
            url("/v1/wishlist-items")
            parameter("purposeId", purposeId)
            cursor?.let { parameter("cursor", it) }
            parameter("limit", limit)
        }

    override suspend fun byCategory(categoryId: String, cursor: String?, limit: Int): ClientResult<ItemPage> =
        transport.call(session, ApiId.ITEM_02, ::parseItemPage) {
            method = HttpMethod.Get
            url("/v1/wishlist-items")
            parameter("categoryId", categoryId)
            cursor?.let { parameter("cursor", it) }
            parameter("limit", limit)
        }
}
