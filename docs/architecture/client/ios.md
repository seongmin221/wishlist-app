# iOS 구조

> 상태: **확정** — Swift + SwiftUI + Share Extension

## 역할

- SwiftUI로 화면과 navigation을 구성한다.
- 상품 링크 웹뷰를 SwiftUI navigation 안에서 열고, 공통 웹뷰 탐색·외부 앱·쿠키 정책을 구현한다.
- Share Extension에서 공유 대상의 URL을 추출한다.
- 공유 확장은 가능한 한 짧게 URL을 app group 저장소에 전달하고 종료한다.
- 본 앱이 그 데이터를 읽어 KMP의 저장 UseCase를 호출한다.

## 주의점

- Share Extension은 메모리·실행 시간이 제한되므로 상품 분석이나 긴 네트워크 요청을 실행하지 않는다.
- 공유 대상에는 URL 외의 텍스트·이미지가 올 수 있으므로 MVP에서는 URL만 명확히 지원하고, 지원하지 않는 입력은 안내한다.
- 인증 토큰과 공유용 임시 데이터의 저장 경계를 별도로 설계한다.
- 웹뷰 닫기와 외부 앱 복귀를 구분하고, 외부 앱 복귀 시 기존 웹뷰 상태를 유지한다.

## 탭 셸과 화면 이동 (C1)

- `TabView`·`NavigationStack`·시스템 `.sheet`를 쓰지 않는다. `Navigation/WLNavigator`가 탭별 독립 스택과 현재 탭을 가진 순수 상태 기계(`@Observable`, 단위 테스트 대상)이고 `WLNavHost`가 그린다. 규칙은 Android `WLNavigator`와 같다. 모션 원본은 [motion.md](../../../design/handoff/interactions/motion.md) 2·3절이고 [iOS 라우터 spike](../../history/architecture/client/ios-router-spike-2026-10-05.md)의 조건부 진행 결정을 따른다.
- 앱 루트 `ContentView`가 `WLNavigator`·`WLNavMotion`을 소유하고 `WLNavHost`에 명시적으로 넘기고 `OverlayHost` 바깥에서 environment로 제공한다. 시트·메뉴·확인창에도 같은 navigator·motion이 전달되며 누락된 환경 값은 즉시 실패한다. overlay의 content가 다시 평가될 때 host의 기본 인자로 새 navigator를 만들지 않는다. 저장·복원은 별도 후속 과제다.
- `WLRoute`는 feature 목적지(`AnyHashable`)와 `showsTabBar`·`pushStyle`을 보유한다. 앱 renderer가 목적지 타입으로 화면을 고르고 라우터는 데모 id 문자열을 해석하지 않는다.
- 전환 중 입력: `push`·`pop`·`selectTab`은 전환을 시작하고 상태를 바로 바꾼다. 화면이 모션을 끝내면 자기 전환일 때만 `finishTransition()`을 부른다. 그동안 이동·탭·뒤로는 무시하고 화면 전체를 `InputBlocker`로 막는다. pop된 칸은 `exiting`으로 남아 뒤로 모션 동안 그려진다.
- 세 탭의 모든 스택 칸을 한 `ZStack`에 펼쳐 살려 둔다(칸 id가 정체성). 스크롤·입력 상태와 공유 요소의 원래 자리가 깊이 2 이상에서도 남는다. 탭 전환은 탭마다 opacity·scale 값으로 페이드 스루하고, 현재 탭을 다시 누르면 `scrollToTopRequest`로 맨 위 스크롤을 요청한다(`WLTabScrollView`).
- 공유 요소는 `matchedGeometryEffect` 대신 원래 자리 ↔ 상세 자리 사각형을 phase 하나로 보간해 그린다(`WLNavMotion`, `WLSharedElement`). 사각형은 요소 뒤의 UIKit 탐침에서 전환을 시작할 때만 읽는다(스크롤마다 올리지 않는다). 사진은 전환 층이 그리다가 끝나면 상세 안의 사진으로 넘긴다. 사진 없는 이동(`WLPushStyle.slide`)은 `WLSlideOffset`이 렌더 단계(`visualEffect`)에서 위 칸을 (1 − phase)·W, 바로 아래 칸을 −0.25·W·phase로 옮기는 가로 밀기다(360/300 `emphasized`). 칸 구조가 바뀌지 않아 스크롤·입력 상태가 유지된다. 두 화면이 모두 탭 바를 보이면 탭 바는 고정이다.
- 모션 값은 칸마다 `@Observable` 객체의 속성으로 둔다. 같은 사전(dictionary) 속성에 담으면 곡선이 다른 두 `withAnimation`이 한 무효화로 합쳐질 수 있어서다.
- 왼쪽 가장자리 끌어 뒤로는 window 수준 `UIPanGestureRecognizer`(`WLEdgeBackGesture`)다. 왼쪽 20pt·오른쪽 가로 우세에서만 시작하고, 띠 안에서는 스크롤·다른 pan보다 먼저다. 전환 중이거나 시트·확인창·메뉴가 떠 있으면 받지 않는다. 놓을 때 50% 이상이거나 초당 1.5 이상이면 확정한다(반대로 튕겨도 50% 이상이면 확정, Android와 같다). 되돌림은 스타일별 뒤로 시간을 남은 비율만큼 줄이고 하한 120ms다.
- 접근성: 가려진 칸·다른 탭·숨은 탭 바는 `wlAccessibilityCovered`로 층마다 뺀다(가려지면 `.ignore` + `accessibilityHidden(true)`, 보이면 `.contain` + `accessibilityHidden(false)`). `.isModal`은 쓰지 않는다. 상세 칸 컨테이너의 escape(두 손가락 문지르기)가 `pop()`이고, push가 끝나면 상세 제목에 VoiceOver 포커스를 준다(`wlArrivalFocus`).
- 칸 `ZStack`은 늘 자식이 둘 이상이게 보이지 않는 자리를 둔다. 자식이 하나인 `.contain` 컨테이너는 접근성 트리에서 접혀 escape 동작이 전달 경로에서 빠졌다.
- 실기기 VoiceOver(포커스 이동·문지르기)는 아직 확인하지 않았다. TODO: 실기기에서 push 뒤 포커스가 제목으로 옮겨 가지 않으면 전환 끝에 `UIAccessibility.post(notification: .screenChanged, argument:)`를 보낸다(spike 기록의 권고).
- 탭 바는 화면 아래 끝에서 24(보드 값) 위에 놓는다. 탭 글자는 Dynamic Type `xxLarge`까지만 따른다.
- 데모 화면·경로 정의와 renderer의 데모 분기를 모두 `#if DEBUG`로 제한한다. release는 데모 타입을 참조하지 않고 탭 이름만 보인다.

