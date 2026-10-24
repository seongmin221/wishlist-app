package app.wishlist.android.feature.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.wishlist.shared.presentation.CategoryFormState
import app.wishlist.shared.presentation.FormMessage
import app.wishlist.shared.presentation.FormMode
import app.wishlist.shared.presentation.FormPhase
import app.wishlist.shared.presentation.Presenter

/**
 * When the form's input fields take the Presenter's values (pure, JVM-tested). The fields lead while
 * the user types (the Presenter lags them by a lane hop), so they are replaced only when the Presenter
 * replaced the form itself: the edit baseline arriving (EDIT, first READY of this opening) and a 409
 * reload ([FormMessage.CONFLICT_LOADED], once per reload). A create's SELECT going LOADING → READY
 * never touches the fields. One instance per sheet opening (the opening's Presenter is fresh, see
 * [CategoryFormSlot], so its first READY is this opening's baseline even if LOADING was not observed).
 */
internal class FormFieldSync {
    private var baselineApplied = false
    private var conflictShown = false

    fun shouldReplace(state: CategoryFormState): Boolean {
        val baseline = state.mode == FormMode.EDIT && state.phase == FormPhase.READY && !baselineApplied
        if (baseline) baselineApplied = true
        val conflict = state.message == FormMessage.CONFLICT_LOADED && !conflictShown
        conflictShown = state.message == FormMessage.CONFLICT_LOADED
        return baseline || conflict
    }
}

/**
 * The form Presenter of one screen, made fresh for every sheet opening ([fresh] closes the previous one),
 * so a sheet never starts from the last opening's state. Closed with the screen's entry store.
 */
class CategoryFormSlot<P : Presenter>(private val make: () -> P) : ViewModel() {
    var current: P? = null
        private set

    fun fresh(): P {
        current?.close()
        return make().also { current = it }
    }

    override fun onCleared() {
        current?.close()
        current = null
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun <P : Presenter> of(store: ViewModelStoreOwner, key: String, make: () -> P): CategoryFormSlot<P> =
            ViewModelProvider(store, viewModelFactory { initializer { CategoryFormSlot(make) } })[key, CategoryFormSlot::class.java]
                as CategoryFormSlot<P>
    }
}
