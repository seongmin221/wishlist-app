# QA-CLI-008: SwiftUI에서 글꼴보다 작은 줄 높이를 여러 줄에 주는 방법

> 2026-10-06 · C1 최종 리뷰 수정

## 질문

IBM Plex Sans KR 본문(14, 줄 높이 1.35 = 18.9pt)을 iOS에서 세 줄로 그리면 Android·보드(56.7)보다 높게(61) 나오는 이유와 고치는 방법은?

## 답변

- Plex는 글꼴 자체 줄 높이(ascent 1.085 + descent 0.415 = 1.5em, 14pt에서 21pt)가 목표보다 크다. 이전 `wlText`는 차이(−2.1)를 `lineSpacing`과 위아래 padding으로 나눴는데, SwiftUI는 **음수 `lineSpacing`을 0으로 자른다**. 위아래 padding은 한 줄 칸만 맞추므로 줄이 하나 늘 때마다 약 2pt씩 커졌다(iOS 17.5·26.5 모두, title 3줄 95 → 목표 86).
- `EnvironmentValues._lineHeightMultiple`(iOS 14+, 공개 문서에 없는 `_` 접두 API)에 `목표 줄 높이 ÷ 글꼴 줄 높이`를 주면 한 줄·여러 줄 모두 정확히 N × 줄 높이가 된다(1보다 작아도 된다). 다만 글자가 줄 안에서 가운데가 아니라 차이의 절반만큼 치우치므로 `offset(y: −차이/2)`로 되돌린다. `offset`은 레이아웃을 바꾸지 않는다.
- iOS 26의 `.lineHeight(.exact(points:))`도 칸 높이는 같지만 글자 위치가 스타일마다 달라(도현은 위 끝에 붙음) 쓰지 않았다.
- `TextField`에는 배수가 먹지 않아 한 줄 입력칸은 위아래 padding 방식(`wlFieldText`)을 유지한다.
- 공개 문서에 없는 API이므로 `WLTypographyTests`가 한 줄·세 줄 칸 높이와 글자 위치(한 줄 padding 방식과 0.5pt 이내)를 시뮬레이터에서 잰다. 새 iOS에서 깨지면 이 테스트가 먼저 실패한다.

## 근거

- iOS 시뮬레이터 측정(iPhone 17 Pro iOS 26.5, iPhone 15 Pro iOS 17.5), `client/ios/WishlistTests/WLTypographyTests.swift`
- [디자인 시스템과 앱 뼈대 — 서체와 글자 스타일](../../../architecture/client/design-system.md#서체와-글자-스타일)
