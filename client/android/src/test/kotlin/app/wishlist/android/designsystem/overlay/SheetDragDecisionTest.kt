package app.wishlist.android.designsystem.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetDragDecisionTest {
    @Test fun dismissesAtQuarterOfHeight() = assertTrue(shouldDismissSheet(100f, 400f, 0f))
    @Test fun keepsBelowThresholds() = assertFalse(shouldDismissSheet(99f, 400f, 999f))
    @Test fun dismissesOnFastFling() = assertTrue(shouldDismissSheet(10f, 400f, 1000f))
}
