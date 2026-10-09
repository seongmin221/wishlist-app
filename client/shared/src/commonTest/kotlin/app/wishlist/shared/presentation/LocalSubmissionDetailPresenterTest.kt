@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.wishlist.shared.presentation

import app.wishlist.shared.core.AuthProvider
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.SessionSnapshot
import app.wishlist.shared.data.fake.error
import app.wishlist.shared.data.fake.successValue
import app.wishlist.shared.data.local.SqlLocalStore
import app.wishlist.shared.data.local.withHarness
import app.wishlist.shared.di.TEST_UTC_OFFSET_SECONDS
import app.wishlist.shared.domain.RelativeTime
import app.wishlist.shared.model.AnalysisStatus
import app.wishlist.shared.model.ItemAnalysis
import app.wishlist.shared.model.LifecycleStatus
import app.wishlist.shared.model.SubmissionStatus
import app.wishlist.shared.model.WishlistItem
import app.wishlist.shared.submission.CoordinatorHarness
import app.wishlist.shared.submission.SubmissionView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

private const val LINK = "https://www.Shop.example/p/1"
private const val GOOGLE_ID = "fake-google-0001"

/** Coordinator harness (real SQLite, scripted ITEM-01, fake login) plus the Presenter over it. */
private class DetailHarness(val test: TestScope, val h: CoordinatorHarness) {
    var lookup: suspend (SessionSnapshot, String) -> ClientResult<WishlistItem?> = h.store::cachedItemBySubmission
    var lookups = 0
    val deletes = mutableListOf<ClientResult<Unit>>()

    /** Runs after the coordinator's delete returned (and published its view), before the Presenter sees the result. */
    var afterDelete: suspend () -> Unit = {}

    val presenter = LocalSubmissionDetailPresenter(
        view = h.coordinator.view,
        lookup = { snapshot, id -> lookups++; lookup(snapshot, id) },
        delete = { id -> h.coordinator.deleteLocal(id).also { deletes += it; afterDelete() } },
        session = h.session,
        clock = h.clock,
        utcOffsetSeconds = { TEST_UTC_OFFSET_SECONDS },
        dispatcher = StandardTestDispatcher(test.testScheduler),
    )

    val state: LocalDetailState get() = presenter.state.value
    val row: LocalDetailRow get() = checkNotNull(state.row) { "no row" }

    /** Shares [LINK] (offline when signed in, so it stays local) and opens its detail. */
    suspend fun shareAndOpen(online: Boolean = false): String {
        h.share(LINK, online)
        val key = h.pending().single().clientSubmissionId
        presenter.load(key)
        test.advanceUntilIdle()
        return key
    }
}

private fun runDetailTest(block: suspend TestScope.(DetailHarness) -> Unit): TestResult = runTest {
    withHarness { storeHarness ->
        val h = CoordinatorHarness(this, storeHarness, storeHarness.store, initiallyReady = true)
        val d = DetailHarness(this, h)
        try {
            advanceUntilIdle()
            block(d)
        } finally {
            d.presenter.close()
            h.scope.cancel()
        }
    }
}

/** The next ITEM-01 answers like the real backend, then [change]s the accepted item. */
private fun DetailHarness.acceptAs(change: (WishlistItem) -> WishlistItem) =
    h.create.then { real -> ClientResult.Success(change(real().successValue())) }

class LocalSubmissionDetailPresenterTest {
    @Test fun showsTheRowAndItsWaitReason() = runDetailTest { d ->
        d.h.signIn()
        val key = d.shareAndOpen()
        assertEquals(LocalDetailRow(key, "shop.example", LINK, RelativeTime.JustNow, RowStatus.WAITING_NETWORK), d.row)
        assertTrue(d.state.canDelete)
        assertNull(d.state.outcome)

        // A server-side failure: the row stays and says the app resends it by itself.
        d.h.create.fail(ClientError(ErrorKind.SERVER))
        d.h.coordinator.requestFlush()
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        assertEquals(RowStatus.RETRYING, d.row.status)
        assertTrue(d.state.canDelete)
        assertNull(d.state.outcome)
    }

