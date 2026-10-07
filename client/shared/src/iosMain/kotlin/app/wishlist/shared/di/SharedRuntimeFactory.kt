package app.wishlist.shared.di

import app.wishlist.shared.core.RuntimeDispatchers
import app.wishlist.shared.data.local.DriverFactory
import app.wishlist.shared.data.remote.platformHttpEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone

/** iOS entry point. The app owns exactly one runtime per process (its `WishlistApp`). */
object SharedRuntimeFactory {
    /** [remote] must be non-null if and only if some binding is REMOTE; otherwise this throws. */
    fun create(bindings: RepositoryBindings, remote: RemoteConfig?): SharedRuntime = assembleSharedRuntime(
        bindings = bindings,
        remote = remote,
        platform = PlatformResources(
            openDriver = { DriverFactory().create() },
            createEngine = ::platformHttpEngine,
            utcOffsetSeconds = { at ->
                NSTimeZone.localTimeZone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(at.epochSeconds.toDouble())).toInt()
            },
        ),
        clock = systemClock,
        ids = randomIds,
        dispatchers = RuntimeDispatchers(default = Dispatchers.Default, io = Dispatchers.IO),
    )
}
