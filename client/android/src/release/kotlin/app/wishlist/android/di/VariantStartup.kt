package app.wishlist.android.di

import android.content.Intent
import app.wishlist.shared.di.SharedRuntime
import kotlinx.coroutines.CoroutineScope

/** Release variant: the runtime is ready right after assembly; there is no debug bootstrap or demo hook. */
internal object VariantStartup {
    @Suppress("UNUSED_PARAMETER")
    fun onRuntimeAssembled(runtime: SharedRuntime) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun onMainLaunch(runtime: SharedRuntime, intent: Intent, scope: CoroutineScope) = Unit
}
