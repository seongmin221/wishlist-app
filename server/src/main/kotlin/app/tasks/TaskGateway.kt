package app.tasks

import java.util.UUID

data class AnalysisTask(val name: String, val jobId: UUID, val generation: Int, val type: String)

fun interface TaskGateway {
    fun create(task: AnalysisTask)
}
