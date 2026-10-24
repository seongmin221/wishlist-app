package app.wishlist.shared.repository
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.model.*
/** PUR-01~04. SUMMARY reads all active purposes in one server snapshot, without pagination. */
interface PurposeRepository {
    suspend fun summary(): ClientResult<PurposeCatalog>
    suspend fun detail(id: String): ClientResult<PurposeDetail>
    suspend fun create(command: PurposeCommand, idempotencyKey: String): ClientResult<PurposeCreated>
    suspend fun update(id: String, expectedVersion: Int, patch: PurposePatch): ClientResult<PurposeDetail>
}
