# Android 구조

> 상태: **확정** — Kotlin + Jetpack Compose + `ACTION_SEND` 기반 Share Receiver

## 역할

- Jetpack Compose로 화면과 navigation을 구성한다.
- 상품 링크 웹뷰를 Compose navigation 안에서 열고, 공통 웹뷰 탐색·외부 앱·쿠키 정책을 구현한다.
- `ACTION_SEND` intent에서 `text/plain` 형태의 URL을 읽는다.
- intent 수신 후 URL을 검증·정규화하고 KMP 저장 UseCase로 연결한다.

## 주의점

- 앱이 실행 중이 아닐 때도 intent가 들어올 수 있으므로 navigation과 pending URL 복구를 설계한다.
- URL이 아닌 공유 텍스트와 여러 URL이 포함된 텍스트의 MVP 처리 방침을 명확히 한다.
- UI layer가 네트워크·추출 상태를 직접 다루지 않게 한다.
- 시스템 뒤로 가기는 웹페이지 방문 기록을 먼저 처리하고, 기록이 없을 때 웹뷰 화면을 닫는다.

## 탭 셸과 화면 이동 (C1)

- Navigation Compose를 쓰지 않는다. `navigation/WLNavigator`가 탭별 독립 스택과 현재 탭·전환을 Compose snapshot 상태로 가지고(단위 테스트 대상), `WLNavHost`가 그린다. 스크롤 요청만 SharedFlow 이벤트다. 탭 값의 Flow 수집 지연과 전환 상태가 한 프레임 어긋나지 않는다. 모션 원본은 [motion.md](../../../design/handoff/interactions/motion.md) 2·3절이다.
- `WLRoute` 인터페이스의 `showsTabBar`·`pushStyle`은 feature가 정한다. 라우터는 데모 경로나 id 앞머리를 해석하지 않고, 앱의 route renderer가 구체적인 feature 화면을 고른다.
- 전환 중 입력: `push`·`pop`·`selectTab`은 전환을 시작하고, 화면이 모션을 끝내면 `finishTransition()`을 부른다. 그동안 들어온 이동·탭·뒤로는 무시한다. 뒤로는 시스템에 넘기지 않고 받아서 버린다.
- 탭 전환은 `AnimatedContent` 페이드 스루, 탭 상태는 `SaveableStateHolder`(탭 → 스택 칸 순서로 두 겹)로 유지한다. 현재 탭을 다시 누르면 `scrollToTopRequests`로 맨 위 스크롤을 요청한다.
- 화면 이동은 `SharedTransitionLayout` + 탭마다 `SeekableTransitionState`를 쓰는 `AnimatedContent`다. 사진 이동은 `sharedElement`(`wlSharedPhoto`)이고, 사진 없는 이동(`WLPushStyle.Slide`)은 위 칸이 W → 0, 아래 칸이 0 → −0.25W로 움직이는 가로 밀기다(360/300 `emphasized`, 페이드·그림자 없음). 끌어서 뒤로 중에는 선형 시간표로 위치가 진행값과 같고, 놓은 뒤 남은 구간도 그 시간표로 끝난다. 탭 바는 화면 내용 위에 따로 그려 밀리지 않으며, 두 화면이 모두 탭 바를 보이면 고정이다.
- 뒤로는 `PredictiveBackHandler` 하나로 처리한다. 끄는 동안 pop 전환을 진행값으로 되감고, 놓을 때 50% 이상이거나 빠르게 놓았으면(진행 속도 초당 1.5 이상, 구현 기본값) 확정, 아니면 되돌린다. 진행값 없이 끝나는 뒤로(API 33 미만, 3버튼 내비게이션, 화면의 뒤로 버튼)는 같은 pop 전환을 처음부터 재생한다. overlay가 떠 있는 동안에는 꺼진다(overlay의 `BackHandler`만 받는다).
- `MainActivity`는 `windowSoftInputMode="adjustResize"`(키보드는 `imePadding`으로만 피한다. 기본 pan이면 창이 한 번 더 밀린다)이고 `configChanges="uiMode"`로 테마가 바뀌어도 다시 만들지 않는다. 색은 `isSystemInDarkTheme()`로 바로 바뀌고 탭 스택·열린 시트가 유지된다. 글자 크기·회전 변경은 Activity를 다시 만들지만 navigator의 탭·스택·entry id·nextId를 저장해 복원한다. UI 람다를 보유한 열린 overlay는 초기화된다.
- 탭 바 글자는 글자 크기 배율을 1.3까지만 따른다(칸 폭 안에 "카테고리"를 넣기 위해서).
- 데모 화면과 경로는 `src/debug/kotlin/…/feature/demo`에 있다. `ui/AppRoute`와 `AppRouteCodec`은 main의 단일 진입점이다. variant별 `VariantRoutes`가 데모 renderer·codec만 제공하며 release에는 데모가 없다. 실제 기능은 main dispatcher·codec에 한 번만 추가한다.
- `LocalOverlayHostState`·`LocalWLNavigator`는 host가 없으면 오류로 실패한다. 기본 임시 저장소나 nullable 호출로 잘못된 계층을 숨기지 않는다.

