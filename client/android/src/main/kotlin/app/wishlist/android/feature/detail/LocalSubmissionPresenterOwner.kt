package app.wishlist.android.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.wishlist.shared.di.SharedRuntime
import app.wishlist.shared.presentation.LocalDetailState
import app.wishlist.shared.presentation.LocalSubmissionDetailPresenter
import kotlinx.coroutines.flow.StateFlow

/**
 * Lifetime owner of one [LocalSubmissionDetailPresenter] (no UI), kept in the screen's entry
 * ViewModelStore: it survives configuration changes and closes the Presenter in [onCleared] (pop,
 * `replaceTop` to the item detail, or leaving the account).
 */
class LocalSubmissionPresenterOwner(private val presenter: LocalSubmissionDetailPresenter) : ViewModel() {
    val state: StateFlow<LocalDetailState> get() = presenter.state

    private var started = false

    fun load(submissionId: String) = presenter.load(submissionId)

    /** The screen's first composition loads; a recomposition after a configuration change does not. */
    fun loadOnce(submissionId: String) {
        if (started) return
        started = true
        load(submissionId)
    }

    fun delete() = presenter.delete()

    /** Recomputes the relative saved time; the screen calls it every minute while shown. */
    fun tick() = presenter.tick()

    override fun onCleared() {
        presenter.close()
    }

    companion object {
        fun factory(runtime: SharedRuntime): ViewModelProvider.Factory = viewModelFactory {
            initializer { LocalSubmissionPresenterOwner(runtime.localSubmissionDetailPresenter()) }
        }
    }
}
