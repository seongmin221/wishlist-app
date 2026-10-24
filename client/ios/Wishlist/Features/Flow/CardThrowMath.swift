import CoreGraphics

/// Where a released review card goes (Android `ThrowDecision`): 확정 = right, 보류 = left.
enum ThrowDecision: Equatable { case right, left, `return` }

/// motion.md §4 card numbers (pure, same names and values as Android `CardThrowMath`).
/// - Release: a fling `|vx| ≥ 800/s` throws in its own direction (even against the distance), else
///   `|dx| ≥ W × 0.3` throws toward dx, else the card returns (250 `emphasized`).
/// - Drag rotation `dx / W × 16°`; the throw ends at ±480 and ±16° in 340 `throw`.
enum CardThrowMath {
    static let distanceRatio: CGFloat = 0.3
    static let flingVelocity: CGFloat = 800
    static let maxRotation: Double = 16
    static let exitDistance: CGFloat = 480
    static let exitMillis = 340
    static let returnMillis = 250
    /// The next stack starts 40 after the throw and rises in 300 `emphasized` (scale .94, y 14).
    static let nextDelayMillis = 40
    static let nextMillis = 300
    static let nextScale: CGFloat = 0.94
    static let nextOffset: CGFloat = 14

    static func decide(dx: CGFloat, velocityX: CGFloat, width: CGFloat) -> ThrowDecision {
        if abs(velocityX) >= flingVelocity { return velocityX > 0 ? .right : .left }
        if width > 0, abs(dx) >= width * distanceRatio { return dx > 0 ? .right : .left }
        return .return
    }

    /// Degrees for a drag of `dx` on a card `width` wide.
    static func rotation(dx: CGFloat, width: CGFloat) -> Double {
        guard width > 0 else { return 0 }
        return Double(dx / width) * maxRotation
    }

    /// The rest of the 340 throw from a released drag at `dx` (the part of ±480 still to go), truncated with
    /// no floor like Android `remainingMillis`: a card released at ±480 or beyond is already out (0).
    static func exitMillis(fromDx dx: CGFloat) -> Int {
        let left = max(0, 1 - abs(dx) / exitDistance)
        return min(exitMillis, Int(CGFloat(exitMillis) * left))
    }
}
