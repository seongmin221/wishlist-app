@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package app.wishlist.shared.di

import app.wishlist.shared.core.ApiId
import app.wishlist.shared.core.AuthProvider
import app.wishlist.shared.core.Clock
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.IdGenerator
import app.wishlist.shared.data.fake.successValue
import app.wishlist.shared.data.local.UUID_A
import app.wishlist.shared.domain.RelativeTime
import app.wishlist.shared.model.SubmissionStatus
import app.wishlist.shared.presentation.HomeState
import app.wishlist.shared.submission.InboxImportResult
import app.wishlist.shared.submission.InboxRecord
import app.wishlist.shared.submission.SUBMISSION_STEP_FAILURE
import app.wishlist.shared.submission.ShareCardKind
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val LINK = "https://shop.example/p/1"

/** SharedRuntime.submissions(): wiring, runtime-scope failure isolation, and close. */
class RuntimeSubmissionsTest {
    @Test fun debugSignInFlushesUnboundSharesThroughTheRuntimeWiring() = runTest {
        val runtime = createRuntime(debugBindings(), dispatcher = StandardTestDispatcher(testScheduler))
        runtime.startDebugSession()
        advanceUntilIdle()
        val submissions = runtime.submissions()
        assertSame(submissions, runtime.submissions())
        assertEquals(ShareCardKind.LOCAL, submissions.receiveShared("공유 $LINK", online = true))
        advanceUntilIdle()
        assertEquals(1, submissions.view.value.local.size)

        runtime.auth().signIn(AuthProvider.GOOGLE).successValue()
        advanceUntilIdle()
        assertTrue(runtime.localStore().pending().successValue().isEmpty())
        assertEquals("fake-google-0001", submissions.view.value.accountId)
        assertEquals(LINK, submissions.view.value.processing.single().sourceUrl)
        runtime.close()
    }

    @Test fun debugRefreshAdvancesFakeAnalysisAfterFiveSeconds() = runTest {
        var now = runtimeTime
        val runtime = createRuntime(debugBindings(), dispatcher = StandardTestDispatcher(testScheduler), clock = Clock { now })
        runtime.startDebugSession()
        advanceUntilIdle()
        runtime.auth().signIn(AuthProvider.GOOGLE).successValue()
        val submissions = runtime.submissions()
        assertEquals(ShareCardKind.SAVED, submissions.receiveShared(LINK, online = true))
        advanceUntilIdle()
        assertEquals(1, submissions.view.value.processing.size)

        now += 4.seconds
        submissions.refresh()
        assertEquals(1, submissions.view.value.processing.size)
        now += 1.seconds
        submissions.refresh()
        assertTrue(submissions.view.value.processing.isEmpty())
        runtime.close()
    }

    @Test fun foregroundRefreshWithoutDataChangeRecomputesLoggedOutRelativeTime() = runTest {
        var now = runtimeTime
        val runtime = createRuntime(debugBindings(), dispatcher = StandardTestDispatcher(testScheduler), clock = Clock { now })
        runtime.startDebugSession()
        advanceUntilIdle()
        val submissions = runtime.submissions()
        assertEquals(ShareCardKind.LOCAL, submissions.receiveShared(LINK, online = true))
        val home = runtime.homePresenter()
        advanceUntilIdle()
        assertEquals(RelativeTime.JustNow, assertIs<HomeState.LoggedOut>(home.state.value).pending.single().savedAt)

        // Signed out the refresh changes nothing (equal view), yet the rows get a fresh clock.now().
        now += 3.hours
        submissions.refresh()
        advanceUntilIdle()
        assertEquals(RelativeTime.Hours(3), assertIs<HomeState.LoggedOut>(home.state.value).pending.single().savedAt)
        home.close()
        runtime.close()
    }

