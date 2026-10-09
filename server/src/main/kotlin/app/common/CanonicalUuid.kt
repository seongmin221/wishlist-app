package app.common

import java.util.UUID

/** Accepts only the canonical 36-character UUID form (any case); other spellings are not identifiers. */
fun parseCanonicalUuid(value: String?): UUID? = value?.let {
    runCatching { UUID.fromString(it) }.getOrNull()?.takeIf { id -> id.toString().equals(it, ignoreCase = true) }
}
