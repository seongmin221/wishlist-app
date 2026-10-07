package app.wishlist.android.di

import android.content.Intent
import app.wishlist.shared.di.SharedRuntime
import kotlinx.coroutines.CoroutineScope

/** Debug variant: start the demo session once the runtime is assembled. */
internal object VariantStartup {
    fun onRuntimeAssembled(runtime: SharedRuntime) = DebugSessionBootstrap.start(runtime)

    /** Debug demo hooks from MainActivity's launch intent (see [DebugLaunchHooks]). */
    fun onMainLaunch(runtime: SharedRuntime, intent: Intent, scope: CoroutineScope) =
        DebugLaunchHooks.apply(runtime, intent, scope)
}
