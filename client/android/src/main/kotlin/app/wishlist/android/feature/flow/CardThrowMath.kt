package app.wishlist.android.feature.flow

import kotlin.math.abs

/** Where a released review card goes: thrown right (확정), left (보류), or back to its place. */
enum class ThrowDecision { Right, Left, Return }

/**
 * Card throw rules (motion.md §4, pure; iOS has the same names and values). Distances are dp and
 * velocities dp/s, so the board's px values apply as written.
 */
object CardThrowMath {
    /** Release throws when the drag is at least this share of the card width… */
    const val DISTANCE_RATIO = 0.3f

    /** …or the horizontal speed is at least this (dp/s). */
    const val VELOCITY = 800f

    /** Rotation at a drag of one card width, and at the end of a throw (degrees). */
    const val MAX_ROTATION = 16f

    /** The exit translateX of a throw (dp). */
    const val EXIT_DISTANCE = 480f

    /** The full exit time (ms, `throw` curve); a released drag runs only the rest. */
    const val EXIT_MILLIS = 340

    /** Back in place after a short drag (ms, `emphasized`, 구현 기본값). */
    const val RETURN_MILLIS = 250

    /** The next stack comes forward this long after the throw starts (ms), over [NEXT_MILLIS] (`emphasized`). */
    const val NEXT_DELAY_MILLIS = 40
    const val NEXT_MILLIS = 300

    /** The next stack's (and the summary's) start: scale .94, 14 dp lower. */
    const val NEXT_START_SCALE = 0.94f
    const val NEXT_START_OFFSET = 14f

    /**
     * Ruling 6 (same as iOS): a fast enough fling decides by its direction (even back across the drag);
     * otherwise a drag past the distance threshold decides by its side; otherwise the card returns.
     */
    fun decide(dx: Float, velocityX: Float, width: Float): ThrowDecision = when {
        abs(velocityX) >= VELOCITY -> if (velocityX > 0f) ThrowDecision.Right else ThrowDecision.Left
        width > 0f && abs(dx) >= width * DISTANCE_RATIO -> if (dx > 0f) ThrowDecision.Right else ThrowDecision.Left
        else -> ThrowDecision.Return
    }

    /** The card's rotation while it follows the finger: `dx / W × 16°`. */
    fun rotationFor(dx: Float, width: Float): Float = if (width <= 0f) 0f else dx / width * MAX_ROTATION

    /** The rest of the 340 ms exit for a throw continued from [dx] (the share of [EXIT_DISTANCE] left). */
    fun remainingMillis(dx: Float): Int =
        (EXIT_MILLIS * (1f - abs(dx) / EXIT_DISTANCE)).toInt().coerceIn(0, EXIT_MILLIS)
}
