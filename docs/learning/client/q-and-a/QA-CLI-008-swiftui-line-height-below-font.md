# QA-CLI-008: SwiftUI에서 글꼴보다 작은 줄 높이를 여러 줄에 주는 방법

> 2026-10-06 · C1 최종 리뷰 수정

## 질문

IBM Plex Sans KR 본문(14, 줄 높이 1.35 = 18.9pt)을 iOS에서 세 줄로 그리면 Android·보드(56.7)보다 높게(61) 나오는 이유와, 공개 API만으로 고치는 방법은?

## 답변

- Plex는 글꼴 자체 줄 높이(ascent 1.085 + descent 0.415 = 1.5em, 14pt에서 21pt)가 목표보다 크다. 이전 `wlText`는 차이(−2.1)를 `lineSpacing`과 위아래 padding으로 나눴는데, SwiftUI는 **음수 `lineSpacing`을 0으로 자른다**. 위아래 padding은 한 줄 상자만 맞추므로 줄이 하나 늘 때마다 약 2pt씩 커졌다(iOS 17.5·26.5 모두, title 3줄 94.7 → 목표 85.8).
- 시도했지만 쓰지 않은 것:
  - `Text(AttributedString)` + 문단 스타일: SwiftUI `Text`는 `NSParagraphStyle` 줄 높이를 무시한다.
  - iOS 26 `.lineHeight(.exact(points:))`: 상자 높이는 맞지만 도현은 줄 높이와 상관없이 기준선이 약 28pt에 놓여 6–8pt 아래로 가고, 줄 상자 밖 잉크를 잘랐다(display28Edit에서 글자 높이 19 → 15). 글꼴 메트릭으로 정한 보정 하나로 맞출 수 없었다.
  - `EnvironmentValues._lineHeightMultiple`: 결과는 맞지만 공개 문서에 없는 `_` API라 버렸다.
- 쓴 방법: 여러 줄일 수 있는 글자는 UILabel(`UIViewRepresentable`)로 그린다. `NSMutableParagraphStyle.minimumLineHeight = maximumLineHeight = 줄 높이`로 모든 줄이 줄 높이가 되고, TextKit은 남는(모자란) 높이를 줄 위쪽에서 더하므로(뺀다) `baselineOffset = (줄 높이 − 글꼴 줄 높이) / 2`로 CSS처럼 가운데에 둔다. 크기는 `sizeThatFits(_:uiView:context:)`, 줄 수·말줄임·정렬은 SwiftUI environment(`lineLimit`, `truncationMode`, `multilineTextAlignment`), Dynamic Type은 `@ScaledMetric`으로 받는다. 접근성은 UILabel을 숨기고 `accessibilityRepresentation { Text(text) }`로 맡겨 정적 글자 하나로 읽히고 바깥 `.isHeader`·`accessibilityHidden`이 그대로 먹는다.
- `.lineLimit(1)` 글자는 SwiftUI `Text` + 위아래 padding(한 줄은 정확)으로 둬 `ViewThatFits`·`minimumScaleFactor`(가격)가 그대로 동작한다. `TextField`도 같은 한 줄 방식이다.

## 근거

- iOS 시뮬레이터 측정(iPhone 17 Pro iOS 26.5, iPhone 15 Pro iOS 17.5), `client/ios/WishlistTests/WLTypographyTests.swift`
- [디자인 시스템과 앱 뼈대 — 서체와 글자 스타일](../../../architecture/client/design-system.md#서체와-글자-스타일)
