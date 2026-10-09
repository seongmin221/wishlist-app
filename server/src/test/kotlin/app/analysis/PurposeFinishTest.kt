package app.analysis

import app.testutil.*
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*

class PurposeFinishTest {
    @Test fun `valid judged purpose links the item and records membership activity on both lanes`() = withAnalysisDatabase { source ->
        for (lane in AnalysisLane.entries) {
            val owner = UUID.randomUUID(); val claim = ownedAnalysisClaim(source, owner, lane)
            val purpose = insertPurpose(source, owner, "출퇴근 헤드폰", "노이즈 캔슬링")
            seedV3Snapshot(source, claim, listOf(purpose))
            savePurposeResult(source, claim, purpose.toString(), judged = true)
            val activity = purposeValue(source, purpose, "activity_at")
            assertEquals(WorkerDisposition.ACKNOWLEDGE, AnalysisResultRepository(source).finish(claim, ProcessingOutcome.Complete))
            assertEquals(purpose.toString(), itemValue(source, claim, "purpose_id"), lane.name)
            assertEquals("AI", itemValue(source, claim, "purpose_source"))
            assertEquals("PENDING", itemValue(source, claim, "review_status"))
            assertEquals("2", purposeValue(source, purpose, "membership_version"))
            assertEquals("CANDIDATE_ADDED", purposeValue(source, purpose, "activity_kind"))
            assertNotEquals(activity, purposeValue(source, purpose, "activity_at"))
        }
    }

    @Test fun `stale or unjudged purpose results keep the existing connection without replacement`() = withAnalysisDatabase { source ->
        for (reason in listOf("name", "description", "archived", "legacy", "unjudged", "unsupplied")) {
            val owner = UUID.randomUUID(); val claim = ownedAnalysisClaim(source, owner, AnalysisLane.GENERAL)
            val existing = insertPurpose(source, owner, "existing", null)
            val chosen = insertPurpose(source, owner, "chosen", "desc")
            val unsupplied = insertPurpose(source, owner, "unsupplied", null)
            analysisSql(source, "update wishlist_items set purpose_id='$existing',purpose_source='AI' where id='${claim.itemId}'")
            seedV3Snapshot(source, claim, listOf(chosen))
            savePurposeResult(source, claim, when (reason) { "unjudged" -> null; "unsupplied" -> unsupplied.toString(); else -> chosen.toString() }, reason != "unjudged")
            when (reason) {
                "name" -> analysisSql(source, "update purposes set name='renamed' where id='$chosen'")
                "description" -> analysisSql(source, "update purposes set description=null where id='$chosen'")
                "archived" -> analysisSql(source, "update purposes set lifecycle_status='ARCHIVED' where id='$chosen'")
                "legacy" -> analysisSql(source, """update analysis_jobs set candidate_snapshot_json='{"schema_version":2,"owner_id":"$owner","custom_categories":{},"categories":["C026"],"purposes":["$chosen"]}' where id='${claim.jobId}'""")
            }
            assertEquals(WorkerDisposition.ACKNOWLEDGE, AnalysisResultRepository(source).finish(claim, ProcessingOutcome.Complete), reason)
            assertEquals(existing.toString(), itemValue(source, claim, "purpose_id"), reason)
            assertEquals("AI", itemValue(source, claim, "purpose_source"), reason)
            assertEquals("READY", itemValue(source, claim, "analysis_status"), reason)
            assertEquals("1", itemValue(source, claim, "current_generation"), reason)
            for (id in listOf(existing, chosen, unsupplied)) assertEquals("1", purposeValue(source, id, "membership_version"), reason)
        }
    }

    @Test fun `color only edits keep the AI decision and judged unassigned clears an AI link`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID(); val claim = ownedAnalysisClaim(source, owner, AnalysisLane.GENERAL)
        val purpose = insertPurpose(source, owner, "gift", null)
        seedV3Snapshot(source, claim, listOf(purpose)); savePurposeResult(source, claim, purpose.toString(), judged = true)
        analysisSql(source, "update purposes set color_key='MINT',icon_key='GIFT',version=version+1 where id='$purpose'")
        AnalysisResultRepository(source).finish(claim, ProcessingOutcome.Complete)
        assertEquals(purpose.toString(), itemValue(source, claim, "purpose_id"))

