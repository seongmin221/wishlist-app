import SwiftUI
import UIKit

/// 왼쪽 가장자리 끌어 뒤로. window에 `UIPanGestureRecognizer`를 달아 왼쪽 20pt 안에서 시작한 가로(오른쪽) 끌기만 받는다
/// (Task 5 spike 방식 B: SwiftUI 띠 제스처는 iOS 17에서 가로 스크롤에 졌다). 진행값은 끈 거리 / 화면 폭, 속도는 초당 진행값.
///
/// 규칙(spike 기록의 Task 7 구현 규칙):
/// - `shouldReceive`: 띠 안의 터치이고 `canBegin()`(전환 중이 아니고, 뒤로 갈 화면이 있고, 직접 그린 시트·확인창·메뉴·막이
///   떠 있지 않음)일 때만 받는다. 그 밑의 화면을 끌어서 뒤로 보내지 않는다.
/// - `shouldBegin`: 오른쪽으로, 가로가 세로보다 빠를 때만.
/// - 우선순위: 띠 안에서 시작한 터치에서는 이 인식기가 다른 모든 pan보다 먼저다. 스크롤 뷰의 pan과 다른 pan 인식기
///   (iOS 18부터는 SwiftUI `DragGesture`도 UIKit 인식기다)는 이 인식기가 실패할 때까지 기다린다(`shouldBeRequiredToFailBy`).
///   누르기(tap)는 기다리지 않는다: 누르기는 pan을 시작하지 않으므로 띠 안의 버튼도 그대로 눌린다. 슬라이더처럼 인식기 없이 터치를
///   직접 쫓는 컨트롤은 끌기가 시작되면 `cancelsTouchesInView = true`로 터치가 취소된다. 화면 좌우 여백이 20이라 띠 안에 그런
///   컨트롤을 두지 않는다.
/// - 뷰가 해제되거나 window가 nil이 되면 인식기를 window에서 뗀다(해제된 coordinator를 target으로 가진 인식기가 남지 않게).
struct WLEdgeBackGesture: UIViewRepresentable {
    let canBegin: () -> Bool
    let onBegin: () -> Bool
    let onChange: (Double) -> Void
    let onEnd: (_ progress: Double, _ velocity: Double) -> Void

    /// 띠 폭: 시스템 뒤로와 비슷한 20pt(토큰 간격 20).
    static var edgeWidth: CGFloat { WishlistTokens.Space.s20 }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> InstallerView {
        let view = InstallerView()
        view.isUserInteractionEnabled = false
        view.isAccessibilityElement = false
        view.accessibilityElementsHidden = true
        view.coordinator = context.coordinator
        context.coordinator.update(self)
        return view
    }

    func updateUIView(_ view: InstallerView, context: Context) {
        context.coordinator.update(self)
    }

    static func dismantleUIView(_ view: InstallerView, coordinator: Coordinator) {
        coordinator.detach()
    }

    final class InstallerView: UIView {
        weak var coordinator: Coordinator?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard let coordinator else { return }
            if let window { coordinator.attach(to: window) } else { coordinator.detach() }
        }
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        private let recognizer = UIPanGestureRecognizer()
        private var config: WLEdgeBackGesture?
        private var active = false

        override init() {
            super.init()
            recognizer.delegate = self
            recognizer.cancelsTouchesInView = true
            recognizer.maximumNumberOfTouches = 1
            recognizer.addTarget(self, action: #selector(handle(_:)))
        }

        func update(_ config: WLEdgeBackGesture) { self.config = config }

        func attach(to window: UIWindow) {
            guard recognizer.view !== window else { return }
            detach()
            window.addGestureRecognizer(recognizer)
        }

        func detach() {
            if active {
                active = false
                config?.onEnd(0, 0)
            }
            recognizer.view?.removeGestureRecognizer(recognizer)
        }

        @objc private func handle(_ g: UIPanGestureRecognizer) {
            guard let view = g.view, let config else { return }
            let width = max(1, view.bounds.width)
            let progress = Double(g.translation(in: view).x / width)
            switch g.state {
            case .began:
                active = config.onBegin()
                if active { config.onChange(progress) }
            case .changed:
                if active { config.onChange(progress) }
            case .ended:
                if active { config.onEnd(progress, Double(g.velocity(in: view).x / width)) }
                active = false
            case .cancelled, .failed:
                if active { config.onEnd(0, 0) }
                active = false
            default:
                break
            }
        }

        func gestureRecognizer(_ g: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            guard let view = g.view, let config, config.canBegin() else { return false }
            return touch.location(in: view).x <= WLEdgeBackGesture.edgeWidth
        }

        func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
            guard let pan = g as? UIPanGestureRecognizer, let view = pan.view, config?.canBegin() == true else { return false }
            let v = pan.velocity(in: view)
            return v.x > abs(v.y)
        }

        func gestureRecognizer(_ g: UIGestureRecognizer, shouldBeRequiredToFailBy other: UIGestureRecognizer) -> Bool {
            other is UIPanGestureRecognizer || other.view is UIScrollView
        }

        func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
            false
        }
    }
}
