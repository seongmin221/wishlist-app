package app.wishlist.shared.di

import android.content.Context
import app.wishlist.shared.core.RuntimeDispatchers
import app.wishlist.shared.data.local.DriverFactory
import app.wishlist.shared.data.remote.platformHttpEngine
import kotlinx.coroutines.Dispatchers

/** Android entry point. The app owns exactly one runtime per process (its Application). */
object SharedRuntimeFactory {
    /** [remote] must be non-null if and only if some binding is REMOTE; otherwise this throws. */
    fun create(context: Context, bindings: RepositoryBindings, remote: RemoteConfig?): SharedRuntime {
        val appContext = context.applicationContext
        return assembleSharedRuntime(
            bindings = bindings,
            remote = remote,
            platform = PlatformResources(
                openDriver = { DriverFactory(appContext).create() },
                createEngine = ::platformHttpEngine,
                utcOffsetSeconds = { at -> java.util.TimeZone.getDefault().getOffset(at.toEpochMilliseconds()) / 1000 },
            ),
            clock = systemClock,
            ids = randomIds,
            dispatchers = RuntimeDispatchers(default = Dispatchers.Default, io = Dispatchers.IO),
        )
    }
}
