package app.testutil

import app.analysis.WorkerDisposition
import app.tasks.AnalysisTask
import app.tasks.TaskGateway
import app.tasks.TaskStatus
import java.util.UUID

/** Fake Cloud Tasks queue: deterministic names, task loss, lookup outages and explicit delivery. */
class InMemoryTaskQueue : TaskGateway {
    private val live = linkedMapOf<String, AnalysisTask>()
    val created = mutableListOf<AnalysisTask>()
    var failLookups = false
    val failCreates = mutableSetOf<String>()

    @Synchronized override fun create(task: AnalysisTask) {
        if (task.name in failCreates) error("queue unavailable")
        if (created.none { it.name == task.name }) { created += task; live[task.name] = task }
    }

    @Synchronized override fun status(task: AnalysisTask): TaskStatus {
        if (failLookups) error("lookup unavailable")
        return if (task.name in live) TaskStatus.ALIVE else TaskStatus.MISSING
    }

    /** Simulates task loss or retry exhaustion: the queue forgets the task. */
    @Synchronized fun drop(name: String) { live.remove(name) }

    @Synchronized fun liveNames(): List<String> = live.keys.toList()

    /** Delivers a live task; an acknowledged task leaves the queue, a retried one stays. */
    fun deliver(name: String, worker: (UUID, Int) -> WorkerDisposition): WorkerDisposition {
        val task = synchronized(this) { checkNotNull(live[name]) { "no live task $name" } }
        val disposition = worker(task.jobId, task.generation)
        if (disposition == WorkerDisposition.ACKNOWLEDGE) drop(name)
        return disposition
    }
}
