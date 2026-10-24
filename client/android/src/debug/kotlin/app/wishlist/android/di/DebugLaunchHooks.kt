package app.wishlist.android.di

import android.content.Intent
import android.util.Log
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.di.SharedRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Debug-only demo hooks read from MainActivity's launch intent, for on-device checks without a debug
 * menu (C3 Task 7). Release has no such code.
 *
 * ```
 * adb shell am start -S -n app.wishlist.android/.MainActivity --el wl.fake.delayItem01 5000
 * adb shell am start -S -n app.wishlist.android/.MainActivity --ei wl.fake.pendingCount 100
 * ```
 * Cold start only: an intent delivered to a running MainActivity, or a recreated one, is not
 * read, so `-S` force-stops the app first.
 * - `wl.fake.delayItem01` (ms): the next Fake ITEM-01 send waits that long.
 * - `wl.fake.pendingCount` (N): saves N unbound pending shares once the runtime is ready.
 * - C5 `wl.fake.longCategory` (category id, e.g. C024): 120 items there (tall and slow photos included).
 * - C5 `wl.fake.delayCategoryCreate` (ms): the next Fake CAT-03 waits that long (time a lost answer).
 * - C5 `wl.fake.editCategory` (`<custom id>|<name>`): renames it as another device would (version + 1).
 * - C5 `wl.fake.signOutAfterMs` (ms): signs out that long after ready, to check what closes when the app
 *   leaves the account while a screen or sheet is open (the UI cannot sign out with a sheet on top).
 */
internal object DebugLaunchHooks {
    const val DELAY_ITEM_01 = "wl.fake.delayItem01"
    const val PENDING_COUNT = "wl.fake.pendingCount"
    const val LONG_CATEGORY = "wl.fake.longCategory"
    const val DELAY_CATEGORY_CREATE = "wl.fake.delayCategoryCreate"
    const val EDIT_CATEGORY = "wl.fake.editCategory"
    const val SIGN_OUT_AFTER = "wl.fake.signOutAfterMs"
    private const val TAG = "WishlistDebug"

    fun apply(runtime: SharedRuntime, intent: Intent, scope: CoroutineScope) {
        val delay = intent.numberExtra(DELAY_ITEM_01)?.toLong()
        val count = intent.numberExtra(PENDING_COUNT)?.toInt()
        val longCategory = intent.getStringExtra(LONG_CATEGORY)
        val delayCreate = intent.numberExtra(DELAY_CATEGORY_CREATE)?.toLong()
        val editCategory = intent.getStringExtra(EDIT_CATEGORY)
        val signOutAfter = intent.numberExtra(SIGN_OUT_AFTER)?.toLong()
        if (signOutAfter != null) scope.launch {
            runtime.ready.first { it }
            kotlinx.coroutines.delay(signOutAfter)
            Log.i(TAG, "debug sign out: ${runtime.auth().signOut()}")
        }
        if (delay == null && count == null && longCategory == null && delayCreate == null && editCategory == null) return
        val controls = runtime.debugControls() ?: return
        if (delayCreate != null) controls.delayNextCategoryCreate(delayCreate)
        if (longCategory != null) scope.launch {
            Log.i(TAG, "long category $longCategory: ${controls.seedLongCategory(longCategory)}")
        }
        if (editCategory != null) {
            val (id, name) = editCategory.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "다른 기기 이름" } }
            scope.launch {
                runtime.ready.first { it }
                Log.i(TAG, "edit category $id: ${controls.editCategoryElsewhere(id, name)}")
            }
        }
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
