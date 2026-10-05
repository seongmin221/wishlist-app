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
| (b) 가로·세로 스크롤 위에서 가장자리 끌기 → 뒤로 — 방식 B | 통과 | 통과 | `*-uikit-edge-b-edge-scroll.txt` e1·e2·e5, `*-b-edge-back.txt` 6 |
| (b) 가로 칩 줄 가운데에서 끌기 → 상세 유지(뒤로가 시작되지 않음) | 통과 | 통과 | `*-b-edge-back.txt` 5. 스크롤이 실제로 움직였는지는 최종 증거 파일로 남기지 않았다 |
| (b) 같은 조건 — 방식 A(SwiftUI 띠) | **실패**: 가로 스크롤이 터치를 가져가 뒤로가 시작되지 않음 | 통과 | `*-swiftui-strip-b-edge-scroll.txt` e1 |
| (b) 실기기 손맛·`UIScreenEdgePanGestureRecognizer` | 미검증 | 미검증 | 주입 터치에는 가장자리 정보가 없어 edge pan 인식기가 한 번도 시작되지 않았다 |
| (c) 가려진 화면·숨은 탭이 접근성 트리에 없음 | 통과 | 통과 | `*-a-ax.txt`, `*-d-tab-stacks-ax.txt` |
| (c) escape → 뒤로(UIKit 전달 흉내) | 통과(흉내) | 통과(흉내) | `*-c-escape-probe.txt`, `*-c-escape-ax.txt` |
| (c) 실제 VoiceOver 두 손가락 문지르기 | 미검증 | 미검증 | 시뮬레이터에 VoiceOver 없음 |
| (c) push 뒤 VoiceOver 포커스가 상세 제목으로 이동(`@AccessibilityFocusState`) | 미검증 | 미검증 | 포커스 상태는 VoiceOver가 켜져 있을 때만 바뀐다 |
| (d) 스택 깊이 1: 홈 목록 → 카테고리 목록 → 홈. 각 탭 스택 유지, 홈 루트 스크롤 위치 유지 | 통과 | 통과 | `*-d-tab-stacks.mov`, `*-d-tab-stacks-ax.txt` 1~7 |
| (d) 스택 깊이 2 이상 | 미검증(구조상 실패) | 미검증(구조상 실패) | spike는 루트와 `stack.last`만 그린다. 깊이 2부터는 아래 화면이 사라져 스크롤·상태와 공유 요소 원래 자리 사각형을 잃는다 |
| 전환 중 입력 차단: 사진 push 직후 80ms 간격으로 탭 바 자리·뒤로 자리를 누르면 무시, 뒤로 직후 사진 카드 자리를 누르면 무시 | 통과 | 통과 | `*-d-tab-stacks-ax.txt` 8a·8b. 사진 상세에서는 탭 바가 원래 눌리지 않는 상태라 탭 바 차단을 확인한 것은 아니다 |
| 전환 중 입력 차단: 탭 바가 보이는 면 push 중 탭 누르기 | 미검증 | 미검증 | 코드상 `select`가 `isTransitioning`이면 무시하고 루트 `allowsHitTesting`도 꺼진다 |

- 프레임 띠 기준 사진 push는 카드 사각형에서 상세 사각형까지 약 0.3초 안에 대부분 움직이고 `emphasized` 꼬리가 420까지 이어진다. pop은 약 0.28초에 대부분 돌아온다. 띠에는 터치 지연(약 40ms)과 완료 직후 원래 사진이 다시 나타나는 프레임이 함께 찍혀 정확한 ms 측정값은 아니다. 시간 값은 토큰 상수 그대로다.
- 면 전환에서 떠오름·커짐 동안 칩 글자가 보이지 않는 것은 motion.md(자리 표시 면은 글자·아이콘을 싣지 않는다)대로다.
- 끌기 중 사진·면이 진행만큼 원래 자리로 줄어들고 내용·탭 바가 함께 옅어진다. 되돌림·확정은 남은 거리 비율만큼 시간을 줄이고 하한 120ms를 둔다(Android와 같은 값). 빠르게 놓음 기준은 진행 속도 1.5/s(Android와 같은 값)다.

## 발견과 한계

