package app.testutil

import app.wishlist.*
import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

data class ReadFixture(
    val position: ReadPosition,
    val state: WishlistItemState,
    val customCategoryId: UUID? = null,
    val purposeId: UUID? = null,
    val purposeSource: ValueSource = ValueSource.UNASSIGNED,
    val imageUrl: String? = null,
    val clientCreatedAt: Instant? = null,
)

fun insertReadFixtures(c: Connection, owner: UUID, fixtures: List<ReadFixture>) {
    c.prepareStatement("insert into app_users(id) values (?) on conflict do nothing").use { s -> s.setObject(1, owner); s.executeUpdate() }
    c.prepareStatement("""insert into wishlist_items(id,owner_id,client_submission_id,source_url,created_at,updated_at,
        analysis_status,review_status,lifecycle_status,product_name,category_id,custom_category_id,category_source,
        category_missing_reason,manual_completion_at,purpose_id,purpose_source,product_image_url,client_created_at)
        values (?,?,?,'https://example.com/item',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""").use { s ->
        for (f in fixtures) {
            s.setObject(1, f.position.id); s.setObject(2, owner); s.setObject(3, UUID.randomUUID())
            s.setObject(4, f.position.createdAt.atOffset(ZoneOffset.UTC)); s.setObject(5, f.position.createdAt.atOffset(ZoneOffset.UTC))
            s.setString(6, f.state.analysisStatus.name); s.setString(7, f.state.reviewStatus.name); s.setString(8, f.state.lifecycleStatus.name)
            s.setString(9, f.state.productName); s.setString(10, if (f.customCategoryId == null) f.state.categoryId else null)
            s.setObject(11, f.customCategoryId); s.setString(12, if (f.state.categoryId == null) "UNASSIGNED" else "USER")
            s.setString(13, f.state.categoryMissingReason?.name); s.setObject(14, f.state.manualCompletionAt?.atOffset(ZoneOffset.UTC))
            s.setObject(15, f.purposeId); s.setString(16, f.purposeSource.name); s.setString(17, f.imageUrl)
            s.setObject(18, f.clientCreatedAt?.atOffset(ZoneOffset.UTC)); s.addBatch()
        }
        s.executeBatch()
    }
}
