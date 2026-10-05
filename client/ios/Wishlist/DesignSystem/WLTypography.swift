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
    /// Plex 700 18. 출처: FCategoryListL 상품 가격(18px/700).
    /// 주의: 이 폰트만 직접 쓰면 `.monospacedDigit()`가 빠진다. 가격에는 `wlText(.price)`를 쓴다.
    /// (Plex 숫자는 기본으로 모두 폭 600/1000이라 사실상 이미 tabular이다. 아래 WLTextStyle 주석 참고.)
    static let wlPrice = Font.custom(WLFontName.plexBold, size: 18)
    /// Plex 700 16. 출처: FPurposeHomeL 만들기·삭제 버튼(16px/700).
    static let wlButton = Font.custom(WLFontName.plexBold, size: 16)
}

/// 글꼴 메트릭(em 단위, units-per-em 1000). 2026-10-05 Python 3.9 표준 라이브러리로 TTF의 hhea/glyf를 직접 읽어 구했다.
/// - ascent/descent: hhea ascender 와 |descender|. 브라우저(보드)와 Compose(includeFontPadding=false)가 쓰는 값이고 lineGap은 쓰지 않는다.
/// - hangulDepth: 한글 음절 아래 끝이 기준선 아래로 내려가는 깊이(glyf yMin의 절댓값). 11172자 음절 전수 조사 결과
///   Do Hyeon: 받침 있는 글자 대부분이 -23(=0.023), 받침 없는 글자는 +82(기준선 위)라 가장 깊은 흔한 값 0.023을 쓴다.
///   Plex Regular/Medium/Bold: 중앙값 -143/-148/-155(최소 -166/-173/-181).
private struct WLFontMetrics {
    let ascent: CGFloat
    let descent: CGFloat
    let hangulDepth: CGFloat

    static let doHyeon = WLFontMetrics(ascent: 0.8, descent: 0.2, hangulDepth: 0.023)
    static let plexRegular = WLFontMetrics(ascent: 1.085, descent: 0.415, hangulDepth: 0.143)
    static let plexMedium = WLFontMetrics(ascent: 1.085, descent: 0.415, hangulDepth: 0.148)
    static let plexBold = WLFontMetrics(ascent: 1.085, descent: 0.415, hangulDepth: 0.155)
}

/// 글꼴 + 줄 높이(CSS line-height 배수) + tabular 여부. 이름은 Android `WLType`과 같다.
///
/// tabular: IBM Plex Sans KR은 GSUB에 `tnum` 기능이 없지만 숫자 0-9가 기본으로 모두 advance 600/1000이다(hmtx 확인).
/// 즉 이미 고정폭이라 `.monospacedDigit()`는 안전장치일 뿐 무해하다. (Do Hyeon 숫자는 비례폭이지만 가격에 쓰지 않는다.)
struct WLTextStyle {
    let font: Font
    let postScriptName: String
    let size: CGFloat
    /// 줄 높이 px. 보드의 line-height 배수 × 글자 크기.
    let lineHeight: CGFloat
    let tabular: Bool
    fileprivate let metrics: WLFontMetrics

    private init(_ font: Font, _ name: String, _ size: CGFloat, lineHeight: CGFloat, tabular: Bool = false, metrics: WLFontMetrics) {
        self.font = font
        self.postScriptName = name
        self.size = size
        self.lineHeight = lineHeight
        self.tabular = tabular
        self.metrics = metrics
    }

    /// 도현 28 · 한 줄 1.0.
    static let display28 = WLTextStyle(.wlDisplay28, WLFontName.doHyeon, 28, lineHeight: 28, metrics: .doHyeon)
    /// 도현 28 · 두 줄 이상 1.2(디자인 결정 2026-10-02).
    static let display28TwoLine = WLTextStyle(.wlDisplay28, WLFontName.doHyeon, 28, lineHeight: 33.6, metrics: .doHyeon)
    /// 도현 28 · 그 자리 편집 칸 전용. 줄 높이 24면 한글 아래 끝이 줄 상자 아래 약 2.96px라
    /// `wlUnderlined`의 밑줄이 줄 상자 바로 아래에 붙어 한글과 3px 떨어진다(디자인 결정 2026-10-04).
    static let display28Edit = WLTextStyle(.wlDisplay28, WLFontName.doHyeon, 28, lineHeight: 24, metrics: .doHyeon)
    /// 도현 20 · 한 줄 1.0.
    static let display20 = WLTextStyle(.wlDisplay20, WLFontName.doHyeon, 20, lineHeight: 20, metrics: .doHyeon)
    /// 도현 20 · 두 줄 1.2.
    static let display20TwoLine = WLTextStyle(.wlDisplay20, WLFontName.doHyeon, 20, lineHeight: 24, metrics: .doHyeon)
    /// 줄 높이 1.3은 보드에 없어 고른 값이다(보드 h1은 line-height 지정 없음).
    static let title = WLTextStyle(.wlTitle, WLFontName.plexBold, 22, lineHeight: 22 * 1.3, metrics: .plexBold)
    static let body = WLTextStyle(.wlBody, WLFontName.plexRegular, 14, lineHeight: 14 * 1.35, metrics: .plexRegular)
    static let bodyStrong = WLTextStyle(.wlBodyStrong, WLFontName.plexMedium, 14, lineHeight: 14 * 1.35, metrics: .plexMedium)
    static let label = WLTextStyle(.wlLabel, WLFontName.plexMedium, 12, lineHeight: 12 * 1.35, metrics: .plexMedium)
    static let price = WLTextStyle(.wlPrice, WLFontName.plexBold, 18, lineHeight: 18, tabular: true, metrics: .plexBold)
    /// 줄 높이 1.25는 보드에 없어 고른 값이다.
    static let button = WLTextStyle(.wlButton, WLFontName.plexBold, 16, lineHeight: 16 * 1.25, metrics: .plexBold)

