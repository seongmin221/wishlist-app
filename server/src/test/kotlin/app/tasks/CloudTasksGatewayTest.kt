package app.tasks

import com.google.cloud.tasks.v2.HttpMethod
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudTasksGatewayTest {
    @Test fun `create task rpc is bounded to five seconds without hidden retries`() {
        val settings = CloudTasksGateway.clientSettings().createTaskSettings()
        assertEquals(java.time.Duration.ofSeconds(5), settings.retrySettings.totalTimeoutDuration)
        assertEquals(java.time.Duration.ofSeconds(5), settings.retrySettings.initialRpcTimeoutDuration)
        assertEquals(java.time.Duration.ofSeconds(5), settings.retrySettings.maxRpcTimeoutDuration)
        assertTrue(settings.retryableCodes.isEmpty())
    }

    @Test
    fun `general task has deterministic name oidc target and 105 second deadline`() {
        val jobId = UUID.randomUUID()
        val config = CloudTasksConfig("project", "asia-southeast1", "general", "browser", "https://worker.run.app", "https://browser.run.app", "caller@project.iam.gserviceaccount.com")

        val task = CloudTasksGateway.buildTask(config, AnalysisTask("analysis-$jobId-1", jobId, 1, "GENERAL_ANALYSIS"))

        assertEquals("projects/project/locations/asia-southeast1/queues/general/tasks/analysis-$jobId-1", task.name)
        assertEquals(HttpMethod.POST, task.httpRequest.httpMethod)
        assertEquals("https://worker.run.app/internal/worker/general", task.httpRequest.url)
        assertEquals("caller@project.iam.gserviceaccount.com", task.httpRequest.oidcToken.serviceAccountEmail)
        assertEquals(105, task.dispatchDeadline.seconds)
        assertTrue(task.httpRequest.body.toStringUtf8().contains(jobId.toString()))
    }

    @Test fun `get task rpc is bounded like create`() {
        val settings = CloudTasksGateway.clientSettings().getTaskSettings()
        assertEquals(java.time.Duration.ofSeconds(5), settings.retrySettings.totalTimeoutDuration)
        assertTrue(settings.retryableCodes.isEmpty())
    }

    @Test fun `schedule time is set only for delayed tasks`() {
        val jobId = UUID.randomUUID()
        val config = CloudTasksConfig("project", "asia-southeast1", "general", "browser", "https://worker.run.app", "https://browser.run.app", "caller@project.iam.gserviceaccount.com")
        val at = java.time.Instant.parse("2026-10-10T01:00:20Z")
        assertEquals(at.epochSecond, CloudTasksGateway.buildTask(config, AnalysisTask("a", jobId, 1, "GENERAL_ANALYSIS", at)).scheduleTime.seconds)
        kotlin.test.assertFalse(CloudTasksGateway.buildTask(config, AnalysisTask("b", jobId, 1, "GENERAL_ANALYSIS")).hasScheduleTime())
    }

    @Test fun `existing task name counts as created and lookups map not found to missing`() {
        val config = CloudTasksConfig("project", "asia-southeast1", "general", "browser", "https://worker.run.app", "https://browser.run.app", "caller@project.iam.gserviceaccount.com")
        var lookup: () -> com.google.cloud.tasks.v2.Task = { com.google.cloud.tasks.v2.Task.getDefaultInstance() }
        val requests = mutableListOf<com.google.cloud.tasks.v2.CreateTaskRequest>()
        val stub = FakeTasksStub({ request -> requests += request; throw alreadyExists() }, { lookup() })
        com.google.cloud.tasks.v2.CloudTasksClient.create(stub).use { client ->
            val gateway = CloudTasksGateway(client, config)
            val task = AnalysisTask("retry-1", UUID.randomUUID(), 1, "GENERAL_ANALYSIS", java.time.Instant.parse("2026-10-10T01:00:20Z"))
            gateway.create(task)
            assertEquals(1, requests.size)
            assertTrue(requests.single().task.hasScheduleTime())
            assertEquals(TaskStatus.ALIVE, gateway.status(task))
            lookup = { throw com.google.api.gax.rpc.NotFoundException(RuntimeException("fake"), com.google.api.gax.grpc.GrpcStatusCode.of(io.grpc.Status.Code.NOT_FOUND), false) }
            assertEquals(TaskStatus.MISSING, gateway.status(task))
            lookup = { throw com.google.api.gax.rpc.UnavailableException(RuntimeException("fake"), com.google.api.gax.grpc.GrpcStatusCode.of(io.grpc.Status.Code.UNAVAILABLE), false) }
            kotlin.test.assertFails { gateway.status(task) }
        }
    }

    private fun alreadyExists() = com.google.api.gax.rpc.AlreadyExistsException(RuntimeException("fake"), com.google.api.gax.grpc.GrpcStatusCode.of(io.grpc.Status.Code.ALREADY_EXISTS), false)

    private class FakeTasksStub(
        private val onCreate: (com.google.cloud.tasks.v2.CreateTaskRequest) -> com.google.cloud.tasks.v2.Task,
        private val onGet: () -> com.google.cloud.tasks.v2.Task,
    ) : com.google.cloud.tasks.v2.stub.CloudTasksStub() {
        override fun createTaskCallable() = callable<com.google.cloud.tasks.v2.CreateTaskRequest> { onCreate(it) }
        override fun getTaskCallable() = callable<com.google.cloud.tasks.v2.GetTaskRequest> { onGet() }
        private fun <Q : Any> callable(body: (Q) -> com.google.cloud.tasks.v2.Task) = object : com.google.api.gax.rpc.UnaryCallable<Q, com.google.cloud.tasks.v2.Task>() {
            override fun futureCall(request: Q, context: com.google.api.gax.rpc.ApiCallContext?): com.google.api.core.ApiFuture<com.google.cloud.tasks.v2.Task> =
                try { com.google.api.core.ApiFutures.immediateFuture(body(request)) } catch (cause: Exception) { com.google.api.core.ApiFutures.immediateFailedFuture(cause) }
        }
        override fun close() {}
        override fun shutdown() {}
        override fun isShutdown() = true
        override fun isTerminated() = true
        override fun shutdownNow() {}
        override fun awaitTermination(duration: Long, unit: java.util.concurrent.TimeUnit) = true
    }
}
