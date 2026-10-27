package app.wishlist.android.feature.flow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.wishlist.shared.model.HomeActionGroup
import app.wishlist.shared.presentation.HomeFlowPresenter

/**
 * Entry-scoped owner of one flow screen's [HomeFlowPresenter] (C4 칸별 ViewModelStore). A session is
 * this owner's lifetime: rotation and the screen coming back keep the passes, a pop or an account change
 * clears the store and closes the Presenter.
 */
internal class HomeFlowOwner(val presenter: HomeFlowPresenter) : ViewModel() {
    private var started = false

    /** Starts the flow on the screen's first composition only (not again after rotation or a covered route). */
    fun startOnce(group: HomeActionGroup, startAnchor: String?) {
        if (started) return
        started = true
        presenter.start(group, startAnchor)
    }

    override fun onCleared() {
        presenter.close()
    }

    companion object {
        fun of(store: ViewModelStoreOwner, make: () -> HomeFlowPresenter): HomeFlowOwner =
            ViewModelProvider(store, viewModelFactory { initializer { HomeFlowOwner(make()) } })["home.flow", HomeFlowOwner::class.java]
    }
}
