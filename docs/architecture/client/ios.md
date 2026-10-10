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
- `Wishlist/Debug/DebugSessionBootstrap.swift`는 파일 전체가 `#if DEBUG`이며 `startDebugSession()`을 호출한 뒤 `DebugLaunchHooks`로 launch argument `-wl.fake.delayItem01 <ms>`·`-wl.fake.pendingCount <N>`을 `runtime.debugControls()`에 넘긴다(C3 Task 7 시연용). 인자는 runtime을 조립하는 프로세스 시작 때만 읽으므로 cold start에서만 적용된다. 이미 실행 중이면 `xcrun simctl launch --terminate-running-process <udid> app.wishlist.ios -wl.fake.pendingCount 100`처럼 먼저 종료하고 띄운다. Release 바이너리에는 이 타입의 심볼과 `wl.fake` 문자열이 없다.
- static `Shared.framework`가 SQLite driver를 포함하므로 앱 target `OTHER_LDFLAGS`에 `-lsqlite3`를 둔다.
- `SharedRuntimeTests`가 앱 설정(Debug)의 binding, 실제 factory로 만든 runtime의 `ready`가 SKIE `for await`로 true가 되는 흐름과 로그인 전 시작(C3부터 debug 고정 계정 없음), close 후 ready=false, RELEASE binding의 즉시 ready를 검증한다.
- `Features/Detail/ItemDetailPresenterOwner.swift`는 `@MainActor @Observable` 수명 소유자다(UI 없음). Presenter의 `state`를 main actor `Task`에서 SKIE `for await`로 수집해 구체 타입 `item: WishlistItem?`·`error: ClientError?`·`loading`으로 다시 게시한다. Task는 owner를 약하게 잡아 순환 참조가 없고, `close()`(멱등)와 `deinit`이 수집을 취소하고 Presenter를 닫는다. `init(runtime:)`은 `runtime.itemDetailPresenter()`를 쓴다.
- `SharedInteropTests`는 Task 1 probe 대신 실제 Presenter·runtime으로 Flow 수집·collector 취소·suspend 호출·close·계정 전환(C3부터 `MutableAuthSession`이 internal이라 테스트 helper `switchAccount`가 `runtime.auth()`의 signOut·signIn을 쓴다)을 검증하고, Swift `PlatformTokenSource` callback 성공/오류를 REMOTE ITEM-03 runtime(도달 불가 `http://127.0.0.1:9`)으로 검증한다. `ItemDetailPresenterOwnerTests`는 owner의 구체 state 수집, 계정 전환·오류 후 retry, close·deinit에 의한 종료를 검증한다.

## 공유 확장·inbox·로그인·홈·설정 (C3)

> 2026-10-07 C3 Task 0·6·7. 공유 수신 방식은 [ADR-030](../../history/architecture/client/ADR-030-share-receipt-mode.md)(C3-D1 C안): 확장은 Shared.framework 없이 app group `inbox/`에 공유 1건 = JSON 파일 1개를 쓰고 끝난다. 본 앱이 실행·foreground 때 가져와 SQLite에 넣고 KMP `SubmissionCoordinator`가 같은 key로 전송한다. 다중 프로세스 DB 공유는 없다. 확장의 background URLSession 직접 전송은 자리(`ShareDirectSender`)만 두고 꺼 두었다("인증 연결" 단계).

### target과 app group

- `ShareExtension` target(`app.wishlist.ios.share`, `com.apple.share-services`, WebURL 1개 + Text)은 Shared.framework를 링크하지 않는다(Build Shared phase 없음, `APPLICATION_EXTENSION_API_ONLY = YES`). 확장이 함께 컴파일하는 앱 파일은 `WishlistTokens.swift`·`WLTypography.swift`·`WLTheme.swift`(WLText 포함)·`WLCard.swift`·`WLIconTile.swift`·`WLLineIcon.swift`와 `AppGroupShared/*`이고, 서체 4종을 resource로 넣고 확장 `Info.plist`에 `UIAppFonts`를 둔다. 문구는 확장 전용 `ShareExtension/Localizable.xcstrings`(`share.*`)다. `WLTypography`의 `.trailing` 정렬은 확장에서 쓸 수 없는 `UIApplication.shared` 대신 SwiftUI `layoutDirection`으로 정한다.
- app group은 `group.app.wishlist`(`AppGroupShared/AppGroup.swift`: group id, `wl.session.accountBinding` 키, `inbox/` 경로).
- **서명 발견(Task 0, Ruling 4):** 시뮬레이터의 entitlements는 서명 단계에서 바이너리 `__TEXT,__entitlements`에 들어간다. `CODE_SIGNING_ALLOWED=NO` 빌드에는 entitlements가 없어 `containerURL(forSecurityApplicationGroupIdentifier:)`가 nil이다. 프로젝트 기본 "Sign to Run Locally"(팀 없음, override 없는 `xcodebuild build`나 Xcode Run)는 앱·확장 모두 app group이 붙고 같은 container를 본다. 그래서 공유 확인용 빌드는 기본 서명, IOS_TEST·CI는 `CODE_SIGNING_ALLOWED=NO`를 유지한다. app group을 쓰는 코드는 디렉터리·defaults를 주입받아 테스트는 임시 폴더를 쓰고, 실행 중 container가 nil이면 inbox만 꺼진다(로그, crash 없음). 실기기·배포는 Apple Developer 팀이 필요하다(인증 연결 단계).

