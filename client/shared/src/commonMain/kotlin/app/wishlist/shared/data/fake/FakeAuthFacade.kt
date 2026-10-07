package app.wishlist.shared.data.fake

import app.wishlist.shared.core.AuthAccount
import app.wishlist.shared.core.AuthFacade
import app.wishlist.shared.core.AuthProvider
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.MutableAuthSession
import app.wishlist.shared.repository.LocalStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val AUTH_ACCOUNT_KEY = "auth.account"
internal const val FIRST_RUN_LOGIN_SEEN_KEY = "onboarding.login.seen"

/**
 * Fake login without any platform SDK. The signed-in account is kept in app_state as
 * `<provider>|<accountId>|<email>`. Order on sign-in: save account -> change session -> seed that
 * account's namespace; on sign-out: clear current cache -> delete saved account -> session null.
 * Seed failures never fail a sign-in (the seed is demo data); [restore] reports them instead.
 */
internal class FakeAuthFacade(
    private val session: MutableAuthSession,
    private val store: LocalStore,
    private val seed: suspend () -> ClientResult<Unit>,
    /** Called after every successful sign-in, once the account's namespace is ready. */
    private val onSignedIn: () -> Unit = {},
) : AuthFacade {
    private val mutableAccount = MutableStateFlow<AuthAccount?>(null)
    private val mutableRestored = MutableStateFlow(false)
    override val account: StateFlow<AuthAccount?> = mutableAccount.asStateFlow()
    override val restored: StateFlow<Boolean> = mutableRestored.asStateFlow()

    /**
     * Restores the saved account (corrupt data is deleted and means signed out), then seeds its
     * namespace. Always ends with restored = true; returns why the restore was not clean, if so.
     */
    suspend fun restore(): ClientError? {
        try {
            val stored = when (val read = store.readAppState(AUTH_ACCOUNT_KEY)) {
                is ClientResult.Failure -> return read.error
                is ClientResult.Success -> read.value
            }
            if (stored == null) return null
            val parsed = parse(stored)
            if (parsed == null) {
                (store.writeAppState(AUTH_ACCOUNT_KEY, null) as? ClientResult.Failure)?.let { return it.error }
                return null
            }
            session.changeAccount(parsed.accountId)
            val failure = (seed() as? ClientResult.Failure)?.error
            mutableAccount.value = parsed
            return failure
        } finally {
            mutableRestored.value = true
        }
    }

    override suspend fun signIn(provider: AuthProvider): ClientResult<AuthAccount> {
        val account = fakeAccount(provider)
        val saved = store.writeAppState(AUTH_ACCOUNT_KEY, format(account))
        if (saved is ClientResult.Failure) return ClientResult.Failure(saved.error)
        session.changeAccount(account.accountId)
        seed()
        mutableAccount.value = account
        onSignedIn()
        return ClientResult.Success(account)
    }

    override suspend fun signOut(): ClientResult<Unit> {
        (store.clearCurrentCache() as? ClientResult.Failure)?.let { return it }
        (store.writeAppState(AUTH_ACCOUNT_KEY, null) as? ClientResult.Failure)?.let { return it }
        session.changeAccount(null)
        mutableAccount.value = null
        return ClientResult.Success(Unit)
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
