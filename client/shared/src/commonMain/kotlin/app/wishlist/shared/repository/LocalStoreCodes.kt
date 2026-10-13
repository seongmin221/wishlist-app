package app.wishlist.shared.repository

/** Failure code of a [LocalStore] delete refused because the submission is SUBMITTING (CONFLICT). */
internal const val SUBMISSION_IN_FLIGHT = "SUBMISSION_IN_FLIGHT"
