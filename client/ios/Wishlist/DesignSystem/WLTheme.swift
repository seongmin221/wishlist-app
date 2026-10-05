import SwiftUI

// 모든 컴포넌트의 색 원본은 `EnvironmentValues.wlColors`다. 하드코딩 색 없이 이 값만 읽는다.
// WLTheme가 시스템 colorScheme을 읽어 light/dark를 고르므로 앱 실행 중 테마가 바뀌면 전체가 다시 그려진다.

private struct WLColorsKey: EnvironmentKey {
    static let defaultValue = WLColors.light
}

private struct WLOnSheetKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var wlColors: WLColors {
        get { self[WLColorsKey.self] }
        set { self[WLColorsKey.self] = newValue }
    }

    /// 시트·확인창 위인지. 입력칸·보조 버튼·묶음 면이 카드색 대신 `sheetField`를 쓴다(디자인 결정 2026-10-03).
    var wlOnSheet: Bool {
        get { self[WLOnSheetKey.self] }
        set { self[WLOnSheetKey.self] = newValue }
    }
}

/// 앱 루트(OverlayHost 바깥)에 한 번 둔다.
struct WLTheme<Content: View>: View {
    @Environment(\.colorScheme) private var colorScheme
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        let colors = colorScheme == .dark ? WLColors.dark : WLColors.light
        content
            .environment(\.wlColors, colors)
            .tint(colors.text)
    }
}

extension View {
    /// 시트·확인창 안쪽 내용에 건다.
    func wlOnSheet(_ value: Bool = true) -> some View {
        environment(\.wlOnSheet, value)
    }
}

/// `wlText`를 한 번에 적용한 글자. 색은 기본으로 글자색 토큰.
struct WLText: View {
    let text: String
    let style: WLTextStyle
    let color: Color?
    let alignment: TextAlignment

    @Environment(\.wlColors) private var c

    init(_ text: String, _ style: WLTextStyle = .body, color: Color? = nil, alignment: TextAlignment = .leading) {
        self.text = text
        self.style = style
        self.color = color
        self.alignment = alignment
    }

    var body: some View {
        Text(text)
            .wlText(style)
            .foregroundStyle(color ?? c.text)
            .multilineTextAlignment(alignment)
    }
}
