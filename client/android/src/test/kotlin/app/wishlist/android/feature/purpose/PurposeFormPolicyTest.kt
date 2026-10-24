package app.wishlist.android.feature.purpose

import app.wishlist.android.designsystem.overlay.SheetDismiss
import app.wishlist.shared.presentation.*
import org.junit.Assert.*
import org.junit.Test

class PurposeFormPolicyTest {
    @Test fun submittingBlocksDismissEvenWhenDirty() {
        assertEquals(SheetDismiss.Block, purposeFormDismiss(PurposeFormState(submitting = true, dirty = true)))
        assertEquals(SheetDismiss.Confirm, purposeFormDismiss(PurposeFormState(dirty = true)))
        assertEquals(SheetDismiss.Allow, purposeFormDismiss(PurposeFormState()))
    }
    @Test fun fieldSyncReplacesBaselineAndConflictWithoutDroppingTyping() {
        val sync = PurposeFieldSync()
        assertFalse(sync.shouldReplace(PurposeFormState()))
        assertFalse(sync.shouldReplace(PurposeFormState(name = "presenter lags typing", dirty = true)))
        assertTrue(sync.shouldReplace(PurposeFormState(mode = FormMode.EDIT)))
        assertFalse(sync.shouldReplace(PurposeFormState(mode = FormMode.EDIT, dirty = true)))
        assertTrue(sync.shouldReplace(PurposeFormState(message = FormMessage.CONFLICT_LOADED)))
        assertFalse(sync.shouldReplace(PurposeFormState(message = FormMessage.CONFLICT_LOADED)))
        assertFalse(sync.shouldReplace(PurposeFormState()))
        assertTrue(sync.shouldReplace(PurposeFormState(message = FormMessage.CONFLICT_LOADED)))
    }
}
