package app.wishlist.android.feature.session

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.wishlist.shared.core.AuthProvider
import app.wishlist.shared.di.SharedRuntime
import app.wishlist.shared.presentation.AccountPresenter
import app.wishlist.shared.presentation.AccountState
import kotlinx.coroutines.flow.StateFlow

/**
 * Lifetime owner of the app's one [AccountPresenter] (no UI), scoped to `MainActivity`: the
 * first-run login layer, the home and settings all read the same state. Closes the Presenter in
 * [onCleared].
 */
class AccountPresenterOwner(private val presenter: AccountPresenter) : ViewModel() {
    /** Thread-safe; the facade work runs on the runtime's background dispatcher. */
    val state: StateFlow<AccountState> get() = presenter.state

    fun signIn(provider: AuthProvider) = presenter.signIn(provider)

    fun skipFirstRunLogin() = presenter.skipFirstRunLogin()

    fun signOut() = presenter.signOut()

    override fun onCleared() {
        presenter.close()
    }

    companion object {
        fun factory(runtime: SharedRuntime): ViewModelProvider.Factory = viewModelFactory {
            initializer { AccountPresenterOwner(runtime.accountPresenter()) }
        }
    }
}

/** Provided by `WishlistApp`; there is no default (a screen outside the app root is a bug). */
val LocalAccountOwner = staticCompositionLocalOf<AccountPresenterOwner> { error("WishlistApp must provide LocalAccountOwner") }
