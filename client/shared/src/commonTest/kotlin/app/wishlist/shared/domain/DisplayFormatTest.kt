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
    private val kst: (Instant) -> Int = { 9 * 3600 }
    private val utc: (Instant) -> Int = { 0 }

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
        assertNotEquals(DisplayFormat.relative(from, now, kst), DisplayFormat.relative(from, now, utc))
        assertEquals(RelativeTime.Minutes(2), DisplayFormat.relative(from, now, utc))
    }

    @Test fun twoOrMoreCalendarDaysIsDays() {
        assertEquals(RelativeTime.Days(2), DisplayFormat.relative(noonKst - 2.days, noonKst, kst))
    }

    @Test fun futureTimeFromAClockGoingBackwardsIsJustNow() {
        assertEquals(RelativeTime.JustNow, DisplayFormat.relative(noonKst + 5.minutes, noonKst, kst))
    }

    @Test fun eachInstantUsesItsOwnOffsetAcrossADaylightSavingSwitch() {
        // The device moves from UTC+0 to UTC+1 at 2026-03-29T00:00Z.
        val switch = Instant.parse("2026-03-29T00:00:00Z")
        val offset: (Instant) -> Int = { if (it < switch) 0 else 3600 }
        val from = Instant.parse("2026-03-28T23:30:00Z") // 23:30 local on the 28th (UTC+0)
        val now = Instant.parse("2026-03-29T00:45:00Z")  // 01:45 local on the 29th (UTC+1)
        // With now's offset for both, from would read 00:30 on the 29th and give Hours(1).
        assertEquals(RelativeTime.Yesterday, DisplayFormat.relative(from, now, offset))
    }

    @Test fun savedUnderAMinuteIsJustNow() {
        assertEquals(SavedLabel.JustNow, DisplayFormat.saved(noonKst, noonKst + 30.seconds, kst))
    }

    @Test fun savedEarlierTheSameDayIsToday() {
        assertEquals(SavedLabel.Today, DisplayFormat.saved(noonKst, noonKst + 3.hours, kst))
    }

    @Test fun savedYesterdayIsADateWithoutYear() {
        assertEquals(SavedLabel.OnDate(null, 10, 6), DisplayFormat.saved(noonKst - 1.days, noonKst, kst))
    }

    @Test fun savedLastYearCarriesTheYear() {
        assertEquals(SavedLabel.OnDate(2025, 10, 7), DisplayFormat.saved(noonKst - 365.days, noonKst, kst))
    }

    @Test fun savedLeapDayAndYearBoundary() {
        // 2024-02-29 12:00 UTC viewed a month later in the same year.
        val leap = Instant.parse("2024-02-29T12:00:00Z")
        assertEquals(SavedLabel.OnDate(null, 2, 29), DisplayFormat.saved(leap, leap + 30.days, utc))
        // 2025-12-31 23:00 UTC seen two days later in 2026.
        val newYearsEve = Instant.parse("2025-12-31T23:00:00Z")
        assertEquals(SavedLabel.OnDate(2025, 12, 31), DisplayFormat.saved(newYearsEve, newYearsEve + 2.days, utc))
        // Same instant seen in KST is already 1/1 of 2026.
        assertEquals(SavedLabel.OnDate(null, 1, 1), DisplayFormat.saved(newYearsEve, newYearsEve + 2.days, kst))
    }

    @Test fun savedWithNegativeOffset() {
        val minusEight: (Instant) -> Int = { -8 * 3600 }
        // 2026-10-07 03:00 UTC is 10-06 19:00 at -08:00; 25 hours later is local 10-07 20:00.
        val at = Instant.parse("2026-10-07T03:00:00Z")
        assertEquals(SavedLabel.OnDate(null, 10, 6), DisplayFormat.saved(at, at + 25.hours, minusEight))
        assertEquals(SavedLabel.Today, DisplayFormat.saved(at, at + 2.hours, minusEight))
    }
}
