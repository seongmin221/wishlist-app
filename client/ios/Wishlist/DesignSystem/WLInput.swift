import SwiftUI

/// 길이 제한을 세는 단위. 기본은 글자 묶음(grapheme, 화면에 보이는 글자)이고, C5 카테고리 폼은 서버 계약대로
/// Unicode code point를 센다(이모지 하나 = 1, ZWJ 가족 이모지 = 여러 개).
enum WLInputLimitUnit { case grapheme, codePoint }

/// 입력칸: 테두리 없는 면(카드색, 시트·확인창 위는 sheetField)·모서리 m 20·높이 56(여러 줄이면 `minLines`로 늘어난다).
/// 위 라벨(13)과 오른쪽 보조 글자(예: "최대 40자")는 `label`·`hint`로 준다.
struct WLInput: View {
    @Binding var value: String
    var label: String?
    var hint: String?
    var placeholder = ""
    var singleLine = true
    var minLines = 1
    var maxLength = Int.max
    var limitUnit: WLInputLimitUnit = .grapheme
    var keyboard: UIKeyboardType = .default
    /// 키보드 완료(↵)에 불린다(한 줄 입력칸). nil이면 기본 동작(키보드 내림).
    var onSubmit: (() -> Void)?

    @Environment(\.wlColors) private var c
    @Environment(\.wlOnSheet) private var onSheet

    @State private var composition = WLInputComposition()

    private func reconcile() {
        guard !composition.isComposing else { return }
        let truncated = WLInput.truncate(value, maxLength: maxLength, unit: limitUnit)
        if value != truncated { value = truncated }
    }

    /// IME 조합이 끝난 글자에만 쓴다(조합 중에는 부르지 않는다). code point 단위는 상한 안에 들어가는
    /// 마지막 글자 묶음 경계에서 자른다: surrogate 쌍·ZWJ 묶음을 반쪽으로 남기지 않는다(상한 근처 이모지는 통째로 빠진다).
    static func truncate(_ text: String, maxLength: Int, unit: WLInputLimitUnit = .grapheme) -> String {
        let limit = max(0, maxLength)
        switch unit {
        case .grapheme:
            return String(text.prefix(limit))
        case .codePoint:
            if text.unicodeScalars.count <= limit { return text }
            var used = 0
            var end = text.startIndex
            for character in text {
                let n = character.unicodeScalars.count
                if used + n > limit { break }
                used += n
                end = text.index(after: end)
            }
            return String(text[..<end])
        }
    }

    var body: some View {
        let face = onSheet ? c.sheetField : c.card
        VStack(alignment: .leading, spacing: WishlistTokens.Space.s8) {
            if label != nil || hint != nil {
                HStack {
                    // 입력칸이 같은 라벨을 읽으므로 보이는 라벨은 접근성에서 뺀다(두 번 읽지 않게).
                    WLText(label ?? "", WLTextStyle.body.resized(13), color: c.textSecondary, maxLines: 1)
                        .accessibilityHidden(true)
                    Spacer(minLength: 0)
                    WLText(hint ?? "", .label, color: c.textSecondary, maxLines: 1)
                }
            }
            ZStack(alignment: singleLine ? .leading : .topLeading) {
                field
                    .font(WLTextStyle.button.font)
                    .foregroundStyle(c.text)
                    .tint(c.text)
                    .keyboardType(keyboard)
                    .accessibilityLabel(label ?? placeholder)
                    // 라벨이 따로 있으면 안내 글자(placeholder)는 힌트로 읽는다. 라벨이 없으면 라벨이 곧 안내 글자다.
                    .accessibilityHint(label != nil ? placeholder : "")
                    .background(WLInputCompositionProbe(composition: composition, onCommitted: reconcile))
            }
            .padding(.horizontal, 18)
            .padding(.vertical, singleLine ? 0 : 16)
            .frame(maxWidth: .infinity, minHeight: 56, alignment: singleLine ? .leading : .topLeading)
            .background(face, in: RoundedRectangle(cornerRadius: WishlistTokens.Radius.m, style: .continuous))
        }
        .onChange(of: value, initial: true) { _, _ in reconcile() }
        .onChange(of: maxLength) { _, _ in reconcile() }
        .onChange(of: limitUnit) { _, _ in reconcile() }
    }

