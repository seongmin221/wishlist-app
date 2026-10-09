package app.wishlist.shared.di

import app.wishlist.shared.core.AuthProvider
import app.wishlist.shared.data.fake.error
import app.wishlist.shared.data.fake.successValue
import app.wishlist.shared.repository.LocalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * close() racing a DB call that is already running on the io thread: the driver is closed only
 * after that call has left the store (no use-after-free), and every DB call that starts after
 * close() is a typed UNAVAILABLE/RUNTIME_NOT_READY failure that never touches the DB.
 * The io dispatcher here is a real thread pool and the query is parked inside the SQL driver.
 */
class RuntimeCloseLeaseTest {
    /** Parks the next query whose SQL contains [sql] on its (io) thread. */
    private fun CountingDriver.parkNextQuery(sql: String, gate: ThreadGate) {
        beforeQuery = { query ->
            if (sql in query) {
                beforeQuery = {}
                gate.block()
            }
        }
    }

    /** Teardown may run on whichever thread leaves the store last; wait for it in real time. */
    private suspend fun CountingDriver.awaitClosed() = withContext(Dispatchers.Default) {
        withTimeout(10.seconds) { while (closes == 0) delay(1) }
    }

    @Test fun close_waits_for_a_local_store_query_already_inside_the_driver() = runTest {
        val probe = RuntimeResourcesProbe()
        val runtime = createRuntime(releaseBindings(), probe = probe, dispatcher = Dispatchers.Default)
        runtime.localStore().pending().successValue()          // opens the driver
        val driver = probe.drivers.single()
        val gate = ThreadGate()
        driver.parkNextQuery(SELECT_UNBOUND, gate)

        val inFlight = async(Dispatchers.Default) { runtime.localStore().pending() }
        try {
            gate.awaitEntered()
            runtime.close()
            assertFalse(runtime.ready.value)
            assertEquals(0, driver.closes, "the driver must stay open while a query is inside it")
            // Started after close: typed failure through the facade and the raw graph store.
            assertEquals(RUNTIME_NOT_READY, runtime.localStore().pending().error().code)
        } finally {
            gate.release()
        }
        assertEquals(emptyList(), inFlight.await().successValue())
        // The last DB call to leave ran the teardown.
        assertEquals(1, driver.closes)
    }

    @Test fun close_waits_for_the_cached_get_path_and_its_later_store_calls_fail_typed() = runTest {
        val probe = RuntimeResourcesProbe()
        val runtime = createRuntime(debugBindings(), probe = probe, dispatcher = Dispatchers.Default)
        runtime.startDebugSession()
        runtime.ready.first { it }
        runtime.auth().signIn(AuthProvider.GOOGLE).successValue()
        val seeded = runtime.catalogRepository().items(null, null).successValue().first()
        runtime.submissions().refresh()  // settles the sign-in flush
        val driver = probe.drivers.single()
        val gate = ThreadGate()
        // CachedGetItemRepository holds the raw graph store: its first call is the cache read (selectItem).
        driver.parkNextQuery(SELECT_ITEM, gate)

        val inFlight = async(Dispatchers.Default) { runtime.getItemRepository().get(seeded.id) }
        try {
            gate.awaitEntered()
            runtime.close()
            assertEquals(0, driver.closes, "the driver must stay open while selectItem runs")
        } finally {
            gate.release()
        }
        // The cache write after the delegate starts after close: refused, typed, without the DB.
        assertEquals(RUNTIME_NOT_READY, inFlight.await().error().code)
        driver.awaitClosed()
        assertEquals(1, driver.closes)
    }

    @Test fun graph_store_refuses_calls_after_close_without_opening_the_driver() = runTest {
        val probe = RuntimeResourcesProbe()
        val runtime = createRuntime(releaseBindings(), probe = probe)
        val raw = runtime.koin.get<LocalStore>()                // what CachedGetItemRepository holds
        runtime.close()

        assertEquals(RUNTIME_NOT_READY, raw.pending().error().code)
        assertEquals(RUNTIME_NOT_READY, raw.readAppState("k").error().code)
        assertEquals(RUNTIME_NOT_READY, raw.cachedItem(runtime.session.state.value, "id").error().code)
        assertTrue(probe.drivers.isEmpty())
    }

    private companion object {
        const val SELECT_UNBOUND = "FROM local_submission WHERE account_binding IS NULL ORDER BY"
        const val SELECT_ITEM = "FROM item_cache WHERE account_id = ? AND item_id = ?"
    }
}
