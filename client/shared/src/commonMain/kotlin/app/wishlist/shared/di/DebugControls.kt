@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.wishlist.shared.di

import app.wishlist.shared.core.ApiId
import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ClientResult
import app.wishlist.shared.core.Clock
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.core.IdGenerator
import app.wishlist.shared.data.fake.FakeStore
import app.wishlist.shared.model.LocalSubmission
import app.wishlist.shared.repository.LocalStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * **DEBUG-only demo hooks** for on-device verification (Android debug intent extras and iOS DEBUG
 * launch arguments `wl.fake.delayItem01` / `wl.fake.pendingCount`). Not app logic: only
 * [SharedRuntime.debugControls] creates one, and it returns null in RELEASE. The class is still in
 * the framework header because DEBUG and RELEASE apps link the same Kotlin binary.
 */
class DebugControls internal constructor(
    private val fake: FakeStore,
    private val store: LocalStore,
    private val ready: StateFlow<Boolean>,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val onPendingCreated: () -> Unit,
) {
    /** The next Fake ITEM-01 request waits [millis] before its session check (FakeStore.delayNext). */
    fun delayNextItem01(millis: Long) {
        fake.delayNext(ApiId.ITEM_01, millis.coerceAtLeast(0))
    }

    /**
     * Waits for ready, then saves [count] unbound PENDING shares (new keys, distinct URLs, sharedAt
     * one minute apart ending now, oldest first) and asks for a flush so the home view shows them.
     * Signed in, that flush binds and sends them like any unbound share. The value is the count saved.
     */
    suspend fun createUnboundPending(count: Int): ClientResult<Int> {
        if (withTimeoutOrNull(READY_WAIT) { ready.first { it } } == null) {
            return ClientResult.Failure(ClientError(ErrorKind.UNAVAILABLE, RUNTIME_NOT_READY))
        }
        val now = clock.now()
        val total = count.coerceAtLeast(0)
        for (index in 0 until total) {
            val row = LocalSubmission(
                clientSubmissionId = ids.newId(),
                sourceUrl = "https://example.com/debug/pending-${index + 1}",
                sharedAt = now - (total - 1 - index).minutes,
                accountBinding = null,
            )
            val saved = store.saveSubmission(row)
            if (saved is ClientResult.Failure) return saved
        }
        if (total > 0) onPendingCreated()
        return ClientResult.Success(total)
    }

    private companion object {
        val READY_WAIT = 30.seconds
    }
}
