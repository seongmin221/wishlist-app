package app.wishlist.shared.di

import app.wishlist.shared.core.AuthAccount
import app.wishlist.shared.core.AuthFacade
import app.wishlist.shared.core.AuthProvider
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.ErrorKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * RELEASE (no real login yet) and a closed runtime: always signed out, sign-in is UNAVAILABLE.
 * The first-run login guide counts as seen, so it stays suppressed until real auth exists.
 */
internal class UnavailableAuthFacade : AuthFacade {
    override val account: StateFlow<AuthAccount?> = MutableStateFlow(null)
    override val restored: StateFlow<Boolean> = MutableStateFlow(true)

    override suspend fun signIn(provider: AuthProvider): ClientResult<AuthAccount> = unavailable(AUTH_UNAVAILABLE)
    override suspend fun signOut(): ClientResult<Unit> = unavailable(AUTH_UNAVAILABLE)
    override suspend fun hasSeenFirstRunLogin(): Boolean = true
    override suspend fun markFirstRunLoginSeen() {}
}

internal const val AUTH_UNAVAILABLE = "AUTH_UNAVAILABLE"

/** Debug facade gate: sign-in/out before the bootstrap finished is refused like every other facade. */
internal class GatedAuthFacade(
    private val ready: StateFlow<Boolean>,
    internal val delegate: AuthFacade,
) : AuthFacade {
    override val account get() = delegate.account
    override val restored get() = delegate.restored
    override suspend fun signIn(provider: AuthProvider) =
        if (ready.value) delegate.signIn(provider) else unavailable(RUNTIME_NOT_READY)
    override suspend fun signOut() =
        if (ready.value) delegate.signOut() else unavailable(RUNTIME_NOT_READY)
    override suspend fun hasSeenFirstRunLogin() = ready.value && delegate.hasSeenFirstRunLogin()
    override suspend fun markFirstRunLoginSeen() {
        if (ready.value) delegate.markFirstRunLoginSeen()
    }
}
