package app.wishlist.android.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.wishlist.shared.di.SharedRuntime
import app.wishlist.shared.presentation.ItemDetailPresenter
import app.wishlist.shared.presentation.ItemDetailState
import kotlinx.coroutines.flow.StateFlow

/**
 * Lifetime owner of one [ItemDetailPresenter] (no UI). The ViewModel keeps the Presenter across
 * configuration changes and closes it in [onCleared], i.e. when the screen's ViewModelStore is
 * cleared for good. C4's detail screen collects [state] on the main thread and forwards intents.
 */
class ItemDetailPresenterOwner(private val presenter: ItemDetailPresenter) : ViewModel() {
    /** Thread-safe; repository work runs on the runtime's background dispatcher. */
    val state: StateFlow<ItemDetailState> get() = presenter.state

    fun load(id: String) = presenter.load(id)

    fun retry() = presenter.retry()

    /** Pull to refresh and the screen's later resumes; keeps the shown item. */
    fun refresh() = presenter.refresh()

    private var started = false

    /** The screen's first composition loads; a recomposition after a configuration change does not. */
    fun loadOnce(id: String) {
        if (started) return
        started = true
        load(id)
    }

    override fun onCleared() {
        presenter.close()
    }

    companion object {
        /** Each owner gets its own Presenter from the process's single runtime. */
        fun factory(runtime: SharedRuntime): ViewModelProvider.Factory = viewModelFactory {
            initializer { ItemDetailPresenterOwner(runtime.itemDetailPresenter()) }
        }
    }
}
