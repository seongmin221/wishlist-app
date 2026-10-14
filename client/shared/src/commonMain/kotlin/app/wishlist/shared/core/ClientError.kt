package app.wishlist.shared.core

enum class ErrorKind {
    UNAUTHENTICATED,
    SESSION_CHANGED,
    NOT_FOUND,
    CONFLICT,
    VALIDATION,
    RATE_LIMITED,
    NETWORK,
    TIMEOUT,
    SERVER,
    INVALID_RESPONSE,
    UNAVAILABLE,
}

data class ClientError(
    val kind: ErrorKind,
    val code: String? = null,
    val requestId: String? = null,
    val currentVersion: Int? = null,
    val retryAfterSeconds: Long? = null,
    /** Server `details.fields` (input field names of a 422, e.g. `name`, `examples`). Empty when absent. */
    val fields: Set<String> = emptySet(),
)
