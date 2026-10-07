package app.wishlist.android.di

import app.wishlist.shared.di.SharedRuntime

/** Debug variant: start the demo session once the runtime is assembled. */
internal object VariantStartup {
    fun onRuntimeAssembled(runtime: SharedRuntime) = DebugSessionBootstrap.start(runtime)
}
