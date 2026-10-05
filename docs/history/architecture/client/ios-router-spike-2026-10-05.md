# iOS 라우터 spike 결과

> 상태: **기록** · 날짜: 2026-10-05 · 영역: **client(iOS)** · 관련: [ADR-027](ADR-027-client-implementation-strategy.md) 재검토 조건

## 질문

`NavigationStack` 없이 탭별 `[route]` 상태 + `ZStack` 오버레이로 다음을 iOS 17과 26에서 만족하는가?

- (a) 사진·면 공유 요소 push/pop 420/360 ([motion.md §2](../../../../design/handoff/interactions/motion.md))
- (b) 왼쪽 가장자리 끌어 뒤로: 진행만큼 원래 자리로 줄어들고, 50% 이상 또는 빠르게 놓으면 확정, 아니면 되돌림
- (c) VoiceOver: push 뒤 포커스가 상세로 옮겨 가고, 두 손가락 문지르기(escape)로 뒤로 가며, 가려진 화면에 닿지 않는다
- (d) 탭마다 스택과 스크롤 위치를 기억한다(motion.md §3)

## 방법

- 화면: `client/ios/Wishlist/Navigation/Spike/RouterSpikeView.swift`(DEBUG 전용, 실행 인자 `-RouterSpike`). 탭 2개(홈·카테고리), 사진 카드 1개(→ 탭 바 없는 상세), 칩 1개(→ 탭 바를 유지하는 목록), 상세 1종. 상세에는 충돌 확인용 가로·세로 `ScrollView`가 있다.
- 상태: `@Observable SpikeRouter`가 탭별 스택과 맨 위 화면의 `phase`(공유 요소 위치)·`content`(내용 불투명도)를 가진다. 시간·곡선은 모두 `WishlistTokens.Motion`·`Curve` 생성 상수를 쓴다.
- 공유 요소: `matchedGeometryEffect` 대신 원래 자리와 상세 자리의 전역 사각형(`PreferenceKey`로 수집)을 `phase` 하나로 보간해 오버레이에 그린다(`Animatable` 뷰). 사진은 0→1, 면은 0(요소)→1(떠오름 3pt·그림자)→2(화면 전체·모서리 0·바탕색). `matchedGeometryEffect`는 상태 전환 한 번을 애니메이션 한 번으로만 움직여 손가락 진행값으로 되감을 수 없어서다.
- 끌어서 뒤로: 두 방식을 비교했다.
  - A. SwiftUI: 상세 맨 위 왼쪽 20pt 띠에 `DragGesture`(`-SpikeEdgeSwiftUI`).
  - B. UIKit(기본): window에 `UIPanGestureRecognizer`를 달고 왼쪽 20pt 안에서 시작한 가로 끌기만 받는다. `shouldBeRequiredToFailBy`로 스크롤 뷰의 pan이 이 인식기의 실패를 기다리게 한다(시스템 `interactivePopGesture`와 같은 구조).
- 조작: Orca에 들어 있는 serve-sim의 WebSocket으로 터치(begin/move/end)를 넣었다. 결과는 serve-sim `/ax` 접근성 트리(제목·버튼 목록)로 판정하고, `simctl io recordVideo` 영상에서 25·10fps 프레임 띠를 뽑아 움직임을 확인했다.
- escape: 시뮬레이터에는 VoiceOver가 없다. 대신 DEBUG 보조(`-SpikeEmulateEscape`)가 push가 끝나고 0.5초 뒤 상세 안 글자 요소부터 `accessibilityContainer`를 따라 올라가며 `accessibilityPerformEscape()`를 보낸다. VoiceOver가 escape를 전달하는 순서를 그대로 흉내 낸 것이고 실제 제스처는 아니다.

## 환경

Xcode 26.6(17F113), macOS 26.2. 시뮬레이터 iPhone 15 Pro · iOS 17.5(393×852), iPhone 17 Pro · iOS 26.5(402×874). Debug 빌드, 라이트 모드.

## 결과

증거 파일은 `.superpowers/sdd/2026-10-05-client-c1-design-system/media/ios-spike/`(git 제외)에 있고 이름 앞의 `ios17`·`ios26`이 OS다.