    @Test fun submittingRowCannotBeDeleted() = runDetailTest { d ->
        d.h.signIn()
        val key = d.shareAndOpen()
        val gate = CompletableDeferred<Unit>()
        d.h.create.then { real -> gate.await(); real() }
        d.h.coordinator.requestFlush()
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        assertEquals(RowStatus.SENDING, d.row.status)
        assertFalse(d.state.canDelete)

        d.presenter.delete()
        runCurrent()
        assertTrue(d.deletes.isEmpty())
        assertFalse(d.state.deleting)

        gate.complete(Unit)
        advanceUntilIdle()
        val item = d.h.view.processing.single()
        assertEquals(key, item.clientSubmissionId)
        assertEquals(LocalDetailOutcome.MovedTo(item.id), d.state.outcome)
    }

    @Test fun acceptedAsProcessingMovesToTheItem() = runDetailTest { d ->
        d.h.signIn()
        d.shareAndOpen()
        d.h.flush()
        advanceUntilIdle()
        val item = d.h.view.processing.single()
        assertEquals(LocalDetailOutcome.MovedTo(item.id), d.state.outcome)
        assertEquals(1, d.lookups)
    }

    @Test fun acceptedAsReadyMovesToTheItemToo() = runDetailTest { d ->
        d.h.signIn()
        val key = d.shareAndOpen()
        d.acceptAs { it.copy(analysis = ItemAnalysis(AnalysisStatus.READY)) }
        d.h.flush()
        advanceUntilIdle()
        assertTrue(d.h.view.processing.isEmpty())
        val cached = d.h.store.cachedItemBySubmission(d.h.session.state.value, key).successValue()
        assertEquals(LocalDetailOutcome.MovedTo(cached!!.id), d.state.outcome)
    }

    @Test fun acceptedAsDeletedIsRemovedOnServer() = runDetailTest { d ->
        d.h.signIn()
        d.shareAndOpen()
        d.acceptAs { it.copy(lifecycleStatus = LifecycleStatus.DELETED) }
        d.h.flush()
        advanceUntilIdle()
        assertTrue(d.h.pending().isEmpty())
        assertEquals(LocalDetailOutcome.RemovedOnServer, d.state.outcome)
    }

    @Test fun lookupFailureClosesWithoutTheDeletedNotice() = runDetailTest { d ->
        d.lookup = { _, _ -> ClientResult.Failure(ClientError(ErrorKind.UNAVAILABLE)) }
        d.h.signIn()
        d.shareAndOpen()
        d.h.flush()
        advanceUntilIdle()
        assertEquals(1, d.lookups)
        assertEquals(LocalDetailOutcome.Gone, d.state.outcome)
    }

    @Test fun vanishingWhileSignedOutIsGone() = runDetailTest { d ->
        val key = d.shareAndOpen()
        assertEquals(RowStatus.LOCAL_ONLY, d.row.status)
        // Removed elsewhere (e.g. from the home list): signed out there is nothing to look up.
        d.h.store.deleteSubmission(d.h.session.state.value, key).successValue()
        d.h.coordinator.requestViewPublish()
        advanceUntilIdle()
        assertEquals(LocalDetailOutcome.Gone, d.state.outcome)
        assertEquals(0, d.lookups)
    }

    @Test fun movedToEvenWhenTheFirstSignedInViewNoLongerHasTheRow() = runDetailTest { d ->
        val views = mutableListOf<SubmissionView?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { d.h.coordinator.view.collect { views += it } }
        val key = d.shareAndOpen()
        assertEquals(RowStatus.LOCAL_ONLY, d.row.status)

        d.h.signIn() // ITEM-01 answers at once: the row is sent before the account's first view
        val firstSignedIn = views.filterNotNull().first { it.accountId == GOOGLE_ID }
        assertTrue(firstSignedIn.local.isEmpty())
        val item = d.h.view.processing.single()
        assertEquals(key, item.clientSubmissionId)
        assertEquals(LocalDetailOutcome.MovedTo(item.id), d.state.outcome)
    }

