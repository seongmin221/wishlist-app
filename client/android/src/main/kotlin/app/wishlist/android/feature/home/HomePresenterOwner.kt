package app.wishlist.android.feature.home

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.wishlist.shared.di.SharedRuntime
import app.wishlist.shared.presentation.HomePresenter
import app.wishlist.shared.presentation.HomeState
import kotlinx.coroutines.flow.StateFlow

/**
 * Lifetime owner of the home tab's [HomePresenter], scoped to `MainActivity` (the home tab root
 * never leaves the stack). Foreground refresh is app-wide (`platform/ForegroundSignals`), not here.
 */
class HomePresenterOwner(private val presenter: HomePresenter) : ViewModel() {
    val state: StateFlow<HomeState> get() = presenter.state

    /** Recomputes relative times; `HomeScreen` calls it every minute while it is shown. */
    fun tick() = presenter.tick()

    /** Pull to refresh. */
    fun refresh() = presenter.refresh()

    override fun onCleared() {
        presenter.close()
    }

    companion object {
        fun factory(runtime: SharedRuntime): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomePresenterOwner(runtime.homePresenter()) }
        }
    }
}

val LocalHomeOwner = staticCompositionLocalOf<HomePresenterOwner> { error("WishlistApp must provide LocalHomeOwner") }
