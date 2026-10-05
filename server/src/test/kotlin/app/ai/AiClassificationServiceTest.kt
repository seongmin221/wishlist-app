package app.ai

import app.DatabaseFactory
import app.analysis.GeneralWorkerService
import app.analysis.ProcessingOutcome
import app.analysis.WorkerDisposition
import app.budget.LlmBudgetService
import app.extraction.ExtractionResult
import app.extraction.GeneralExtractionProcessor
import app.extraction.Metadata
import app.wishlist.CreateWishlistItemService
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import app.testutil.PostgresTestContainer

import app.testutil.*
import app.analysis.*
import kotlin.test.assertNull

class AiClassificationServiceTest {
    @Test fun `stale model results preserve pending data while settling actual usage`() = withAnalysisDatabase { source ->
        for (classification in listOf(ClassificationResult.Assigned("CAT_HOME", null), ClassificationResult.Abstained,
            ClassificationResult.Unusable("bad"), ClassificationResult.Terminal("configuration"), ClassificationResult.Retryable)) {
            val claim = newAnalysisClaim(source)
            var expected: String? = null
            val outcome = pausedAnalysisCall({ pause ->
                AiClassificationService(source, LlmBudgetService(source), { CandidateSnapshot(setOf("CAT_HOME"), emptySet()) },
                    { _, _ -> pause(); GatewayResponse(classification, 500, 20) }).classify(claim, Metadata("Lamp", null, null, "https://example.com/item"))
            }, {
                analysisSql(source, "update wishlist_items set version=version+1 where id='${claim.itemId}'")
                expected = pendingSnapshot(source, claim)
            })
            assertEquals(ProcessingOutcome.Stale, outcome)
            assertEquals(expected, pendingSnapshot(source, claim))
            assertEquals("SETTLED", analysisScalar(source, "select state from llm_budget_reservations where analysis_job_id='${claim.jobId}'"))
            assertEquals("124", analysisScalar(source, "select actual_microusd from llm_budget_reservations where analysis_job_id='${claim.jobId}'"))
            assertEquals("PROCESSING", analysisScalar(source, "select analysis_status from wishlist_items where id='${claim.itemId}'"))
        }
    }

    @Test fun `stale response without usage retains maximum and reconciliation accounting`() = withAnalysisDatabase { source ->
        for (classification in listOf(ClassificationResult.Assigned("CAT_HOME", null), ClassificationResult.Retryable)) {
            val claim = newAnalysisClaim(source)
            val budget = LlmBudgetService(source)
            var expected: String? = null
            val outcome = pausedAnalysisCall({ pause ->
                AiClassificationService(source, budget, { CandidateSnapshot(setOf("CAT_HOME"), emptySet()) },
                    { _, _ -> pause(); GatewayResponse(classification, null, null) }).classify(claim, Metadata("Lamp", null, null, "https://example.com/item"))
            }, {
                analysisSql(source, "update analysis_jobs set execution_token='${UUID.randomUUID()}' where id='${claim.jobId}'")
                expected = pendingSnapshot(source, claim)
            })
            assertEquals(ProcessingOutcome.Stale, outcome)
            assertEquals(expected, pendingSnapshot(source, claim))
            val state = analysisScalar(source, "select state from llm_budget_reservations where analysis_job_id='${claim.jobId}'")
            if (classification == ClassificationResult.Retryable) {
                assertEquals("IN_FLIGHT", state)
                budget.reconcileExpired(java.time.Instant.now().plusSeconds(121))
            } else assertEquals("SETTLED", state)
            assertEquals("496", analysisScalar(source, "select actual_microusd from llm_budget_reservations where analysis_job_id='${claim.jobId}'"))
        }
    }

    @Test fun `expired classification never supplies candidates reserves budget or calls gateway`() = withAnalysisDatabase { source ->
        val claim = newAnalysisClaim(source)
        analysisSql(source, "update analysis_jobs set lease_until=clock_timestamp()-interval '1 second' where id='${claim.jobId}'")
        val before = pendingSnapshot(source, claim)
        val pending = AnalysisPendingResultRepository(source)
        kotlin.test.assertFalse(pending.saveMetadata(claim, Metadata("stale", null, null, "https://example.com/item")))
        kotlin.test.assertFalse(pending.saveAssignment(claim, ClassificationResult.Assigned("CAT_HOME", null)))
        kotlin.test.assertFalse(pending.saveFailure(claim, "stale"))
        assertNull(pending.candidateSnapshot(claim) { error("stale provider must not run") })
        val classifier = AiClassificationService(source, LlmBudgetService(source), { error("candidate supply must not run") }, { _, _ -> error("gateway must not run") })
        assertEquals(ProcessingOutcome.Stale, classifier.classify(claim, Metadata("Lamp", null, null, "https://example.com/item")))
        assertEquals(before, pendingSnapshot(source, claim))
        assertEquals("0", analysisScalar(source, "select count(*) from llm_budget_reservations"))
    }

    @Test fun `extraction is not ready until classification succeeds`() = withJob { source, jobId ->
        val classifier = AiClassificationService(source, LlmBudgetService(source),
            { CandidateSnapshot(setOf("CAT_HOME"), setOf("PUR_GIFT")) },
            { _, _ -> GatewayResponse(ClassificationResult.Assigned("CAT_HOME", "PUR_GIFT"), 500, 20) })
        val processor = GeneralExtractionProcessor(source,
            { ExtractionResult.Complete(Metadata("Lamp", null, null, "https://example.com/item")) },
            classifier::classify)

        assertEquals(WorkerDisposition.ACKNOWLEDGE, GeneralWorkerService(source, processor::process).runGeneral(jobId, 1))
        source.connection.use { c -> c.createStatement().executeQuery("select analysis_status,predicted_category_id,predicted_purpose_id from wishlist_items").use { r ->
            r.next(); assertEquals("READY", r.getString(1)); assertEquals("CAT_HOME", r.getString(2)); assertEquals("PUR_GIFT", r.getString(3))
        } }
    }

