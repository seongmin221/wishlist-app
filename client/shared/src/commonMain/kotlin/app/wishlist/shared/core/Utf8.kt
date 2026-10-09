package app.wishlist.shared.core

/** Do not let malformed UTF-16 become a transport exception or a replacement-character URL. */
internal fun hasValidUtf8Encoding(value: String): Boolean = try {
    value.encodeToByteArray(throwOnInvalidSequence = true)
    true
} catch (_: CharacterCodingException) {
    false
}
