package app.wishlist.shared.model

import app.wishlist.shared.core.ClientError
import kotlin.time.Instant

/** C3: ACCEPTED is gone; an accepted submission is deleted in the same transaction as its cache write. */
enum class SubmissionStatus { PENDING, SUBMITTING, FAILED }

/**
 * A share kept on this device until the server accepts it. [sharedAt] and [retryAfter] are stored
 * as epoch microseconds (sub-microsecond digits are truncated, the server's precision).
 */
data class LocalSubmission(
    val clientSubmissionId: String,
    val sourceUrl: String,
    val sharedAt: Instant,
    val accountBinding: String? = null,
    val submissionStatus: SubmissionStatus = SubmissionStatus.PENDING,
    val lastSubmissionError: ClientError? = null,
    val retryAfter: Instant? = null,
) {
    val sharedAtIso: String get() = sharedAt.toString()
}
