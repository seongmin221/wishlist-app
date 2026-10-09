package app.wishlist.android.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import app.wishlist.android.share.ShareReceiverActivity
import app.wishlist.shared.submission.FlushTrigger

/**
 * App start/foreground signal without `ProcessLifecycleOwner` (lifecycle-process is not a declared
 * dependency): counts started activities and reports each 0 → 1 transition — [FlushTrigger.LAUNCH]
 * for the first one in this process, [FlushTrigger.FOREGROUND] afterwards (as iOS does). The share
 * card Activity is not "the app" and is not counted. A configuration change (stop → start of a
 * recreated activity) is not a new foreground. Pure state, so it is unit-tested without Android.
 */
class ForegroundTransitions {
    private var started = 0
    private var changingConfigurations = false
    private var launched = false

    /** The trigger to send for this start, or null when the app was already in the foreground. */
    fun onStarted(isShareActivity: Boolean): FlushTrigger? {
        if (isShareActivity) return null
        if (changingConfigurations) {
            changingConfigurations = false
            return null
        }
        if (started++ != 0) return null
        if (launched) return FlushTrigger.FOREGROUND
        launched = true
        return FlushTrigger.LAUNCH
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
class ForegroundSignals(private val onTrigger: (FlushTrigger) -> Unit) : Application.ActivityLifecycleCallbacks {
    private val transitions = ForegroundTransitions()

    override fun onActivityStarted(activity: Activity) {
        transitions.onStarted(activity is ShareReceiverActivity)?.let(onTrigger)
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
