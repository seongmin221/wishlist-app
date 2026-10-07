package app.wishlist.shared.model

import app.wishlist.shared.core.ClientError
import kotlin.time.Instant

enum class SubmissionStatus { PENDING, SUBMITTING, ACCEPTED }

data class LocalSubmission(
    val clientSubmissionId: String,
    val sourceUrl: String,
    val createdAt: Instant,
    val accountBinding: String? = null,
    val submissionStatus: SubmissionStatus = SubmissionStatus.PENDING,
    val serverItemId: String? = null,
    val lastSubmissionError: ClientError? = null,
) {
    val createdAtIso: String get() = createdAt.toString()
}
