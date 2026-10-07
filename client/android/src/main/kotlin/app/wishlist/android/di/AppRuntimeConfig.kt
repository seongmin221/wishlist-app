package app.wishlist.android.di

import app.wishlist.shared.core.ApiId
import app.wishlist.shared.di.Backend
import app.wishlist.shared.di.ClientBuildMode
import app.wishlist.shared.di.RemoteConfig
import app.wishlist.shared.di.RepositoryBindings

/**
 * The app's explicit backend choice per API. `BuildConfig.DEBUG` selects the build mode and its
 * complete 37-API map; the shared runtime never infers a backend from the mode.
 */
internal object AppRuntimeConfig {
    /** C2 has no auth provider or server connection: no API is REMOTE, so there is no remote config. */
    val remote: RemoteConfig? = null

    fun bindings(debug: Boolean): RepositoryBindings =
        if (debug) {
            // C2 Fake wire APIs only; every other API is explicitly unavailable.
            RepositoryBindings(
                ClientBuildMode.DEBUG,
                allBackends(Backend.UNAVAILABLE) + mapOf(ApiId.ITEM_01 to Backend.FAKE, ApiId.ITEM_03 to Backend.FAKE),
            )
        } else {
            // C2 release: all 37 APIs unavailable.
            RepositoryBindings(ClientBuildMode.RELEASE, allBackends(Backend.UNAVAILABLE))
        }

    private fun allBackends(backend: Backend): Map<ApiId, Backend> = ApiId.entries.associateWith { backend }
}
