package app.wishlist.shared.submission

import app.wishlist.shared.core.ClientError
import app.wishlist.shared.core.ErrorKind
import app.wishlist.shared.model.SubmissionStatus.FAILED
import app.wishlist.shared.model.SubmissionStatus.PENDING
import app.wishlist.shared.submission.SubmissionErrorPolicy.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SubmissionErrorPolicyTest {
    private val now = Instant.parse("2026-10-07T00:00:00Z")

    /** C3-D8 with Ruling 10 (NETWORK/TIMEOUT/RATE_LIMITED stop the flush). NOT_FOUND is not in the table: retryable. */
    private val table: Map<ErrorKind, Decision> = mapOf(
        ErrorKind.NETWORK to Decision(PENDING, null, stopFlush = true),
        ErrorKind.TIMEOUT to Decision(PENDING, null, stopFlush = true),
        ErrorKind.SERVER to Decision(PENDING, null, stopFlush = false),
        ErrorKind.INVALID_RESPONSE to Decision(PENDING, null, stopFlush = false),
        ErrorKind.UNAVAILABLE to Decision(PENDING, null, stopFlush = false),
        ErrorKind.NOT_FOUND to Decision(PENDING, null, stopFlush = false),
        ErrorKind.RATE_LIMITED to Decision(PENDING, now + 60.seconds, stopFlush = true),
        ErrorKind.SESSION_CHANGED to Decision(PENDING, null, stopFlush = true),
        ErrorKind.UNAUTHENTICATED to Decision(PENDING, null, stopFlush = true),
        ErrorKind.VALIDATION to Decision(FAILED, null, stopFlush = false),
        ErrorKind.CONFLICT to Decision(FAILED, null, stopFlush = false),
    )

    @Test fun everyErrorKindHasTheC3D8Decision() = ErrorKind.entries.forEach { kind ->
        val expected = table[kind] ?: fail("No C3-D8 expectation for $kind")
        assertEquals(expected, SubmissionErrorPolicy.decide(ClientError(kind), now), "kind=$kind")
    }

    @Test fun rateLimitedUsesTheServerRetryAfter() {
        val decision = SubmissionErrorPolicy.decide(ClientError(ErrorKind.RATE_LIMITED, retryAfterSeconds = 30), now)
        assertEquals(Decision(PENDING, now + 30.seconds, stopFlush = true), decision)
    }
}
