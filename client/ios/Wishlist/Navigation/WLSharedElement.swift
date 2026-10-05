import SwiftUI
import UIKit

// 공유 요소 전환의 원래 쪽·다음 화면 쪽 표시와 전환 중 그리기.
//
// matchedGeometryEffect를 쓰지 않는다: 상태 전환 한 번을 애니메이션 한 번으로만 움직여 손가락 진행값으로 되감을 수 없다
// (Task 5 spike). 대신 "원래 자리 사각형 ↔ 상세 자리 사각형"을 phase 값 하나로 보간해 그린다.
// - 사진: phase 0(원래 자리) → 1(상세 자리). 모서리 20 유지.
// - 자리 표시 면: phase 0(원래 요소) → 1(사방 3 떠오름·그림자) → 2(화면 전체·모서리 0·다음 화면 바탕색).
//
// 사각형은 스크롤마다 올리지 않는다. 요소 뒤에 둔 UIKit 탐침(`WLFrameProbe`)을 key로 등록해 두고, 전환을 시작하는 순간에만
// window 좌표를 읽는다(push 때 원래 자리·상세 자리, pop·끌기 시작 때 다시). key는 탭 이름을 앞에 둔 항목 id다.

/// 자리 표시 면의 시작 색. 테마가 바뀌어도 맞도록 색 대신 토큰 이름을 들고 그릴 때 푼다.
enum WLSurfaceFill {
    case chip, card
    case purpose(WLPurposeColor)

    func color(_ c: WLColors) -> Color {
        switch self {
        case .chip: c.chip
        case .card: c.card
        case .purpose(let p): p.face
        }
    }
}

/// 자리 표시 면의 시작 모서리. 알약은 높이의 절반.
enum WLSurfaceRadius {
    case pill
    case fixed(CGFloat)

    func value(for rect: CGRect) -> CGFloat {
        switch self {
        case .pill: rect.height / 2
        case .fixed(let r): r
        }
    }
}

struct WLSurfaceSpec {
    let fill: WLSurfaceFill
    let radius: WLSurfaceRadius
}

/// 원래 요소·상세 자리의 탐침과 사진 그리기·면 모양. 관찰하지 않는 저장소다(값이 바뀌어도 화면을 다시 그리지 않는다).
final class WLSharedRegistry {
    private final class WeakView {
        weak var view: UIView?
        init(_ view: UIView) { self.view = view }
    }

    private var probes: [String: WeakView] = [:]
    private var photos: [String: () -> AnyView] = [:]
    private var surfaces: [String: WLSurfaceSpec] = [:]

    static func targetKey(_ entryID: Int) -> String { "target:\(entryID)" }

    func register(_ key: String, view: UIView) { probes[key] = WeakView(view) }

    func unregister(_ key: String, view: UIView) {
        if probes[key]?.view === view { probes[key] = nil }
    }

    /// window 좌표 사각형. 아직 window에 없거나 크기가 0이면 nil.
    func frame(_ key: String?) -> CGRect? {
        guard let key, let view = probes[key]?.view, view.window != nil else { return nil }
        let rect = view.convert(view.bounds, to: nil)
        return rect.width > 0 && rect.height > 0 ? rect : nil
    }

    func setPhoto(_ key: String, _ builder: @escaping () -> AnyView) { photos[key] = builder }
    func photo(_ key: String?) -> AnyView? { key.flatMap { photos[$0] }?() }

    func setSurface(_ key: String, _ spec: WLSurfaceSpec) { surfaces[key] = spec }
    func surface(_ key: String?) -> WLSurfaceSpec? { key.flatMap { surfaces[$0] } }
}

/// 요소 뒤에 까는 보이지 않는 UIKit 뷰. 자기 자리를 key로 등록만 하고 그리거나 터치·접근성에 끼어들지 않는다.
struct WLFrameProbe: UIViewRepresentable {
    let key: String
    let registry: WLSharedRegistry?

    func makeUIView(context: Context) -> ProbeView {
        let view = ProbeView()
        view.isUserInteractionEnabled = false
        view.isAccessibilityElement = false
        view.accessibilityElementsHidden = true
        view.key = key
        view.registry = registry
        return view
    }

    func updateUIView(_ view: ProbeView, context: Context) {
        if view.key != key {
            if let old = view.key { view.registry?.unregister(old, view: view) }
            view.key = key
            view.attach()
        }
    }

    static func dismantleUIView(_ view: ProbeView, coordinator: ()) {
        if let key = view.key { view.registry?.unregister(key, view: view) }
    }

    final class ProbeView: UIView {
        var key: String?
        weak var registry: WLSharedRegistry?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            attach()
        }

        func attach() {
            guard let key else { return }
            if window != nil { registry?.register(key, view: self) } else { registry?.unregister(key, view: self) }
        }
    }
}

private struct WLNavMotionKey: EnvironmentKey {
    static let defaultValue: WLNavMotion? = nil
}

private struct WLEntryIDKey: EnvironmentKey {
    static let defaultValue: Int? = nil
}

extension EnvironmentValues {
    var wlNavMotion: WLNavMotion? {
        get { self[WLNavMotionKey.self] }
        set { self[WLNavMotionKey.self] = newValue }
    }

    /// 지금 그리는 스택 칸의 id(`WLNavHost`가 칸마다 넣는다).
    var wlEntryID: Int? {
        get { self[WLEntryIDKey.self] }
        set { self[WLEntryIDKey.self] = newValue }
    }
}

private let photoShape = RoundedRectangle(cornerRadius: WishlistTokens.Radius.m, style: .continuous)

