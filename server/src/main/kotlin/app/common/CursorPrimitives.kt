package app.common

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Shared wire primitives for purpose and item position hints. */
internal object CursorPrimitives {
    const val MAX_MICROS = 253_402_300_799_999_999L
    fun ownerTag(owner: UUID): String = MessageDigest.getInstance("SHA-256")
        .digest(owner.toString().toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
    fun micros(time: Instant): Long = Math.addExact(Math.multiplyExact(time.epochSecond,1_000_000L),time.nano/1000L)
    fun instant(micros: Long): Instant {
        return Instant.ofEpochSecond(Math.floorDiv(micros,1_000_000L),Math.floorMod(micros,1_000_000L)*1000L)
    }
}
