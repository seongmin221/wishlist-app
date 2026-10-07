package app.wishlist.android.di

import app.wishlist.android.BuildConfig
import app.wishlist.shared.core.ApiId
import app.wishlist.shared.di.Backend
import app.wishlist.shared.di.ClientBuildMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the debug app passes to the shared runtime. */
class AppRuntimeConfigDebugTest {
    @Test fun debug_variant_passes_debug_mode_with_c2_fake_apis_and_the_rest_unavailable() {
        assertTrue(BuildConfig.DEBUG)
        val bindings = AppRuntimeConfig.bindings(BuildConfig.DEBUG)
        assertEquals(ClientBuildMode.DEBUG, bindings.buildMode)
        assertEquals(ApiId.entries.toSet(), bindings.backends.keys)
        ApiId.entries.forEach { api ->
            val expected = if (api == ApiId.ITEM_01 || api == ApiId.ITEM_03) Backend.FAKE else Backend.UNAVAILABLE
            assertEquals(api.wireId, expected, bindings.backendOf(api))
        }
        assertNull(AppRuntimeConfig.remote)
    }
}
