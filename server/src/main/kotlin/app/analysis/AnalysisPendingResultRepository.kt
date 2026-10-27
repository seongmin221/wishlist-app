package app.analysis

import app.ai.CandidateSnapshotCodec
import app.ai.CandidateSnapshot
import app.ai.ClassificationResult
import app.extraction.Metadata
import app.extraction.UrlCanonicalizer
import app.product.CachedProduct
import app.product.ProductCacheRepository
import app.wishlist.AnalysisFailureCode
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

class AnalysisPendingResultRepository(private val dataSource: DataSource) {
    fun isCurrent(claim: AnalysisClaim): Boolean = guarded(claim) { true } ?: false

    fun sourceUrl(claim: AnalysisClaim): String? = guarded(claim) { c -> c.sourceUrl(claim.itemId) }

    /** An unexpired READY cache entry for the claim's share URL; read under the same guard as every claim write. */
    fun cachedProduct(claim: AnalysisClaim): CachedProduct? = guarded(claim) { c ->
        ProductCacheRepository.lookup(c, UrlCanonicalizer.normalize(c.sourceUrl(claim.itemId)))
    }

    /**
     * Stores the page read for finish. A fresh titled read also refreshes the shared cache in the same guarded transaction;
     * a read copied from [cached] keeps the cache untouched and carries its extraction time as the price check time.
     */
    fun saveMetadata(claim: AnalysisClaim, metadata: Metadata, cached: CachedProduct? = null): Boolean = guarded(claim) { c ->
        c.prepareStatement("""update analysis_jobs set pending_product_name=?,pending_product_description=?,
            pending_product_image_url=?,pending_canonical_url=?,pending_brand=?,pending_price=?,pending_currency=?,pending_merchant=?,
            pending_gtin=?,pending_mpn_key=?,pending_checked_at=?,pending_product_cache_id=? where id=?""").use { s ->
            s.setString(1, metadata.title); s.setString(2, metadata.description); s.setString(3, metadata.imageUrl)
            s.setString(4, metadata.canonicalUrl); s.setString(5, metadata.brand)
            // The parser yields both or neither; never store half a price pair.
            val pair = metadata.price != null && metadata.currency != null
            s.setBigDecimal(6, metadata.price.takeIf { pair }); s.setString(7, metadata.currency.takeIf { pair })
            s.setString(8, metadata.merchant); s.setString(9, metadata.gtin); s.setString(10, metadata.mpnKey)
            s.setTimestamp(11, cached?.let { java.sql.Timestamp.from(it.extractedAt) }); s.setObject(12, cached?.id)
            s.setObject(13, claim.jobId); check(s.executeUpdate() == 1)
        }
        if (cached == null && !metadata.title.isNullOrBlank()) {
            ProductCacheRepository.upsert(c, UrlCanonicalizer.normalize(c.sourceUrl(claim.itemId)), metadata)
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

private fun Connection.sourceUrl(itemId: UUID): String = prepareStatement("select source_url from wishlist_items where id=?").use { s ->
    s.setObject(1, itemId); s.executeQuery().use { r -> check(r.next()); r.getString(1) }
}
