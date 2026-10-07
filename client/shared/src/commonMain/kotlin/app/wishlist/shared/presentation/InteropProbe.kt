package app.wishlist.shared.presentation

import app.wishlist.shared.core.PlatformTokenSource
import app.wishlist.shared.core.TokenCallback
import app.wishlist.shared.core.TokenRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.updateAndGet

/** Small, temporary ABI gate retained until the real presenters replace it. */
class InteropProbe {
    private val lifetime = Job()
    private val mutableState = MutableStateFlow(0)
    val state: StateFlow<Int> = mutableState.asStateFlow()

    suspend fun increment(): Int = coroutineScope {
        lifetime.ensureActive()
        mutableState.updateAndGet { it + 1 }
    }

    fun close() {
        lifetime.cancel()
    }

    fun invokeToken(source: PlatformTokenSource, completion: TokenCallback): TokenRequest =
        source.fetchToken(forceRefresh = false, completion = completion)
}
