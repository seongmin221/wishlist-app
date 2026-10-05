package app

import java.util.IdentityHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class RuntimeResources : AutoCloseable {
    private val lock = ReentrantLock()
    private val drained = lock.newCondition()
    private var activeWork = 0
    private val seen = IdentityHashMap<AutoCloseable, Boolean>()
    private val resources = mutableListOf<AutoCloseable>()
    private var closed = false
    private var acceptingWork = true
    val isClosed: Boolean get() = lock.withLock { closed }

    /** Stop waits for admitted synchronous work; later calls cannot begin. */
    fun stopAcceptingWork() = lock.withLock {
        acceptingWork = false
        while (activeWork > 0) drained.awaitUninterruptibly()
    }

    fun runIfOpen(action: () -> Unit): Boolean {
        lock.withLock {
            if (closed || !acceptingWork) return false
            activeWork++
        }
        try { action(); return true } finally {
            lock.withLock { activeWork--; if (activeWork == 0) drained.signalAll() }
        }
    }

    fun <T : AutoCloseable> own(resource: T): T {
        val releaseLate = lock.withLock {
            if (!closed) {
                if (seen.put(resource, true) == null) resources.add(resource)
                return resource
            }
            seen.put(resource, true) == null
        }
        val rejected = IllegalStateException("Runtime resources are closed")
        if (releaseLate) try { resource.close() } catch (cause: Throwable) { rejected.addSuppressed(cause) }
        throw rejected
    }

    override fun close() {
        val toClose = lock.withLock {
            if (closed) return
            acceptingWork = false
            while (activeWork > 0) drained.awaitUninterruptibly()
            if (closed) return
            closed = true
            resources.asReversed().toList().also { resources.clear() }
        }
        var failure: Throwable? = null
        for (resource in toClose) {
            try { resource.close() } catch (cause: Throwable) {
                if (failure == null) failure = cause else if (cause !== failure) failure.addSuppressed(cause)
            }
        }
        failure?.let { throw it }
    }
}
