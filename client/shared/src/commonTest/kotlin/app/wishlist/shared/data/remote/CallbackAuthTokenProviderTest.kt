package app.wishlist.shared.data.remote

import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.MutableAuthSession
import app.wishlist.shared.core.SessionSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CallbackAuthTokenProviderTest {
    private val session = MutableAuthSession()
    private val source = FakeTokenSource()
    private val provider = CallbackAuthTokenProvider(session, source)

    private suspend fun snapshotA(): SessionSnapshot {
        session.changeAccount("A")
        return session.state.value
    }

    @Test
    fun synchronousCallbackSuccess() = runTest {
        val snapshot = snapshotA()
        source.respondToken("tok")
        assertEquals(ClientResult.Success("tok"), provider.getToken(snapshot, forceRefresh = false))
        assertEquals(listOf(false), source.requests.map { it.forceRefresh })
    }

    @Test
    fun forceRefreshFlagReachesPlatform() = runTest {
        val snapshot = snapshotA()
        source.respondToken("a", "b")
        assertEquals(ClientResult.Success("b"), provider.getToken(snapshot, forceRefresh = true))
        assertEquals(listOf(true), source.requests.map { it.forceRefresh })
    }

    @Test
    fun errorCodeBecomesUnauthenticatedAndIsKept() = runTest {
        val snapshot = snapshotA()
        source.responder = { it.callback.complete(null, "auth/network-request-failed") }
        val error = provider.getToken(snapshot, false).failureError()
        assertEquals(ErrorKind.UNAUTHENTICATED, error.kind)
        assertEquals("auth/network-request-failed", error.code)
    }

    @Test
    fun nullTokenWithoutErrorCodeIsUnauthenticated() = runTest {
        val snapshot = snapshotA()
        source.responder = { it.callback.complete(null, null) }
        val error = provider.getToken(snapshot, false).failureError()
        assertEquals(ErrorKind.UNAUTHENTICATED, error.kind)
        assertNull(error.code)
    }

    @Test
    fun asynchronousCallbackAndDuplicateCompletionAreIgnored() = runTest {
        val snapshot = snapshotA()
        val result = async { provider.getToken(snapshot, false) }
        runCurrent()
        val request = source.requests.single()
        request.callback.complete("first", null)
        request.callback.complete("second", null)
        request.callback.complete(null, "late-error")
        assertEquals(ClientResult.Success("first"), result.await())
        assertEquals(0, request.cancelCount)
    }

    @Test
    fun cancellationCancelsPlatformRequestOnceAndLateCallbackIsIgnored() = runTest {
        val snapshot = snapshotA()
        var propagated = false
        val job = launch {
            try {
                provider.getToken(snapshot, false)
            } catch (e: CancellationException) {
                propagated = true
                throw e
            }
        }
        runCurrent()
        val request = source.requests.single()
        job.cancel()
        runCurrent()
        assertTrue(propagated)
        assertEquals(1, request.cancelCount)
        // Late and duplicate completions after cancel must neither throw nor cancel again.
        request.callback.complete("late", null)
        request.callback.complete("later", null)
        assertEquals(1, request.cancelCount)
    }

    @Test
    fun staleSnapshotAtStartDoesNotAskPlatform() = runTest {
        val snapshot = snapshotA()
        session.changeAccount("B")
        assertEquals(ErrorKind.SESSION_CHANGED, provider.getToken(snapshot, false).failureKind())
        assertTrue(source.requests.isEmpty())
    }

    @Test
    fun sessionChangeBeforeCallbackCompletesIsSessionChanged() = runTest {
        val snapshot = snapshotA()
        val result = async { provider.getToken(snapshot, false) }
        runCurrent()
        session.changeAccount("B")
        source.requests.single().callback.complete("token-for-a", null)
        assertEquals(ErrorKind.SESSION_CHANGED, result.await().failureKind())
    }

    @Test
    fun sameAccountReloginIsAlsoSessionChanged() = runTest {
        val snapshot = snapshotA()
        val result = async { provider.getToken(snapshot, false) }
        runCurrent()
        session.changeAccount(null)
        session.changeAccount("A")
        source.requests.single().callback.complete("token", null)
        assertEquals(ErrorKind.SESSION_CHANGED, result.await().failureKind())
    }
}
