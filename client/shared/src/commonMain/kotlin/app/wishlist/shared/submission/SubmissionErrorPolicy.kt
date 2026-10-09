package app.wishlist.shared.submission

import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.model.SubmissionStatus
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** C3-D8: what a failed ITEM-01 send does to its row and to the running flush. */
internal object SubmissionErrorPolicy {
    const val DEFAULT_RETRY_AFTER_SECONDS = 60L

    /**
     * A server-side failure is resent by the coordinator's timer after this ("잠시 후 다시 보내요"); unlike
     * a 429 wait it does not hold the row, so any earlier trigger resends it too.
     */
    const val SERVER_RETRY_SECONDS = 30L

    data class Decision(val status: SubmissionStatus, val retryAfter: Instant?, val stopFlush: Boolean)

    fun decide(error: ClientError, now: Instant): Decision = when (error.kind) {
        // Transient: keep it queued with the error recorded and retry it after a short wait. NOT_FOUND
        // is not in C3-D8 and is treated the same way, so a misrouted endpoint never loses a share.
        ErrorKind.SERVER, ErrorKind.INVALID_RESPONSE, ErrorKind.UNAVAILABLE, ErrorKind.NOT_FOUND ->
            Decision(SubmissionStatus.PENDING, now + SERVER_RETRY_SECONDS.seconds, stopFlush = false)
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
}