    @Test fun foregroundRefreshWithoutDataChangeRecomputesLoggedInRelativeTime() = runTest {
        var now = runtimeTime
        // ITEM-03 unbound: the refresh cannot change the cached PROCESSING item.
        val runtime = createRuntime(
            debugBindings(ApiId.ITEM_03 to Backend.UNAVAILABLE),
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = Clock { now },
        )
        runtime.startDebugSession()
        advanceUntilIdle()
        runtime.auth().signIn(AuthProvider.GOOGLE).successValue()
        val submissions = runtime.submissions()
        assertEquals(ShareCardKind.SAVED, submissions.receiveShared(LINK, online = true))
        val home = runtime.homePresenter()
        advanceUntilIdle()
        val before = submissions.view.value
        assertEquals(RelativeTime.JustNow, assertIs<HomeState.LoggedIn>(home.state.value).processing.single().savedAt)

        now += 3.hours
        submissions.refresh()
        advanceUntilIdle()
        assertEquals(before, submissions.view.value)
        assertEquals(RelativeTime.Hours(3), assertIs<HomeState.LoggedIn>(home.state.value).processing.single().savedAt)
        home.close()
        runtime.close()
    }

    @Test fun coordinatorJobFailureIsNotABootstrapFailureAndTheConsumerSurvives() = runTest {
        var explode = false
        val ids = IdGenerator { if (explode) throw IllegalStateException("id source down") else Uuid.random().toString() }
        val runtime = createRuntime(debugBindings(), dispatcher = StandardTestDispatcher(testScheduler), ids = ids)
        runtime.startDebugSession()
        advanceUntilIdle()
        runtime.auth().signIn(AuthProvider.GOOGLE).successValue()
        advanceUntilIdle()
        val submissions = runtime.submissions()
        assertEquals(ShareCardKind.OFFLINE, submissions.receiveShared(LINK, online = false))

        // The Fake ITEM-01 throws inside the flush job running on the runtime scope.
        explode = true
        submissions.requestFlush()
        // Read without suspending: while explode is set the row's 30s retry timer keeps failing, and an
        // idle test scheduler would advance into it forever.
        runCurrent()
        assertNull(runtime.bootstrapFailure.value)
        assertTrue(runtime.ready.value)
        val row = submissions.view.value.local.single()
        assertEquals(SubmissionStatus.PENDING, row.submissionStatus)
        assertEquals(ErrorKind.UNAVAILABLE, row.lastSubmissionError?.kind)
        assertEquals(SUBMISSION_STEP_FAILURE, row.lastSubmissionError?.code)

        // The same single-flight consumer still serves the next request.
        explode = false
        submissions.refresh()
        assertTrue(runtime.localStore().pending().successValue().isEmpty())
        assertEquals(1, submissions.view.value.processing.size)
        assertNull(runtime.bootstrapFailure.value)
        runtime.close()
    }

    @Test fun submissionsAfterCloseAreInertAndSharesReportStoreFailed() = runTest {
        val runtime = createRuntime(debugBindings(), dispatcher = StandardTestDispatcher(testScheduler))
        runtime.startDebugSession()
        advanceUntilIdle()
        val submissions = runtime.submissions()
        runtime.close()
        advanceUntilIdle()

        assertSame(submissions, runtime.submissions())
        assertEquals(ShareCardKind.STORE_FAILED, submissions.receiveShared(LINK, online = true))
        submissions.requestFlush()
        submissions.refresh() // returns instead of waiting forever
        val record = InboxRecord(UUID_A, LINK, "2026-10-07T00:00:00Z", null)
        assertEquals(InboxImportResult(emptyList(), listOf(UUID_A)), submissions.importInbox(listOf(record)))

        // First used only after close: the same inert behaviour.
        val unused = createRuntime(releaseBindings(), dispatcher = StandardTestDispatcher(testScheduler))
        unused.close()
        assertEquals(ShareCardKind.STORE_FAILED, unused.submissions().receiveShared(LINK, online = true))
        unused.submissions().refresh()
    }

    @Test fun refreshBeforeReadyReturnsWhenTheRuntimeCloses() = runTest {
        val runtime = createRuntime(debugBindings(), dispatcher = StandardTestDispatcher(testScheduler))
        val waiter = launch { runtime.submissions().refresh() } // bootstrap never started
        runCurrent()
        runtime.close()
        advanceUntilIdle()
        val returned = waiter.isCompleted
        waiter.cancel()
        assertTrue(returned)
    }

    @Test fun releaseSharesStayLocalAndUnsent() = runTest {
        val runtime = createRuntime(releaseBindings(), dispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(ShareCardKind.LOCAL, runtime.submissions().receiveShared(LINK, online = true))
        runtime.submissions().refresh()
        val row = runtime.localStore().pending().successValue().single()
        assertNull(row.accountBinding)
        assertEquals(SubmissionStatus.PENDING, row.submissionStatus)
        runtime.close()
    }
}
