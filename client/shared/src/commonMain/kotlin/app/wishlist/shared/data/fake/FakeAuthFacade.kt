package app.wishlist.shared.data.fake

import app.wishlist.shared.core.AuthAccount
import app.wishlist.shared.core.AuthFacade
import app.wishlist.shared.core.AuthProvider
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.di.BOOTSTRAP_FAILURE
import app.wishlist.shared.core.MutableAuthSession
import app.wishlist.shared.repository.LocalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val AUTH_ACCOUNT_KEY = "auth.account"
internal const val FIRST_RUN_LOGIN_SEEN_KEY = "onboarding.login.seen"

/**
 * Fake login without any platform SDK. The signed-in account is kept in app_state as
 * `<provider>|<accountId>|<email>`. Order on sign-in: save account -> change session -> seed that
 * account's namespace; on sign-out: clear current cache -> delete saved account -> session null.
 * Seed failures/exceptions never fail a sign-in (the seed is demo data); [restore] reports them instead.
 * Signing in while signed in first runs the sign-out path; the three operations are serialized.
 */
internal class FakeAuthFacade(
    private val session: MutableAuthSession,
    private val store: LocalStore,
    private val seed: suspend () -> ClientResult<Unit>,
    /** Called after every successful sign-in, once the account's namespace is ready. */
    private val onSignedIn: () -> Unit = {},
) : AuthFacade {
    // Serializes restore/signIn/signOut. Taken before any store or session gate, never inside one.
    private val lock = Mutex()
    private val mutableAccount = MutableStateFlow<AuthAccount?>(null)
    private val mutableRestored = MutableStateFlow(false)
    override val account: StateFlow<AuthAccount?> = mutableAccount.asStateFlow()
    override val restored: StateFlow<Boolean> = mutableRestored.asStateFlow()

    /**
     * Restores the saved account (corrupt data is deleted and means signed out), then seeds its
     * namespace. Returns why the restore was not clean, if so; the account stays signed in when
     * only the seed failed. [restored] is published separately by [publishRestored].
     */
    suspend fun restore(): ClientError? = lock.withLock {
        val stored = when (val read = store.readAppState(AUTH_ACCOUNT_KEY)) {
            is ClientResult.Failure -> return@withLock read.error
            is ClientResult.Success -> read.value
        } ?: return@withLock null
        val parsed = parse(stored)
        if (parsed == null) {
            return@withLock (store.writeAppState(AUTH_ACCOUNT_KEY, null) as? ClientResult.Failure)?.error
        }
        session.changeAccount(parsed.accountId)
        val failure = seedSafely()
        mutableAccount.value = parsed
        failure
    }

    /** The runtime calls this once ready is published, so a collector of [restored] is never refused. */
    fun publishRestored() {
        mutableRestored.value = true
    }

    override suspend fun signIn(provider: AuthProvider): ClientResult<AuthAccount> {
        val result = lock.withLock {
            if (mutableAccount.value != null) {
                // Switching accounts first runs the logout cleanup of the current one.
                (signOutLocked() as? ClientResult.Failure)?.let { return@withLock ClientResult.Failure(it.error) }
            }
            val account = fakeAccount(provider)
            val saved = store.writeAppState(AUTH_ACCOUNT_KEY, format(account))
            if (saved is ClientResult.Failure) return@withLock ClientResult.Failure(saved.error)
            session.changeAccount(account.accountId)
            seedSafely()
            mutableAccount.value = account
            ClientResult.Success(account)
        }
        if (result is ClientResult.Success) onSignedIn()
        return result
    }

    override suspend fun signOut(): ClientResult<Unit> = lock.withLock { signOutLocked() }

    private suspend fun signOutLocked(): ClientResult<Unit> {
        (store.clearCurrentCache() as? ClientResult.Failure)?.let { return it }
        (store.writeAppState(AUTH_ACCOUNT_KEY, null) as? ClientResult.Failure)?.let { return it }
        session.changeAccount(null)
        mutableAccount.value = null
        return ClientResult.Success(Unit)
    }

    /** The seed is demo data: a failure or exception is reported, never allowed to half-apply a login. */
    private suspend fun seedSafely(): ClientError? = try {
        (seed() as? ClientResult.Failure)?.error
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ClientError(ErrorKind.UNAVAILABLE, BOOTSTRAP_FAILURE)
    }

    override suspend fun hasSeenFirstRunLogin(): Boolean =
        (store.readAppState(FIRST_RUN_LOGIN_SEEN_KEY) as? ClientResult.Success)?.value == "1"

    override suspend fun markFirstRunLoginSeen() {
        store.writeAppState(FIRST_RUN_LOGIN_SEEN_KEY, "1")
    }

    private fun fakeAccount(provider: AuthProvider) = when (provider) {
        AuthProvider.GOOGLE -> AuthAccount("fake-google-0001", "user@example.com", AuthProvider.GOOGLE)
        AuthProvider.APPLE -> AuthAccount("fake-apple-0001", "apple@example.com", AuthProvider.APPLE)
    }

    private fun format(account: AuthAccount) = "${account.provider.name}|${account.accountId}|${account.email}"

    private fun parse(stored: String): AuthAccount? {
        val parts = stored.split('|', limit = 3)
        if (parts.size != 3 || parts[1].isBlank() || parts[2].isBlank()) return null
        val provider = AuthProvider.entries.firstOrNull { it.name == parts[0] } ?: return null
        return AuthAccount(parts[1], parts[2], provider)
    }
}
