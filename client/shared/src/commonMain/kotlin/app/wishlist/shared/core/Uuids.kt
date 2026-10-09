@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.wishlist.shared.core

import kotlin.uuid.Uuid

/**
 * The one UUID reading used for client keys and item ids (local store, inbox import and the Fake
 * backend): the canonical lowercase hex-dash form, or null when [value] is not a UUID. Comparing
 * these strings compares UUIDs by value, whatever casing a platform produced.
 */
internal fun canonicalUuidOrNull(value: String): String? =
    try { Uuid.parse(value).toString() } catch (_: IllegalArgumentException) { null }
