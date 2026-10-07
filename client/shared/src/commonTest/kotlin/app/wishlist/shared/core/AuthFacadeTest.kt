package app.wishlist.shared.core

import app.wishlist.shared.data.fake.BoardSeeds
import app.wishlist.shared.data.fake.FakeAuthFacade
import app.wishlist.shared.data.fake.FakeStore
import app.wishlist.shared.data.local.StoreHarness
import app.wishlist.shared.data.local.UUID_A
import app.wishlist.shared.data.local.failureKind
import app.wishlist.shared.model.itemFixture
import app.wishlist.shared.data.local.submission
import app.wishlist.shared.data.local.withHarness
import app.wishlist.shared.di.UnavailableAuthFacade
import app.wishlist.shared.repository.LocalStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import app.wishlist.shared.data.fake.successValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private val authTime = Instant.parse("2026-10-07T00:00:00Z")

/** A store harness plus the Fake store and the fake facade, restored from [preset] app_state. */
internal class AuthHarness(val store: StoreHarness) {
    val session get() = store.session
    val clock = Clock { authTime }
    var ids = 0
    val idGenerator = IdGenerator { "00000000-0000-4000-8000-" + (++ids).toString().padStart(12, '0') }
    val fakeStore = FakeStore(session, clock, idGenerator)
    val signedInCalls = mutableListOf<Long>()
    val events = mutableListOf<String>()
    val recording = RecordingStore(store.store, session, events)
    var auth = newAuth()

    fun newAuth() = FakeAuthFacade(
        session = session,
        store = recording,
        seed = { fakeStore.seed(BoardSeeds.create(clock, idGenerator)) },
        onSignedIn = { signedInCalls += session.state.value.generation },
    )
}

internal fun runAuthTest(preset: Map<String, String> = emptyMap(), block: suspend (AuthHarness) -> Unit): TestResult =
    runTest {
        withHarness { store ->
            val h = AuthHarness(store)
            preset.forEach { (k, v) -> h.store.store.writeAppState(k, v).successValue() }
            h.auth.restore()
            h.auth.publishRestored()
            block(h)
        }
    }

/** Logs every login-state write and cache clear together with the session account at that moment. */
internal class RecordingStore(
    private val delegate: LocalStore,
    private val session: MutableAuthSession,
    private val events: MutableList<String>,
) : LocalStore by delegate {
    var failWrites = false
    var failClear = false

    override suspend fun writeAppState(key: String, value: String?): ClientResult<Unit> {
        if (key != "auth.account") return delegate.writeAppState(key, value)
        events += "write:${value ?: "null"}@${session.state.value.accountId}"
        return if (failWrites) ClientResult.Failure(ClientError(ErrorKind.UNAVAILABLE, "WRITE")) else delegate.writeAppState(key, value)
    }

    override suspend fun clearCurrentCache(): ClientResult<Unit> {
        events += "clear@${session.state.value.accountId}"
        return if (failClear) ClientResult.Failure(ClientError(ErrorKind.UNAVAILABLE, "CLEAR")) else delegate.clearCurrentCache()
    }
}

class AuthFacadeTest {
    @Test fun signInPersistsAccountChangesSessionAndSeedsThatNamespace() = runAuthTest { h ->
        val account = h.auth.signIn(AuthProvider.GOOGLE).successValue()
        assertEquals(AuthAccount("fake-google-0001", "user@example.com", AuthProvider.GOOGLE), account)
        assertEquals("fake-google-0001", h.session.state.value.accountId)
        assertTrue(h.fakeStore.categories().successValue().isNotEmpty())
        assertEquals("GOOGLE|fake-google-0001|user@example.com", h.store.store.readAppState("auth.account").successValue())
        assertEquals(account, h.auth.account.value)
        assertEquals(listOf(h.session.state.value.generation), h.signedInCalls)
    }

    @Test fun restoreOnStartPublishesRestoredAccount() =
        runAuthTest(preset = mapOf("auth.account" to "APPLE|fake-apple-0001|apple@example.com")) { h ->
            h.auth.restored.first { it }
            assertEquals(AuthProvider.APPLE, h.auth.account.value?.provider)
            assertEquals("fake-apple-0001", h.session.state.value.accountId)
            assertTrue(h.fakeStore.categories().successValue().isNotEmpty())
            assertTrue(h.signedInCalls.isEmpty())
        }