    @Test fun leavingTheAccountDecidesNothing() = runDetailTest { d ->
        d.h.signIn(AuthProvider.GOOGLE)
        d.shareAndOpen()
        val shown = d.row

        d.h.signOut()
        d.h.signIn(AuthProvider.APPLE)
        d.h.flush() // more views of the new account
        d.presenter.tick()
        advanceUntilIdle()
        assertNull(d.state.outcome)
        assertEquals(0, d.lookups)
        assertEquals(shown.submissionId, d.row.submissionId)
        assertFalse(d.state.canDelete)
    }

    @Test fun signedOutDeleteWorks() = runDetailTest { d ->
        d.shareAndOpen()
        assertTrue(d.state.canDelete)
        d.presenter.delete()
        advanceUntilIdle()
        assertEquals(LocalDetailOutcome.Deleted, d.state.outcome)
        assertFalse(d.state.deleting)
        assertTrue(d.h.pending().isEmpty())
        assertEquals(0, d.lookups)
    }

    @Test fun aViewWithoutTheRowDuringTheDeleteWaitsForItsAnswer() = runDetailTest { d ->
        d.shareAndOpen()
        val gate = CompletableDeferred<Unit>()
        d.afterDelete = { gate.await() }
        d.presenter.delete()
        runCurrent()
        assertTrue(d.h.view.local.isEmpty()) // the delete's view already arrived
        assertTrue(d.state.deleting)
        assertNull(d.state.outcome)

        gate.complete(Unit)
        runCurrent()
        assertEquals(LocalDetailOutcome.Deleted, d.state.outcome)
        assertFalse(d.state.deleting)
    }

    @Test fun deleteRacingASendWaitsForMovedTo() = runDetailTest { d ->
        d.h.signIn()
        val key = d.shareAndOpen()
        val gate = CompletableDeferred<Unit>()
        d.h.create.then { real -> gate.await(); real() }
        // The user taps delete while the row still shows as waiting, right as the send marks it.
        d.h.store.afterMark = { status ->
            if (status == SubmissionStatus.SUBMITTING) {
                d.h.store.afterMark = {}
                assertTrue(d.state.canDelete)
                d.presenter.delete()
            }
        }
        d.h.coordinator.requestFlush()
        runCurrent()
        val failure = d.deletes.single().error()
        assertEquals(ErrorKind.CONFLICT, failure.kind)
        assertEquals(SqlLocalStore.SUBMISSION_IN_FLIGHT, failure.code)
        assertFalse(d.state.deleting)
        assertFalse(d.state.deleteFailed)
        assertNull(d.state.outcome)

        gate.complete(Unit)
        advanceUntilIdle()
        val item = d.h.view.processing.single()
        assertEquals(key, item.clientSubmissionId)
        assertEquals(LocalDetailOutcome.MovedTo(item.id), d.state.outcome)
    }

    @Test fun tickRecomputesSavedAt() = runDetailTest { d ->
        d.shareAndOpen()
        assertEquals(RelativeTime.JustNow, d.row.savedAt)
        advanceTimeBy(5.minutes)
        runCurrent()
        assertEquals(RelativeTime.JustNow, d.row.savedAt)
        d.presenter.tick()
        runCurrent()
        assertEquals(RelativeTime.Minutes(5), d.row.savedAt)
    }

    @Test fun closeIgnoresLaterIntents() = runDetailTest { d ->
        d.shareAndOpen()
        val before = d.state
        d.presenter.close()
        d.presenter.close()
        d.presenter.delete()
        d.presenter.tick()
        d.presenter.load("00000000-0000-4000-8000-000000000999")
        advanceTimeBy(5.minutes)
        advanceUntilIdle()
        assertEquals(before, d.state)
        assertTrue(d.deletes.isEmpty())
        assertEquals(1, d.h.pending().size)
    }
}
