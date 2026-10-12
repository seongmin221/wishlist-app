package app.wishlist.android.feature.detail

import org.junit.Test
import org.junit.Assert.assertEquals

class ProductPhotoTest {
    @Test fun nullAndBlankArePlaceholder() {
        assertEquals(PhotoSource.Placeholder, photoSourceFor(null))
        assertEquals(PhotoSource.Placeholder, photoSourceFor("  "))
    }

    @Test fun nonUrlsArePlaceholder() {
        assertEquals(PhotoSource.Placeholder, photoSourceFor("not a url"))
        assertEquals(PhotoSource.Placeholder, photoSourceFor("https://"))
        assertEquals(PhotoSource.Placeholder, photoSourceFor("ftp://example.com/a.png"))
        assertEquals(PhotoSource.Placeholder, photoSourceFor("file:///sdcard/a.png"))
    }

    @Test fun httpAndHttpsWithHostAreRemote() {
        assertEquals(PhotoSource.Remote("https://img.example.com/a.png"), photoSourceFor("https://img.example.com/a.png"))
        assertEquals(PhotoSource.Remote("http://example.com/a.png"), photoSourceFor(" http://example.com/a.png "))
    }
}
