package app.wishlist.android.di

import app.wishlist.shared.di.SharedRuntime

/**
 * Debug-only start of the demo session. The shared runtime owns the order
 * (starts signed out -> saved fake account restored -> that account seeded -> ready = true); requests made before
 * `ready` is true return UNAVAILABLE.
 */
internal object DebugSessionBootstrap {
    fun start(runtime: SharedRuntime) {
        runtime.startDebugSession()
    }
}
