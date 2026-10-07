# ADR-028: iOS 화면 이동은 NavigationStack 없는 자체 라우터로 구현한다

> 상태: **확정(조건부)** · 날짜: 2026-10-06 · 영역: **client(iOS)** · 관련: [ADR-027](ADR-027-client-implementation-strategy.md) 재검토 조건, [iOS 라우터 spike 결과](ios-router-spike-2026-10-05.md)

## 맥락

모션 명세([motion.md](../../../../design/handoff/interactions/motion.md) 2·3절)는 사진 공유 요소 이동 420/360 `emphasized`, 사진 없는 이동의 떠오름 80 → 화면 전체 420(색·모서리 보간, 내용 190 뒤 230), 탭별 스택 유지와 끌어서 뒤로(진행만큼 되감기, 50% 확정)를 요구한다. SwiftUI `NavigationStack`의 전환은 iOS 18 이상 `.navigationTransition(.zoom)`이 전부이고 시간·곡선·면 보간을 정할 수 없으며, iOS 17에는 공유 요소가 없다. ADR-027은 자체 라우터를 C1 spike로 먼저 확인하고, 시스템 스와이프 뒤로·VoiceOver 요구를 못 맞추면 `NavigationStack`으로 내려가기로 했다.

## 결정

- `TabView`·`NavigationStack`·`.sheet`·`matchedGeometryEffect`를 쓰지 않는다. `@Observable WLNavigator`(탭별 `[WLRoute]` 스택, Android `WLNavigator`와 같은 규칙)와 `WLNavHost`가 모든 스택 칸을 한 `ZStack`에 그린다.
- 공유 요소는 원래 자리·상세 자리 사각형을 phase 하나로 보간하는 전환 층으로 그린다. 끌기 진행값으로 같은 전환을 되감을 수 있다.
- 끌어서 뒤로는 window 수준 `UIPanGestureRecognizer`(왼쪽 20pt, 가로 우세, 스크롤 pan이 실패를 기다림)다. SwiftUI 제스처 띠는 iOS 17에서 가로 스크롤에 져서 채택하지 않았다.
- 접근성은 가려진 층마다 숨기고(`wlAccessibilityCovered`), 상세 칸 escape가 `pop()`이다. `.isModal`과 조상의 맨 `accessibilityHidden(false)`는 쓰지 않는다.
- **조건부 진행(사용자 결정 2026-10-06):** spike의 (a) 공유 요소, (b) window pan 끌기, (c) 숨김·escape 연결, (d) 탭별 스택이 iOS 17.5·26.5 시뮬레이터에서 통과했다. 실기기 VoiceOver(push 뒤 포커스 이동, 두 손가락 문지르기 뒤로)와 손가락 손맛은 C1에서 기기가 없어 미검증이며, 사용자가 실기기로 확인한다.

## 이유와 trade-off

- 모션 명세의 시간표를 두 플랫폼에서 같게 만들 수 있다. Android도 Navigation Compose 없이 같은 상태 기계를 쓰므로 규칙(전환 중 입력 무시, 루트 pop false, 재선택 맨 위)을 같은 테스트 사례로 확인한다.
- 대신 시스템이 주던 것을 직접 유지해야 한다: 가장자리 뒤로 제스처와 스크롤의 우선순위, VoiceOver 트리 관리와 escape, 상태 복원. SwiftUI 접근성 트리의 동작(접힌 컨테이너, 숨김 상속)에 기대는 부분이 있어 OS 업데이트마다 다시 확인해야 한다.
- 모든 칸을 살려 두므로 스택이 깊어지면 메모리가 늘어난다. MVP 화면 깊이(대부분 2 이하)에서는 문제가 되지 않는다고 본다.

## 재검토 조건

- 실기기에서 push 뒤 VoiceOver 포커스가 상세로 옮겨 가지 않으면 먼저 전환 끝에 `UIAccessibility.post(notification: .screenChanged, argument:)`를 보낸다. 그래도 escape·포커스를 맞출 수 없거나 손맛이 시스템 뒤로와 크게 다르면 `NavigationStack` + `.navigationTransition(.zoom)`(iOS 18 이상) / 페이드(iOS 17)로 내려가고, 디자인과의 차이를 사용자에게 묻고 디자인 결정에 기록한다(차이 목록은 spike 기록의 "참고").
- 시스템 전환이 시간·곡선·면 보간을 지정할 수 있게 되면 다시 비교한다.

## 관련 문서

- [디자인 시스템과 앱 뼈대](../../../architecture/client/design-system.md), [iOS 구조](../../../architecture/client/ios.md)
- [QA-CLI-006](../../../learning/client/q-and-a/QA-CLI-006-swiftui-accessibility-hiding.md), [QA-CLI-007](../../../learning/client/q-and-a/QA-CLI-007-swiftui-gesture-vs-scrollview.md)
