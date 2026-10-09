package app.purpose

import app.ai.*
import app.analysis.*
import app.budget.LlmBudgetService
import app.extraction.Metadata
import app.testutil.*
import app.wishlist.CreateWishlistItemService
import java.util.UUID
import kotlin.test.*

class PurposeAiIntegrationTest {
    private val input = PurposeInput("목적", "설명", PurposeColor.CORAL, PurposeIcon.HEART)

    @Test fun `supply uses top ten active purposes and newest two other item names`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID()
        val ids = List(11) { insertPurpose(source, owner, "p$it", "설명") } // more than the 10-per-60s create limit
        analysisSql(source, "update purposes set activity_at='2026-10-07T00:00:00Z'::timestamptz + (substring(name from 2)::int * interval '1 minute') where owner_id='$owner'")
        analysisSql(source, "update purposes set lifecycle_status='ARCHIVED' where id='${ids[10]}'")
        insertPurpose(source, UUID.randomUUID(), "foreign", null)
        val claim = ownedAnalysisClaim(source, owner, AnalysisLane.GENERAL)
        val names = listOf("  ", "첫째", "둘째", "가".repeat(25))
        names.forEachIndexed { n, name -> CreateWishlistItemService(source).create(owner, UUID.randomUUID(), "https://example.com/$n").createdItemId.also { id ->
            analysisSql(source, "update wishlist_items set product_name='$name',purpose_id='${ids[9]}',purpose_source='USER',created_at='2026-10-0${n + 1}T00:00:00Z' where id='$id'") } }
        analysisSql(source, "update wishlist_items set purpose_id='${ids[9]}',purpose_source='USER',product_name='분석 대상' where id='${claim.itemId}'")
        val snapshot = AnalysisPendingResultRepository(source).candidateSnapshotWithConnection(claim, CategoryCandidateProvider()::snapshot)!!
        assertEquals(3, snapshot.schemaVersion)
        assertEquals((9 downTo 0).map { ids[it].toString() }, snapshot.purposeCandidates.map { it.id })
        assertEquals(listOf("가".repeat(20), "둘째"), snapshot.purposeCandidates.first().itemNames)
        assertTrue(snapshot.purposeCandidates.drop(1).all { it.itemNames.isEmpty() })
    }

    @Test fun `reused snapshot rebuilds the same alias order`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID(); val service = PurposeService(source)
        repeat(7) { service.create(owner, UUID.randomUUID(), input.copy(name = "p$it")) }
        val claim = ownedAnalysisClaim(source, owner, AnalysisLane.GENERAL)
        val pending = AnalysisPendingResultRepository(source)
        val fresh = pending.candidateSnapshotWithConnection(claim, CategoryCandidateProvider()::snapshot)!!
        val reused = pending.candidateSnapshotWithConnection(claim) { _, _ -> error("stored snapshot must be reused") }!!
        val gateway = OpenAiResponsesGateway(OpenAiConfig("test-snapshot", "secret"))
        assertEquals(gateway.requestBody("m", fresh).toString(), gateway.requestBody("m", reused).toString())
        assertEquals(fresh.purposeCandidates, reused.purposeCandidates)
    }

    @Test fun `purpose rename during the AI call discards only the purpose while color edits and new purposes do not`() = withAnalysisDatabase { source ->
        for (edit in listOf("rename", "color", "create")) {
            val owner = UUID.randomUUID(); val service = PurposeService(source)
            val purpose = service.create(owner, UUID.randomUUID(), input).purpose
            val claim = ownedAnalysisClaim(source, owner, AnalysisLane.GENERAL)
            val outcome = pausedAnalysisCall({ pause ->
                AiClassificationService(source, LlmBudgetService(source), CategoryCandidateProvider()) { _, _, before ->
                    before(); pause(); GatewayResponse(ClassificationResult.Assigned("C026", purpose.id.toString(), true), 1000, 20)
                }.classify(claim, Metadata("headphones", null, null, "https://example.com/item"))
            }, {
                when (edit) {
                    "rename" -> service.patch(owner, purpose.id, 1, PurposeChanges(name = "renamed"))
                    "color" -> service.patch(owner, purpose.id, 1, PurposeChanges(color = PurposeColor.PINK))
                    else -> service.create(owner, UUID.randomUUID(), input.copy(name = "new"))
                }
            })
            assertEquals(ProcessingOutcome.Complete, outcome, edit)
            AnalysisResultRepository(source).finish(claim, outcome)
            assertEquals(if (edit == "rename") null else purpose.id.toString(),
                analysisScalar(source, "select purpose_id::text from wishlist_items where id='${claim.itemId}'"), edit)
            assertEquals("READY", analysisScalar(source, "select analysis_status from wishlist_items where id='${claim.itemId}'"))
            assertEquals("1", analysisScalar(source, "select count(*) from analysis_jobs where wishlist_item_id='${claim.itemId}'"), edit)
        }
    }
}
