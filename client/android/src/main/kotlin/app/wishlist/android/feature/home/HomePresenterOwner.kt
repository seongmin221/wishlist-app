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
 * never leaves the stack). The app-wide foreground signal (`platform/ForegroundSignals`) still runs the
 * send refresh; while home is shown the screen also calls [foreground] (C7 Ruling 4: the coordinator
 * single-flights the overlap).
 */
class HomePresenterOwner(private val presenter: HomePresenter) : ViewModel() {
    val state: StateFlow<HomeState> get() = presenter.state

    /** Recomputes relative times; `HomeScreen` calls it every minute while it is shown. */
    fun tick() = presenter.tick()

    /** Pull to refresh. */
    fun refresh() = presenter.refresh()

    /** The home screen became visible (entry, tab switch, a covered route popped): HOME-01. */
    fun appeared() = presenter.appeared()

    /** The app came back while home is shown: send refresh, then HOME-01. */
    fun foreground() = presenter.foreground()

    /** The first HOME-01 failed: read it again. */
    fun retry() = presenter.retry()

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
