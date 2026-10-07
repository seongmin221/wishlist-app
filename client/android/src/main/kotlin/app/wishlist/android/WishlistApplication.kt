package app.wishlist.android

import android.app.Application
import app.wishlist.android.di.AppRuntimeConfig
import app.wishlist.android.di.VariantStartup
import app.wishlist.shared.di.SharedRuntime
import app.wishlist.shared.di.SharedRuntimeFactory

/** Owns the process's single shared runtime (isolated graph, one auth session). */
class WishlistApplication : Application() {
    lateinit var runtime: SharedRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = SharedRuntimeFactory.create(this, AppRuntimeConfig.bindings(BuildConfig.DEBUG), AppRuntimeConfig.remote)
        VariantStartup.onRuntimeAssembled(runtime)
    }
}