    private var prompt: Text {
        Text(placeholder).font(WLTextStyle.buttonRegular.font).foregroundStyle(c.textSecondary)
    }

    @ViewBuilder private var field: some View {
        if singleLine {
            if let onSubmit {
                TextField("", text: $value, prompt: prompt).submitLabel(.done).onSubmit(onSubmit)
            } else {
                TextField("", text: $value, prompt: prompt)
            }
        } else {
            TextField("", text: $value, prompt: prompt, axis: .vertical).lineLimit(minLines...)
        }
    }
}

/// SwiftUI TextField의 marked text를 자르지 않는다. 조합이 끝난 알림에서 길이를 다시 맞춘다.
final class WLInputComposition {
    weak var probe: UIView?

    func owns(_ input: UIView?) -> Bool {
        guard let probe, let input, let window = probe.window, input.window === window else { return false }
        let fieldFrame = probe.convert(probe.bounds, to: window)
        let inputFrame = input.convert(input.bounds, to: window)
        return !fieldFrame.isEmpty && fieldFrame.intersects(inputFrame)
    }

    var isComposing: Bool {
        guard let window = probe?.window,
              let input = WLEditingInputMonitor.shared.focusedInput(in: window) as? (UIView & UITextInput) else { return false }
        return owns(input) && input.markedTextRange != nil
    }
}

private struct WLInputCompositionProbe: UIViewRepresentable {
    let composition: WLInputComposition
    let onCommitted: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.isUserInteractionEnabled = false
        view.isAccessibilityElement = false
        composition.probe = view
        context.coordinator.observe()
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {
        composition.probe = view
        context.coordinator.onCommitted = onCommitted
        context.coordinator.composition = composition
    }

    final class Coordinator {
        var onCommitted: (() -> Void)?
        weak var composition: WLInputComposition?
        private var subscription: UUID?

        func observe() {
            subscription = WLEditingInputMonitor.shared.subscribe { [weak self] input in
                guard self?.composition?.owns(input) == true else { return }
                // SwiftUI가 Binding에 새 문자열을 반영한 뒤 확인한다.
                DispatchQueue.main.async { self?.onCommitted?() }
            }
        }

        deinit { if let subscription { WLEditingInputMonitor.shared.unsubscribe(subscription) } }
    }
}

/// 전체 입력칸이 6개 UIKit 알림 관찰자를 공유한다. window별 첫 responder를 약하게 캐시해 키 입력마다 트리를 훑지 않는다.
private final class WLEditingInputMonitor {
    static let shared = WLEditingInputMonitor()
    private final class Focus {
        weak var input: UIView?
        init(_ input: UIView?) { self.input = input }
    }
    private let focused = NSMapTable<UIWindow, Focus>(keyOptions: .weakMemory, valueOptions: .strongMemory)
    private var observers: [NSObjectProtocol] = []
    private var callbacks: [UUID: (UIView) -> Void] = [:]

    private init() {
        let begin = [UITextField.textDidBeginEditingNotification, UITextView.textDidBeginEditingNotification]
        let end = [UITextField.textDidEndEditingNotification, UITextView.textDidEndEditingNotification]
        let change = [UITextField.textDidChangeNotification, UITextView.textDidChangeNotification]
        for name in begin + end + change {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] notification in
                guard let self, let input = notification.object as? UIView else { return }
                if let window = input.window {
                    if input.isFirstResponder { focused.setObject(Focus(input), forKey: window) }
                    else if end.contains(notification.name), focused.object(forKey: window)?.input === input {
                        focused.setObject(Focus(nil), forKey: window)
                    }
                }
                if !begin.contains(notification.name) {
                    for callback in Array(callbacks.values) { callback(input) }
                }
            })
        }
    }

    func focusedInput(in window: UIWindow) -> UIView? {
        if let focus = focused.object(forKey: window) { return focus.input }
        func find(in view: UIView) -> UIView? {
            if view.isFirstResponder { return view }
            for child in view.subviews { if let responder = find(in: child) { return responder } }
            return nil
        }
        let input = find(in: window)
        focused.setObject(Focus(input), forKey: window)
        return input
    }

    func subscribe(_ callback: @escaping (UIView) -> Void) -> UUID {
        let id = UUID()
        callbacks[id] = callback
        return id
    }

    func unsubscribe(_ id: UUID) { callbacks[id] = nil }
}
