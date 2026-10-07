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
    @Test fun `missing flight callback with usage cannot store a free classification`() = withAnalysisDatabase { source ->
        val claim = newAnalysisClaim(source)
        val budget = LlmBudgetService(source)
        val classifier = AiClassificationService(source, budget, { CandidateSnapshot(setOf("CAT_HOME"), emptySet()) },
            { _, _, _ -> GatewayResponse(ClassificationResult.Assigned("CAT_HOME", null), 500, 20) })
        kotlin.test.assertFailsWith<IllegalStateException> { classifier.classify(claim, Metadata("Lamp", null, null, "https://example.com/item")) }
        assertEquals("SETTLED", analysisScalar(source, "select state from llm_budget_reservations"))
        assertEquals(496L, budget.windowTotals("DAILY").settled)
        assertEquals(0L, budget.windowTotals("DAILY").reserved)
        assertNull(analysisScalar(source, "select pending_category_id from analysis_jobs where id='${claim.jobId}'"))
    }

    @Test fun `reservation cleanup preserves a flight commit exception and suppresses cleanup failure`() = withAnalysisDatabase { source ->
        val claim = newAnalysisClaim(source)
        val original = java.sql.SQLException("flight commit acknowledged then failed")
        val observed = object : javax.sql.DataSource by source {
            override fun getConnection(): java.sql.Connection {
                val c = source.connection
                return object : java.sql.Connection by c {
                    var flight = false
                    override fun prepareStatement(sql: String): java.sql.PreparedStatement {
                        if (sql.contains("state='IN_FLIGHT'")) flight = true
                        return c.prepareStatement(sql)
                    }
                    override fun commit() { c.commit(); if (flight) throw original }
                }
            }
        }
        val budget = LlmBudgetService(observed)
        val classifier = AiClassificationService(source, budget, { CandidateSnapshot(setOf("CAT_HOME"), emptySet()) },
            { _, _, beforeSend -> beforeSend(); error("send must not occur") })
        val caught = kotlin.test.assertFailsWith<java.sql.SQLException> { classifier.classify(claim, Metadata("Lamp", null, null, "https://example.com/item")) }
        kotlin.test.assertSame(original, caught)
        assertEquals(1, caught.suppressed.size)
        assertEquals("IN_FLIGHT", analysisScalar(source, "select state from llm_budget_reservations"))
        assertEquals(496L, budget.windowTotals("DAILY").reserved)
    }

    @Test fun `deadline consumed by flight DB work sends no paid request and releases reservation`() = withAnalysisDatabase { source ->
        val claim = newAnalysisClaim(source)
        val budget = LlmBudgetService(source)
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val responses = java.util.concurrent.atomic.AtomicInteger()
        var enteredFlight = false
        server.createContext("/v1/responses/input_tokens") { exchange ->
            exchange.requestBody.readAllBytes()
            val bytes = """{"input_tokens":1200}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/responses") { exchange ->
            responses.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close()
        }
        server.start()
        try {
            val gateway = OpenAiResponsesGateway(OpenAiConfig("test-snapshot", "secret"), baseUri = java.net.URI("http://127.0.0.1:${server.address.port}/v1"))
            val classifier = AiClassificationService(source, budget, { CandidateSnapshot(setOf("CAT_HOME"), emptySet()) },
                { text, candidates, beforeSend -> gateway.classify(text, candidates) {
                    beforeSend(); enteredFlight = true
                    while (!WorkerExecution.expired()) Thread.yield()
                } })
            WorkerExecution(timeoutMillis = 10000, processingMillis = 2000).use { execution ->
                execution.run {
                    assertEquals(ProcessingOutcome.Retryable, classifier.classify(claim, Metadata("Lamp", null, null, "https://example.com/item")))
                    WorkerDisposition.ACKNOWLEDGE
                }
            }
            kotlin.test.assertTrue(enteredFlight)
            assertEquals(0, responses.get())
            assertEquals("RELEASED", analysisScalar(source, "select state from llm_budget_reservations"))
            assertEquals(0L, budget.windowTotals("DAILY").settled)
            assertEquals(0L, budget.windowTotals("DAILY").reserved)
        } finally { server.stop(0) }
    }

    @Test fun `oversized token preflight stays reserved and releases without model charge`() = withAnalysisDatabase { source ->
        val claim = newAnalysisClaim(source)
        val budget = LlmBudgetService(source)
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        var duringPreflight: String? = null
        var responses = 0
        server.createContext("/v1/responses/input_tokens") { exchange ->
            exchange.requestBody.readAllBytes()
            duringPreflight = analysisScalar(source, "select state from llm_budget_reservations")
            val bytes = """{"input_tokens":2001}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/responses") { exchange ->
            responses++
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        try {
            val gateway = OpenAiResponsesGateway(OpenAiConfig("test-snapshot", "secret"),
                baseUri = java.net.URI("http://127.0.0.1:${server.address.port}/v1"))
            val classifier = AiClassificationService(source, budget,
                { CandidateSnapshot(setOf("CAT_HOME"), emptySet()) }, gateway::classify)
            assertEquals(ProcessingOutcome.Partial, classifier.classify(claim, Metadata("Lamp", null, null, "https://example.com/item")))
            assertEquals("RESERVED", duringPreflight)
            assertEquals(0, responses)
            assertEquals("RELEASED", analysisScalar(source, "select state from llm_budget_reservations"))
            assertEquals(0L, budget.windowTotals("DAILY").reserved)
            assertEquals(0L, budget.windowTotals("DAILY").settled)
        } finally { server.stop(0) }
    }

    @Test fun `deadline before any HTTP send releases budget without charging`() = withAnalysisDatabase { source ->
        val claim = newAnalysisClaim(source)
        val budget = LlmBudgetService(source)
        val gateway = OpenAiResponsesGateway(OpenAiConfig("test-snapshot", "secret"))
        val classifier = AiClassificationService(source, budget,
            { CandidateSnapshot(setOf("CAT_HOME"), emptySet()) },
            { text, candidates, beforeSend ->
                Thread.currentThread().interrupt()
                try { gateway.classify(text, candidates, beforeSend) } finally { Thread.interrupted() }
            })
        WorkerExecution(timeoutMillis = 10000, processingMillis = 9000).use { execution ->
            execution.run {
                assertEquals(ProcessingOutcome.Retryable, classifier.classify(claim, Metadata("Lamp", null, null, "https://example.com/item")))
                WorkerDisposition.ACKNOWLEDGE
            }
        }
        assertEquals("RELEASED", analysisScalar(source, "select state from llm_budget_reservations"))
        assertEquals(0L, budget.windowTotals("DAILY").reserved)
        assertEquals(0L, budget.windowTotals("DAILY").settled)
    }
    @Test fun `stale model results preserve pending data while settling actual usage`() = withAnalysisDatabase { source ->
        for (classification in listOf(ClassificationResult.Assigned("CAT_HOME", null), ClassificationResult.Abstained,
            ClassificationResult.Unusable("bad"), ClassificationResult.Terminal("configuration"), ClassificationResult.Retryable)) {
            val claim = newAnalysisClaim(source)
            var expected: String? = null
            val outcome = pausedAnalysisCall({ pause ->
                AiClassificationService(source, LlmBudgetService(source), { CandidateSnapshot(setOf("CAT_HOME"), emptySet()) },
                    { _, _, beforeSend -> beforeSend(); pause(); GatewayResponse(classification, 500, 20) }).classify(claim, Metadata("Lamp", null, null, "https://example.com/item"))
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
                    { _, _, beforeSend -> beforeSend(); pause(); GatewayResponse(classification, null, null) }).classify(claim, Metadata("Lamp", null, null, "https://example.com/item"))
            }, {
                analysisSql(source, "update analysis_jobs set execution_token='${UUID.randomUUID()}' where id='${claim.jobId}'")
                expected = pendingSnapshot(source, claim)
            })
            assertEquals(ProcessingOutcome.Stale, outcome)
            assertEquals(expected, pendingSnapshot(source, claim))
            val state = analysisScalar(source, "select state from llm_budget_reservations where analysis_job_id='${claim.jobId}'")
            if (classification == ClassificationResult.Retryable) {
                assertEquals("IN_FLIGHT", state)
                analysisSql(source, "update llm_budget_reservations set lease_until=clock_timestamp()-interval '1 second' where analysis_job_id='${claim.jobId}'")
                assertEquals(1, budget.reconcileExpired())
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
        kotlin.test.assertFalse(pending.saveFailure(claim, app.wishlist.AnalysisFailureCode.AI_ABSTAINED))
        assertNull(pending.candidateSnapshot(claim) { error("stale provider must not run") })
        val classifier = AiClassificationService(source, LlmBudgetService(source), { error("candidate supply must not run") }, { _, _, _ -> error("gateway must not run") })
        assertEquals(ProcessingOutcome.Stale, classifier.classify(claim, Metadata("Lamp", null, null, "https://example.com/item")))
        assertEquals(before, pendingSnapshot(source, claim))
        assertEquals("0", analysisScalar(source, "select count(*) from llm_budget_reservations"))
    }

    @Test fun `extraction is not ready until classification succeeds`() = withJob { source, jobId ->
        val classifier = AiClassificationService(source, LlmBudgetService(source),
            { CandidateSnapshot(setOf("CAT_HOME"), setOf("PUR_GIFT")) },
            { _, _, beforeSend -> beforeSend(); GatewayResponse(ClassificationResult.Assigned("CAT_HOME", "PUR_GIFT"), 500, 20) })
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
            { _, _, beforeSend -> beforeSend(); GatewayResponse(ClassificationResult.Unusable("invalid_candidate_id_or_status"), 500, 20) })
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
            { _, _, _ -> error("model must not be called") })
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
            { _, _, beforeSend -> beforeSend(); GatewayResponse(ClassificationResult.Assigned("CAT_HOME", null), 500, 20) })
        assertEquals(ProcessingOutcome.Complete, classifier.classify(claim, Metadata("Lamp", null, null, "https://example.com/item")))
        source.connection.use { c -> c.createStatement().executeQuery("select predicted_category_id,analysis_status from wishlist_items").use { r ->
            r.next(); assertEquals(null,r.getString(1)); assertEquals("PROCESSING",r.getString(2))
        } }
    }

    @Test fun `retry uses sealed candidate snapshot even when provider changes`() = withJob { source, jobId ->
        var claim = claimJob(source, jobId)
        var current = CandidateSnapshot(setOf("CAT_FIRST"), emptySet())
        var response = GatewayResponse(ClassificationResult.Retryable, null, null)
        val classifier = AiClassificationService(source, LlmBudgetService(source), { current }, { _, _, beforeSend -> beforeSend(); response })
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
            { _, snapshot, beforeSend ->
                beforeSend()
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
