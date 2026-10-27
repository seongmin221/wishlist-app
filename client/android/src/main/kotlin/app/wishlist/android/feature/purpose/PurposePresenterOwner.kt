package app.wishlist.android.feature.purpose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.wishlist.shared.presentation.Presenter

/** Entry-owned shared presenter, retained on rotation and closed when the account route leaves. */
internal class PurposePresenterOwner<P : Presenter>(val presenter: P) : ViewModel() {
    private var started = false

    /** The last list anchor restore token the screen applied (C7; survives the screen leaving composition). */
    var appliedRestore = 0L

    /** The list's first visible card while it was last shown (C7: the anchor of the refresh when the screen comes back). */
    var lastAnchor: app.wishlist.android.feature.category.ListAnchor.Position? = null
    private var seenRevision = PurposeChanges.revision.value
    fun once(start: (P) -> Unit) {
        if (started) return
        started = true
        start(presenter)
    }
    /** Composition entry fetches again after a tab switch or a covered route returns. */
    fun enter(load: (P) -> Unit, refresh: (P) -> Unit) {
        if (started) refresh(presenter) else once(load)
    }
    fun markSeen(revision: Int): Boolean {
        if (revision == seenRevision) return false
        seenRevision = revision
        return true
    }
    override fun onCleared() { presenter.close() }
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun <P : Presenter> of(store: ViewModelStoreOwner, key: String, make: () -> P): PurposePresenterOwner<P> =
            ViewModelProvider(store, viewModelFactory { initializer { PurposePresenterOwner(make()) } })[key, PurposePresenterOwner::class.java] as PurposePresenterOwner<P>
    }
}