| 항목 | iOS 17.5 | iOS 26.5 | 증거 |
| --- | --- | --- | --- |
| (a) 사진 push 420 / pop 360 | 통과 | 통과 | `*-a-push-pop.mov`, `*-a-strip-photo-push/pop.png`, `*-a-photo-detail.png` |
| (a) 면 떠오름 80 → 커짐 420 / 뒤로 360 → 내려앉음 80 | 통과 | 통과 | `*-a-strip-surface-push/pop.png`, `*-a-surface-detail.png` |
| (b) 끌어서 뒤로: 30% 되돌림·70% 확정·25% 빠르게 확정(사진), 35% 되돌림·65% 확정(면) — 방식 B | 통과 | 통과 | `*-b-edge-back.mov/.txt`, `*-b-strip-*.png` |
| (b) 가로 스크롤 위 가장자리 끌기 → 뒤로, 가운데 끌기 → 스크롤 — 방식 B | 통과 | 통과 | `*-uikit-edge-b-edge-scroll.mov/.txt` |
| (b) 같은 조건 — 방식 A(SwiftUI 띠) | **실패**: 가로 스크롤이 터치를 가져가 뒤로가 시작되지 않음 | 통과 | `*-swiftui-strip-b-edge-scroll.txt` e1 |
| (b) 실기기 손맛·`UIScreenEdgePanGestureRecognizer` | 미검증 | 미검증 | 주입 터치에는 가장자리 정보가 없어 edge pan 인식기가 한 번도 시작되지 않았다 |
| (c) 가려진 화면·숨은 탭이 접근성 트리에 없음 | 통과 | 통과 | `*-a-ax.txt`, `*-d-tab-stacks-ax.txt` |
| (c) escape → 뒤로(UIKit 전달 흉내) | 통과(흉내) | 통과(흉내) | `*-c-escape-probe.txt`, `*-c-escape-ax.txt` |
| (c) 실제 VoiceOver 두 손가락 문지르기 | 미검증 | 미검증 | 시뮬레이터에 VoiceOver 없음 |
| (c) push 뒤 VoiceOver 포커스가 상세 제목으로 이동(`@AccessibilityFocusState`) | 미검증 | 미검증 | 포커스 상태는 VoiceOver가 켜져 있을 때만 바뀐다 |
| (d) 홈 목록 → 카테고리 목록 → 홈: 각 탭 스택 유지, 홈 루트 스크롤 위치 유지 | 통과 | 통과 | `*-d-tab-stacks.mov`, `*-d-tab-stacks-ax.txt` 1~7 |
| 전환 중 입력 차단(push 직후 80ms 간격 탭·뒤로 탭 무시) | 통과 | 통과 | `*-d-tab-stacks-ax.txt` 8a·8b |

- 프레임 띠 기준 사진 push는 카드 사각형에서 상세 사각형까지 약 0.3초 안에 대부분 움직이고 `emphasized` 꼬리가 420까지 이어진다. pop은 약 0.28초에 대부분 돌아온다. 띠에는 터치 지연(약 40ms)과 완료 직후 원래 사진이 다시 나타나는 프레임이 함께 찍혀 정확한 ms 측정값은 아니다. 시간 값은 토큰 상수 그대로다.
- 끌기 중 사진·면이 진행만큼 원래 자리로 줄어들고 내용·탭 바가 함께 옅어진다. 되돌림·확정은 남은 거리 비율만큼 시간을 줄이고 하한 120ms를 둔다(Android와 같은 값). 빠르게 놓음 기준은 진행 속도 1.5/s(Android와 같은 값)다.

## 발견과 한계

