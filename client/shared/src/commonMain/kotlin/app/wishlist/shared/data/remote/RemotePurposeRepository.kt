package app.wishlist.shared.data.remote

import app.wishlist.shared.core.*
import app.wishlist.shared.domain.PurposeInputRules
import app.wishlist.shared.model.*
import app.wishlist.shared.repository.PurposeRepository
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.TextContent

/** B3 PUR-01~04 with the same request-snapshot boundary as the item/category repositories. */
internal class RemotePurposeRepository(private val session: AuthSession, private val transport: AuthenticatedTransport) : PurposeRepository {
    override suspend fun summary(): ClientResult<PurposeCatalog> = transport.call(session, ApiId.PUR_01, ::parsePurposeCatalog) {
        method = HttpMethod.Get
        url("/v1/purposes")
        parameter("projection", "SUMMARY")
    }
    override suspend fun detail(id: String): ClientResult<PurposeDetail> = transport.call(session, ApiId.PUR_03, ::parsePurposeDetail) {
        method = HttpMethod.Get
        url("/v1/purposes/${id.encodeURLPathPart()}")
    }
    override suspend fun create(command: PurposeCommand, idempotencyKey: String): ClientResult<PurposeCreated> {
        val fields = PurposeInputRules.validate(command)
        if (fields.isNotEmpty()) return invalid(fields)
        val body = purposeCreateBody(command)
        return transport.call(session, ApiId.PUR_02, ::parsePurposeCreated) {
            method = HttpMethod.Post
            url("/v1/purposes")
            header("Idempotency-Key", idempotencyKey)
            setBody(TextContent(body, ContentType.Application.Json))
        }
    }
    override suspend fun update(id: String, expectedVersion: Int, patch: PurposePatch): ClientResult<PurposeDetail> {
        val fields = buildSet {
            patch.colorKey?.let { if (PurposeStyleKeys.colorToWire(it) == null) add("colorKey") }
            patch.iconKey?.let { if (PurposeStyleKeys.iconToWire(it) == null) add("iconKey") }
        }
        if (fields.isNotEmpty()) return invalid(fields)
        val body = purposePatchBody(expectedVersion, patch)
        return transport.call(session, ApiId.PUR_04, ::parsePurposeDetail) {
            method = HttpMethod.Patch
            url("/v1/purposes/${id.encodeURLPathPart()}")
            setBody(TextContent(body, ContentType.Application.Json))
        }
    }
    private fun invalid(fields: Set<String>) = ClientResult.Failure(ClientError(ErrorKind.VALIDATION, "INVALID_PURPOSE_INPUT", fields = fields))
}