- 유지된 세 탭·모든 스택 칸의 메모리·관찰·레이아웃 비용은 [C3 성능 확인](c3-performance-checks.md)에서 측정한다. 입력 조합 감지는 앱 공용 알림 관찰자와 window별 약한 responder cache를 사용하며 첫 탐색 이후 키 입력마다 전체 트리를 재탐색하지 않는다.

## 공유 runtime 연결 (C2)

- `WishlistApp`이 `init()`에서 `SharedRuntimeFactory.shared.create(bindings:remote:)`로 프로세스당 `SharedRuntime` 하나를 만들어 보유한다. `AppRuntimeConfig.bindings()`가 `#if DEBUG`로 mode와 37개 map을 고른다(DEBUG: ITEM-01·03 FAKE, 나머지 UNAVAILABLE / Release: 모두 UNAVAILABLE). C2에는 remote config가 없다.
- `Wishlist/Debug/DebugSessionBootstrap.swift`는 파일 전체가 `#if DEBUG`이며 `startDebugSession()`만 호출한다. Release 바이너리에는 이 타입의 심볼이 없다.
- static `Shared.framework`가 SQLite driver를 포함하므로 앱 target `OTHER_LDFLAGS`에 `-lsqlite3`를 둔다.
- `SharedRuntimeTests`가 앱 설정(Debug)의 binding, 실제 factory로 만든 runtime의 `ready`가 SKIE `for await`로 true가 되는 흐름과 debug owner 계정, close 후 ready=false, RELEASE binding의 즉시 ready를 검증한다.
- `Features/Detail/ItemDetailPresenterOwner.swift`는 `@MainActor @Observable` 수명 소유자다(UI 없음). Presenter의 `state`를 main actor `Task`에서 SKIE `for await`로 수집해 구체 타입 `item: WishlistItem?`·`error: ClientError?`·`loading`으로 다시 게시한다. Task는 owner를 약하게 잡아 순환 참조가 없고, `close()`(멱등)와 `deinit`이 수집을 취소하고 Presenter를 닫는다. `init(runtime:)`은 `runtime.itemDetailPresenter()`를 쓴다.
- `SharedInteropTests`는 Task 1 probe 대신 실제 Presenter·runtime으로 Flow 수집·collector 취소·suspend 호출·close·계정 전환(runtime session의 `MutableAuthSession.changeAccount`)을 검증하고, Swift `PlatformTokenSource` callback 성공/오류를 REMOTE ITEM-03 runtime(도달 불가 `http://127.0.0.1:9`)으로 검증한다. `ItemDetailPresenterOwnerTests`는 owner의 구체 state 수집, 계정 전환·오류 후 retry, close·deinit에 의한 종료를 검증한다.
