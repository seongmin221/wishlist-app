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
import org.testcontainers.containers.PostgreSQLContainer

class AiClassificationServiceTest {
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
        source.connection.use { c -> c.prepareStatement("update analysis_jobs set stage='GENERAL_RUNNING' where id=?").use { s -> s.setObject(1,jobId); s.executeUpdate() } }
        val classifier = AiClassificationService(source, LlmBudgetService(source),
            { CandidateSnapshot(setOf("CAT_HOME"), emptySet()) },
            { _, _ -> GatewayResponse(ClassificationResult.Assigned("CAT_HOME", null), 500, 20) })
        assertEquals(ProcessingOutcome.Complete, classifier.classify(jobId, Metadata("Lamp", null, null, "https://example.com/item")))
        source.connection.use { c -> c.createStatement().executeQuery("select predicted_category_id,analysis_status from wishlist_items").use { r ->
            r.next(); assertEquals(null,r.getString(1)); assertEquals("PROCESSING",r.getString(2))
        } }
    }

    private fun withJob(block: (javax.sql.DataSource, UUID) -> Unit) {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { db ->
            db.start(); DatabaseFactory.migrate(db.jdbcUrl, db.username, db.password)
            val source = DatabaseFactory.dataSource(db.jdbcUrl, db.username, db.password)
            CreateWishlistItemService(source).create(UUID.randomUUID(), UUID.randomUUID(), "https://example.com/item")
            val jobId = source.connection.use { c -> c.createStatement().executeQuery("select id from analysis_jobs").use { r -> r.next(); r.getObject(1, UUID::class.java) } }
            block(source, jobId)
        }
    }
}
