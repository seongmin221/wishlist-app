package app.wishlist.android.feature.detail

import app.wishlist.android.navigation.PhotoHandoff
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
}