/// 사진 공유 요소의 원래 쪽(목록 카드 사진). 전환 동안 이 자리는 비고(그리지 않음) 사진은 전환 층이 그린다.
struct WLSharedPhotoSource<Content: View>: View {
    let key: String
    @ViewBuilder let content: () -> Content

    @Environment(\.wlNavMotion) private var motion

    var body: some View {
        let hidden = motion?.hiddenSources.contains(key) ?? false
        content()
            .clipShape(photoShape)
            .opacity(hidden ? 0 : 1)
            .background(WLFrameProbe(key: key, registry: motion?.registry))
            .onAppear { motion?.registry.setPhoto(key) { AnyView(content()) } }
    }
}

/// 사진 공유 요소의 다음 화면 쪽(상세 사진). 전환 동안은 숨고(전환 층이 그린다), 전환이 끝나면 여기서 그려 상세와 함께 스크롤된다.
struct WLSharedPhotoTarget<Content: View>: View {
    @ViewBuilder let content: () -> Content

    @Environment(\.wlNavMotion) private var motion
    @Environment(\.wlEntryID) private var entryID

    var body: some View {
        let animating = entryID.flatMap { motion?.channels($0).animating } ?? false
        content()
            .clipShape(photoShape)
            .opacity(animating ? 0 : 1)
            .background {
                if let entryID { WLFrameProbe(key: WLSharedRegistry.targetKey(entryID), registry: motion?.registry) }
            }
    }
}

/// 사진 없는 요소(칩·카드·줄)를 누르면 커지는 자리 표시 면의 원래 쪽. 요소의 면 색·모서리를 넘긴다.
struct WLSharedSurfaceSource<Content: View>: View {
    let key: String
    let fill: WLSurfaceFill
    let radius: WLSurfaceRadius
    @ViewBuilder let content: () -> Content

    @Environment(\.wlNavMotion) private var motion

    var body: some View {
        content()
            .background(WLFrameProbe(key: key, registry: motion?.registry))
            .onAppear { motion?.registry.setSurface(key, WLSurfaceSpec(fill: fill, radius: radius)) }
    }
}

// MARK: - 전환 중 그리기

private func lerp(_ a: CGFloat, _ b: CGFloat, _ t: Double) -> CGFloat { a + (b - a) * CGFloat(t) }

private func lerp(_ a: CGRect, _ b: CGRect, _ t: Double) -> CGRect {
    CGRect(x: lerp(a.minX, b.minX, t), y: lerp(a.minY, b.minY, t), width: lerp(a.width, b.width, t), height: lerp(a.height, b.height, t))
}

private func mix(_ a: Color, _ b: Color, _ t: Double) -> Color {
    let ra = UIColor(a).cgColor.components ?? [0, 0, 0, 1]
    let rb = UIColor(b).cgColor.components ?? [0, 0, 0, 1]
    func ch(_ i: Int) -> Double { Double(lerp(ra[min(i, ra.count - 1)], rb[min(i, rb.count - 1)], t)) }
    return Color(.sRGB, red: ch(0), green: ch(1), blue: ch(2), opacity: 1)
}

/// 날아가는 사진(window 좌표 사각형을 phase로 보간). 모서리 20 유지.
struct WLPhotoFlight: View, Animatable {
    var phase: Double
    let source: CGRect
    let target: CGRect
    let photo: AnyView?

    @Environment(\.wlColors) private var c

    var animatableData: Double {
        get { phase }
        set { phase = newValue }
    }

    var body: some View {
        GeometryReader { proxy in
            let origin = proxy.frame(in: .global).origin
            let r = lerp(source, target, phase)
            Group {
                if let photo { photo } else { c.photo }
            }
            .frame(width: r.width, height: r.height)
            .clipShape(photoShape)
            .offset(x: r.minX - origin.x, y: r.minY - origin.y)
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// 자리 표시 면. 글자·아이콘은 싣지 않는다. phase 2에서 화면 전체를 다음 화면 바탕색으로 덮고, 그대로 그 화면의 바탕이 된다.
struct WLSurfaceBackdrop: View, Animatable {
    var phase: Double
    let source: CGRect
    let spec: WLSurfaceSpec

    @Environment(\.wlColors) private var c

    var animatableData: Double {
        get { phase }
        set { phase = newValue }
    }

    var body: some View {
        GeometryReader { proxy in
            let screenGlobal = proxy.frame(in: .global)
            let origin = screenGlobal.origin
            // 원래 자리를 모르면(탐침 없음) 화면 전체에서 시작해 바탕만 깔고 내용이 나타나게 한다.
            let from = source == .zero ? screenGlobal : source
            let outset = CGFloat(WishlistTokens.Motion.pushSurfaceLiftOutset)
            let lifted = from.insetBy(dx: -outset, dy: -outset)
            let lift = min(1, max(0, phase))
            let expand = min(1, max(0, phase - 1))
            let rect = phase <= 1 ? lerp(from, lifted, lift) : lerp(lifted, screenGlobal, expand)
            let radius = lerp(spec.radius.value(for: lifted), 0, expand)
            // 그림자: 떠오르며 생기고 커지며 사라진다(0 8 24 / .14).
            let shadow = lift * (1 - expand)
            RoundedRectangle(cornerRadius: min(radius, rect.height / 2), style: .continuous)
                .fill(mix(spec.fill.color(c), c.background, expand))
                .shadow(color: .black.opacity(WishlistTokens.Motion.pushSurfaceLiftShadowAlpha * shadow),
                        radius: WishlistTokens.Motion.pushSurfaceLiftShadowBlur / 2,
                        y: WishlistTokens.Motion.pushSurfaceLiftShadowY * shadow)
                .frame(width: rect.width, height: rect.height)
                .offset(x: rect.minX - origin.x, y: rect.minY - origin.y)
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
