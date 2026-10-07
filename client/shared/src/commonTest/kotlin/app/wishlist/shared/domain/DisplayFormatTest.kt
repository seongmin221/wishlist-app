package app.wishlist.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DisplayFormatTest {
    private val kst = 9 * 3600

    // 2026-10-07 12:00 KST.
    private val noonKst = Instant.parse("2026-10-07T03:00:00Z")

    @Test fun hostIsLowercaseWithoutLeadingWww() {
        assertEquals("musinsa.com", DisplayFormat.host("https://www.Musinsa.com/p"))
        assertEquals("ohou.se", DisplayFormat.host("https://ohou.se"))
        assertEquals("shop.example", DisplayFormat.host("HTTPS://user@Shop.Example:8443/a?b#c"))
    }

    @Test fun hostFallsBackToTheFirst40Characters() {
        assertEquals("not a url", DisplayFormat.host("not a url"))
        val long = "x".repeat(50)
        assertEquals(long.take(40), DisplayFormat.host(long))
        assertEquals("https://", DisplayFormat.host("https://"))
    }

    @Test fun underAMinuteIsJustNow() {
        assertEquals(RelativeTime.JustNow, DisplayFormat.relative(noonKst, noonKst + 30.seconds, kst))
    }

    @Test fun under60MinutesIsMinutes() {
        assertEquals(RelativeTime.Minutes(59), DisplayFormat.relative(noonKst, noonKst + 59.minutes, kst))
    }

    @Test fun sameCalendarDayUnder24HoursIsHours() {
        assertEquals(RelativeTime.Hours(5), DisplayFormat.relative(noonKst, noonKst + 5.hours, kst))
    }

    @Test fun previousCalendarDayIsYesterdayInTheGivenOffset() {
        val from = Instant.parse("2026-10-06T14:59:00Z") // 23:59 KST
        val now = Instant.parse("2026-10-06T15:01:00Z")  // 00:01 KST the next day
        assertEquals(RelativeTime.Yesterday, DisplayFormat.relative(from, now, kst))
        // In UTC both instants are on the same day: the offset decides the calendar date.
        assertNotEquals(DisplayFormat.relative(from, now, kst), DisplayFormat.relative(from, now, 0))
        assertEquals(RelativeTime.Minutes(2), DisplayFormat.relative(from, now, 0))
    }

    @Test fun twoOrMoreCalendarDaysIsDays() {
        assertEquals(RelativeTime.Days(2), DisplayFormat.relative(noonKst - 2.days, noonKst, kst))
    }

    @Test fun futureTimeFromAClockGoingBackwardsIsJustNow() {
        assertEquals(RelativeTime.JustNow, DisplayFormat.relative(noonKst + 5.minutes, noonKst, kst))
    }
}
