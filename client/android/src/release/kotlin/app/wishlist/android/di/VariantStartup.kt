package app.wishlist.android.di

import app.wishlist.shared.di.SharedRuntime

/** Release variant: the runtime is ready right after assembly; there is no debug bootstrap. */
internal object VariantStartup {
    @Suppress("UNUSED_PARAMETER")
    fun onRuntimeAssembled(runtime: SharedRuntime) = Unit
}
