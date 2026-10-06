package app.analysis

import app.ai.CandidateSnapshot
import app.ai.ClassificationResult
import app.extraction.Metadata
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import kotlinx.serialization.json.*

class AnalysisPendingResultRepository(private val dataSource: DataSource) {
    fun isCurrent(claim: AnalysisClaim): Boolean = guarded(claim) { true } ?: false

    fun sourceUrl(claim: AnalysisClaim): String? = guarded(claim) { c ->
        c.prepareStatement("select source_url from wishlist_items where id=?").use { s ->
            s.setObject(1, claim.itemId); s.executeQuery().use { r -> check(r.next()); r.getString(1) }
        }
    }

    fun saveMetadata(claim: AnalysisClaim, metadata: Metadata): Boolean = guarded(claim) { c ->
        c.prepareStatement("""update analysis_jobs set pending_product_name=?,pending_product_description=?,
            pending_product_image_url=?,pending_canonical_url=? where id=?""").use { s ->
            s.setString(1, metadata.title); s.setString(2, metadata.description); s.setString(3, metadata.imageUrl)
            s.setString(4, metadata.canonicalUrl); s.setObject(5, claim.jobId); check(s.executeUpdate() == 1)
        }
        true
    } ?: false

    fun saveAssignment(claim: AnalysisClaim, result: ClassificationResult.Assigned): Boolean = guarded(claim) { c ->
        c.prepareStatement("update analysis_jobs set pending_category_id=?,pending_purpose_id=?,pending_failure_code=null where id=?").use { s ->
            s.setString(1, result.categoryId); s.setString(2, result.purposeId); s.setObject(3, claim.jobId); check(s.executeUpdate() == 1)
        }
        true
    } ?: false

    fun saveFailure(claim: AnalysisClaim, code: String): Boolean = guarded(claim) { c ->
        c.prepareStatement("update analysis_jobs set pending_failure_code=? where id=?").use { s ->
            s.setString(1, code); s.setObject(2, claim.jobId); check(s.executeUpdate() == 1)
        }
        true
    } ?: false

    /** supply may read local/DB candidates only; no remote call while row locks are held. */
    fun candidateSnapshot(claim: AnalysisClaim, supply: (UUID) -> CandidateSnapshot): CandidateSnapshot? = guarded(claim) { c ->
        val existing = c.prepareStatement("select candidate_snapshot_json from analysis_jobs where id=?").use { s ->
            s.setObject(1, claim.jobId); s.executeQuery().use { r -> check(r.next()); r.getString(1) }
        }
        if (existing != null) {
            val json = Json.parseToJsonElement(existing).jsonObject
            CandidateSnapshot(
                json.getValue("categories").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                json.getValue("purposes").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                json["category_labels"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
                json["purpose_labels"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
            )
        } else {
            val fresh = supply(claim.jobId)
            require(fresh.categoryIds.isNotEmpty() && fresh.purposeIds.size <= 10)
            require(fresh.categoryLabels.keys.all { it in fresh.categoryIds })
            require(fresh.purposeLabels.keys.all { it in fresh.purposeIds })
            val json = JsonObject(mapOf(
                "categories" to JsonArray(fresh.categoryIds.sorted().map(::JsonPrimitive)),
                "purposes" to JsonArray(fresh.purposeIds.sorted().map(::JsonPrimitive)),
                "category_labels" to JsonObject(fresh.categoryLabels.mapValues { JsonPrimitive(it.value) }),
                "purpose_labels" to JsonObject(fresh.purposeLabels.mapValues { JsonPrimitive(it.value) }),
            )).toString()
            // Local candidate work can still consume time: check the lease again before storing.
            if (!AnalysisWriteGuard.lockCurrent(c, claim)) null else {
                c.prepareStatement("update analysis_jobs set candidate_snapshot_json=? where id=?").use { s ->
                    s.setString(1, json); s.setObject(2, claim.jobId); check(s.executeUpdate() == 1)
                }
                fresh
            }
        }
    }

    private fun <T> guarded(claim: AnalysisClaim, block: (Connection) -> T): T? = dataSource.connection.use { c ->
        c.autoCommit = false
        try {
            val result = if (AnalysisWriteGuard.lockCurrent(c, claim)) block(c) else null
            c.commit()
            result
        } catch (cause: Throwable) { c.rollback(); throw cause }
    }
}