### inbox 형식 v1

- 공유 1건 = `inbox/<clientSubmissionId>.json` 하나: `{"v":1,"clientSubmissionId":"<소문자 uuid>","sourceUrl":"<추출한 링크>","sharedAt":"2026-10-07T01:02:03.456Z","accountBinding":null}`. `sharedAt`은 UTC 밀리초(부동소수 표기에 기대지 않고 정수 ms로 한 번 반올림), `accountBinding`은 공유 순간 미러된 계정이며 없으면 명시적 `null`(`AppGroupShared/InboxRecordFile.swift`).
- 쓰기(`ShareExtension/ShareInboxWriter.swift`): `inbox/.tmp-<key>`에 `Data.write(.atomic)` 후 `moveItem`으로 최종 이름. 같은 URL을 다시 공유해도 새 key다.
- 읽기(`Platform/ShareInboxReader.swift`): `.`으로 시작하지 않는 `*.json`을 이름순으로 읽는다. v1 레코드만 `runtime.submissions().importInbox(records:)`에 넘기고, 결과의 `deletable`만 지운다(retained·importer 오류는 남겨 다음에 다시). 읽을 수 없는 바이트·필드 누락은 corrupt로 바로 지우고, `v`가 1이 아니면 미래 형식으로 보고 건드리지 않는다. 진행 중 쓰기(`.tmp-*`)는 무시한다. import 뒤·삭제 전에 죽으면 같은 key를 다시 import하며 KMP가 no-op으로 처리한다.

### 확장 흐름과 화면

