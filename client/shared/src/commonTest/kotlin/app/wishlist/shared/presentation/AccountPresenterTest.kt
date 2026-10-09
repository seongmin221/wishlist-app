@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.wishlist.shared.presentation

import app.wishlist.shared.core.AuthAccount
import app.wishlist.shared.core.AuthFacade
import app.wishlist.shared.core.AuthProvider
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.di.UnavailableAuthFacade
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class ScriptedAuth(restored: Boolean = true, var seen: Boolean = false) : AuthFacade {
    val mutableAccount = MutableStateFlow<AuthAccount?>(null)
    val mutableRestored = MutableStateFlow(restored)
    override val account: StateFlow<AuthAccount?> = mutableAccount
    override val restored: StateFlow<Boolean> = mutableRestored
    var signInCalls = 0
    var signOutCalls = 0
    var markCalls = 0

    /** Like the runtime's gated facade: the first-run write is dropped until the login is restored (ready). */
    var dropMarksUntilRestored = false

    /** When set, sign-in waits for it; otherwise it succeeds at once. */
    var signInGate: CompletableDeferred<ClientResult<AuthAccount>>? = null

    override suspend fun signIn(provider: AuthProvider): ClientResult<AuthAccount> {
        signInCalls++
        val result = signInGate?.await() ?: ClientResult.Success(AuthAccount("a", "a@x", provider))
        if (result is ClientResult.Success) mutableAccount.value = result.value
        return result
    }

    override suspend fun signOut(): ClientResult<Unit> {
        signOutCalls++
        mutableAccount.value = null
        return ClientResult.Success(Unit)
    }

    override suspend fun hasSeenFirstRunLogin() = seen
    override suspend fun markFirstRunLoginSeen() {
        markCalls++
        if (dropMarksUntilRestored && !mutableRestored.value) return
        seen = true
    }
}

class AccountPresenterTest {
    private fun TestScope.presenter(auth: AuthFacade) =
        AccountPresenter(auth, StandardTestDispatcher(testScheduler))

    @Test
    fun firstRunShownOnlyWhenRestoredSignedOutAndNotSeen() = runTest {
        val notRestored = ScriptedAuth(restored = false)
        val p = presenter(notRestored)
        advanceUntilIdle()
        assertFalse(p.state.value.showFirstRunLogin)
        assertFalse(p.state.value.restored)
        notRestored.mutableRestored.value = true
        advanceUntilIdle()
        assertTrue(p.state.value.showFirstRunLogin)
        p.close()

        val seen = presenter(ScriptedAuth(seen = true))
        advanceUntilIdle()
        assertFalse(seen.state.value.showFirstRunLogin)
        seen.close()

        val signedIn = ScriptedAuth().also { it.mutableAccount.value = AuthAccount("a", "a@x", AuthProvider.GOOGLE) }
        val s = presenter(signedIn)
        advanceUntilIdle()
        assertFalse(s.state.value.showFirstRunLogin)
        assertNotNull(s.state.value.account)
        s.close()
    }

    @Test
    fun accountRestoredBeforeReadyStoresTheFirstRunFlagOnceRestored() = runTest {
        // The runtime publishes the saved account during its restore, before ready/restored.
        val auth = ScriptedAuth(restored = false).also { it.dropMarksUntilRestored = true }
        val p = presenter(auth)
        advanceUntilIdle()
        auth.mutableAccount.value = AuthAccount("a", "a@x", AuthProvider.GOOGLE)
        advanceUntilIdle()
        auth.mutableRestored.value = true
        advanceUntilIdle()
        assertTrue(auth.seen, "the flag must be stored once the facade accepts it")

        p.signOut()
        advanceUntilIdle()
        assertFalse(p.state.value.showFirstRunLogin)
        p.close()
        // The next launch (a new presenter over the same storage) does not offer it either.
        val next = presenter(auth)
        advanceUntilIdle()
        assertFalse(next.state.value.showFirstRunLogin)
        next.close()
    }

    @Test
    fun skipMarksSeenAndHides() = runTest {
        val auth = ScriptedAuth()
        val p = presenter(auth)
        advanceUntilIdle()
        assertTrue(p.state.value.showFirstRunLogin)
        p.skipFirstRunLogin()
        advanceUntilIdle()
        assertFalse(p.state.value.showFirstRunLogin)
        assertEquals(1, auth.markCalls)
        p.close()
    }

    @Test
    fun signInTwiceWhileSigningInCallsOnce() = runTest {
        val auth = ScriptedAuth().also { it.signInGate = CompletableDeferred() }
        val p = presenter(auth)
        advanceUntilIdle()
        p.signIn(AuthProvider.APPLE)
        p.signIn(AuthProvider.GOOGLE)
        advanceUntilIdle()
        assertEquals(1, auth.signInCalls)
        assertEquals(AuthProvider.APPLE, p.state.value.signingIn)
        auth.signInGate!!.complete(ClientResult.Success(AuthAccount("a", "a@x", AuthProvider.APPLE)))
        advanceUntilIdle()
        assertNull(p.state.value.signingIn)
        assertNotNull(p.state.value.account)
        assertFalse(p.state.value.showFirstRunLogin)
        p.close()
    }

    @Test
    fun signInFailureSetsErrorAndClearsSigningIn() = runTest {
        val p = presenter(UnavailableAuthFacade())
        advanceUntilIdle()
        p.signIn(AuthProvider.APPLE)
        advanceUntilIdle()
        val state = p.state.value
        assertNull(state.signingIn)
        assertNull(state.account)
        assertEquals(ErrorKind.UNAVAILABLE, state.error?.kind)
        assertFalse(state.showFirstRunLogin)
        // A new attempt clears the old error while it runs.
        val retry = ScriptedAuth().also { it.signInGate = CompletableDeferred() }
        val q = presenter(retry)
        advanceUntilIdle()
        retry.signInGate!!.complete(ClientResult.Failure(ClientError(ErrorKind.NETWORK)))
        q.signIn(AuthProvider.GOOGLE)
        advanceUntilIdle()
        assertEquals(ErrorKind.NETWORK, q.state.value.error?.kind)
        retry.signInGate = CompletableDeferred()
        q.signIn(AuthProvider.GOOGLE)
        advanceUntilIdle()
        assertNull(q.state.value.error)
        assertEquals(AuthProvider.GOOGLE, q.state.value.signingIn)
        p.close()
        q.close()
    }

    @Test
    fun signOutReturnsToSignedOutWithoutFirstRun() = runTest {
        val auth = ScriptedAuth()
        val p = presenter(auth)
        advanceUntilIdle()
        p.signIn(AuthProvider.GOOGLE)
        advanceUntilIdle()
        assertNotNull(p.state.value.account)
        p.signOut()
        advanceUntilIdle()
        assertEquals(1, auth.signOutCalls)
        assertNull(p.state.value.account)
        assertFalse(p.state.value.showFirstRunLogin)
        p.close()
    }

    @Test
    fun closedPresenterIgnoresIntents() = runTest {
        val auth = ScriptedAuth()
        val p = presenter(auth)
        advanceUntilIdle()
        p.close()
        p.close()
        p.signIn(AuthProvider.APPLE)
        p.skipFirstRunLogin()
        p.signOut()
        advanceUntilIdle()
        assertEquals(0, auth.signInCalls)
        assertEquals(0, auth.markCalls)
        assertEquals(0, auth.signOutCalls)
    }
}
