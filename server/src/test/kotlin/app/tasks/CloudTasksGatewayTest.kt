package app.tasks

import com.google.cloud.tasks.v2.HttpMethod
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudTasksGatewayTest {
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
}
