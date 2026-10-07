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
    var auth = newAuth()

    fun newAuth() = FakeAuthFacade(
        session = session,
        store = store.store,
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
            block(h)
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
        assertTrue(h.newAuth().hasSeenFirstRunLogin())
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
            assertTrue(auth.restored.value)
            assertEquals("fake-google-0001", auth.account.value?.accountId)
        }

    @Test fun releaseAuthIsUnavailableAndSignedOut() = runTest {
        val auth = UnavailableAuthFacade()
        assertEquals(ErrorKind.UNAVAILABLE, auth.signIn(AuthProvider.GOOGLE).failureKind())
        assertTrue(auth.restored.value)
        assertNull(auth.account.value)
    }
}
