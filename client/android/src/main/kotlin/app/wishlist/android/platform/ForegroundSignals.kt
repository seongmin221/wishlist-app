package app.wishlist.android.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import app.wishlist.android.share.ShareReceiverActivity

/**
 * App start/foreground signal without `ProcessLifecycleOwner` (lifecycle-process is not a declared
 * dependency): counts started activities and reports each 0 → 1 transition (the first one is the
 * app's launch; iOS reports the same moments). The share card Activity is not "the app" and is not counted. A configuration change (stop → start of a
 * recreated activity) is not a new foreground. Pure state, so it is unit-tested without Android.
 */
class ForegroundTransitions {
    private var started = 0
    private var changingConfigurations = false

    /** True when this start brings the app to the foreground (false: it already was). */
    fun onStarted(isShareActivity: Boolean): Boolean {
        if (isShareActivity) return false
        if (changingConfigurations) {
            changingConfigurations = false
            return false
        }
        return started++ == 0
    }

    fun onStopped(isShareActivity: Boolean, changingConfigurations: Boolean) {
        if (isShareActivity) return
        if (changingConfigurations) {
            this.changingConfigurations = true
            return
        }
        started = (started - 1).coerceAtLeast(0)
    }
}

/** Lifecycle callbacks feeding [ForegroundTransitions]. Main thread only (lifecycle callbacks). */
class ForegroundSignals(private val onForeground: () -> Unit) : Application.ActivityLifecycleCallbacks {
    private val transitions = ForegroundTransitions()

    override fun onActivityStarted(activity: Activity) {
        if (transitions.onStarted(activity is ShareReceiverActivity)) onForeground()
    }

    override fun onActivityStopped(activity: Activity) {
        transitions.onStopped(activity is ShareReceiverActivity, activity.isChangingConfigurations)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