    @Test fun corruptStoredAccountRestoresAsSignedOut() = runAuthTest(preset = mapOf("auth.account" to "garbage")) { h ->
        h.auth.restored.first { it }
        assertNull(h.auth.account.value)
        assertNull(h.session.state.value.accountId)
        assertNull(h.store.store.readAppState("auth.account").successValue())
    }

    @Test fun startsSignedOutAndRestoredWhenNothingIsStored() = runAuthTest { h ->
        assertTrue(h.auth.restored.value)
        assertNull(h.auth.account.value)
        assertNull(h.session.state.value.accountId)
    }

    @Test fun signOutClearsCacheKeepsBoundPendingAndNullsSession() = runAuthTest { h ->
        h.auth.signIn(AuthProvider.GOOGLE).successValue()
        val fixture = itemFixture()
        h.store.store.upsertItem(h.session.state.value, fixture).successValue()
        h.store.store.saveSubmission(submission(id = UUID_A, binding = "fake-google-0001")).successValue()
        h.auth.signOut().successValue()
        assertNull(h.session.state.value.accountId)
        assertNull(h.auth.account.value)
        assertNull(h.store.store.readAppState("auth.account").successValue())
        h.auth.signIn(AuthProvider.GOOGLE).successValue()
        assertNull(h.store.store.cachedItem(h.session.state.value, fixture.id).successValue())
        assertEquals(listOf(UUID_A), h.store.store.pending().successValue().map { it.clientSubmissionId })
    }

    @Test fun reSignInSameAccountBumpsGeneration() = runAuthTest { h ->
        h.auth.signIn(AuthProvider.GOOGLE).successValue()
        h.auth.signOut().successValue()
        h.auth.signIn(AuthProvider.GOOGLE).successValue()
        assertEquals(3L, h.session.state.value.generation)
        assertEquals(listOf(1L, 3L), h.signedInCalls)
    }

    @Test fun firstRunFlagPersists() = runAuthTest { h ->
        assertFalse(h.auth.hasSeenFirstRunLogin())
        h.auth.markFirstRunLoginSeen()
        assertTrue(h.auth.hasSeenFirstRunLogin())
        h.store.reopen()
        assertTrue(FakeAuthFacade(h.session, h.store.store, seed = { ClientResult.Success(Unit) }).hasSeenFirstRunLogin())
    }

    @Test fun signInSeedFailureKeepsTheAccountSignedIn() = runAuthTest { h ->
        val auth = FakeAuthFacade(h.session, h.store.store, seed = {
            ClientResult.Failure(ClientError(ErrorKind.UNAVAILABLE, "SEED"))
        })
        val account = auth.signIn(AuthProvider.APPLE).successValue()
        assertEquals("fake-apple-0001", account.accountId)
        assertEquals(account, auth.account.value)
    }

    @Test fun restoreReportsASeedFailureButStaysSignedIn() =
        runAuthTest(preset = mapOf("auth.account" to "GOOGLE|fake-google-0001|user@example.com")) { h ->
            val auth = FakeAuthFacade(h.session, h.store.store, seed = {
                ClientResult.Failure(ClientError(ErrorKind.UNAVAILABLE, "SEED"))
            })
            assertEquals("SEED", auth.restore()?.code)
            assertFalse(auth.restored.value) // published by the runtime once ready
            auth.publishRestored()
            assertTrue(auth.restored.value)
            assertEquals("fake-google-0001", auth.account.value?.accountId)
        }

    @Test fun releaseAuthIsUnavailableAndSignedOut() = runTest {
        val auth = UnavailableAuthFacade()
        assertEquals(ErrorKind.UNAVAILABLE, auth.signIn(AuthProvider.GOOGLE).failureKind())
        assertTrue(auth.restored.value)
        assertNull(auth.account.value)
        // RELEASE has no real login yet: the first-run login guide stays suppressed.
        assertTrue(auth.hasSeenFirstRunLogin())
        auth.markFirstRunLoginSeen()
        assertTrue(auth.hasSeenFirstRunLogin())
    }

    @Test fun signInOrderIsSaveThenSessionChange() = runAuthTest { h ->
        h.auth.signIn(AuthProvider.GOOGLE).successValue()
        // The account is written while the session is still signed out.
        assertEquals(listOf("write:GOOGLE|fake-google-0001|user@example.com@null"), h.events)
    }

    @Test fun signOutOrderIsClearCacheThenDeleteSavedThenSessionNull() = runAuthTest { h ->
        h.auth.signIn(AuthProvider.GOOGLE).successValue()
        h.events.clear()
        h.auth.signOut().successValue()
        assertEquals(listOf("clear@fake-google-0001", "write:null@fake-google-0001"), h.events)
        assertNull(h.session.state.value.accountId)
    }

