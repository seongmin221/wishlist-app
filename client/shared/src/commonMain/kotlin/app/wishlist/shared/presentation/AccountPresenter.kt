package app.wishlist.shared.presentation

import app.wishlist.shared.core.AuthAccount
import app.wishlist.shared.core.AuthFacade
import app.wishlist.shared.core.AuthProvider
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Login state of the app. [showFirstRunLogin] is true only after the restore finished, while
 * signed out and the first-run login was neither skipped nor used.
 */
data class AccountState(
    val restored: Boolean,
    val account: AuthAccount?,
    val showFirstRunLogin: Boolean,
    val signingIn: AuthProvider?,
    val error: ClientError?,
)

/**
 * Sign-in/out intents over [AuthFacade]. Intents return at once; handling is serialized on a
 * single-lane view of [dispatcher], so a second sign-in tap during a running one is ignored.
 * [close] cancels everything, is idempotent, and later intents are ignored.
 */
class AccountPresenter internal constructor(
    private val auth: AuthFacade,
    dispatcher: CoroutineDispatcher,
) : Presenter {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher.limitedParallelism(1))

    // null until read from the facade (after restore); true once skipped, signed in, or stored.
    private val seen = MutableStateFlow<Boolean?>(null)
    private val signingIn = MutableStateFlow<AuthProvider?>(null)
    private val error = MutableStateFlow<ClientError?>(null)
    private val mutableState = MutableStateFlow(
        AccountState(auth.restored.value, auth.account.value, showFirstRunLogin = false, signingIn = null, error = null),
    )

    /** Read-only and thread-safe; platform owners collect it and render on the main thread. */
    val state: StateFlow<AccountState> = mutableState.asStateFlow()

    init {
        scope.launch {
            combine(auth.restored, auth.account, seen, signingIn, error) { restored, account, seenNow, busy, err ->
                AccountState(
                    restored = restored,
                    account = account,
                    showFirstRunLogin = restored && account == null && seenNow == false,
                    signingIn = busy,
                    error = err,
                )
            }.collect { mutableState.value = it }
        }
        scope.launch {
            // The saved flag is readable only once the runtime restored the login.
            auth.restored.first { it }
            val stored = auth.hasSeenFirstRunLogin()
            seen.update { (it == true) || stored }
        }
        scope.launch {
            // Having signed in once (also restored) ends the first-run offer for good.
            auth.account.collect { account ->
                if (account != null && seen.value != true) {
                    seen.value = true
                    auth.markFirstRunLoginSeen()
                }
            }
        }
    }

    fun signIn(provider: AuthProvider) {
        scope.launch {
            if (signingIn.value != null) return@launch
            signingIn.value = provider
            error.value = null
            val result = auth.signIn(provider)
            if (result is ClientResult.Failure) error.value = result.error
            signingIn.value = null
        }
    }

    /** "나중에": the first-run login is not offered again. */
    fun skipFirstRunLogin() {
        scope.launch {
            seen.value = true
            auth.markFirstRunLoginSeen()
        }
    }

    fun signOut() {
        scope.launch {
            error.value = null
            val result = auth.signOut()
            if (result is ClientResult.Failure) error.value = result.error
        }
    }

    override fun close() {
        scope.cancel()
    }
}
