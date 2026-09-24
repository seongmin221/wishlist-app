package app.tasks

import com.google.api.gax.rpc.AlreadyExistsException
import com.google.cloud.tasks.v2.CloudTasksClient
import com.google.cloud.tasks.v2.HttpMethod
import com.google.cloud.tasks.v2.HttpRequest
import com.google.cloud.tasks.v2.OidcToken
import com.google.cloud.tasks.v2.QueueName
import com.google.cloud.tasks.v2.Task
import com.google.protobuf.ByteString
import com.google.protobuf.Duration

data class CloudTasksConfig(
    val projectId: String,
    val location: String,
    val generalQueue: String,
    val browserQueue: String,
    val generalWorkerUrl: String,
    val browserWorkerUrl: String,
    val callerServiceAccount: String,
)

class CloudTasksGateway(private val client: CloudTasksClient, private val config: CloudTasksConfig) : TaskGateway {
    override fun create(task: AnalysisTask) {
        val queue = if (task.type == "BROWSER_ANALYSIS") config.browserQueue else config.generalQueue
        val queuePath = QueueName.of(config.projectId, config.location, queue).toString()
        try {
            client.createTask(queuePath, buildTask(config, task))
        } catch (_: AlreadyExistsException) {
            // A previous dispatcher may have created this deterministic task before crashing.
        }
    }

    companion object {
        fun buildTask(config: CloudTasksConfig, task: AnalysisTask): Task {
            val browser = task.type == "BROWSER_ANALYSIS"
            val queue = if (browser) config.browserQueue else config.generalQueue
            val baseUrl = if (browser) config.browserWorkerUrl else config.generalWorkerUrl
            val path = if (browser) "/internal/worker/browser" else "/internal/worker/general"
            val parent = QueueName.of(config.projectId, config.location, queue).toString()
            val body = """{"jobId":"${task.jobId}","generation":${task.generation}}"""
            return Task.newBuilder()
                .setName("$parent/tasks/${task.name}")
                .setDispatchDeadline(Duration.newBuilder().setSeconds(105).build())
                .setHttpRequest(
                    HttpRequest.newBuilder()
                        .setHttpMethod(HttpMethod.POST)
                        .setUrl(baseUrl.trimEnd('/') + path)
                        .setOidcToken(OidcToken.newBuilder().setServiceAccountEmail(config.callerServiceAccount).setAudience(baseUrl.trimEnd('/')))
                        .setBody(ByteString.copyFromUtf8(body))
                        .putHeaders("Content-Type", "application/json")
                        .build(),
                ).build()
        }
    }
}
