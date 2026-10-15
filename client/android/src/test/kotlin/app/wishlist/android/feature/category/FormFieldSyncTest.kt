package app.wishlist.android.feature.category

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import app.wishlist.shared.presentation.CategoryFormState
import app.wishlist.shared.presentation.FormMessage
import app.wishlist.shared.presentation.FormMode
import app.wishlist.shared.presentation.FormPhase
import app.wishlist.shared.presentation.Presenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FormFieldSyncTest {
    private val initial = CategoryFormState.Initial

    @Test fun createSelectLoadingToReadyNeverReplacesTyping() {
        val sync = FormFieldSync()
        assertFalse(sync.shouldReplace(initial.copy(mode = FormMode.CREATE, phase = FormPhase.LOADING)))
        assertFalse(sync.shouldReplace(initial.copy(mode = FormMode.CREATE, phase = FormPhase.READY, name = "")))
        assertFalse(sync.shouldReplace(initial.copy(mode = FormMode.CREATE, phase = FormPhase.READY, name = "책")))
    }

    @Test fun editBaselineReplacesOnce() {
        val sync = FormFieldSync()
        assertFalse(sync.shouldReplace(initial.copy(mode = FormMode.EDIT, phase = FormPhase.LOADING)))
        assertTrue(sync.shouldReplace(initial.copy(mode = FormMode.EDIT, phase = FormPhase.READY, name = "오디오")))
        // Later states (the user's typing coming back from the Presenter) leave the fields alone.
        assertFalse(sync.shouldReplace(initial.copy(mode = FormMode.EDIT, phase = FormPhase.READY, name = "오디")))
        assertFalse(sync.shouldReplace(initial.copy(mode = FormMode.EDIT, phase = FormPhase.READY, name = "오디오x", dirty = true)))
    }

    @Test fun editBaselineSeenWithoutTheLoadingStateStillReplaces() {
        // StateFlow may skip LOADING; the opening's Presenter is fresh, so its first READY is this baseline.
        assertTrue(FormFieldSync().shouldReplace(initial.copy(mode = FormMode.EDIT, phase = FormPhase.READY, name = "오디오")))
    }

    @Test fun eachConflictReloadReplacesOnce() {
        val sync = FormFieldSync()
        sync.shouldReplace(initial.copy(mode = FormMode.EDIT, phase = FormPhase.READY))
        val reloaded = initial.copy(mode = FormMode.EDIT, phase = FormPhase.READY, name = "다른 기기", message = FormMessage.CONFLICT_LOADED)
        assertTrue(sync.shouldReplace(reloaded))
        assertFalse(sync.shouldReplace(reloaded.copy(name = "다른 기기 x", dirty = true)))
        assertFalse(sync.shouldReplace(reloaded.copy(message = null)))
        assertTrue(sync.shouldReplace(reloaded.copy(name = "또 다른"))) // a second 409 reload
    }

    private class FakePresenter : Presenter {
        var closed = 0
        override fun close() { closed++ }
    }

    @Test fun eachOpeningGetsAFreshPresenterAndTheOldOneCloses() {
        val store = ViewModelStore()
        val entry = object : ViewModelStoreOwner { override val viewModelStore = store }
        val slot = CategoryFormSlot.of(entry, "form") { FakePresenter() }
        val first = slot.fresh()
        val second = slot.fresh()
        assertNotSame(first, second)
        assertEquals(1, first.closed)
        store.clear()
        assertEquals(1, second.closed)
    }
}
