import SwiftUI
import UIKit

// 서체: Do Hyeon, IBM Plex Sans KR 400·500·700 (OFL). Info.plist의 UIAppFonts로 등록한다.
// 두 플랫폼 공통 스타일 이름: display28, display20, title, body, bodyStrong, label, price, button.
// px 값은 보드 HTML(design/handoff/screens/boards)의 해당 요소 값이다.

private enum WLFontName {
    static let doHyeon = "DoHyeon-Regular"
    static let plexRegular = "IBMPlexSansKR-Regular"
    static let plexMedium = "IBMPlexSansKR-Medium"
    static let plexBold = "IBMPlexSansKR-Bold"
}

extension Font {
    /// 도현 28. 출처: FPurposeHomeL h1 / 목적 카드 이름(28px).
    static let wlDisplay28 = Font.custom(WLFontName.doHyeon, size: 28)
    /// 도현 20. 출처: FHomeL 비교 중인 목적 카드 이름(20px).
    static let wlDisplay20 = Font.custom(WLFontName.doHyeon, size: 20)
    /// Plex 700 22. 출처: FCategoryListL h1(22px/700). 시트 제목 20/700(FPurposeHomeL h2)은 크기만 줄여 쓴다.
    static let wlTitle = Font.custom(WLFontName.plexBold, size: 22)
    /// Plex 400 14. 출처: FCategoryListL 상품명(14px, line-height 1.35).
    static let wlBody = Font.custom(WLFontName.plexRegular, size: 14)
    /// Plex 500 14. 출처: FHomeL 상품 도메인 줄(14px/500).
    static let wlBodyStrong = Font.custom(WLFontName.plexMedium, size: 14)
    /// Plex 500 12, 자간 0. 출처: FCategoryListL 메타 줄(12px/500).
    static let wlLabel = Font.custom(WLFontName.plexMedium, size: 12)
    /// Plex 700 18. 출처: FCategoryListL 상품 가격(18px/700). tabular는 `wlText(.price)`가 `.monospacedDigit()`로 건다.
    static let wlPrice = Font.custom(WLFontName.plexBold, size: 18)
    /// Plex 700 16. 출처: FPurposeHomeL 만들기·삭제 버튼(16px/700).
    static let wlButton = Font.custom(WLFontName.plexBold, size: 16)
}

/// 글꼴 + 줄 높이(CSS line-height 배수) + tabular 여부. 이름은 Android `WLType`과 같다.
struct WLTextStyle {
    let font: Font
    let postScriptName: String
    let size: CGFloat
    /// 줄 높이 px. 보드의 line-height 배수 × 글자 크기.
    let lineHeight: CGFloat
    let tabular: Bool

    private init(_ font: Font, _ name: String, _ size: CGFloat, lineHeight: CGFloat, tabular: Bool = false) {
        self.font = font
        self.postScriptName = name
        self.size = size
        self.lineHeight = lineHeight
        self.tabular = tabular
    }

    /// 도현 28 · 한 줄 1.0.
    static let display28 = WLTextStyle(.wlDisplay28, WLFontName.doHyeon, 28, lineHeight: 28)
    /// 도현 28 · 두 줄 이상 1.2(디자인 결정 2026-10-02).
    static let display28TwoLine = WLTextStyle(.wlDisplay28, WLFontName.doHyeon, 28, lineHeight: 33.6)
    /// 도현 28 · 그 자리 편집 칸 전용. 한글 아래 끝 ↔ 밑줄 3px 규칙(디자인 결정 2026-10-04)에 따라 줄 높이 24.
    /// 아래 패딩 보정은 편집 칸 컴포넌트에서 한다.
    static let display28Edit = WLTextStyle(.wlDisplay28, WLFontName.doHyeon, 28, lineHeight: 24)
    /// 도현 20 · 한 줄 1.0.
    static let display20 = WLTextStyle(.wlDisplay20, WLFontName.doHyeon, 20, lineHeight: 20)
    /// 도현 20 · 두 줄 1.2.
    static let display20TwoLine = WLTextStyle(.wlDisplay20, WLFontName.doHyeon, 20, lineHeight: 24)
    static let title = WLTextStyle(.wlTitle, WLFontName.plexBold, 22, lineHeight: 22 * 1.3)
    static let body = WLTextStyle(.wlBody, WLFontName.plexRegular, 14, lineHeight: 14 * 1.35)
    static let bodyStrong = WLTextStyle(.wlBodyStrong, WLFontName.plexMedium, 14, lineHeight: 14 * 1.35)
    static let label = WLTextStyle(.wlLabel, WLFontName.plexMedium, 12, lineHeight: 12 * 1.35)
    static let price = WLTextStyle(.wlPrice, WLFontName.plexBold, 18, lineHeight: 18, tabular: true)
    static let button = WLTextStyle(.wlButton, WLFontName.plexBold, 16, lineHeight: 16 * 1.25)
}

private struct WLTextModifier: ViewModifier {
    let style: WLTextStyle

    func body(content: Content) -> some View {
        // SwiftUI의 행간은 글꼴 자체 줄 높이에 더해지므로 (목표 줄 높이 − 글꼴 줄 높이)를 준다. 음수도 허용된다.
        let natural = UIFont(name: style.postScriptName, size: style.size)?.lineHeight ?? style.size
        let spacing = style.lineHeight - natural
        let styled = content.font(style.font).lineSpacing(spacing)
        if style.tabular {
            styled.monospacedDigit()
        } else {
            styled
        }
    }
}

extension View {
    /// 글꼴, 줄 높이, tabular 숫자를 한 번에 적용한다.
    func wlText(_ style: WLTextStyle) -> some View {
        modifier(WLTextModifier(style: style))
    }
}
