package app.wishlist.shared.data.fake
import app.wishlist.shared.core.*
import app.wishlist.shared.model.*
import app.wishlist.shared.repository.PurposeRepository
internal class FakePurposeRepository(private val store: FakeStore) : PurposeRepository {
    override suspend fun summary(): ClientResult<PurposeCatalog> = store.purposeSummary()
    override suspend fun detail(id: String): ClientResult<PurposeDetail> = store.purposeDetail(id)
    override suspend fun create(command: PurposeCommand, idempotencyKey: String): ClientResult<PurposeCreated> = store.createPurpose(command, idempotencyKey)
    override suspend fun update(id: String, expectedVersion: Int, patch: PurposePatch): ClientResult<PurposeDetail> = store.updatePurpose(id, expectedVersion, patch)
}
