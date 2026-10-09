package app.testutil

import app.ai.CandidateSnapshot
import app.ai.CandidateSnapshotCodec
import app.ai.ClassificationResult
import app.ai.PurposeCandidate
import app.analysis.AnalysisClaim
import app.analysis.AnalysisLane
import app.analysis.AnalysisPendingResultRepository
import app.extraction.Metadata
import app.wishlist.CreateWishlistItemService
import java.util.UUID
import javax.sql.DataSource

fun ownedAnalysisClaim(source: DataSource, owner: UUID, lane: AnalysisLane): AnalysisClaim {
    val item = CreateWishlistItemService(source).create(owner, UUID.randomUUID(), "https://example.com/item").createdItemId
    val job = UUID.fromString(analysisScalar(source, "select id from analysis_jobs where wishlist_item_id='$item'"))
    if (lane == AnalysisLane.BROWSER) analysisSql(source, "update analysis_jobs set stage='BROWSER_PENDING',browser_attempted=true,attempt_count=1,first_attempt_at=clock_timestamp() where id='$job'")
    return claimJob(source, job, lane)
}

/** Inserts an ACTIVE purpose without the service so schema-level tests do not depend on PUR-02. */
fun insertPurpose(source: DataSource, owner: UUID, name: String, description: String?): UUID {
    val id = UUID.randomUUID()
    source.connection.use { c ->
        c.prepareStatement("insert into app_users(id) values (?) on conflict do nothing").use { s -> s.setObject(1, owner); s.executeUpdate() }
        c.prepareStatement("""insert into purposes(id,owner_id,name,description,color_key,icon_key,activity_at)
            values (?,?,?,?,'CORAL','HEART',clock_timestamp())""").use { s ->
            s.setObject(1, id); s.setObject(2, owner); s.setString(3, name); s.setString(4, description); check(s.executeUpdate() == 1)
        }
    }
    return id
}

fun seedV3Snapshot(source: DataSource, claim: AnalysisClaim, purposes: List<UUID>) {
    val candidates = purposes.map { id ->
        val (name, description) = source.connection.use { c -> c.prepareStatement("select name,description from purposes where id=?").use { s ->
            s.setObject(1, id); s.executeQuery().use { r -> check(r.next()); r.getString(1) to r.getString(2) }
        } }
        PurposeCandidate(id.toString(), name, description, emptyList())
    }
    val snapshot = CandidateSnapshot(setOf("C026"), candidates.map { it.id }.toCollection(LinkedHashSet()),
        ownerId = claim.ownerId.toString(), schemaVersion = 3, purposeCandidates = candidates)
    source.connection.use { c -> c.prepareStatement("update analysis_jobs set candidate_snapshot_json=? where id=?").use { s ->
        s.setString(1, CandidateSnapshotCodec.encode(snapshot)); s.setObject(2, claim.jobId); check(s.executeUpdate() == 1)
    } }
}

fun savePurposeResult(source: DataSource, claim: AnalysisClaim, purposeId: String?, judged: Boolean) {
    val pending = AnalysisPendingResultRepository(source)
    check(pending.saveMetadata(claim, Metadata("AI name", null, null, "https://example.com/item")))
    check(pending.saveAssignment(claim, ClassificationResult.Assigned("C026", purposeId, judged)))
}