- 앱 루트가 `rememberWLNavigator(AppRouteCodec)`를 소유하고 `OverlayHost` 바깥에서 CompositionLocal로 제공한다. 시트·메뉴·확인창도 같은 navigator를 읽는다.
- `WLRouteCodec`은 feature route를 저장 가능한 문자열 목록으로 변환한다. saver는 현재 탭·스택·칸 식별자·sourceKey·nextId를 저장하며 진행 중 전환은 복원 시 완료 상태로 정리한다. `SaveableStateHolder`의 정리 대상 칸 목록도 저장해 pop된 칸의 상태를 제거한다. 실제 기능을 추가할 때 codec 복원 계약을 함께 구현한다.
- 입력은 부모 문자열 callback을 거치지 않는 단일 `TextFieldState`다. 조합 중에는 보존하고 commit 뒤 grapheme 제한을 적용한다. API 26 ICU·실기기 한글 IME 확인은 [C3 확인 목록](c3-performance-checks.md)에 있다.

## 공유 runtime 연결 (C2)

- `WishlistApplication`(manifest `android:name`)이 프로세스당 `SharedRuntime` 하나를 소유한다. `SharedRuntimeFactory.create(context, bindings, remote)`에 `AppRuntimeConfig.bindings(BuildConfig.DEBUG)`와 `remote = null`을 넘긴다. DEBUG는 ITEM-01·03 FAKE와 나머지 UNAVAILABLE, RELEASE는 37개 모두 UNAVAILABLE이다.
- 조립 직후 variant별 `di/VariantStartup`을 부른다. debug는 `di/DebugSessionBootstrap`으로 `startDebugSession()`을 호출하고, release는 아무것도 하지 않는다(release APK dex에 `DebugSessionBootstrap` 없음). `VariantRoutes`와 같은 debug/release source set 방식이다.
- Robolectric이 없어 Application은 JVM에서 실행하지 않는다. `testDebug`/`testRelease`의 `AppRuntimeConfig*Test`가 각 variant가 넘기는 mode·37개 map을 순수 함수로 검증하고, runtime 동작은 shared commonTest가 검증한다.
- `feature/detail/ItemDetailPresenterOwner`는 `ItemDetailPresenter` 하나의 수명 소유자인 `ViewModel`이다(UI 없음). `factory(runtime)`가 `runtime.itemDetailPresenter()`로 Presenter를 만들고, 구성 변경 동안 유지하며 `onCleared()`에서 `close()`한다. `state`는 Presenter의 thread-safe StateFlow 그대로이고 repository 작업은 runtime의 background dispatcher에서 실행되므로 C4 화면은 main에서 수집만 한다. activity-compose가 가져오는 lifecycle-viewmodel(2.9.4)을 쓰며 catalog 항목은 추가하지 않았다. JVM 테스트 `ItemDetailPresenterOwnerTest`가 `ViewModelStore.clear()`로 Presenter 종료(진행 요청 취소·이후 intent 무시)를 Robolectric 없이 검증한다.
