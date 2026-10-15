package app.wishlist.android.feature.category

import app.wishlist.android.feature.category.PhotoPagePlanner.Card
import app.wishlist.android.feature.category.PhotoPagePlanner.Photo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PhotoPagePlannerTest {
    private val page = listOf(Card("a", true), Card("b", true), Card("c", false))

    @Test fun pageWaitsUntilEverySizeIsKnown() {
        val planner = PhotoPagePlanner()
        assertFalse(planner.settle(0, page, mapOf("a" to Photo.Sized(400, 500)), 100))
        assertNull(planner.ratioOf("a"))
        assertTrue(planner.settle(0, page, mapOf("a" to Photo.Sized(400, 500), "b" to Photo.Failed), 200))
        assertEquals(1.25f, planner.ratioOf("a"))
        assertEquals(1f, planner.ratioOf("b")) // failed → 1:1
        assertEquals(1f, planner.ratioOf("c")) // no photo → 1:1 at once
    }

    @Test fun naturalRatioIsNotClamped() {
        val planner = PhotoPagePlanner()
        planner.settle(0, listOf(Card("tall", true), Card("wide", true)),
            mapOf("tall" to Photo.Sized(100, 2000), "wide" to Photo.Sized(1000, 100)), 0)
        assertEquals(20f, planner.ratioOf("tall"))
        assertEquals(0.1f, planner.ratioOf("wide"))
    }

    @Test fun timeoutLaysOutUnknownsAsSquares() {
        val planner = PhotoPagePlanner(timeoutMs = 3_000)
        assertFalse(planner.settle(0, page, mapOf("a" to Photo.Sized(300, 600)), 2_999))
        assertTrue(planner.settle(0, page, mapOf("a" to Photo.Sized(300, 600)), 3_000))
        assertEquals(2f, planner.ratioOf("a"))
        assertEquals(1f, planner.ratioOf("b"))
    }

    @Test fun laidOutHeightsNeverChange() {
        val planner = PhotoPagePlanner()
        planner.settle(0, page, emptyMap(), 5_000)
        assertEquals(1f, planner.ratioOf("a"))
        // The photo arrives late: the square stays (the photo fills it).
        planner.settle(0, page, mapOf("a" to Photo.Sized(100, 400)), 6_000)
        assertEquals(1f, planner.ratioOf("a"))
        planner.reset()
        assertNull(planner.ratioOf("a"))
    }

    @Test fun pagesAreLaidOutInOrder() {
        val planner = PhotoPagePlanner()
        val first = listOf(Card("a", true))
        val second = listOf(Card("b", true), Card("c", false))
        // The second page has every size first; it still waits for the first page.
        assertFalse(planner.settle(1, second, mapOf("b" to Photo.Sized(100, 200)), 5_000))
        assertNull(planner.ratioOf("b"))
        assertEquals(0, planner.laidOutPages)
        assertFalse(planner.settle(0, first, emptyMap(), 100))
        assertTrue(planner.settle(0, first, mapOf("a" to Photo.Sized(100, 100)), 200))
        assertTrue(planner.settle(1, second, mapOf("b" to Photo.Sized(100, 200)), 5_000))
        assertEquals(2f, planner.ratioOf("b"))
        assertEquals(2, planner.laidOutPages)
        assertTrue(planner.settle(0, first, emptyMap(), 0)) // an earlier page stays laid out
        planner.reset()
        assertEquals(0, planner.laidOutPages)
        assertFalse(planner.settle(1, second, emptyMap(), 10_000))
    }

    @Test fun decodeSizeIsCapped() {
        val col = 480
        listOf(100 to 10_000, 480 to 48_000, 4000 to 3000, 300 to 300, 1080 to 1920).forEach { (w, h) ->
            val (fw, fh) = PhotoPagePlanner.fittedSize(w, h, col)
            assertTrue("$w×$h → $fw×$fh", fw.toLong() * fh <= col.toLong() * col * 3)
            // Ratio kept to within one pixel of width (whole pixels).
            assertEquals("ratio kept for $w×$h", fw * h.toDouble() / w, fh.toDouble(), h.toDouble() / w + 1)
            assertTrue(fw <= w && fh <= h) // never upscaled
        }
        assertEquals(480 to 1440, PhotoPagePlanner.decodeBox(480))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun atMostSixRequestsRunAndACancelledWaiterNeverStarts() = runTest {
        val gate = PhotoRequestGate()
        val release = CompletableDeferred<Unit>()
        var running = 0
        var peak = 0
        var started = 0
        val jobs = (1..8).map {
            launch {
                gate.run {
                    started++; running++; peak = maxOf(peak, running)
                    release.await()
                    running--
                }
            }
        }
        runCurrent()
        assertEquals(6, started)
        jobs[7].cancel() // still waiting for a permit
        release.complete(Unit)
        runCurrent()
        assertEquals(6, peak)
        assertEquals(7, started) // the 7th ran after a permit freed; the cancelled 8th never started
    }

    @Test fun loadMoreRules() {
        assertTrue(shouldLoadMore(0, 0, hasMore = true, busy = false, appendFailed = false)) // page 1 shorter than the screen
        assertTrue(shouldLoadMore(4000, 5000, hasMore = true, busy = false, appendFailed = false)) // near the end
        assertFalse(shouldLoadMore(0, 5000, hasMore = true, busy = false, appendFailed = false)) // far from it
        assertFalse(shouldLoadMore(5000, 5000, hasMore = true, busy = true, appendFailed = false)) // in flight: asked again when free
        assertFalse(shouldLoadMore(5000, 5000, hasMore = false, busy = false, appendFailed = false))
        assertFalse(shouldLoadMore(5000, 5000, hasMore = true, busy = false, appendFailed = true)) // waits for 다시 시도
    }
}