    @Test fun failedSaveKeepsTheSessionSignedOut() = runAuthTest { h ->
        h.recording.failWrites = true
        assertEquals(ErrorKind.UNAVAILABLE, h.auth.signIn(AuthProvider.GOOGLE).failureKind())
        assertNull(h.session.state.value.accountId)
        assertNull(h.auth.account.value)
    }

    @Test fun failedCacheClearKeepsTheAccountSignedIn() = runAuthTest { h ->
        h.auth.signIn(AuthProvider.GOOGLE).successValue()
        h.recording.failClear = true
        assertEquals(ErrorKind.UNAVAILABLE, h.auth.signOut().failureKind())
        assertEquals("fake-google-0001", h.session.state.value.accountId)
        assertEquals("fake-google-0001", h.auth.account.value?.accountId)
        assertEquals("GOOGLE|fake-google-0001|user@example.com", h.store.store.readAppState("auth.account").successValue())
    }

    @Test fun signInWhileSignedInRunsTheSignOutPathFirst() = runAuthTest { h ->
        h.auth.signIn(AuthProvider.GOOGLE).successValue()
        val fixture = itemFixture()
        h.store.store.upsertItem(h.session.state.value, fixture).successValue()
        h.events.clear()

        h.auth.signIn(AuthProvider.APPLE).successValue()
        assertEquals(
            listOf("clear@fake-google-0001", "write:null@fake-google-0001", "write:APPLE|fake-apple-0001|apple@example.com@null"),
            h.events,
        )
        assertEquals("fake-apple-0001", h.session.state.value.accountId)
        assertEquals("APPLE|fake-apple-0001|apple@example.com", h.store.store.readAppState("auth.account").successValue())
        h.auth.signOut().successValue()
        h.auth.signIn(AuthProvider.GOOGLE).successValue()
        assertNull(h.store.store.cachedItem(h.session.state.value, fixture.id).successValue())
    }

    @Test fun logoutDuringSignInWaitsAndEndsConsistentlySignedOut() = runTest {
        withHarness { store ->
            val h = AuthHarness(store)
            val gate = CompletableDeferred<Unit>()
            val auth = FakeAuthFacade(h.session, h.store.store, seed = { gate.await(); ClientResult.Success(Unit) })
            launch { auth.signIn(AuthProvider.GOOGLE) }
            runCurrent()
            launch { auth.signOut() }
            runCurrent()
            gate.complete(Unit)
            advanceUntilIdle()

            assertNull(h.session.state.value.accountId)
            assertNull(auth.account.value)
            assertNull(h.store.store.readAppState("auth.account").successValue())
        }
    }

    @Test fun overlappingSignInsEndOnOneAccount() = runTest {
        withHarness { store ->
            val h = AuthHarness(store)
            val gates = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
            var call = 0
            val auth = FakeAuthFacade(h.session, h.store.store, seed = { gates[call++].await(); ClientResult.Success(Unit) })
            launch { auth.signIn(AuthProvider.GOOGLE) }
            runCurrent()
            launch { auth.signIn(AuthProvider.APPLE) }
            runCurrent()
            gates.forEach { it.complete(Unit) }
            advanceUntilIdle()

            val account = auth.account.value
            assertEquals(account?.accountId, h.session.state.value.accountId)
            assertEquals(
                account?.let { "${it.provider}|${it.accountId}|${it.email}" },
                h.store.store.readAppState("auth.account").successValue(),
            )
        }
    }

    @Test fun seedExceptionOnSignInKeepsTheAccountSignedIn() = runAuthTest { h ->
        val auth = FakeAuthFacade(h.session, h.store.store, seed = { throw IllegalStateException("boom") })
        val account = auth.signIn(AuthProvider.GOOGLE).successValue()
        assertEquals(account, auth.account.value)
        assertEquals(account.accountId, h.session.state.value.accountId)
    }

    @Test fun seedExceptionOnRestoreReportsAndKeepsSessionAndAccountTogether() =
        runAuthTest(preset = mapOf("auth.account" to "GOOGLE|fake-google-0001|user@example.com")) { h ->
            val auth = FakeAuthFacade(h.session, h.store.store, seed = { throw IllegalStateException("boom") })
            assertEquals(ErrorKind.UNAVAILABLE, auth.restore()?.kind)
            assertEquals("fake-google-0001", h.session.state.value.accountId)
            assertEquals("fake-google-0001", auth.account.value?.accountId)
        }
}