- `ShareViewController`: `extensionContext.inputItems`의 `NSItemProvider`에서 `UTType.url` 먼저, 없거나 웹 링크가 아니면(앱 scheme URL 등) `UTType.plainText`(각 3초 상한, 글은 필요할 때만 읽음) → `ShareTextExtractor`(결정은 `ShareExtension/ShareInput.swift`의 순수 함수 `decide`·`string(_:type:)`로 테스트한다. `Data`는 `url`로 요청한 값만 `URL(dataRepresentation:)`으로 읽고 글은 UTF-8로 읽는다 — 글을 URL로 읽으면 공백이 `%20`이 되어 링크가 글 끝까지 늘어난다) → 카드 종류(Ruling 2, Swift 자체 enum `ShareCardKind`): 링크 없음·2048 초과 `invalid`, container 없음·쓰기 실패 `storeFailed`, 미러된 계정이 있으면 `savedOpenApp`("위시리스트에 저장했어요 / 앱을 열면 정보를 가져와요"), 없으면 `local`("이 기기에 저장했어요 / 로그인하면 정보를 가져와요"). 쓰기 뒤 `DisabledShareDirectSender.send`(아무것도 하지 않음, 활성화 조건은 문서 주석: 가입·Keychain 공유·토큰 만료 정책).
- `ShareTextExtractor`는 Kotlin `ShareTextParser`의 Swift 사본이다. 패턴의 공백은 Kotlin `\s`와 같은 ASCII 6자(space·\t·\n·\x0B·\f·\r)를 직접 적는다(ICU `\s`는 NBSP 등까지 잡아 Android와 달라진다). 길이·자르기는 UTF-16 단위다. `ShareTextExtractorTests`가 Kotlin `ShareTextParserTest`와 같은 벡터를 같은 순서로 검사한다. Kotlin의 따옴표·`>` 자르기는 패턴이 이미 제외해 도달하지 않으므로 옮기지 않았다.
- 카드(`ShareCardView`): motion.md 6절 — 340 `standard`로 올라오고 1500 유지, 260 `accelerate`로 내려간 뒤 `completeRequest`. 저장이 끝나고 **`viewDidAppear` 뒤에만** 시작한다(`viewDidLoad`의 `completeRequest`는 무시되어 시트가 닫히지 않았다, Task 0). 등장 때 VoiceOver announcement(제목, 보조 줄).
- **표시 방식(Task 6 시뮬레이터 확인, iOS 26.5):** 확장 view와 hosting view는 투명이고 `modalPresentationStyle = .overFullScreen`을 주지만, iOS가 확장 window 안에서 우리 화면을 담는 page sheet(`UIDropShadowView`, `systemBackgroundColor`)를 그리고 그 뒤 Safari를 어둡게 한다. 그래서 보드처럼 "원래 앱 위의 카드"가 아니라 "불투명 시스템 시트 아래쪽의 카드"로 보인다. 시트 크기(`preferredContentSize`)는 반영되지 않고 `sheetPresentationController`는 nil이다. 시스템 view 배경을 직접 지우면 카드만 뜨지만 UIKit 내부 계층에 기대므로 쓰지 않는다. 카드가 내려간 뒤 시트는 저절로 닫힌다(탭 후 약 4초 안).
- **시트 바탕(Ruling 17, C3 Task 7):** 시트를 없앨 수 없으므로 확장 view·hosting view·`ShareCardHost`가 보드 FShareSaved*의 "다른 앱" 바탕색(`ShareBackdrop`: 라이트 #E9E9E9, 다크 #2A2A2A)을 칠해 카드 대비를 보드와 같게 유지한다. 카드는 시트 아래쪽에서 보드 motion대로 오르내리고 그림자는 없다. 디자인 토큰이 아니라 이 확장 전용 값이며, 보드와의 차이(전체 높이 불투명 시스템 시트)는 history에 보드 차이로 기록한다.

- **직접 전송 자리:** `ShareExtension/ShareDirectSender.swift`의 `DisabledShareDirectSender`는 아무것도 하지 않는다. 켜려면 개발자 팀(Keychain 공유 access group으로 token 전달), token 만료(Firebase ID token 1시간) 정책, 401에서도 inbox 파일을 지우지 않는 규칙이 필요하다. 켜도 앱의 inbox import·같은 key 재전송은 그대로이며 서버 멱등 replay로 결과를 받는다.

### 앱 쪽 신호·세션 미러

- **inbox 중단 복구(3차 리뷰):** 확장은 `.tmp-<key>`를 `.atomic`으로 쓴 뒤 이름을 바꾼다. 그 사이에 확장이 끝나면 완전한 임시 파일이 남는데, reader는 60초(`staleTemporaryAge`) 넘은 임시 파일을 제자리로 옮겨(이미 같은 record가 있으면 지움) 다른 record처럼 가져온다(`testStaleTemporaryWriteIsRecoveredAndImported`). 홈 화면은 보이는 동안 1분마다 `HomePresenterOwner.tick()`을 불러 상대 시각을 다시 계산한다.
- `Platform/AppSignals.swift`(Ruling 1): 실행과 background에서 돌아올 때만(`ForegroundTransitions`: 첫 `.active`와 `.background` 뒤의 `.active`. 제어 센터·알림 센터·Face ID 창의 `.inactive → .active`는 앱을 떠난 것이 아니라 제외, PR #12 2차 리뷰) inbox pass(앞 pass가 끝난 뒤 순서대로) → `refresh()`. refresh는 다음 pass가 기다리지 않는다. XCTest host(`XCTestConfigurationFilePath` 환경 변수)에서는 inbox·refresh·네트워크 신호를 모두 끈다(IOS_TEST host는 설치된 debug 앱과 같은 container·DB를 쓴다). `NetworkSignals`는 `NWPathMonitor`의 unsatisfied → satisfied 전이에서 `requestFlush()`(첫 갱신은 기준값).
- `Platform/SessionMirror.swift`: `AccountPresenterOwner.binding`(복원 전 `unknown` / `signedOut` / `signedIn(id)`)이 바뀔 때마다 app group defaults에 `wl.session.accountBinding`을 쓰거나 지운다. 복원 전에는 지난 값을 그대로 둔다. 자격 증명이 아니다.

### 화면과 owner

- `Features/Session/AccountPresenterOwner`·`HomePresenterOwner`: `ItemDetailPresenterOwner`와 같은 `@MainActor @Observable` 수명 소유자. `WishlistApp`이 하나씩 만들어 environment로 넣는다. `HomeState.Loading`(복원 전·계정 전환 중)은 머리만 그려 이전 계정 줄이 비치지 않는다. 당겨서 새로고침은 로그인 뒤에만 `.refreshable`(시스템 indicator)이고, `HomePresenterOwner.refresh()`가 SKIE async로 `HomePresenter.refreshNow()`를 기다려 그 refresh가 끝나면 돌아온다(state polling 없음. 당김이 취소되면 `CancellationError`는 조용히 버리고 refresh는 Presenter에서 계속된다).
- 첫 실행 로그인(FLogin)은 `ContentView`의 탭 셸 위 레이어다(`showFirstRunLogin`, 사라짐 opacity 260 `accelerate` — 애니메이션은 이 레이어의 컨테이너에만 걸어 같은 갱신의 탭 셸 변화에 번지지 않게 한다, 뜨는 동안 아래는 접근성에서 가림). 홈 로그인 카드·설정 "로그인"은 같은 화면을 `AppDestination.login`(가로 밀기)으로 연다. 홈 오른쪽 위 설정은 `AppDestination.settings`. 로그인 중에는 로그인·로그아웃 버튼을 막고, 실패는 화면에 남기지 않는다.
- 홈(FHomeLoggedOut·FHome): 로그인 전은 로그인 카드 + "분석 대기"(줄마다 "원본" → 원본 링크 웹뷰, C4 PR B). 로그인 뒤 머리 보조 줄은 "할 일 N개"(`home.todo.count`, N = 분류 중 줄 수, Ruling 13), "분류 중" 카드 줄 상태 줄은 "상품 정보 추출 중"(Ruling 14)이고 오른쪽 동작이 없다(Ruling 15). 할 일 카드는 머리 전체와 화살표 버튼이 같은 펼치기이며 화살표 VoiceOver 이름은 "펼치기"/"접기". 줄 key는 목록 정체성으로만 쓴다.
- 설정(FSettings·FSettingsLoggedOut): 로그아웃(먹색)·웹뷰 데이터 삭제(빨강) 확인창은 `WLConfirmDialog`. 웹뷰 삭제는 `WKWebsiteDataStore.default()`의 모든 형식, "방금 삭제했어요"는 화면 수명 동안만. 버전은 `CFBundleShortVersionString`. 라이선스 줄은 C12까지 숨긴다.
- C1 홈 데모(`DemoHomeScreen`)는 지웠다. ⋯ 메뉴·삭제 확인창 데모는 상품 상세 데모에 있다.
- 테스트: `ShareTextExtractorTests`·`InboxWriterReaderTests`는 확장 소스(`ShareTextExtractor.swift`·`ShareInboxWriter.swift`)를 테스트 target에도 컴파일한다. 테스트 target은 `WISHLIST_TESTS` 조건을 켜서 `ShareInboxWriter.swift`가 app group 타입을 `@testable import Wishlist`로 본다. `HomeRowTextTests`(문구 키 매핑·한영 번역 존재), `AccountPresenterOwnerTests`·`SessionMirrorTests`(owner 수명·미러), `HomePresenterOwnerTests`(당겨서 새로고침이 ITEM-01 800ms 지연 전송이 끝난 뒤에 돌아옴).
- 시연 hook(C3 Task 7): 위 launch argument로 전송 중 계정 전환과 전송 중 `simctl terminate` 뒤 같은 key 재전송을 확인했다(저장소 밖 XCUITest harness로 Safari 공유 시트도 조작). 기록은 [C3 검증 기록](../../history/architecture/client/c3-verification-2026-10-07.md).

### 검증 빌드와 서명

- 단위 테스트(IOS_TEST)와 Release simulator build는 `CODE_SIGNING_ALLOWED=NO`다. 이 빌드에는 app group이 없어 app group 코드는 주입한 임시 폴더로만 테스트한다.
- 공유 확장을 손으로 확인할 때는 서명 override 없는 기본 빌드("Sign to Run Locally", 팀 없음)를 설치한다. IOS_TEST가 같은 bundle id의 서명 없는 앱을 설치하므로 테스트 뒤 다시 설치한다. 명령은 [client/README.md](../../../client/README.md#공유-확장-확인-빌드ios).

## C3 남은 점

- 확장 표시: iOS 26이 확장을 불투명 전체 높이 시트로 감싼다(Ruling 17로 보드 바탕색을 칠해 대비만 맞춤). A(현재)·B(다른 대비)·C(UIKit 내부 계층 배경 제거) 중 A를 사용자가 확정했다(2026-10-09). 바탕 hex가 세 곳에 따로 정의돼 있다(확장 전용 값, 정리 대상).
- 실기기 공유 확장·VoiceOver는 확인하지 않았다(개발자 팀 필요, 인증 연결 단계). Notes 앱의 링크 없는 글 공유는 자동화하지 않았고 2048자 초과 URL로 INVALID 카드를 확인했다.
- 확장 입력 읽기는 첨부마다 3초 상한이고 넘으면 링크 없음(INVALID)으로 본다(구현 중 정한 규칙).
- XCTest host는 앱 신호는 끄지만 runtime·debug 복원·seed는 설치된 debug 앱과 같은 `wishlist.db`에서 돈다([KMP 알려진 한계](kmp.md#알려진-한계와-인계-단계)).
- `NetworkSignals`·`AppSignals`는 자동 테스트가 없다.
- 홈 목록은 lazy가 아니다(`ScrollView` 안 `VStack`). 대기 300개에서 XCTest 측정 CPU가 N보다 빠르게 늘었다. Instruments는 `DevToolsSecurity` 승인 뒤 다시 잰다([C3 성능 확인](c3-performance-checks.md#c3-측정-결과-2026-10-07)).
- 로그인 뒤 할 일 머리 "할 일 N개"는 0개일 때도 보인다(보드는 비지 않은 예만 있다). 영어는 plural로 "1 to-do"·"7 to-dos"다(iOS xcstrings plural variation, Android `<plurals>`).

## 상품 상세 내비게이션 기반 (C4)

- **계정 범위 route:** `WLRoute.accountScoped`(기본 false). `AppDestination.item(id)`·`.local(submissionId)`가 true이고 탭 바 없는 가로 밀기다(`ItemDetailScreen`·`LocalSubmissionScreen`). `ContentView`가 `account.state.account?.accountId`의 `onChange`에서 `ContentView.shouldDropAccountScoped(previous:next:)`(이전 값이 nil이 아니고 다르면)일 때 `WLNavMotion.dropAccountScoped()` → `WLNavigator.dropAccountScoped()`를 부르고 돌려받은 id의 owner를 바로 닫는다. 모든 탭에서 첫 계정 범위 칸과 그 위를 전환 없이 뺀다(아래 설정 같은 칸은 남는다). 로그인(nil → A)은 떠남이 아니고, 첫 값은 기준값일 뿐이다(Android와 같다).
- **전환 중 정리:** 영향받은 탭의 끌어서 뒤로·push·replace는 navigator에서 바로 끝난다(뒤이은 commit·cancel·모션 끝 콜백은 자기 전환이 아니라 무시). `WLNavMotion`은 끌기 상태와 남은 뒤로 거리를 잊고, 빠진 칸이 숨긴 원래 자리 사진을 되돌리고, 그 칸의 모션 값을 지운다. 되돌림 끝의 `cancelBackGesture`도 자기 끌기일 때만 부른다. 화면은 스택만 그리므로 남은 모션은 지금 맨 위 칸에 머문다. pop 중이면 떠나는 칸의 모션은 끝까지 간다.
- **`replaceTop(route)`:** 맨 위 칸을 새 id의 칸으로 바꾸고(`sourceKey` 이어 받음) 바뀐 칸을 `exiting`으로 남겨 `.replace` 전환으로 cross-fade한다(칸 전체 `fade` 채널, 200 `easeOut` = `dialogIn`, Android와 같은 구현 기본값). 탭 첫 화면이거나 전환 중이면 false.
- **칸별 owner(`WLEntryOwners`):** `ZStack`이 모든 칸을 살려 두므로 Presenter owner 수명은 뷰가 아니라 스택을 따른다. `WishlistApp`이 runtime을 `\.wlRuntime`으로 넘기고 `ContentView`가 `WLEntryOwners`를 `@State`로 만들어 `\.wlEntryOwners`로 제공한다. 화면은 `\.wlEntryID`로 `itemDetail(id)`·`localDetail(id)`(`LocalSubmissionPresenterOwner`, Android와 같은 이름)를 받는다. 화면은 owner를 처음 나타날 때 한 번만 받아 `@State`에 둔다(은퇴한 id는 요청마다 임시 Presenter를 만들고 닫는다). navigator가 pop·끌어서 뒤로 확정·replace·drop으로 빠진 id를 모으고, `ContentView`가 "전환 없음 + 빠진 id 있음"이 되면 `drainRemoved()`의 owner를 닫는다(떠나는 모션 동안은 살아 있다). 닫은 id는 은퇴해 다시 요청하면 이미 닫힌 임시 owner를 준다.
- **상세 화면(Task 11, Android Task 10과 같은 규칙):**
  - `DetailScaffold`: 스크롤 본문 위에 떠 있는 `WLTopBar`(뒤로, 선택 ⋯), 본문은 안전 영역 + 68부터, 하단 고정 "원본 보기"(위 12·아래 36, PR B부터 웹뷰 `.web` push). `refresh`가 있으면 `.refreshable`. 위쪽 바 아래 짧은 안내 pill(3초, VoiceOver 알림, C1에 토스트가 없다).
  - `ItemDetailScreen`: `DetailKinds`로 분석 중(222 카드 + 분석 중 타일) · 완료/보완(1:1 칸 안 여백 56에 fit 사진, 브랜드·이름·가격 묶음, 정보 카드, 저장 시점) · GONE(삭제됨 + 닫기)을 고른다. ⋯는 없다(D2). 항목 없는 오류는 D12 문구 + 다시 시도(NOT_FOUND는 닫기), 항목 있는 오류는 항목 유지 + 안내.
  - owner가 화면의 칸별 사실을 든다(뷰가 다시 만들어져도 반복하지 않게): `loadOnce`, `seenWork`와 `shouldClose`(시작 뒤 Initial = 계정 세대 변경, 또는 항목 없음 + 로딩 아님 + UNAUTHENTICATED → `pop`), 실패 안내 횟수(`refreshNotices`, 같은 오류 객체는 한 번), `ForegroundTransitions`(처음 `.active`를 기준으로 넣어 배경에서 돌아올 때만 `refresh()`).
  - `refreshAndWait()`(Ruling 2): 보이는 것이 없으면 바로 끝. 아니면 `refresh()` 뒤 Presenter 상태를 50ms마다 읽어 loading = true(또는 상태 변화)를 2초까지 기다리고, 그다음 loading = false까지 기다린다. 관찰 흐름이 아니라 `state.value`를 읽어 빠른 응답의 중간 상태를 놓쳐도 끝난다.
  - `LocalSubmissionScreen`: 대기 타일 문구는 홈 줄 상태(`row.*`, LOCAL_ONLY는 `home.pending.title`), 저장 줄은 `home.pending.meta`(Ruling 7). ⋯ 메뉴는 "삭제" 하나(`canDelete && !deleting`가 아니면 0.4로 흐리고 못 누름) → D8 확인창(대상 줄 `WLDialogTarget` + 글머리표 2 + 삭제 빨강). outcome: `MovedTo` → `replaceTop(.item)`, Deleted·Gone → `pop`, RemovedOnServer → 삭제됨 + 닫기. `deleteFailed`의 상승 때마다 "지우지 못했어요".
  - 스택 변경은 `WLNavigator.whenSettled(entryID:_:)`로 한다: 전환이 끝날 때까지 30ms마다 다시 보고, 이 칸이 지금 탭의 맨 위가 아니면 포기한다(Android `whenSettled`와 같다).
  - 홈: 줄 전체(타일·글)가 버튼이고 `HomeRow.target`으로 `.item`/`.local`을 push한다(VoiceOver 힌트 "상세 보기"). "원본" 버튼의 VoiceOver 이름은 "원본 열기".
  - 문구: `DetailLine`(key + 여러 인자, 중첩 `HomeText`는 같은 표에서 먼저 푼다). 숫자는 locale 없이 쓴다(연도에 "2,025" 구분 기호가 붙지 않게).
  - 디자인 시스템: `WLLineIcon.more`·`.trash`, `WLDialogSpec.target`, `WLMenuItem.enabled`.
- **상품 사진(`RemoteImageLoader`·`ProductPhoto`):** 칸 크기(pt)·배율·방식(fill/fit)을 받아 원본 픽셀 크기에서 필요한 긴 변을 계산한다(fill은 원본 짧은 변이 칸을 덮어야 해서 칸보다 긴 변이 커진다, 원본보다 크게는 만들지 않는다). 칸 크기가 0이면 받지 않고, (주소, 칸 픽셀 크기, 방식)이 메모리 캐시 key이자 `.task(id:)` key라 캐시에 있으면 본문에서 바로 그린다. 같은 key의 동시 요청은 한 번만 받는다(lock + 진행 중 Task). 상세는 `fill: false, inset: 56`, 자리표시는 칸 전체다.

## 원본 링크 웹뷰 (C4 PR B)

> 2026-10-10 C4 Task 13·15·16. 규칙은 [C4 설계](../../superpowers/specs/2026-10-09-client-c4-product-detail-design.md#4-웹뷰) §4와 D6·D13~D16, Android 쪽은 [android.md](android.md#원본-링크-웹뷰-c4-pr-b). 화면 확인 사진은 `docs/history/architecture/client/c4-shots/ios-web-*.png`.

- **화면 위치:** `Features/Web/{WebViewRoute,WebNavigationPolicy,ExternalPromptGate,WebPageState,WebViewModel,WebShareSheet,WebViewScreen}`. `AppRoute`가 `AppDestination.web(WebPageURL)`(http/https + host만, 계정 범위, 가로 밀기·탭 바 숨김)를 `WebViewScreen`으로 그린다.
- **진입점(Task 16):** 상세·로컬 대기 `OriginalBar`와 로그인 전 홈 줄 `OriginalLink`가 body에서 `WebPageURL(string:)`을 만들어 있으면 `nav.push(AppDestination.web(page).route, sourceKey:)`("detail/original"·"home/row/<key>/original"), nil이면 `.disabled(true)` + 0.4 흐림(`WLButton`과 같은 값). `openURL`은 더 쓰지 않는다. 계정 범위라 로그아웃·계정 바뀜에 닫히고 로그인 전 → 로그인에는 남는다(Android와 같은 `dropAccountScoped` 규칙).
- **수명:** `WebViewModel`(`@MainActor @Observable`, `WKNavigationDelegate`·`WKUIDelegate`)이 `WKWebView`를 갖고 `WLEntryOwners.web(entryId, url:)`가 칸마다 하나를 준다(화면은 처음 나타날 때 한 번 받아 `@State`에 둔다, 은퇴한 id는 닫힌 임시 owner). 외부 앱을 다녀와도 페이지·기록이 그대로다(D15). pop·계정 떠남으로 칸이 빠지면 `close()`: KVO 해제 → `stopLoading` → `pauseAllMediaPlayback` → delegate 해제, 그 뒤 요청은 무시한다. `deinit`은 `stopLoading` 백업이다. 프로세스 종료 뒤에는 route URL만 다시 연다. `WebViewHost`(UIViewRepresentable)는 컨테이너 뷰에 웹뷰를 붙이고, 해체 때 자기 컨테이너 안에 있을 때만 뗀다.
- **저장소:** `WishlistWebStore.dataStore`(`WKWebsiteDataStore.default()`)를 웹뷰 configuration과 설정 `WebViewDataCleaner`가 함께 쓴다(`WebViewStoreTests`가 같은 인스턴스·persistent임을 고정).
- **탐색 판정:** `decidePolicyFor` → `decide(url:mainFrame:userGesture:)` = `WebNavigationPolicy.decide(scheme, targetFrame?.isMainFrame ?? true, navigationType == .linkActivated, isAboutBlank)`. 외부(바로·확인 후)는 main frame이든 iframe이든 WebKit에는 늘 `.cancel`이라 어느 frame에도 실리지 않는다. 외부 실행은 `UIApplication.shared.open(url, options: [:])`(`canOpenURL` 안 씀, false면 아무 일도 없음, D16). 시뮬레이터에는 전화·App Store 앱이 없어 `tel:`·`itms-apps:`는 false로 끝난다.
- **새 창:** `createWebViewWith`가 요청 URL이 `WebPageURL`(http/https + host)일 때만 같은 웹뷰에 `load(request)` 후 nil(`target=_blank`·`window.open`). 다시 main frame 탐색으로 판정된다. 그 밖의 URL(빈 새 창 `window.open()`·`about:blank`·`data:`·`javascript:`·외부 scheme)은 버린다. 빈 새 창은 페이지를 지우므로 열지 않는다(그 opener 흐름은 null 창을 받는다). `configuration.preferences.javaScriptCanOpenWindowsAutomatically = false`를 명시해(테스트로 고정) 사용자 동작 없는 팝업은 WebKit이 먼저 막는다. iframe의 `top.location` 바꾸기는 WebKit 기본 정책(교차 출처 iframe은 사용자 동작 필요)에 맡긴다.
- **확인창 반복 차단(`ExternalPromptGate`, Android와 같은 벡터):** 확인창은 한 번에 하나, 취소하면 main frame이 **다른 host**(`DisplayFormat.shared.host`, 소문자·`www.` 뺌)로 `didStartProvisionalNavigation`할 때까지 탭 없는 외부 요청을 버린다(Ruling 13: 같은 host의 재로드·`?n=2`도 풀지 않음). 탭은 늘 바로 연다. overlay가 확인창을 못 띄우면(`showDialog` false·화면 없음) 막지 않는다. 화면이 `presentPrompt`를 넣고, 확인창은 `WLConfirmDialog` FWebViewExternal(48 오프라인 상태색 타일 + phone, `webview.external.*`, 열기 먹색). iOS `WLDialogSpec`에도 `icon`·`onDismissed`(취소·확인·escape 모두 닫힘 모션이 끝난 뒤 한 번)를 더했다.
- **gesture 범위(D16, Ruling 12):** 사용자 탭 = `navigationType == .linkActivated` **그리고** 최근 1.0초 안에 웹뷰에 실제 손가락이 닿았음(`WebUserGesture.isUserGesture(navigationType:lastTouch:now:)`, 순수 함수 XCTest). 스크립트의 `a.click()`·합성 click도 `.linkActivated`로 오기 때문이다. 닿음은 `WebViewHost` 컨테이너의 `WebTouchStampRecognizer`(touchesBegan에서 `systemUptime`을 model에 남기고 바로 failed, `cancelsTouchesInView`·`delays*` false, 모든 인식기와 동시 인식)가 기록해 스크롤·링크·끌어서 뒤로를 방해하지 않는다. 탭 뒤 JS로 바꾸는 이동(`setTimeout(location=…)`)은 `.other`라 확인창이 뜬다(Android `hasGesture()`보다 좁다). 시뮬레이터: 터치 없이 1.5초 뒤 `a.click()`한 `tel:` → 고치기 전 확인창 없이 실행(confirm=0), 고친 뒤 확인창, 실제 탭 `itms-apps:`·`tel:` → 바로 실행.
- **막대:** 위쪽 `WLTopBar`(배경색, 닫기 `webview.close` · 가운데 자물쇠 13(https만) + `DisplayFormat.host` 14/700 · 제목 12/500 보조색, 제목이 비거나 URL이면 도메인만, 오른쪽 44 빈 자리) + 2px 진행 선(`isLoading`이고 `estimatedProgress` < 1). 값은 `url`·`title`·`isLoading`·`estimatedProgress`·`canGoBack`·`canGoForward` KVO → `WebPageState`(순수, XCTest). 바의 URL은 웹 URL일 때만 바꾼다(`adopt`, `about:blank` 등은 마지막 웹 페이지의 도메인·자물쇠를 유지). 아래쪽(위 8·좌우 12, 아래 max(32, 홈 표시줄 + 8)): 뒤로(기록 없으면 닫기)·앞으로(기록 없으면 `underline` 색, 비활성) | 새로고침(로딩 중 중지)·공유. VoiceOver escape도 기록 먼저. 키보드 안전 영역은 무시한다(페이지가 스스로 입력칸을 올린다).
- **끌어서 뒤로:** `allowsBackForwardNavigationGestures = true`. 기록이 있으면 화면이 `WLNavMotion.setEdgeBackBlocked(entryID, true)`로 셸의 왼쪽 가장자리 pan(`canBeginDrag`)을 끄고 WKWebView 스와이프가 뒤로 간다. 기록이 없을 때만 셸 pop이다.
- **오류:** main frame `didFailProvisionalNavigation`·`didFail`(WebKit은 iframe 오류를 여기 알리지 않는다)만 `webview.load.failed` + `detail.retry` 덮개(`DetailStatusBlock`). `NSURLErrorCancelled`와 WebKit 102(frame load interrupted)는 오류가 아니다. 다시 시도는 실패한 URL(`NSURLErrorFailingURLErrorKey`)을 연다. web content 프로세스가 죽으면 같은 덮개.
- **공유 시트(FWebViewShare):** `OverlayHost.showSheet`(블러 배경). 현재 페이지 URL(웹 URL이 아니면 route URL, `WebPageState.shareURL`)로 외부 브라우저(`UIApplication.open`, 웹뷰 유지)·링크 복사(`UIPasteboard.general.string`, iOS는 앱 복사에 시스템 표시가 없어 늘 "링크를 복사했어요" `DetailNoticeLine`)·다른 앱(`UIActivityViewController`, key window 맨 위 VC에서, iPad popover 기준점 아래 가운데). 줄을 누르면 시트를 닫고 실행한다.
- **아이콘:** `WLLineIcon.close·lock·reload·share·globe·copy·apps·phone`(Android와 같은 path).
- **시뮬레이터 확인(2026-10-10, iPhone 17 Pro, `http://127.0.0.1` 시험 페이지, 커밋하지 않은 launch argument push):** 링크 → 제목·뒤로/앞으로, `target=_blank` 같은 웹뷰, `tel:` 탭은 확인창 없음(앱 없음 → 아무 일도 없음), 탭 1.5초 뒤 JS `tel:` → 확인창, 취소 뒤 같은 페이지에서 다시 → 확인창 없음, 광고 iframe의 `itms-apps:` 반복 → 확인창 한 번·취소 뒤 버림·어느 frame도 이동 안 함, 공유 시트·링크 복사(pasteboard 확인 + 안내)·`UIActivityViewController`, 기록 없을 때 가장자리 끌기 → pop, 기록 있을 때 셸 pop 안 됨, 연결 거부 → 실패 덮개, 제목 없는 페이지 → 도메인만. http://localhost(IP)는 ATS 예외 없이 열렸다.
- **남은 점:** 합성 터치(serve-sim)로는 WKWebView 자체의 기록 스와이프가 동작하지 않아 손가락 확인이 남았다. WebKit 제한 포트(예: `:1`)는 실패 콜백 없이 빈 페이지다. 흰 페이지 위의 "링크를 복사했어요" pill(카드색)은 대비가 약하다(Android와 같은 부품). "외부 브라우저로 열기"는 앱을 떠나므로 시뮬레이터에서 누르지 않았다.