- **SwiftUI 제스처만으로는 iOS 17에서 가장자리 뒤로가 가로 스크롤에 진다.** iOS 26에서는 띠가 이긴다. 원인은 추정이다: iOS 18부터 SwiftUI 제스처가 UIKit 인식기 위에서 돌아 겹친 순서를 따르지만, iOS 17에서는 위에 겹친 띠보다 아래 `UIScrollView`의 pan이 먼저 터치를 가져가는 것으로 보인다. window 수준 UIKit pan(방식 B)은 두 OS에서 같게 동작하고 세로·가로 스크롤과 충돌하지 않았다. 실제 라우터는 방식 B를 쓴다.
- **`accessibilityHidden(false)`를 조상에 두면 자손의 `accessibilityHidden(true)`가 무시된다.** 처음에는 탭 층마다 `.accessibilityHidden(탭이 선택되지 않음)`을 걸었는데, 선택된 탭(false)의 아래 층에서 가려진 목록·공유 사진 오버레이·끌기 띠가 접근성 트리에 남았다. 숨김은 가려지는 잎 층마다 따로 건다.
- **`.isModal`은 쓰지 않는다.** 상세에 `.accessibilityAddTraits(.isModal)`을 걸면 형제인 탭 바까지 트리에서 빠져, 탭 바를 유지하는 화면(칩 → 목록)에서 탭을 바꿀 수 없다. 위 잎 층 숨김만으로 가려진 화면이 빠진다.
- **공유 사진은 오버레이가 그린다.** spike는 사진을 오버레이에 고정해 두고 상세에는 자리만 둔다. 실제 상세는 사진이 함께 스크롤되므로 Task 7에서는 전환이 끝나면 오버레이를 감추고 상세 안의 사진을 보여 줘야 한다.
- **손맛:** 시스템 뒤로는 화면 전체가 손가락을 1:1로 따라가지만, 이 앱은 디자인대로 사진·면이 원래 자리로 줄어든다. 가장자리 20pt·50% 확정은 시스템과 비슷하다. 실기기에서 손가락으로 느낀 확인은 하지 않았다.
- **VoiceOver:** 가려진 화면 숨김과 escape 연결은 확인했지만, 실제 VoiceOver 포커스 이동과 문지르기 제스처는 실기기가 필요하다. 포커스가 옮겨 가지 않을 때의 보완은 아래 권고에 적었다.
- **스택 깊이:** spike는 탭마다 루트와 맨 위 화면만 그린다. 깊이 2 이상에서는 가운데 화면이 매번 새로 만들어져 스크롤·입력 상태를 잃고, 그 화면에서 시작한 공유 요소의 원래 자리 사각형도 없어진다.
- **프레임 수집:** spike는 모든 원본 요소의 전역 사각형을 `PreferenceKey`로 매번 올린다. 목록이 길면 스크롤마다 갱신이 많아진다. 상세 자리 사각형이 측정되기 전 첫 프레임에는 `.zero`가 될 수 있다.
- **되돌림 시간:** 끌기 취소 되돌림은 면 화면에도 `pushPhotoBack`을 썼다. 값은 같은 360이지만 면에는 `pushSurfaceBack`을 써야 한다.
- 다크 모드·큰 글자 크기는 이 spike 범위에서 보지 않았다.

## 권고

**조건부 진행(조건부 go), 사용자 결정 2026-10-06.** Task 7은 자체 라우터로 만든다. (a), 스택 깊이 1의 (d), 방식 B의 (b), (c)의 숨김·escape 연결이 iOS 17.5·26.5에서 통과했다. 실패한 것은 방식 A 하나이고 방식 B로 해결됐다.

- **Task 7 완료 조건:** 실기기 한 대에서 VoiceOver로 push 뒤 포커스가 상세(제목)로 옮겨 가는지, 두 손가락 문지르기로 뒤로 가는지 확인한다. 기기가 없으면 PR에 미검증으로 적고 사용자가 나중에 확인한다. 손가락 손맛도 같은 때 본다.
- **포커스가 옮겨 가지 않을 때의 보완:** push가 끝날 때 `UIAccessibility.post(notification: .screenChanged, argument:)`를 보낸다. 주의할 점이 두 가지 있다.
  - SwiftUI는 제목 요소를 `argument`로 넘길 손잡이를 쉽게 주지 않는다. `nil`을 넘기면 첫 요소("뒤로")에 포커스가 간다.
  - 처음 나타날 때 `AccessibilityFocusState`를 바로 바꾸면 무시되는 경우가 많아 짧은 지연이 필요할 수 있다.

Task 7 구현 규칙:

