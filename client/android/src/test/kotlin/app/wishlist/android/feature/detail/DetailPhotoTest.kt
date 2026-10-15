package app.wishlist.android.feature.detail

import app.wishlist.android.navigation.PhotoHandoff
import app.wishlist.shared.domain.DetailKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetailPhotoTest {
    @Test
    fun heldCardPhotoAndCacheKeyBecomeTheDetailRequestPlaceholder() {
        val start = detailPhotoStart(PhotoHandoff.Held("card", "card-key"))

        assertEquals("card", start.placeholder)
        assertEquals("card-key", start.placeholderMemoryCacheKey)
    }

    @Test
    fun withoutHandoffTheDetailLoadsPlainly() {
        val start = detailPhotoStart<String, String>(null)

        assertNull(start.placeholder)
        assertNull(start.placeholderMemoryCacheKey)
    }

    @Test
    fun theSharedBoxFollowsTheHeldPhotoFirstSoItDoesNotJumpMidFlight() {
        assertEquals(2f, detailPhotoRatio(held = PhotoPixels(100, 200), drawn = PhotoPixels(301, 600))!!, 0f)
        assertEquals(0.5f, detailPhotoRatio(held = null, drawn = PhotoPixels(600, 300))!!, 0f)
    }

    @Test
    fun noPhotoSizeMeansTheWholeFramePlaceholder() {
        assertNull(detailPhotoRatio(held = null, drawn = null))
        assertNull(detailPhotoRatio(held = PhotoPixels(0, 10), drawn = null))
    }

    /**
     * One photo slot from the first frame (item still loading) to the shown product, so the shared
     * element's target is the same node through the flight (a new node mid-flight does not animate).
     */
    @Test
    fun aPhotoEntryKeepsOneSharedSlotFromLoadingToTheProduct() {
        assertEquals(true, keepsSharedPhotoSlot(photoEntry = true, hasItem = false, hasError = false, kind = null))
        assertEquals(true, keepsSharedPhotoSlot(photoEntry = true, hasItem = true, hasError = false, kind = DetailKind.READY))
        assertEquals(true, keepsSharedPhotoSlot(photoEntry = true, hasItem = true, hasError = true, kind = DetailKind.INCOMPLETE))
        assertEquals(false, keepsSharedPhotoSlot(photoEntry = true, hasItem = false, hasError = true, kind = null))
        assertEquals(false, keepsSharedPhotoSlot(photoEntry = true, hasItem = true, hasError = false, kind = DetailKind.PROCESSING))
        assertEquals(false, keepsSharedPhotoSlot(photoEntry = true, hasItem = true, hasError = false, kind = DetailKind.GONE))
        assertEquals(false, keepsSharedPhotoSlot(photoEntry = false, hasItem = true, hasError = false, kind = DetailKind.READY))
    }
}
