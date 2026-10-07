package app.wishlist.android.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import app.wishlist.android.share.ShareReceiverActivity

/**
 * App foreground signal without `ProcessLifecycleOwner` (lifecycle-process is not a declared
 * dependency): counts started activities and fires [onForeground] on each 0 → 1 transition.
 * The share card Activity is not "the app" and is not counted. A configuration change
 * (stop → start of a recreated activity) is not a new foreground.
 * Main thread only (lifecycle callbacks).
 */
class ForegroundSignals(private val onForeground: () -> Unit) : Application.ActivityLifecycleCallbacks {
    private var started = 0
    private var changingConfigurations = false

    override fun onActivityStarted(activity: Activity) {
        if (activity is ShareReceiverActivity) return
        if (changingConfigurations) {
            changingConfigurations = false
            return
        }
        if (started++ == 0) onForeground()
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity is ShareReceiverActivity) return
        if (activity.isChangingConfigurations) {
            changingConfigurations = true
            return
        }
        started = (started - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