- **스택:** 스택의 모든 화면을 살려 두고(가려진 화면은 잎 층마다 숨김), 그렇지 않으면 화면 상태를 명시적으로 보존한다. 공유 요소의 원래 자리 사각형은 아래 화면이 가려진 동안에도 남아 있어야 pop이 돌아갈 수 있다. 깊이 2 이상 push/pop/끌어서 뒤로를 다시 확인한다.
- **공유 요소 사각형:** 모든 원본 요소의 전역 사각형을 스크롤마다 올리지 않는다. 누른 순간에 그 요소의 사각형을 잡거나, 해당 key에만 anchor preference를 둔다. key는 항목 id로 한다. 상세 자리 사각형이 아직 `.zero`이면 측정될 때까지 전환을 시작하지 않는다(또는 원래 자리에 머문다). 전환이 끝나면 오버레이를 감추고 상세 안의 요소로 넘긴다.
- **끌어서 뒤로(window 수준 `UIPanGestureRecognizer`):**
  - 왼쪽 20pt, 가로 우세에서만 시작하고, 스크롤 뷰 pan이 이 인식기의 실패를 기다리게 한다(`shouldBeRequiredToFailBy`).
  - 뷰가 해제되거나 window가 `nil`이 되면 인식기를 window에서 뗀다. 해제된 coordinator를 target으로 가진 인식기가 남지 않게 한다.
  - `shouldReceive`는 전환 중일 때와 직접 그린 시트·확인창·scrim이 열려 있을 때 false다. 그 밑의 화면을 끌어서 뒤로 보내지 않는다.
  - 20pt 띠 안의 다른 제스처와의 우선순위를 정한다. iOS 18 이상에서는 SwiftUI 제스처도 UIKit 인식기이고, 슬라이더나 시트 끌기 같은 `UIScrollView`가 아닌 pan도 있다.
  - `cancelsTouchesInView = true`라 끌기가 시작되면 띠 안 컨트롤의 터치가 취소된다.
- **확정·되돌림:** 되돌림 시간은 스타일별 값(`pushPhotoBack`·`pushSurfaceBack`)을 쓴다. 50%를 넘긴 뒤 반대로 빠르게 튕겼을 때 확정할지 되돌릴지는 Android와 같은 규칙으로 정한다(spike와 Android 모두 지금은 진행 50% 이상이면 확정).
- **접근성:** 숨김은 잎 층마다 걸고, 조상에 `accessibilityHidden(false)`를 두지 않는다. `.isModal`은 쓰지 않는다.
- **입력 차단:** 탭 바가 보이는 화면에서 push 중 탭을 누르는 경우를 확인한다.

### 참고: 대안과 디자인 차이(채택하지 않음)

실기기 확인에서 구조로 풀 수 없는 문제가 나오면 `NavigationStack` + iOS 18 이상 `.navigationTransition(.zoom(sourceID:in:))` / iOS 17은 페이드로 내려간다. 이때 디자인과의 차이는 다음과 같고, 디자인 결정에 추가할지는 사용자에게 묻는다.

- 시간·곡선이 시스템 zoom 값이 되어 420/360 `emphasized`와 다르다.
- 면 전환의 떠오름·내려앉음 80, 모서리·색 보간, 내용 지연(190 뒤 230)을 만들 수 없다.
- iOS 17에서는 공유 요소 없이 페이드만 남는다.
- 끌어서 뒤로는 시스템 zoom 제스처가 되어 50% 확정 기준을 정할 수 없다.
- 탭 바·탭별 스택은 `NavigationStack`을 탭마다 두어 유지할 수 있다.

## Task 7 구현에서 보탠 사실 (2026-10-06)

Task 7 실제 라우터를 만들며 iOS 17.5·26.5 시뮬레이터에서 serve-sim `/ax`와 escape 흉내로 확인했다.

- **숨김 규칙 보완:** 조상에 그냥 `accessibilityHidden(false)`를 걸면 후손의 숨김이 취소되는 것은 그대로다. 그러나 `.accessibilityElement(children: .contain)` 뒤에 건 `accessibilityHidden(false)`는 후손의 `accessibilityHidden(true)`를 취소하지 않았다. 가려진 층은 `.ignore` + `accessibilityHidden(true)`로 빼면 라벨 없는 빈 요소도 남지 않는다(`.ignore`만 쓰면 빈 요소가 남았다). 그래서 위 "조상에 `accessibilityHidden(false)`를 두지 않는다" 규칙은 "`.contain` 컨테이너 뒤에서만 쓴다"로 고친다(`wlAccessibilityCovered`).
- **escape 전달 경로:** 자식이 하나뿐인 `ZStack`에 건 `.contain` 컨테이너는 트리에서 접혀, 거기 건 escape 동작이 전달 경로에서 빠졌다(상세 안 글자에서 8단계 올라가도 처리 안 됨). 칸마다 보이지 않는 형제를 하나 두자 3단계(글자 → 스크롤 → 칸 컨테이너)에서 처리됐다. 흉내는 UIView에도 `superview`보다 `accessibilityContainer`를 먼저 따라가야 VoiceOver 순서와 맞다.
- **깊이 2:** 모든 칸을 살려 두는 방식으로 깊이 2의 push·pop·끌어서 뒤로가 두 OS에서 통과했다.
- **실기기 VoiceOver:** 여전히 미검증이다(연결된 기기 없음). `.screenChanged` 보완은 코드에 넣지 않았다.
