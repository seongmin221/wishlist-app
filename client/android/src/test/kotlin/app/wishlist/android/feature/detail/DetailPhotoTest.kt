package app.wishlist.android.feature.detail

import app.wishlist.shared.domain.DetailKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetailPhotoTest {
    /**
     * Under the detail-size photo: the held card photo until that photo is drawn — also when it fails to
     * load (not an empty frame) — else the placeholder. The detail draws it itself rather than through the
     * request's `placeholder`, which would keep the card bitmap for as long as the detail is shown.
     */
    @Test
    fun underTheDetailPhotoIsTheHeldPhotoUntilDrawnElseThePlaceholder() {
        assertEquals(DetailPhotoUnder.Held, detailPhotoUnder(hasHeld = true, drawn = false))
        assertEquals(DetailPhotoUnder.Placeholder, detailPhotoUnder(hasHeld = false, drawn = false))
        assertEquals(DetailPhotoUnder.Nothing, detailPhotoUnder(hasHeld = true, drawn = true))
        assertEquals(DetailPhotoUnder.Nothing, detailPhotoUnder(hasHeld = false, drawn = true))
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
