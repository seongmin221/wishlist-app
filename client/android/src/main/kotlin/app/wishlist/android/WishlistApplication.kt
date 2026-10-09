package app.wishlist.android

import android.app.Application
import app.wishlist.android.di.AppRuntimeConfig
import app.wishlist.android.di.VariantStartup
import app.wishlist.android.platform.ForegroundSignals
import app.wishlist.android.platform.NetworkSignals
import app.wishlist.shared.di.SharedRuntime
import app.wishlist.shared.di.SharedRuntimeFactory
import app.wishlist.shared.submission.FlushTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Owns the process's single shared runtime (isolated graph, one auth session) and the app-wide
 * send signals: the first 0 → 1 started-activity transition (LAUNCH), later ones (FOREGROUND) and
 * network restore. The shared coordinator coalesces them into one single-flight flush.
 */
class WishlistApplication : Application() {
    lateinit var runtime: SharedRuntime
        private set

    /**
     * Process-lifetime scope (main). Work that must outlive a screen runs here — e.g. a share
     * that is still being saved when its card Activity has already gone.
     */
    val appScope: CoroutineScope = MainScope()

    override fun onCreate() {
        super.onCreate()
        runtime = SharedRuntimeFactory.create(this, AppRuntimeConfig.bindings(BuildConfig.DEBUG), AppRuntimeConfig.remote)
        VariantStartup.onRuntimeAssembled(runtime)
        val submissions = runtime.submissions()
        // No separate launch refresh: the first foreground of the app is the LAUNCH signal.
        registerActivityLifecycleCallbacks(
            ForegroundSignals { trigger -> appScope.launch { submissions.refresh(trigger) } },
        )
        NetworkSignals(this) { submissions.requestFlush(FlushTrigger.NETWORK_RESTORED) }.start()
    }
}
