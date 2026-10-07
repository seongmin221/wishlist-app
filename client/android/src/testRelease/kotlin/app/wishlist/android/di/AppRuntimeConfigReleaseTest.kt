package app.wishlist.android.di

import app.wishlist.android.BuildConfig
import app.wishlist.shared.core.ApiId
import app.wishlist.shared.di.Backend
import app.wishlist.shared.di.ClientBuildMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** What the release app passes to the shared runtime. */
class AppRuntimeConfigReleaseTest {
    @Test fun release_variant_passes_release_mode_with_all_37_apis_unavailable() {
        assertFalse(BuildConfig.DEBUG)
        val bindings = AppRuntimeConfig.bindings(BuildConfig.DEBUG)
        assertEquals(ClientBuildMode.RELEASE, bindings.buildMode)
        assertEquals(37, bindings.backends.size)
        assertEquals(ApiId.entries.associateWith { Backend.UNAVAILABLE }, bindings.backends)
        assertNull(AppRuntimeConfig.remote)
    }
}