    @Test fun `invalid classification leaves extracted item partial`() = withJob { source, jobId ->
        val classifier = AiClassificationService(source, LlmBudgetService(source),
            { CandidateSnapshot(setOf("CAT_HOME"), emptySet()) },
            { _, _ -> GatewayResponse(ClassificationResult.Unusable("invalid_candidate_id_or_status"), 500, 20) })
        val processor = GeneralExtractionProcessor(source,
            { ExtractionResult.Complete(Metadata("Lamp", null, null, "https://example.com/item")) },
            classifier::classify)
        assertEquals(WorkerDisposition.ACKNOWLEDGE, GeneralWorkerService(source, processor::process).runGeneral(jobId, 1))
        source.connection.use { c -> c.createStatement().executeQuery("select product_name,analysis_status from wishlist_items").use { r ->
            r.next(); assertEquals("Lamp", r.getString(1)); assertEquals("PARTIAL", r.getString(2))
        } }
    }

    @Test fun `budget refusal makes item partial without calling model`() = withJob { source, jobId ->
        val classifier = AiClassificationService(source, LlmBudgetService(source, dailyCeilingMicrousd = 1),
            { CandidateSnapshot(setOf("CAT_HOME"), emptySet()) },
            { _, _ -> error("model must not be called") })
        val processor = GeneralExtractionProcessor(source,
            { ExtractionResult.Complete(Metadata("Lamp", null, null, "https://example.com/item")) },
            classifier::classify)
        assertEquals(WorkerDisposition.ACKNOWLEDGE, GeneralWorkerService(source, processor::process).runGeneral(jobId, 1))
        source.connection.use { c -> c.createStatement().executeQuery("select analysis_status,analysis_failure_code from wishlist_items").use { r ->
            r.next(); assertEquals("PARTIAL", r.getString(1)); assertEquals("AI_BUDGET_EXCEEDED", r.getString(2))
        } }
    }

    @Test fun `classification result remains provisional until worker commits terminal state`() = withJob { source, jobId ->
        val claim = claimJob(source, jobId)
        val classifier = AiClassificationService(source, LlmBudgetService(source),
            { CandidateSnapshot(setOf("CAT_HOME"), emptySet()) },
            { _, _ -> GatewayResponse(ClassificationResult.Assigned("CAT_HOME", null), 500, 20) })
        assertEquals(ProcessingOutcome.Complete, classifier.classify(claim, Metadata("Lamp", null, null, "https://example.com/item")))
        source.connection.use { c -> c.createStatement().executeQuery("select predicted_category_id,analysis_status from wishlist_items").use { r ->
            r.next(); assertEquals(null,r.getString(1)); assertEquals("PROCESSING",r.getString(2))
        } }
    }

    @Test fun `retry uses sealed candidate snapshot even when provider changes`() = withJob { source, jobId ->
        var claim = claimJob(source, jobId)
        var current = CandidateSnapshot(setOf("CAT_FIRST"), emptySet())
        var response = GatewayResponse(ClassificationResult.Retryable, null, null)
        val classifier = AiClassificationService(source, LlmBudgetService(source), { current }, { _, _ -> response })
        assertEquals(ProcessingOutcome.Retryable, classifier.classify(claim, Metadata("Lamp", null, null, "https://example.com/item")))
        analysisSql(source, "update analysis_jobs set stage='GENERAL_PENDING' where id='$jobId'")
        claim = claimJob(source, jobId)
        current = CandidateSnapshot(setOf("CAT_SECOND"), emptySet())
        response = GatewayResponse(ClassificationResult.Assigned("CAT_FIRST", null), 500, 20)
        assertEquals(ProcessingOutcome.Complete, classifier.classify(claim, Metadata("Lamp", null, null, "https://example.com/item")))
    }

    @Test fun `retry preserves human readable candidate labels`() = withJob { source, jobId ->
        val claim = claimJob(source, jobId)
        var first = true
        val classifier = AiClassificationService(source,LlmBudgetService(source),
            { CandidateSnapshot(setOf("C026"),emptySet(),mapOf("C026" to "디지털·IT > 헤드폰")) },
            { _, snapshot ->
                if (first) { first=false; GatewayResponse(ClassificationResult.Retryable,null,null) }
                else if (snapshot.categoryLabels["C026"] == "디지털·IT > 헤드폰") GatewayResponse(ClassificationResult.Assigned("C026",null),500,20)
                else GatewayResponse(ClassificationResult.Unusable("missing_label"),500,20)
            })
        assertEquals(ProcessingOutcome.Retryable,classifier.classify(claim,Metadata("Headphones",null,null,"https://example.com/item")))
        assertEquals(ProcessingOutcome.Complete,classifier.classify(claim,Metadata("Headphones",null,null,"https://example.com/item")))
    }

    private fun withJob(block: (javax.sql.DataSource, UUID) -> Unit) {
        PostgresTestContainer().use { db ->
            db.start(); DatabaseFactory.migrate(db.jdbcUrl, db.username, db.password)
            val source = DatabaseFactory.dataSource(db.jdbcUrl, db.username, db.password)
            CreateWishlistItemService(source).create(UUID.randomUUID(), UUID.randomUUID(), "https://example.com/item")
            val jobId = source.connection.use { c -> c.createStatement().executeQuery("select id from analysis_jobs").use { r -> r.next(); r.getObject(1, UUID::class.java) } }
            block(source, jobId)
        }
    }
}