    /// 한글 아래 끝에서 줄 상자 아래까지의 거리(pt). 줄 높이가 글꼴 내용 높이(ascent+descent)보다 작거나 크면
    /// 남는 높이의 절반이 위아래로 나뉜다(CSS half-leading, Compose Alignment.Center와 같은 모델).
    /// `scale`은 Dynamic Type 배율(글자 크기 비율)이다.
    func hangulBottomGap(scale: CGFloat = 1) -> CGFloat {
        let s = size * scale
        return (lineHeight * scale - (metrics.ascent + metrics.descent) * s) / 2 + (metrics.descent - metrics.hangulDepth) * s
    }

    /// 밑줄 윗면이 한글 아래 끝에서 이 거리(pt)만큼 떨어지게 한다(디자인 결정 2026-10-04).
    static let underlineGap: CGFloat = 3
}

private struct WLTextModifier: ViewModifier {
    let style: WLTextStyle
    // Font.custom(size:)이 Dynamic Type으로 커지는 것과 같은 곡선으로 크기를 얻는다.
    @ScaledMetric(relativeTo: .body) private var scaledSize: CGFloat = 1

    init(style: WLTextStyle) {
        self.style = style
        _scaledSize = ScaledMetric(wrappedValue: style.size, relativeTo: .body)
    }

    func body(content: Content) -> some View {
        let scale = scaledSize / style.size
        let natural = UIFont(name: style.postScriptName, size: scaledSize)?.lineHeight ?? scaledSize
        // SwiftUI Text 상자는 글꼴 자체 줄 높이라 한 줄 높이를 직접 못 정한다. 목표와의 차이(extra)를
        // 줄 사이 간격(lineSpacing)과 위아래 패딩(extra/2씩, 음수 허용)으로 나눠 N줄 높이가 정확히 N×lineHeight가 되게 한다.
        let extra = style.lineHeight * scale - natural
        let styled = content
            .font(style.font)
            .lineSpacing(extra)
            .padding(.vertical, extra / 2)
        if style.tabular {
            styled.monospacedDigit()
        } else {
            styled
        }
    }
}

private struct WLUnderlineModifier: ViewModifier {
    let style: WLTextStyle
    let color: Color
    let thickness: CGFloat
    @ScaledMetric(relativeTo: .body) private var scaledSize: CGFloat = 1

    init(style: WLTextStyle, color: Color, thickness: CGFloat) {
        self.style = style
        self.color = color
        self.thickness = thickness
        _scaledSize = ScaledMetric(wrappedValue: style.size, relativeTo: .body)
    }

    func body(content: Content) -> some View {
        let gap = style.hangulBottomGap(scale: scaledSize / style.size)
        // 밑줄 윗면 = 줄 상자 아래 + (3 - gap). gap > 3이면 밑줄이 상자 안쪽으로 올라온다.
        let top = WLTextStyle.underlineGap - gap
        content.overlay(alignment: .bottom) {
            Rectangle().fill(color).frame(height: thickness).offset(y: thickness + top)
        }
    }
}

extension View {
    /// 글꼴, 줄 높이, tabular 숫자를 한 번에 적용한다.
    func wlText(_ style: WLTextStyle) -> some View {
        modifier(WLTextModifier(style: style))
    }

    /// `wlText(style)` 뒤에 붙인다. 한글 아래 끝에서 3px 아래에 `thickness`(기본 1) 밑줄을 그린다.
    func wlUnderlined(_ style: WLTextStyle, color: Color, thickness: CGFloat = 1) -> some View {
        modifier(WLUnderlineModifier(style: style, color: color, thickness: thickness))
    }
}
