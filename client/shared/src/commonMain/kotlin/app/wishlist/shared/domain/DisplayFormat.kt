package app.wishlist.shared.domain

import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** How long ago something was saved, for list rows (C3-D5 j). */
sealed interface RelativeTime {
    /** Under a minute, or a time in the future (the clock went backwards). */
    data object JustNow : RelativeTime

    /** Under 60 minutes, on the same calendar date. */
    data class Minutes(val value: Int) : RelativeTime

    /** Under 24 hours, on the same calendar date. */
    data class Hours(val value: Int) : RelativeTime

    /** The previous calendar date. */
    data object Yesterday : RelativeTime

    /** Two or more calendar dates ago. */
    data class Days(val value: Int) : RelativeTime
}

object DisplayFormat {
    private const val FALLBACK_LENGTH = 40
    private const val SECONDS_PER_DAY = 86_400L

    /** Lowercase host without a leading "www."; the first 40 characters of [url] if it has no host. */
    fun host(url: String): String {
        val host = ShareTextParser.hostOf(url).lowercase().removePrefix("www.")
        return host.ifEmpty { url.take(FALLBACK_LENGTH) }
    }

    /**
     * Without kotlinx-datetime, calendar dates come from the platform's UTC offset (seconds) at a
     * given instant; the presenter supplies it. Each instant is read with its own offset, so a
     * daylight-saving switch between [from] and [now] does not move [from] to another date.
     * Calendar dates win over elapsed time: 23:59 → 00:01 is Yesterday, not 2 minutes.
     */
    fun relative(from: Instant, now: Instant, utcOffsetSeconds: (Instant) -> Int): RelativeTime {
        val elapsed = now - from
        if (elapsed < 1.minutes) return RelativeTime.JustNow
        val days = (localDay(now, utcOffsetSeconds(now)) - localDay(from, utcOffsetSeconds(from))).toInt()
        return when {
            days >= 2 -> RelativeTime.Days(days)
            days == 1 -> RelativeTime.Yesterday
            elapsed < 1.hours -> RelativeTime.Minutes(elapsed.inWholeMinutes.toInt())
            else -> RelativeTime.Hours(elapsed.inWholeHours.toInt())
        }
    }

    private fun localDay(instant: Instant, utcOffsetSeconds: Int): Long =
        (instant.epochSeconds + utcOffsetSeconds).floorDiv(SECONDS_PER_DAY)
}