- **SwiftUI 제스처만으로는 iOS 17에서 가장자리 뒤로가 가로 스크롤에 진다.** iOS 17은 SwiftUI 제스처가 UIKit 인식기가 아니어서 위에 겹친 띠보다 아래 `UIScrollView`의 pan이 먼저 터치를 가져간다. iOS 26에서는 띠가 이긴다. window 수준 UIKit pan(방식 B)은 두 OS에서 같게 동작하고 세로·가로 스크롤과 충돌하지 않았다. 실제 라우터는 방식 B를 쓴다.
- **`accessibilityHidden(false)`를 조상에 두면 자손의 `accessibilityHidden(true)`가 무시된다.** 처음에는 탭 층마다 `.accessibilityHidden(탭이 선택되지 않음)`을 걸었는데, 선택된 탭(false)의 아래 층에서 가려진 목록·공유 사진 오버레이·끌기 띠가 접근성 트리에 남았다. 숨김은 가려지는 잎 층마다 따로 건다.
- **`.isModal`은 쓰지 않는다.** 상세에 `.accessibilityAddTraits(.isModal)`을 걸면 형제인 탭 바까지 트리에서 빠져, 탭 바를 유지하는 화면(칩 → 목록)에서 탭을 바꿀 수 없다. 위 잎 층 숨김만으로 가려진 화면이 빠진다.
- **공유 사진은 오버레이가 그린다.** spike는 사진을 오버레이에 고정해 두고 상세에는 자리만 둔다. 실제 상세는 사진이 함께 스크롤되므로 Task 7에서는 전환이 끝나면 오버레이를 감추고 상세 안의 사진을 보여 줘야 한다.
- **손맛:** 시스템 뒤로는 화면 전체가 손가락을 1:1로 따라가지만, 이 앱은 디자인대로 사진·면이 원래 자리로 줄어든다. 가장자리 20pt·50% 확정은 시스템과 비슷하다. 실기기에서 손가락으로 느낀 확인은 하지 않았다.
- **VoiceOver:** 가려진 화면 숨김과 escape 연결은 확인했지만, 실제 VoiceOver 포커스 이동과 문지르기 제스처는 실기기가 필요하다. 포커스가 옮겨 가지 않으면 push 완료 시 `UIAccessibility.post(notification: .screenChanged, argument:)`를 보내는 것으로 라우터 안에서 고칠 수 있어 구조를 바꿀 문제는 아니다.
- 다크 모드·큰 글자 크기는 이 spike 범위에서 보지 않았다.

## 권고

**자체 라우터로 진행한다(go).** (a)(d)와 (b)(방식 B), (c)의 숨김·escape 연결이 iOS 17.5·26.5에서 통과했다. 실패한 것은 방식 A 하나이고, 방식 B로 대체해 해결됐다. 남은 미검증 항목(실기기 VoiceOver 포커스·문지르기, 실기기 손맛)은 Task 7 라우터가 끝난 뒤 실기기 한 대에서 확인한다. 실패하면 위 `.screenChanged` 보완을 먼저 쓴다.

Task 7에 넘기는 구현 규칙:

- 끌어서 뒤로는 window 수준 `UIPanGestureRecognizer`(왼쪽 20pt, 가로 우세, 스크롤 뷰 pan이 실패를 기다림)로 만든다.
- 공유 요소는 원래 자리·상세 자리 사각형을 `phase`로 보간하고, 전환이 끝나면 상세 안의 요소로 넘긴다.
- 접근성 숨김은 잎 층마다 걸고 조상에 `accessibilityHidden(false)`를 두지 않는다. `.isModal`은 쓰지 않는다.

### 참고: 대안과 디자인 차이(채택하지 않음)

실기기 확인에서 구조로 풀 수 없는 문제가 나오면 `NavigationStack` + iOS 18 이상 `.navigationTransition(.zoom(sourceID:in:))` / iOS 17은 페이드로 내려간다. 이때 디자인과의 차이는 다음과 같고, 디자인 결정에 추가할지는 사용자에게 묻는다.

- 시간·곡선이 시스템 zoom 값이 되어 420/360 `emphasized`와 다르다.
- 면 전환의 떠오름·내려앉음 80, 모서리·색 보간, 내용 지연(190 뒤 230)을 만들 수 없다.
- iOS 17에서는 공유 요소 없이 페이드만 남는다.
- 끌어서 뒤로는 시스템 zoom 제스처가 되어 50% 확정 기준을 정할 수 없다.
- 탭 바·탭별 스택은 `NavigationStack`을 탭마다 두어 유지할 수 있다.
