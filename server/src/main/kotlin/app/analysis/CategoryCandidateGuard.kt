package app.analysis

import app.ai.CandidateSnapshot
import app.ai.CandidateSnapshotCodec
import app.category.CategoryRef
import java.sql.Connection

internal data class StoredCandidates(
    val hasSnapshot: Boolean,
    val snapshot: CandidateSnapshot?,
    val pendingCategoryId: String?,
)

internal object CategoryCandidateGuard {
    fun read(connection: Connection, claim: AnalysisClaim): StoredCandidates = connection.prepareStatement(
        "select candidate_snapshot_json,pending_category_id from analysis_jobs where id=?",
    ).use { statement ->
        statement.setObject(1, claim.jobId)
        statement.executeQuery().use { rows ->
            check(rows.next())
            val raw = rows.getString(1)
            StoredCandidates(raw != null, raw?.let(CandidateSnapshotCodec::decode), rows.getString(2))
        }
    }

    /** The owner lock excludes category edits; do not acquire category locks after item/job. */
    fun valid(connection: Connection, claim: AnalysisClaim, stored: StoredCandidates = read(connection, claim)): Boolean {
        if (!stored.hasSnapshot) {
            return stored.pendingCategoryId == null || CategoryRef.parse(stored.pendingCategoryId) is CategoryRef.Public
        }
        val snapshot = stored.snapshot ?: return false
        if (stored.pendingCategoryId != null && stored.pendingCategoryId !in snapshot.categoryIds) return false
        val refs = snapshot.categoryIds.map { CategoryRef.parse(it) ?: return false }
        if (snapshot.schemaVersion == 1) return refs.all { it is CategoryRef.Public }
        if (snapshot.ownerId != claim.ownerId.toString()) return false
        val customIds = refs.filterIsInstance<CategoryRef.Custom>().map { it.value }.toSet()
        if (customIds != snapshot.customCategories.keys) return false
        return customIds.all { id ->
            val ref = CategoryRef.parse(id) as CategoryRef.Custom
            val expected = snapshot.customCategories.getValue(id)
            connection.prepareStatement(
                "select version from custom_categories where owner_id=? and id=? and deleted_at is null and ai_eligible",
            ).use { statement ->
                statement.setObject(1, claim.ownerId)
                statement.setObject(2, ref.id)
                statement.executeQuery().use { rows -> rows.next() && rows.getInt(1) == expected.version }
            }
        }
    }
}
