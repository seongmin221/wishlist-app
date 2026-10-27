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
                allBackends(Backend.UNAVAILABLE) + DEBUG_FAKE_APIS.associateWith { Backend.FAKE },
            )
        } else {
            // C2 release: all 37 APIs unavailable.
            RepositoryBindings(ClientBuildMode.RELEASE, allBackends(Backend.UNAVAILABLE))
        }

    private fun allBackends(backend: Backend): Map<ApiId, Backend> = ApiId.entries.associateWith { backend }
}

/** APIs the DEBUG app runs on the Fake backend: C2/C3 ITEM-01·03, C5 CAT-01~04/ITEM-02, C6 PUR-01~04, C7 HOME-01·02. */
internal val DEBUG_FAKE_APIS: Set<ApiId> = setOf(
    ApiId.ITEM_01, ApiId.ITEM_03, ApiId.ITEM_02, ApiId.CAT_01, ApiId.CAT_02, ApiId.CAT_03, ApiId.CAT_04,
    ApiId.PUR_01, ApiId.PUR_02, ApiId.PUR_03, ApiId.PUR_04, ApiId.HOME_01, ApiId.HOME_02,
)
