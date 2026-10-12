package app.tasks

import java.time.Instant
import java.util.UUID

/** scheduleAt delays delivery (retry backoff); null delivers immediately. */
data class AnalysisTask(val name: String, val jobId: UUID, val generation: Int, val type: String, val scheduleAt: Instant? = null)

enum class TaskStatus { ALIVE, MISSING }

fun interface TaskGateway {
    fun create(task: AnalysisTask)

    /**
     * Lookup failure throws. The default treats every lookup as failed, so a gateway without lookups never
     * causes a PENDING job to be rescheduled.
     */
    fun status(task: AnalysisTask): TaskStatus = throw UnsupportedOperationException("Task lookup unavailable")
}
