package app.wishlist.android.feature.flow

import org.junit.Assert.assertEquals
import org.junit.Test

/** motion.md §4: release threshold `|dx| ≥ W × 0.3` or 800/s, rotation `dx / W × 16°` (same names and values as iOS). */
class CardThrowMathTest {
    private val w = 350f

    @Test fun a_drag_of_thirty_percent_of_the_width_throws_that_way() {
        assertEquals(ThrowDecision.Right, CardThrowMath.decide(dx = 0.3f * w, velocityX = 0f, width = w))
        assertEquals(ThrowDecision.Left, CardThrowMath.decide(dx = -0.3f * w, velocityX = 0f, width = w))
    }

    @Test fun below_both_thresholds_the_card_returns() {
        assertEquals(ThrowDecision.Return, CardThrowMath.decide(dx = 0.29f * w, velocityX = 799f, width = w))
        assertEquals(ThrowDecision.Return, CardThrowMath.decide(dx = -0.29f * w, velocityX = -799f, width = w))
        assertEquals(ThrowDecision.Return, CardThrowMath.decide(dx = 0f, velocityX = 0f, width = w))
    }

    @Test fun a_fast_flick_throws_in_the_direction_of_the_velocity() {
        assertEquals(ThrowDecision.Left, CardThrowMath.decide(dx = 0.1f * w, velocityX = -800f, width = w))
        assertEquals(ThrowDecision.Right, CardThrowMath.decide(dx = -0.1f * w, velocityX = 800f, width = w))
    }

    @Test fun a_fling_wins_over_the_drag_side() {
        // Ruling 6 (same as iOS): |vx| ≥ 800/s decides by the fling's direction even past the distance threshold.
        assertEquals(ThrowDecision.Left, CardThrowMath.decide(dx = 0.4f * w, velocityX = -900f, width = w))
        assertEquals(ThrowDecision.Right, CardThrowMath.decide(dx = -0.4f * w, velocityX = 800f, width = w))
        // A slower flick back does not override the drag side.
        assertEquals(ThrowDecision.Right, CardThrowMath.decide(dx = 0.4f * w, velocityX = -799f, width = w))
    }

    @Test fun rotation_follows_the_drag_up_to_sixteen_degrees_at_full_width() {
        assertEquals(8f, CardThrowMath.rotationFor(w / 2, w), 0.0001f)
        assertEquals(-16f, CardThrowMath.rotationFor(-w, w), 0.0001f)
        assertEquals(0f, CardThrowMath.rotationFor(10f, 0f), 0.0001f)
    }

    @Test fun a_continued_throw_runs_only_the_rest_of_the_340ms_exit() {
        assertEquals(340, CardThrowMath.remainingMillis(dx = 0f))
        assertEquals(170, CardThrowMath.remainingMillis(dx = 240f))
        assertEquals(170, CardThrowMath.remainingMillis(dx = -240f))
        // Truncated, no floor (the same as iOS `exitMillis`).
        assertEquals(332, CardThrowMath.remainingMillis(dx = 10f))
        assertEquals(0, CardThrowMath.remainingMillis(dx = 480f))
        assertEquals(0, CardThrowMath.remainingMillis(dx = 600f))
    }
}
