package app.analysis

import app.ai.CandidateSnapshotCodec
import app.ai.CandidateSnapshot
import app.ai.ClassificationResult
import app.extraction.Metadata
import app.wishlist.AnalysisFailureCode
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

class AnalysisPendingResultRepository(private val dataSource: DataSource) {
    fun isCurrent(claim: AnalysisClaim): Boolean = guarded(claim) { true } ?: false

    fun sourceUrl(claim: AnalysisClaim): String? = guarded(claim) { c ->
        c.prepareStatement("select source_url from wishlist_items where id=?").use { s ->
            s.setObject(1, claim.itemId); s.executeQuery().use { r -> check(r.next()); r.getString(1) }
        }
    }

    fun saveMetadata(claim: AnalysisClaim, metadata: Metadata): Boolean = guarded(claim) { c ->
        c.prepareStatement("""update analysis_jobs set pending_product_name=?,pending_product_description=?,
            pending_product_image_url=?,pending_canonical_url=?,pending_brand=?,pending_price=?,pending_currency=?,pending_merchant=? where id=?""").use { s ->
            s.setString(1, metadata.title); s.setString(2, metadata.description); s.setString(3, metadata.imageUrl)
            s.setString(4, metadata.canonicalUrl); s.setString(5, metadata.brand)
            // The parser yields both or neither; never store half a price pair.
            val pair = metadata.price != null && metadata.currency != null
            s.setBigDecimal(6, metadata.price.takeIf { pair }); s.setString(7, metadata.currency.takeIf { pair })
            s.setString(8, metadata.merchant); s.setObject(9, claim.jobId); check(s.executeUpdate() == 1)
        }
        true
    } ?: false

    fun saveAssignment(claim: AnalysisClaim, result: ClassificationResult.Assigned): Boolean = guarded(claim) { c ->
        c.prepareStatement("""update analysis_jobs set pending_category_id=?,pending_purpose_id=?,pending_purpose_judged=?,
            pending_failure_code=null where id=?""").use { s ->
            s.setString(1, result.categoryId); s.setString(2, result.purposeId); s.setBoolean(3, result.purposeJudged)
            s.setObject(4, claim.jobId); check(s.executeUpdate() == 1)
        }
        true
    } ?: false

    fun saveFailure(claim: AnalysisClaim, code: AnalysisFailureCode): Boolean = guarded(claim) { c ->
        c.prepareStatement("update analysis_jobs set pending_failure_code=? where id=?").use { s ->
            s.setString(1, code.name); s.setObject(2, claim.jobId); check(s.executeUpdate() == 1)
        }
        true
    } ?: false

    /** supply may read local/DB candidates only; no remote call while row locks are held. */
    fun candidateSnapshotWithConnection(claim:AnalysisClaim,supply:(Connection,AnalysisClaim)->CandidateSnapshot):CandidateSnapshot? = guarded(claim) { c ->
        snapshotLocked(c,claim) { supply(c,claim) }
    }

    private fun snapshotLocked(c:Connection,claim:AnalysisClaim,supply:()->CandidateSnapshot):CandidateSnapshot? {
        val stored = CategoryCandidateGuard.read(c, claim)
        return if (stored.hasSnapshot) {
            if (CategoryCandidateGuard.valid(c, claim, stored)) stored.snapshot else null
        } else {
            val fresh = supply().copy(ownerId = claim.ownerId.toString())
            val json = CandidateSnapshotCodec.encode(fresh)
            requireNotNull(CandidateSnapshotCodec.decode(json)) { "Invalid candidate snapshot" }
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