        val second = ownedAnalysisClaim(source, owner, AnalysisLane.GENERAL)
        analysisSql(source, "update wishlist_items set purpose_id='$purpose',purpose_source='AI' where id='${second.itemId}'")
        seedV3Snapshot(source, second, listOf(purpose)); savePurposeResult(source, second, null, judged = true)
        val before = purposeValue(source, purpose, "membership_version")!!.toInt()
        AnalysisResultRepository(source).finish(second, ProcessingOutcome.Complete)
        assertNull(itemValue(source, second, "purpose_id"))
        assertEquals("UNASSIGNED", itemValue(source, second, "purpose_source"))
        assertEquals((before + 1).toString(), purposeValue(source, purpose, "membership_version"))
    }

    @Test fun `no purpose judgement keeps an existing AI link the model did not see unchanged`() = withAnalysisDatabase { source ->
        for (reason in listOf("unsent", "renamed")) {
            val owner = UUID.randomUUID(); val claim = ownedAnalysisClaim(source, owner, AnalysisLane.GENERAL)
            val existing = insertPurpose(source, owner, "existing", null); val other = insertPurpose(source, owner, "other", null)
            analysisSql(source, "update wishlist_items set purpose_id='$existing',purpose_source='AI' where id='${claim.itemId}'")
            seedV3Snapshot(source, claim, if (reason == "unsent") listOf(other) else listOf(existing, other))
            if (reason == "renamed") analysisSql(source, "update purposes set name='renamed' where id='$existing'")
            savePurposeResult(source, claim, null, judged = true)
            AnalysisResultRepository(source).finish(claim, ProcessingOutcome.Complete)
            assertEquals(existing.toString(), itemValue(source, claim, "purpose_id"), reason)
            assertEquals("1", purposeValue(source, existing, "membership_version"), reason)
        }
    }

    @Test fun `predicted purpose records only judged results and the default result does not judge`() = withAnalysisDatabase { source ->
        for (case in listOf("unjudged", "renamed", "default", "judged")) {
            val owner = UUID.randomUUID(); val claim = ownedAnalysisClaim(source, owner, AnalysisLane.GENERAL)
            val existing = insertPurpose(source, owner, "existing", null); val chosen = insertPurpose(source, owner, "chosen", null)
            analysisSql(source, "update wishlist_items set purpose_id='$existing',purpose_source='AI',predicted_purpose_id='$existing' where id='${claim.itemId}'")
            seedV3Snapshot(source, claim, listOf(existing, chosen))
            val pending = app.analysis.AnalysisPendingResultRepository(source)
            check(pending.saveMetadata(claim, app.extraction.Metadata("AI name", null, null, "https://example.com/item")))
            when (case) {
                "unjudged" -> check(pending.saveAssignment(claim, app.ai.ClassificationResult.Assigned("C026", null, purposeJudged = false)))
                "renamed" -> { check(pending.saveAssignment(claim, app.ai.ClassificationResult.Assigned("C026", chosen.toString(), purposeJudged = true)))
                    analysisSql(source, "update purposes set name='renamed' where id='$chosen'") }
                "default" -> check(pending.saveAssignment(claim, app.ai.ClassificationResult.Assigned("C026", null)))
                else -> check(pending.saveAssignment(claim, app.ai.ClassificationResult.Assigned("C026", chosen.toString(), purposeJudged = true)))
            }
            AnalysisResultRepository(source).finish(claim, ProcessingOutcome.Complete)
            val expected = if (case == "judged") chosen else existing
            assertEquals(expected.toString(), itemValue(source, claim, "purpose_id"), case)
            assertEquals(expected.toString(), itemValue(source, claim, "predicted_purpose_id"), case)
        }
        assertFalse(app.ai.ClassificationResult.Assigned("C026", null).purposeJudged)
    }

    @Test fun `reviewed user and override purposes are never filled or replaced`() = withAnalysisDatabase { source ->
        val cases = listOf(
            "CONFIRMED" to "purpose_source='UNASSIGNED'", "DEFERRED" to "purpose_source='UNASSIGNED'",
            "PENDING" to "purpose_source='USER'", "PENDING" to "purpose_source='UNASSIGNED',user_override_fields=array['PURPOSE']",
            "CONFIRMED" to "purpose_source='AI',purpose_id=%KEEP%",
        )
        for ((review, assignment) in cases) {
            val owner = UUID.randomUUID(); val claim = ownedAnalysisClaim(source, owner, AnalysisLane.GENERAL)
            val keep = insertPurpose(source, owner, "keep", null); val chosen = insertPurpose(source, owner, "chosen", null)
            analysisSql(source, "update wishlist_items set review_status='$review',${assignment.replace("%KEEP%", "'$keep'")} where id='${claim.itemId}'")
            val beforeId = itemValue(source, claim, "purpose_id"); val beforeSource = itemValue(source, claim, "purpose_source")
            seedV3Snapshot(source, claim, listOf(chosen)); savePurposeResult(source, claim, chosen.toString(), judged = true)
            AnalysisResultRepository(source).finish(claim, ProcessingOutcome.Complete)
            assertEquals(beforeId, itemValue(source, claim, "purpose_id"), assignment)
            assertEquals(beforeSource, itemValue(source, claim, "purpose_source"), assignment)
            assertEquals(review, itemValue(source, claim, "review_status"), assignment)
            assertEquals("1", purposeValue(source, chosen, "membership_version"), assignment)
        }
    }

    @Test fun `an item cannot reference another owner's purpose`() = withAnalysisDatabase { source ->
        val owner = UUID.randomUUID(); val claim = ownedAnalysisClaim(source, owner, AnalysisLane.GENERAL)
        val foreign = insertPurpose(source, UUID.randomUUID(), "foreign", null)
        val failure = assertFailsWith<SQLException> { analysisSql(source, "update wishlist_items set purpose_id='$foreign',purpose_source='USER' where id='${claim.itemId}'") }
        assertEquals("23503", failure.sqlState)
    }

    private fun itemValue(source: DataSource, claim: AnalysisClaim, column: String) =
        analysisScalar(source, "select $column::text from wishlist_items where id='${claim.itemId}'")
    private fun purposeValue(source: DataSource, id: UUID, column: String) =
        analysisScalar(source, "select $column::text from purposes where id='$id'")
}
