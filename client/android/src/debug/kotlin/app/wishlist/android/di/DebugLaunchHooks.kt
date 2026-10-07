package app.wishlist.android.di

import android.content.Intent
import android.util.Log
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.di.SharedRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Debug-only demo hooks read from MainActivity's launch intent, for on-device checks without a debug
 * menu (C3 Task 7). Release has no such code.
 *
 * ```
 * adb shell am start -n app.wishlist.android/.MainActivity --el wl.fake.delayItem01 5000
 * adb shell am start -n app.wishlist.android/.MainActivity --ei wl.fake.pendingCount 100
 * ```
 * - `wl.fake.delayItem01` (ms): the next Fake ITEM-01 send waits that long.
 * - `wl.fake.pendingCount` (N): saves N unbound pending shares once the runtime is ready.
 */
internal object DebugLaunchHooks {
    const val DELAY_ITEM_01 = "wl.fake.delayItem01"
    const val PENDING_COUNT = "wl.fake.pendingCount"
    private const val TAG = "WishlistDebug"

    fun apply(runtime: SharedRuntime, intent: Intent, scope: CoroutineScope) {
        val delay = intent.numberExtra(DELAY_ITEM_01)?.toLong()
        val count = intent.numberExtra(PENDING_COUNT)?.toInt()
        if (delay == null && count == null) return
        val controls = runtime.debugControls() ?: return
        if (delay != null) {
            controls.delayNextItem01(delay)
            Log.i(TAG, "next ITEM-01 delayed ${delay}ms")
        }
        if (count != null) {
            scope.launch {
                when (val result = controls.createUnboundPending(count)) {
                    is ClientResult.Success -> Log.i(TAG, "created ${result.value} unbound pending rows")
                    is ClientResult.Failure -> Log.w(TAG, "pending rows not created: ${result.error}")
                }
            }
        }
    }

    // adb sends --ei as Int, --el as Long and --es as String; accept any of them.
    @Suppress("DEPRECATION")
    private fun Intent.numberExtra(key: String): Number? = when (val value = extras?.get(key)) {
        is Number -> value
        is String -> value.trim().toLongOrNull()
        else -> null
    }
}
