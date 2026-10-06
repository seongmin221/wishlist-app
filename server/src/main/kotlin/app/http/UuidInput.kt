package app.http

import java.util.UUID

internal fun parseCanonicalUuid(value: String?): UUID? = value?.let {
    runCatching { UUID.fromString(it) }.getOrNull()?.takeIf { id -> id.toString().equals(it, ignoreCase = true) }
}
