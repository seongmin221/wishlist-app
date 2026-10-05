package app

import kotlin.test.*

class RuntimeResourcesTest {
    @Test fun `stopping drains admitted dispatch and rejects new work`() {
        val resources = RuntimeResources()
        val admitted = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val stopping = java.util.concurrent.CountDownLatch(1)
        val stopped = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val threads = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val work = threads.submit<Boolean> { resources.runIfOpen { admitted.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); calls.incrementAndGet() } }
            assertTrue(admitted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val stop = threads.submit { stopping.countDown(); resources.stopAcceptingWork(); stopped.countDown() }
            assertTrue(stopping.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(stopped.await(100, java.util.concurrent.TimeUnit.MILLISECONDS), "stop must wait for admitted synchronous work")
            release.countDown()
            assertTrue(work.get(5, java.util.concurrent.TimeUnit.SECONDS))
            stop.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertFalse(resources.runIfOpen { calls.incrementAndGet() })
            assertEquals(1, calls.get())
            resources.close()
            assertFalse(resources.runIfOpen { error("must not run after close") })
        } finally { release.countDown(); threads.shutdownNow(); resources.close() }
    }

    @Test fun `resources close in reverse order exactly once including duplicate ownership`() {
        val order = mutableListOf<Int>()
        val resources = RuntimeResources()
        val first = AutoCloseable { order.add(1) }
        assertSame(first, resources.own(first))
        resources.own(first)
        resources.own(AutoCloseable { order.add(2) })
        resources.close()
        resources.close()
        assertEquals(listOf(2, 1), order)
        assertTrue(resources.isClosed)
    }

    @Test fun `one close failure still releases other resources and repeated close is safe`() {
        val order = mutableListOf<Int>()
        val resources = RuntimeResources()
        resources.own(AutoCloseable { order.add(1) })
        resources.own(AutoCloseable { order.add(2); error("close failed") })
        resources.own(AutoCloseable { order.add(3); error("another close failed") })
        val failure = assertFailsWith<IllegalStateException> { resources.close() }
        assertEquals(1, failure.suppressed.size)
        assertEquals(listOf(3, 2, 1), order)
        resources.close()
        assertEquals(listOf(3, 2, 1), order)
    }

    @Test fun `late ownership rejects and releases new resource without closing owned resource twice`() {
        var closes = 0
        val resources = RuntimeResources()
        val first = resources.own(AutoCloseable { closes++ })
        resources.close()
        assertFailsWith<IllegalStateException> { resources.own(first) }
        assertFailsWith<IllegalStateException> { resources.own(AutoCloseable { closes++ }) }
        assertEquals(2, closes)
    }
}
