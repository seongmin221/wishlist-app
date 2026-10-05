package app

import java.util.IdentityHashMap

class RuntimeResources : AutoCloseable {
    private val lock = Any()
    private val seen = IdentityHashMap<AutoCloseable, Boolean>()
    private val resources = mutableListOf<AutoCloseable>()
    private var closed = false
    private var acceptingWork = true
    val isClosed: Boolean get() = synchronized(lock) { closed }

    /** Stop waits for admitted synchronous work; later calls cannot begin. */
    fun stopAcceptingWork() { synchronized(lock) { acceptingWork = false } }

    fun runIfOpen(action: () -> Unit): Boolean = synchronized(lock) {
        if (closed || !acceptingWork) return false
        action()
        true
    }

    fun <T : AutoCloseable> own(resource: T): T {
        val releaseLate = synchronized(lock) {
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
        val toClose = synchronized(lock) {
            if (closed) return
            closed = true
            acceptingWork = false
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
