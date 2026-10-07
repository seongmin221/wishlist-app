package app.wishlist.shared.core

import app.cash.turbine.test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuthSessionTest {
    @Test
    fun startsWithoutAccountAtGenerationZero() {
        val session: AuthSession = MutableAuthSession()
        assertEquals(SessionSnapshot(null, 0), session.state.value)
    }

    @Test
    fun switchingAccountsIncrementsGeneration() = runTest {
        val session = MutableAuthSession()
        session.changeAccount("A")
        assertEquals(SessionSnapshot("A", 1), session.state.value)
        session.changeAccount("B")
        assertEquals(SessionSnapshot("B", 2), session.state.value)
    }

    @Test
    fun logoutAndReturningAccountCannotReuseOldGeneration() = runTest {
        val session = MutableAuthSession()
        session.changeAccount("A")
        session.changeAccount(null)
        assertEquals(SessionSnapshot(null, 2), session.state.value)
        session.changeAccount("A")
        assertEquals(SessionSnapshot("A", 3), session.state.value)
    }

    @Test
    fun sameAccountReloginPublishesNewGeneration() = runTest {
        val session = MutableAuthSession()
        session.state.test {
            assertEquals(SessionSnapshot(null, 0), awaitItem())
            session.changeAccount("A")
            assertEquals(SessionSnapshot("A", 1), awaitItem())
            session.changeAccount("A")
            assertEquals(SessionSnapshot("A", 2), awaitItem())
        }
    }

    @Test
    fun repeatedLogoutStillChangesSessionGeneration() = runTest {
        val session = MutableAuthSession()
        session.changeAccount(null)
        session.changeAccount(null)
        assertEquals(SessionSnapshot(null, 2), session.state.value)
    }

    @Test
    fun currentGateReturnsSuccessWithoutNestingOrChangingSnapshot() = runTest {
        val session = MutableAuthSession()
        session.changeAccount("A")
        val snapshot = session.state.value
        val success = ClientResult.Success(7)
        val result: ClientResult<Int> = session.withCurrent(snapshot) { success }
        assertSame(success, result)
        assertEquals(ClientResult.Success(7), result)
        // Token refresh keeps the account session: it never calls changeAccount.
        assertEquals(SessionSnapshot("A", 1), session.state.value)
        assertEquals(ClientResult.Success(8), session.withCurrent(snapshot) { ClientResult.Success(8) })
        assertEquals(snapshot, session.state.value)
    }

    @Test
    fun currentGateReturnsFailureWithoutNesting() = runTest {
        val session = MutableAuthSession()
        val failure = ClientResult.Failure(ClientError(kind = ErrorKind.NETWORK))
        val result: ClientResult<Int> = session.withCurrent(session.state.value) { failure }
        assertSame(failure, result)
    }

    @Test
    fun staleAccountNeverInvokesCommit() = runTest {
        val session = MutableAuthSession()
        session.changeAccount("A")
        val snapshot = session.state.value
        session.changeAccount("B")
        var committed = false
        val result = session.withCurrent(snapshot) {
            committed = true
            ClientResult.Success(7)
        }
        assertEquals(ClientResult.Failure(ClientError(ErrorKind.SESSION_CHANGED)), result)
        assertFalse(committed)
    }

    @Test
    fun staleGenerationOfSameAccountNeverInvokesCommit() = runTest {
        val session = MutableAuthSession()
        session.changeAccount("A")
        val snapshot = session.state.value
        session.changeAccount("A")
        var committed = false
        val result = session.withCurrent(snapshot) {
            committed = true
            ClientResult.Success(7)
        }
        assertEquals(ClientResult.Failure(ClientError(ErrorKind.SESSION_CHANGED)), result)
        assertFalse(committed)
    }

    @Test
    fun accountIdMustMatchEvenWhenGenerationMatches() = runTest {
        val session = MutableAuthSession()
        session.changeAccount("A")
        var committed = false
        val result = session.withCurrent(SessionSnapshot("B", 1)) {
            committed = true
            ClientResult.Success(7)
        }
        assertEquals(ClientResult.Failure(ClientError(ErrorKind.SESSION_CHANGED)), result)
        assertFalse(committed)
    }

    @Test
    fun operationCancellationPropagatesAndReleasesGate() = runTest {
        val session = MutableAuthSession()
        val cancellation = CancellationException("commit cancelled")
        val thrown = assertFailsWith<CancellationException> {
            session.withCurrent<Int>(session.state.value) { throw cancellation }
        }
        assertSame(cancellation, thrown)
        session.changeAccount("B")
        assertEquals(SessionSnapshot("B", 1), session.state.value)
        assertEquals(ClientResult.Success(7), session.withCurrent(session.state.value) { ClientResult.Success(7) })
    }

    @Test
    fun accountChangeWaitsForCurrentCommitToFinish() = runTest {
        val session = MutableAuthSession()
        session.changeAccount("A")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val commit = async {
            session.withCurrent(session.state.value) {
                events += "commit-start"
                entered.complete(Unit)
                // Deterministic test barrier only; production gate commits must stay short.
                release.await()
                assertEquals(SessionSnapshot("A", 1), session.state.value)
                events += "commit-end"
                ClientResult.Success(7)
            }
        }
        entered.await()
        val change = launch {
            session.changeAccount("B")
            events += "account-B"
        }
        runCurrent()
        assertFalse(change.isCompleted)
        assertEquals(SessionSnapshot("A", 1), session.state.value)
        release.complete(Unit)
        assertEquals(ClientResult.Success(7), commit.await())
        change.join()
        assertEquals(listOf("commit-start", "commit-end", "account-B"), events)
        assertEquals(SessionSnapshot("B", 2), session.state.value)
    }

    @Test
    fun queuedCommitChecksSnapshotAfterAccountChangeWinsGate() = runTest {
        val session = MutableAuthSession()
        session.changeAccount("A")
        val snapshot = session.state.value
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch {
            session.withCurrent(snapshot) {
                entered.complete(Unit)
                release.await()
                ClientResult.Success(Unit)
            }
        }
        entered.await()
        val change = launch { session.changeAccount("B") }
        runCurrent()
        var committed = false
        val queuedCommit = async {
            session.withCurrent(snapshot) {
                committed = true
                ClientResult.Success(7)
            }
        }
        runCurrent()
        release.complete(Unit)
        first.join()
        change.join()
        assertEquals(ClientResult.Failure(ClientError(ErrorKind.SESSION_CHANGED)), queuedCommit.await())
        assertFalse(committed)
        assertEquals(SessionSnapshot("B", 2), session.state.value)
    }

    @Test
    fun cancellingQueuedCommitNeverInvokesItOrBlocksAccountChange() = runTest {
        val session = MutableAuthSession()
        val snapshot = session.state.value
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch {
            session.withCurrent(snapshot) {
                entered.complete(Unit)
                release.await()
                ClientResult.Success(Unit)
            }
        }
        entered.await()
        var committed = false
        val queuedCommit = launch {
            session.withCurrent(snapshot) {
                committed = true
                ClientResult.Success(7)
            }
        }
        runCurrent()
        queuedCommit.cancel()
        queuedCommit.join()
        assertTrue(queuedCommit.isCancelled)
        release.complete(Unit)
        first.join()
        session.changeAccount("B")
        assertFalse(committed)
        assertEquals(SessionSnapshot("B", 1), session.state.value)
    }
}
