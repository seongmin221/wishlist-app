@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.wishlist.shared.di

import app.wishlist.shared.core.AuthProvider
import app.wishlist.shared.data.fake.successValue
import app.wishlist.shared.model.SubmissionStatus
import app.wishlist.shared.submission.ShareCardKind
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SharedRuntime.debugControls(): DEBUG-only demo hooks (launch argument / intent extra). */
class RuntimeDebugControlsTest {
    @Test fun releaseRuntimeHasNoDebugControls() = runTest {
        val runtime = createRuntime(releaseBindings())
        assertNull(runtime.debugControls())
        runtime.close()
    }

    @Test fun pendingCountCreatesUnboundRowsInSharedOrderOnceReady() = runTest {
        val runtime = createRuntime(debugBindings(), dispatcher = StandardTestDispatcher(testScheduler))
        val controls = assertNotNull(runtime.debugControls())
        // Asked before the bootstrap, as the apps do right after assembly: it waits for ready.
        val created = async { controls.createUnboundPending(3) }
        runtime.startDebugSession()
        advanceUntilIdle()
        assertEquals(3, created.await().successValue())

        val rows = runtime.localStore().pending().successValue()
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.accountBinding == null && it.submissionStatus == SubmissionStatus.PENDING })
        assertEquals(3, rows.map { it.clientSubmissionId }.toSet().size)
        assertEquals(3, rows.map { it.sourceUrl }.toSet().size)
        assertEquals(rows.map { it.sharedAt }.sorted(), rows.map { it.sharedAt })
        assertTrue(rows.zipWithNext().all { (a, b) -> a.sharedAt < b.sharedAt })
        assertTrue(rows.last().sharedAt <= runtimeTime)
        // The home view picks them up without another signal.
        assertEquals(rows, runtime.submissions().view.value.local)
        runtime.close()
    }

    @Test fun zeroPendingCountCreatesNothing() = runTest {
        val runtime = createRuntime(debugBindings(), dispatcher = StandardTestDispatcher(testScheduler))
        runtime.startDebugSession()
        advanceUntilIdle()
        assertEquals(0, assertNotNull(runtime.debugControls()).createUnboundPending(0).successValue())
        assertTrue(runtime.localStore().pending().successValue().isEmpty())
        runtime.close()
    }

    @Test fun delayItem01HoldsTheNextSendOnly() = runTest {
        val runtime = createRuntime(debugBindings(), dispatcher = StandardTestDispatcher(testScheduler))
        runtime.startDebugSession()
        advanceUntilIdle()
        runtime.auth().signIn(AuthProvider.GOOGLE).successValue()
        advanceUntilIdle()
        assertNotNull(runtime.debugControls()).delayNextItem01(5_000)
        val submissions = runtime.submissions()

        assertEquals(ShareCardKind.SAVED, submissions.receiveShared("https://shop.example/p/1", online = true))
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(SubmissionStatus.SUBMITTING, runtime.localStore().pending().successValue().single().submissionStatus)
        advanceTimeBy(1_001)
        advanceUntilIdle()
        assertTrue(runtime.localStore().pending().successValue().isEmpty())
        assertEquals(1, submissions.view.value.processing.size)

        // Consumed: the next share is sent at once.
        assertEquals(ShareCardKind.SAVED, submissions.receiveShared("https://shop.example/p/2", online = true))
        runCurrent()
        assertTrue(runtime.localStore().pending().successValue().isEmpty())
        assertEquals(2, submissions.view.value.processing.size)
        runtime.close()
    }
}
