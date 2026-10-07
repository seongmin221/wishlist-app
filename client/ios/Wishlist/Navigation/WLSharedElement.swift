import SwiftUI
import UIKit
import Observation

// 공유 요소 전환의 원래 쪽·다음 화면 쪽 표시와 전환 중 그리기.
//
// matchedGeometryEffect를 쓰지 않는다: 상태 전환 한 번을 애니메이션 한 번으로만 움직여 손가락 진행값으로 되감을 수 없다
// (Task 5 spike). 대신 "원래 자리 사각형 ↔ 상세 자리 사각형"을 phase 값 하나로 보간해 그린다.
// - 사진: phase 0(원래 자리) → 1(상세 자리). 모서리 20 유지.
// 사진이 없는 이동은 공유 요소 없이 가로 밀기다(`WLNavHost`의 `WLSlideOffset`).
//
// 사각형은 스크롤마다 올리지 않는다. 요소 뒤에 둔 UIKit 탐침(`WLFrameProbe`)을 key로 등록해 두고, 전환을 시작하는 순간에만
// window 좌표를 읽는다(push 때 원래 자리·상세 자리, pop·끌기 시작 때 다시). key는 탭 이름을 앞에 둔 항목 id다.

/// 원래 요소·상세 자리의 탐침과 사진 그리기. 관찰하지 않는 저장소다(값이 바뀌어도 화면을 다시 그리지 않는다).
@Observable
final class WLSourceVisibility { var hidden = false }

final class WLSharedRegistry {
    private final class WeakView {
        weak var view: UIView?
        init(_ view: UIView) { self.view = view }
    }

    private var probes: [String: WeakView] = [:]
    private var photos: [String: () -> AnyView] = [:]
    private var visibility: [String: WLSourceVisibility] = [:]

    func sourceVisibility(_ key: String) -> WLSourceVisibility {
        if let state = visibility[key] { return state }
        let state = WLSourceVisibility()
        visibility[key] = state
        return state
    }

    func setHidden(_ key: String, _ hidden: Bool) { visibility[key]?.hidden = hidden }

    static func targetKey(_ entryID: Int) -> String { "target:\(entryID)" }

    func register(_ key: String, view: UIView) { probes[key] = WeakView(view) }

    func unregister(_ key: String, view: UIView, releaseVisibility: Bool = false) {
        if probes[key]?.view === view {
            probes[key] = nil
            photos[key] = nil
        }
        // window에서 잠깐 빠져도 같은 probe의 관찰 객체는 유지한다. 최종 해제만 관찰 객체를 버린다.
        if releaseVisibility, probes[key]?.view == nil { visibility[key] = nil }
    }

    /// window 좌표 사각형. 아직 window에 없거나 크기가 0이면 nil.
    func frame(_ key: String?) -> CGRect? {
        guard let key, let view = probes[key]?.view, view.window != nil else { return nil }
        let rect = view.convert(view.bounds, to: nil)
        return rect.width > 0 && rect.height > 0 ? rect : nil
    }

    func setPhoto(_ key: String, _ builder: @escaping () -> AnyView) { photos[key] = builder }
    func photo(_ key: String?) -> AnyView? { key.flatMap { photos[$0] }?() }
}

/// 요소 뒤에 까는 보이지 않는 UIKit 뷰. 자기 자리를 key로 등록만 하고 그리거나 터치·접근성에 끼어들지 않는다.
struct WLFrameProbe: UIViewRepresentable {
    let key: String
    let registry: WLSharedRegistry?
    var photo: (() -> AnyView)? = nil

    func makeUIView(context: Context) -> ProbeView {
        let view = ProbeView()
        view.isUserInteractionEnabled = false
        view.isAccessibilityElement = false
        view.accessibilityElementsHidden = true
        view.key = key
        view.registry = registry
        view.photo = photo
        return view
    }

    func updateUIView(_ view: ProbeView, context: Context) {
        if view.key != key || view.registry !== registry {
            if let old = view.key { view.registry?.unregister(old, view: view, releaseVisibility: true) }
        }
        view.key = key
        view.registry = registry
        view.photo = photo
        view.attach()
    }

    static func dismantleUIView(_ view: ProbeView, coordinator: ()) {
        if let key = view.key { view.registry?.unregister(key, view: view, releaseVisibility: true) }
    }

    final class ProbeView: UIView {
        var key: String?
        weak var registry: WLSharedRegistry?
        var photo: (() -> AnyView)?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            attach()
        }

        func attach() {
            guard let key else { return }
            if window != nil {
                registry?.register(key, view: self)
                if let photo { registry?.setPhoto(key, photo) }
            } else { registry?.unregister(key, view: self) }
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
    /// 앱 루트가 motion을 넣는 자리. key path 쓰기는 getter를 먼저 부르므로 넣는 쪽은 optional이어야 한다.
    var wlNavMotionStorage: WLNavMotion? {
        get { self[WLNavMotionKey.self] }
        set { self[WLNavMotionKey.self] = newValue }
    }

    /// 화면이 읽는 motion(읽기 전용). 앱 루트가 넣지 않았으면 바로 실패한다.
    var wlNavMotion: WLNavMotion {
        guard let motion = self[WLNavMotionKey.self] else {
            preconditionFailure("Inject wlNavMotion above OverlayHost at the app root.")
        }
        return motion
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
        let hidden = motion.registry.sourceVisibility(key).hidden
        content()
            .clipShape(photoShape)
            .opacity(hidden ? 0 : 1)
            .background(WLFrameProbe(key: key, registry: motion.registry, photo: { AnyView(content()) }))
    }
}

/// 사진 공유 요소의 다음 화면 쪽(상세 사진). 전환 동안은 숨고(전환 층이 그린다), 전환이 끝나면 여기서 그려 상세와 함께 스크롤된다.
struct WLSharedPhotoTarget<Content: View>: View {
    @ViewBuilder let content: () -> Content

    @Environment(\.wlNavMotion) private var motion
    @Environment(\.wlEntryID) private var entryID

    var body: some View {
        let animating = entryID.map { motion.channels($0).animating } ?? false
        content()
            .clipShape(photoShape)
            .opacity(animating ? 0 : 1)
            .background {
                if let entryID { WLFrameProbe(key: WLSharedRegistry.targetKey(entryID), registry: motion.registry) }
            }
    }
}

// MARK: - 전환 중 그리기

private func lerp(_ a: CGFloat, _ b: CGFloat, _ t: Double) -> CGFloat { a + (b - a) * CGFloat(t) }

private func lerp(_ a: CGRect, _ b: CGRect, _ t: Double) -> CGRect {
    CGRect(x: lerp(a.minX, b.minX, t), y: lerp(a.minY, b.minY, t), width: lerp(a.width, b.width, t), height: lerp(a.height, b.height, t))
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
