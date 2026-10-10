import SwiftUI

/// The C3 feature screens' board icons (login, home, settings, share card): 24-grid line icons,
/// no fill, stroke = text color (Android `WLLineIcon`, same paths). `stroke` is the board SVG's
/// stroke-width on the 24 grid. Round caps and joins only for back (design fix §6) and the warning
/// (its dot); the rest use the SVG defaults (butt, miter). Also compiled into the share extension.
enum WLLineIcon {
    case back, chevronRight, settings, person, clock, sorting, external, heart, check, checkBold, clockBold, warning, more, trash
    // 원본 링크 웹뷰(FWebView·FWebViewShare·FWebViewExternal, Android와 같은 path).
    case close, lock, reload, share, globe, copy, apps, phone

    var stroke: CGFloat {
        switch self {
        case .sorting, .checkBold, .clockBold, .warning: 2
        default: 1.8
        }
    }

    var round: Bool { self == .back || self == .warning }

    private static func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: y) }

    private static func lines(_ segments: [(CGPoint, CGPoint)]) -> Path {
        var path = Path()
        for (a, b) in segments {
            path.move(to: a)
            path.addLine(to: b)
        }
        return path
    }

    private static func circle(_ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat) -> Path {
        Path(ellipseIn: CGRect(x: cx - r, y: cy - r, width: 2 * r, height: 2 * r))
    }

    /// SVG `rect x y width height rx`.
    private static func rect(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat, _ r: CGFloat) -> Path {
        Path(roundedRect: CGRect(x: x, y: y, width: w, height: h), cornerRadius: r, style: .circular)
    }

    /// SVG `a r r 0 0 sweep` from `from` to `to` (the small arc), `sweep` 1 = clockwise on screen.
    private static func arc(_ path: inout Path, from: CGPoint, to: CGPoint, radius r: CGFloat, sweep: Bool) {
        let mid = CGPoint(x: (from.x + to.x) / 2, y: (from.y + to.y) / 2)
        let dx = to.x - from.x, dy = to.y - from.y
        let chord = (dx * dx + dy * dy).squareRoot()
        let offset = (max(0, r * r - chord * chord / 4)).squareRoot()
        // The center sits on the chord's normal, on the side that makes the small arc turn the asked way.
        let sign: CGFloat = sweep ? 1 : -1
        let center = CGPoint(x: mid.x - sign * dy / chord * offset, y: mid.y + sign * dx / chord * offset)
        let start = atan2(from.y - center.y, from.x - center.x)
        let end = atan2(to.y - center.y, to.x - center.x)
        path.addArc(center: center, radius: r, startAngle: .radians(start), endAngle: .radians(end), clockwise: !sweep)
    }

    /// The board SVG paths on the 24 grid.
    var path: Path {
        let p = Self.p
        var path = Path()
        switch self {
        case .back: // M15 5l-7 7 7 7
            path.addLines([p(15, 5), p(8, 12), p(15, 19)])
        case .chevronRight: // M9 5l7 7-7 7
            path.addLines([p(9, 5), p(16, 12), p(9, 19)])
        case .settings:
            path.addPath(Self.circle(12, 12, 3))
            path.addPath(Self.lines([
                (p(12, 2), p(12, 5)), (p(12, 19), p(12, 22)), (p(2, 12), p(5, 12)), (p(19, 12), p(22, 12)),
                (p(4.9, 4.9), p(7, 7)), (p(17, 17), p(19.1, 19.1)), (p(4.9, 19.1), p(7, 17)), (p(17, 7), p(19.1, 4.9)),
            ]))
        case .person: // circle 12 8 4 + M4 20c1.5-4 4.5-6 8-6s6.5 2 8 6
            path.addPath(Self.circle(12, 8, 4))
            path.move(to: p(4, 20))
            path.addCurve(to: p(12, 14), control1: p(5.5, 16), control2: p(8.5, 14))
            path.addCurve(to: p(20, 20), control1: p(15.5, 14), control2: p(18.5, 16))
        case .clock, .clockBold: // circle 12 12 9 + M12 7v5l3 2
            path.addPath(Self.circle(12, 12, 9))
            path.addLines([p(12, 7), p(12, 12), p(15, 14)])
        case .sorting:
            path = Self.lines([
                (p(12, 3), p(12, 6)), (p(12, 18), p(12, 21)), (p(3, 12), p(6, 12)), (p(18, 12), p(21, 12)),
                (p(5.6, 5.6), p(7.7, 7.7)), (p(16.3, 16.3), p(18.4, 18.4)), (p(5.6, 18.4), p(7.7, 16.3)), (p(16.3, 7.7), p(18.4, 5.6)),
            ])
        case .external: // M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5
            path.addLines([p(14, 4), p(20, 4), p(20, 10)])
            path.move(to: p(20, 4))
            path.addLine(to: p(11, 13))
            path.move(to: p(18, 14))
            path.addLine(to: p(18, 19))
            path.addArc(tangent1End: p(18, 20), tangent2End: p(17, 20), radius: 1)
            path.addLine(to: p(5, 20))
            path.addArc(tangent1End: p(4, 20), tangent2End: p(4, 19), radius: 1)
            path.addLine(to: p(4, 7))
            path.addArc(tangent1End: p(4, 6), tangent2End: p(5, 6), radius: 1)
            path.addLine(to: p(10, 6))
        case .heart: // M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.5-7 10-7 10z
            path.move(to: p(12, 20))
            path.addCurve(to: p(5, 10), control1: p(12, 20), control2: p(5, 15.5))
            path.addArc(center: p(9, 10.045), radius: 4, startAngle: .degrees(180.64), endAngle: .degrees(318.6), clockwise: false)
            path.addArc(center: p(15, 10.045), radius: 4, startAngle: .degrees(221.4), endAngle: .degrees(359.36), clockwise: false)
            path.addCurve(to: p(12, 20), control1: p(19, 15.5), control2: p(12, 20))
            path.closeSubpath()
        case .check, .checkBold: // M5 12l5 5L20 7
            path.addLines([p(5, 12), p(10, 17), p(20, 7)])
        case .warning: // M12 4l9 16H3z M12 10v4M12 17h.01
            path.addLines([p(12, 4), p(21, 20), p(3, 20)])
            path.closeSubpath()
            path.addPath(Self.lines([(p(12, 10), p(12, 14)), (p(12, 17), p(12.01, 17))]))
        case .more: // circles 5/12/19, 12, r 1.5
            path.addPath(Self.circle(5, 12, 1.5))
            path.addPath(Self.circle(12, 12, 1.5))
            path.addPath(Self.circle(19, 12, 1.5))
        case .trash: // M5 7h14M10 7V5h4v2M7 7l1 13h8l1-13
            path.addPath(Self.lines([(p(5, 7), p(19, 7))]))
            // addLines starts its own subpath at its first point.
            path.addLines([p(10, 7), p(10, 5), p(14, 5), p(14, 7)])
            path.addLines([p(7, 7), p(8, 20), p(16, 20), p(17, 7)])
        case .close: // M6 6l12 12M18 6L6 18
            path = Self.lines([(p(6, 6), p(18, 18)), (p(18, 6), p(6, 18))])
        case .lock: // rect 5 11 14 9 r2 + M8 11V8a4 4 0 0 1 8 0v3
            path.addPath(Self.rect(5, 11, 14, 9, 2))
            path.move(to: p(8, 11))
            path.addLine(to: p(8, 8))
            Self.arc(&path, from: p(8, 8), to: p(16, 8), radius: 4, sweep: true)
            path.addLine(to: p(16, 11))
        case .reload: // M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5 (large arc: 315° clockwise around 12,12)
            path.move(to: p(20, 12))
            path.addArc(center: p(12, 12), radius: 8, startAngle: .degrees(0), endAngle: .degrees(315), clockwise: false)
            path.addLines([p(20, 4), p(20, 9), p(15, 9)])
        case .share: // M12 3v12M7 8l5-5 5 5M5 14v5a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-5
            path.addPath(Self.lines([(p(12, 3), p(12, 15))]))
            path.addLines([p(7, 8), p(12, 3), p(17, 8)])
            path.move(to: p(5, 14))
            path.addLine(to: p(5, 19))
            path.addArc(tangent1End: p(5, 20), tangent2End: p(6, 20), radius: 1)
            path.addLine(to: p(18, 20))
            path.addArc(tangent1End: p(19, 20), tangent2End: p(19, 19), radius: 1)
            path.addLine(to: p(19, 14))
        case .globe: // circle 12 12 9 + M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18
            path.addPath(Self.circle(12, 12, 9))
            path.addPath(Self.lines([(p(3, 12), p(21, 12))]))
            path.move(to: p(12, 3))
            Self.arc(&path, from: p(12, 3), to: p(12, 21), radius: 14, sweep: true)
            path.move(to: p(12, 3))
            Self.arc(&path, from: p(12, 3), to: p(12, 21), radius: 14, sweep: false)
        case .copy: // rect 8 8 12 12 r2 + M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3
            path.addPath(Self.rect(8, 8, 12, 12, 2))
            path.move(to: p(16, 8))
            path.addLine(to: p(16, 5))
            path.addArc(tangent1End: p(16, 4), tangent2End: p(15, 4), radius: 1)
            path.addLine(to: p(5, 4))
            path.addArc(tangent1End: p(4, 4), tangent2End: p(4, 5), radius: 1)
            path.addLine(to: p(4, 15))
            path.addArc(tangent1End: p(4, 16), tangent2End: p(5, 16), radius: 1)
            path.addLine(to: p(8, 16))
        case .apps: // four rect 7×7 r2
            for (x, y) in [(4.0, 4.0), (13.0, 4.0), (4.0, 13.0), (13.0, 13.0)] { path.addPath(Self.rect(x, y, 7, 7, 2)) }
        case .phone: // rect 7 3 10 18 r2 + M11 18h2
            path.addPath(Self.rect(7, 3, 10, 18, 2))
            path.addPath(Self.lines([(p(11, 18), p(13, 18))]))
        }
        return path
    }
}

/// One line icon drawn at `size` (decorative; the parent carries the accessibility label).
struct WLIcon: View {
    let icon: WLLineIcon
    var size: CGFloat = 20
    var color: Color?

    @Environment(\.wlColors) private var c

    init(_ icon: WLLineIcon, size: CGFloat = 20, color: Color? = nil) {
        self.icon = icon
        self.size = size
        self.color = color
    }

    var body: some View {
        let color = color ?? c.text
        let icon = icon
        Canvas { ctx, sz in
            let k = sz.width / 24
            ctx.scaleBy(x: k, y: k)
            let style = icon.round
                ? StrokeStyle(lineWidth: icon.stroke, lineCap: .round, lineJoin: .round)
                : StrokeStyle(lineWidth: icon.stroke)
            ctx.stroke(icon.path, with: .color(color), style: style)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}
