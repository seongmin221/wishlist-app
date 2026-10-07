package app.wishlist.android.di

import app.wishlist.shared.di.SharedRuntime

/**
 * Debug-only start of the demo session. The shared runtime owns the order
 * (account "debug-board-owner" -> seed of that namespace -> ready = true); requests made before
 * `ready` is true return UNAVAILABLE.
 */
internal object DebugSessionBootstrap {
    fun start(runtime: SharedRuntime) {
        runtime.startDebugSession()
    }
}
