package app.wishlist.shared.submission

import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.model.SubmissionStatus
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** C3-D8: what a failed ITEM-01 send does to its row and to the running flush. */
internal object SubmissionErrorPolicy {
    const val DEFAULT_RETRY_AFTER_SECONDS = 60L

    /** The first server-side retry ("잠시 후 다시 보내요"); it doubles per failure in a row up to [SERVER_RETRY_MAX_SECONDS]. */
    const val SERVER_RETRY_SECONDS = 30L
    const val SERVER_RETRY_MAX_SECONDS = 900L

    data class Decision(val status: SubmissionStatus, val retryAfter: Instant?, val stopFlush: Boolean)

    /** Failures of the server or the way to it, retried with backoff (NOT_FOUND: a misrouted endpoint). */
    fun isServerSide(kind: ErrorKind): Boolean =
        kind == ErrorKind.SERVER || kind == ErrorKind.INVALID_RESPONSE || kind == ErrorKind.UNAVAILABLE || kind == ErrorKind.NOT_FOUND

    /** [serverFailures]: server-side failures in a row, this one included (1 for the first). */
    fun decide(error: ClientError, now: Instant, serverFailures: Int = 1): Decision = when (error.kind) {
        // Transient: keep it queued with the error recorded. The next rows would most likely fail the
        // same way, so the flush stops (one request per attempt, not the whole queue), and the
        // coordinator's timer retries after 30s, 60s, 120s ... up to 15 minutes. NOT_FOUND is not in
        // C3-D8 and is treated the same way, so a misrouted endpoint never loses a share.
        ErrorKind.SERVER, ErrorKind.INVALID_RESPONSE, ErrorKind.UNAVAILABLE, ErrorKind.NOT_FOUND ->
            Decision(SubmissionStatus.PENDING, now + serverBackoffSeconds(serverFailures).seconds, stopFlush = true)
        // Ruling 10: the next rows would fail the same way (offline, timing out, throttled), so stop.
        ErrorKind.NETWORK, ErrorKind.TIMEOUT -> Decision(SubmissionStatus.PENDING, null, stopFlush = true)
        ErrorKind.RATE_LIMITED -> {
            // At least 1s: `Retry-After: 0` must not turn the retry timer into a send loop.
            val wait = (error.retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS).coerceAtLeast(1)
            Decision(SubmissionStatus.PENDING, now + wait.seconds, stopFlush = true)
        }
        // The account changed: no store write for this snapshot can succeed; the row keeps its binding.
        ErrorKind.SESSION_CHANGED -> Decision(SubmissionStatus.PENDING, null, stopFlush = true)
        ErrorKind.UNAUTHENTICATED -> Decision(SubmissionStatus.PENDING, null, stopFlush = true)
        // Permanent: never resent automatically ("보낼 수 없는 링크예요").
        ErrorKind.VALIDATION, ErrorKind.CONFLICT -> Decision(SubmissionStatus.FAILED, null, stopFlush = false)
    }

    private fun serverBackoffSeconds(failures: Int): Long {
        val doublings = (failures.coerceAtLeast(1) - 1).coerceAtMost(10)
        return (SERVER_RETRY_SECONDS shl doublings).coerceAtMost(SERVER_RETRY_MAX_SECONDS)
    }
}
