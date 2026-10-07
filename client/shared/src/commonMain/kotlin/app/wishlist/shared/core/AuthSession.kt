@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package app.wishlist.shared.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.native.HiddenFromObjC

/** The platform authentication SDK's stable account ID, separate from the server owner UUID. */
data class SessionSnapshot(val accountId: String?, val generation: Long)

interface AuthSession {
    val state: StateFlow<SessionSnapshot>

    /**
     * Serialize a short store/DB commit with account changes; stale commits are never invoked.
     * Acquire store/DB locks only inside this gate. Fetch tokens, call network APIs, and delay
     * outside it. The operation's result and cancellation propagate directly to the caller.
     * Swift consumes concrete runtime facades instead of this generic Kotlin contract.
     */
    @HiddenFromObjC
    suspend fun <T> withCurrent(
        snapshot: SessionSnapshot,
        operation: suspend () -> ClientResult<T>,
    ): ClientResult<T>
}

class MutableAuthSession : AuthSession {
    private val gate = Mutex()
    private val mutableState = MutableStateFlow(SessionSnapshot(null, 0))
    override val state: StateFlow<SessionSnapshot> = mutableState.asStateFlow()

    /** Every call denotes login/logout/relogin. Token refresh must not call this method. */
    suspend fun changeAccount(accountId: String?) {
        gate.withLock {
            mutableState.value = SessionSnapshot(accountId, mutableState.value.generation + 1)
        }
    }

    @HiddenFromObjC
    override suspend fun <T> withCurrent(
        snapshot: SessionSnapshot,
        operation: suspend () -> ClientResult<T>,
    ): ClientResult<T> = gate.withLock {
        if (mutableState.value != snapshot) {
            ClientResult.Failure(ClientError(kind = ErrorKind.SESSION_CHANGED))
        } else {
            operation()
        }
    }
}
