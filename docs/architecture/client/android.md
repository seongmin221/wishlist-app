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
- debug `MainActivity`는 새로 만들어질 때(`savedInstanceState == null`) `VariantStartup.onMainLaunch`로 launch intent의 시연 extra를 `di/DebugLaunchHooks`에 넘긴다: `--el wl.fake.delayItem01 <ms>`(다음 Fake ITEM-01 지연), `--ei wl.fake.pendingCount <N>`(미귀속 대기 N개 생성). release의 `onMainLaunch`는 아무것도 하지 않는다. hook은 cold start에서만 적용된다: 이미 떠 있는 `MainActivity`에 보낸 intent와 재생성(저장 상태 있음)은 extra를 읽지 않으므로, `-S`로 앱을 먼저 강제 종료하고 띄운다.
  ```
  adb shell am start -S -n app.wishlist.android/.MainActivity --el wl.fake.delayItem01 5000
  adb shell am start -S -n app.wishlist.android/.MainActivity --ei wl.fake.pendingCount 100
  ```
- Robolectric이 없어 Application은 JVM에서 실행하지 않는다. `testDebug`/`testRelease`의 `AppRuntimeConfig*Test`가 각 variant가 넘기는 mode·37개 map을 순수 함수로 검증하고, runtime 동작은 shared commonTest가 검증한다.
- `feature/detail/ItemDetailPresenterOwner`는 `ItemDetailPresenter` 하나의 수명 소유자인 `ViewModel`이다(UI 없음). `factory(runtime)`가 `runtime.itemDetailPresenter()`로 Presenter를 만들고, 구성 변경 동안 유지하며 `onCleared()`에서 `close()`한다. `state`는 Presenter의 thread-safe StateFlow 그대로이고 repository 작업은 runtime의 background dispatcher에서 실행되므로 C4 화면은 main에서 수집만 한다. activity-compose가 가져오는 lifecycle-viewmodel(2.9.4)을 쓰며 catalog 항목은 추가하지 않았다. JVM 테스트 `ItemDetailPresenterOwnerTest`가 `ViewModelStore.clear()`로 Presenter 종료(진행 요청 취소·이후 intent 무시)를 Robolectric 없이 검증한다.

## 공유 수신·로그인·홈·설정 (C3)

> 2026-10-07 C3 Task 5·7. 공유 수신 방식은 [ADR-030](../../history/architecture/client/ADR-030-share-receipt-mode.md)(C3-D1): Android 공유 Activity는 앱과 같은 프로세스의 `SharedRuntime`으로 받아 즉시 로컬 저장하고, 로그인·온라인이면 바로 전송을 요청한다. 전송·재전송·오류 분류는 KMP `SubmissionCoordinator`가 맡고 Android는 신호만 준다([KMP 구조](kmp.md#공유-수신전송-조정기submissioncoordinator)).

- **화면 위치(C3-D10: 모듈은 나누지 않는다):** `feature/login/LoginScreen`(FLogin, `LoginMode.FirstRun`·`Pushed`), `feature/home/{HomeScreen,HomeLoggedOutContent,HomeLoggedInContent,HomeRowText}`(FHomeLoggedOut, FHome의 "분류 중" 카드만 — C3-D3), `feature/settings/SettingsScreen`(FSettings 4상태), `share/{ShareReceiverActivity,ShareCard,ShareIntentText}`, `platform/{NetworkSignals,ForegroundSignals,WebViewDataCleaner}`. feature 폴더 경계와 "feature는 `finishTransition`을 호출하지 않는다" 규칙을 유지하고 C5 카테고리 feature 때 모듈 분리를 다시 본다.
- **route:** `ui/AppRoutes.kt`의 `SettingsRoute`·`LoginRoute`(가로 밀기, 탭 바 없음)와 `ProductionRouteCodec`(`["settings"]`·`["login"]`)를 `AppRouteCodec`이 variant codec보다 먼저 쓴다. `AppRoute`가 홈 탭 첫 화면·설정·로그인을 debug·release 공통으로 그리고, 나머지(카테고리·목적 데모)는 `VariantRoutes`에 맡긴다.
- **Presenter owner:** `AccountPresenterOwner`(`feature/session`)·`HomePresenterOwner`는 `MainActivity`의 ViewModelStore에 둔다(로그인 상태는 앱 전체가, 홈 목록은 스택에서 빠지지 않는 홈 탭 첫 화면이 쓴다). `WishlistApp(account, home)`이 `LocalAccountOwner`·`LocalHomeOwner`로 제공한다. viewmodel-compose가 의존성에 없어 Compose `viewModel()` 대신 Activity에서 `ViewModelProvider`로 만든다. Presenter 생성자가 `:shared` internal이라 `AccountPresenterOwnerTest`는 reflection으로 Presenter를 만들어 `ViewModelStore.clear()` 뒤 intent가 무시되는지 본다.
- **첫 실행 로그인:** `WishlistApp`이 `OverlayHost`+탭 셸 위에 전체 화면 레이어로 그린다(`showFirstRunLogin`일 때만, 탭 바 없음, 아래 층은 입력·접근성에서 가림). 사라짐은 opacity 260 `accelerate`(구현 기본값). 홈 로그인 카드·설정 "로그인"은 같은 화면을 `LoginRoute`로 push하고, 로그인 전 → 로그인으로 바뀌면 전환이 끝난 뒤 pop한다. 로그인 중(`signingIn`)에는 로그인·로그아웃 버튼을 막고, `AccountState.error`는 C3에서 보여 주지 않는다(RELEASE는 인증이 없어 버튼이 아무 일도 하지 않는다).
- **홈:** `HomeState.Loading`은 머리만 그린다(계정 전환 중 이전 계정 줄이 비치지 않게). 로그인 뒤는 material3 1.4.0 `PullToRefreshBox`로 `HomePresenter.refresh()`. 줄 key(`local-…` → `item-…`)는 Compose `key`로만 쓴다. 머리 보조 줄은 로그인 전 "로그인 전", 로그인 뒤 "할 일 N개"(N = 분류 중 줄 수, Ruling 13). "원본"(`Intent.ACTION_VIEW`, C3-D5 a)은 로그인 전 분석 대기 줄에만 있고 로그인 뒤 분류 중 줄에는 오른쪽 동작이 없다(Ruling 15: 보드의 삭제는 C4/C8). 문구 매핑(`RelativeTime`·`RowStatus` → 문구 표 키)은 `HomeRowText`가 순수 함수로 갖고 JVM 테스트로 전수 검증한다.
- **설정:** 로그아웃(먹색)·웹뷰 데이터 삭제(빨강)는 `WLConfirmDialog`. 웹뷰 삭제는 `CookieManager.removeAllCookies`(끝나면 `flush`)·`WebStorage.deleteAllData`·`WebView.clearCache(true)` 후 `destroy`(main thread)이고 "방금 삭제했어요"는 화면 수명(`rememberSaveable`) 동안만이다. 버전은 `BuildConfig.VERSION_NAME`, 오픈소스 라이선스 줄은 C12까지 숨긴다.
- **공유 Activity:** `ShareReceiverActivity`(exported, `excludeFromRecents`, `noHistory`, `taskAffinity=""`, `Theme.Wishlist.ShareCard` 투명·dim 없음·창 애니메이션 없음). `ShareIntentText`가 `EXTRA_TEXT` 우선, 없으면 `EXTRA_SUBJECT`, 둘 다 있고 글에 링크가 없으면 `"$subject $text"`를 넘기고 링크 추출·검증은 KMP parser가 한다. 저장은 Activity·ViewModel이 아니라 `WishlistApplication.appScope`에서 돈다(카드를 일찍 벗어나거나 `noHistory`로 닫혀도 저장이 취소되지 않음). `ShareReceiveModel`(ViewModel)이 그 결과를 들고 있어 구성 변경에도 한 번만 받는다. 프로세스 종료 뒤 복원(`savedInstanceState != null`인데 ViewModelStore가 남지 않음)이면 다시 받지 않고 바로 닫는다(죽은 인스턴스가 이미 저장했으므로 새 key의 중복 줄을 막는다, `ShareLaunch.shouldReceive`). 카드 종류가 정해진 뒤에만 340 `standard`로 올리고 1500 유지, 260 `accelerate`로 내린 뒤 `finish()`(API 34+ `overrideActivityTransition`, 아래는 `overridePendingTransition(0, 0)`). 카드는 아래 40(내비게이션 막대가 높으면 그 위 16)·좌우 16, 이동 거리는 140과 "카드 + 아래 여백" 중 큰 값.
- **늦은 저장의 파일 inbox(3차 리뷰, 사용자 확정):** 공유 Activity의 `receiveShared`가 1500ms 안에 저장하지 못하면(콜드 스타트·저장 실패) `ShareInbox`(`filesDir/share-inbox`, `<key>.tmp`를 쓴 뒤 `<key>.share`로 이름 변경, 줄: 형식 `1`·key·sharedAt·URL)에 미귀속 record로 쓰고 `DEFERRED` 카드("위시리스트에 저장했어요 / 앱을 열면 정보를 가져와요")를 보인다. `WishlistApplication.importShareInbox()`가 그 직후와 매 프로세스 시작 때 `importInbox`(ready 무기한 대기)로 가져와 deletable만 지우고 flush를 요청한다. 깨진 `.share`는 지우고, 죽은 프로세스가 남긴 `.tmp`는 불완전할 수 있어 60초 뒤 지운다(`ShareInboxTest`). 홈 화면은 보이는 동안 1분마다 `HomePresenterOwner.tick()`을 부른다.
- **신호(전송은 KMP coordinator가 single-flight로 합친다):** `ForegroundSignals`(ActivityLifecycleCallbacks로 시작된 Activity 수 0 → 1, 공유 Activity·구성 변경 제외 — lifecycle-process는 선언된 의존성이 아니라 쓰지 않는다)의 전이마다 `refresh()`(iOS와 같은 순간, PR #12 리뷰에서 Application 시작 때의 별도 refresh를 없애 cold start refresh가 두 번 돌지 않는다. 공유 카드만 띄운 프로세스는 refresh하지 않는다). 전이 판단은 Android 타입 없는 `ForegroundTransitions`로 나눠 `ForegroundTransitionsTest`(JVM)로 검증한다. `NetworkSignals`(`registerDefaultNetworkCallback`, 기본 네트워크가 INTERNET+VALIDATED가 아니었다가 되는 전이, 시작 때 기준값을 읽어 온라인 시작은 복구로 세지 않음)에서 `requestFlush()`. 공유 카드의 온라인/오프라인은 `NetworkSignals.isOnline`(active network INTERNET+VALIDATED). `ACCESS_NETWORK_STATE` 권한.
- **카드 종류(C3-D9):** 공유한 순간 상태로 정한다. 로그인 전 LOCAL("이 기기에 저장했어요 / 로그인하면 정보를 가져와요"), 로그인+온라인 SAVED("위시리스트에 저장했어요 / 정보를 가져오는 중이에요"), 로그인+오프라인 OFFLINE("이 기기에 저장했어요 / 다음에 앱을 열면 보내요"), 링크 없음·2048자 초과 INVALID, 저장 실패 STORE_FAILED. 제목·보조 줄 간격은 보드와 iOS처럼 6이다(`de01ded`).
- **에뮬레이터 확인(2026-10-07, API 36):** 첫 실행 안내 → 나중에 하기 → 로그인 전 홈, 공유 → LOCAL 카드, `am force-stop` 뒤 대기 줄 유지(실제 Android SQLite `wishlist.db`), 링크 없는 글 → 실패 카드, Google 로그인 → 분류 중 → 5초 뒤 당겨서 새로고침으로 완료, 오프라인 공유 → OFFLINE 카드·"연결되면 보내요" → 네트워크 복구 때 자동 전송, 로그아웃 → Apple 로그인 뒤 Google의 미전송 줄 안 보임(DB에는 Google binding `PENDING`으로 남음), 웹뷰 데이터 삭제 → "방금 삭제했어요".
- **시연 hook 확인(C3 Task 7):** 위 debug extra로 전송 중 계정 전환(binding 유지, 다른 계정에 안 보임, 원래 계정으로 돌아오면 같은 key 전송)과 전송 중 `am force-stop` 뒤 재실행 시 같은 key 재전송을 확인했다. 기록은 [C3 검증 기록](../../history/architecture/client/c3-verification-2026-10-07.md).

## C3 남은 점

- 한글 줄바꿈: 보드는 `word-break: keep-all`인데 Compose는 글자 단위로 끊는다(설정 "다른 기기에/서도"). `WLText` 전체 정책으로 정한다(`LineBreak.WordBreak.Phrase`는 API 33+). [디자인 시스템 알려진 한계](design-system.md#알려진-한계).
- 공유 Activity 창은 전체 화면 투명이라 카드가 보이는 약 2.1초 동안 아래 앱의 터치를 받는다(brief가 정한 테마, 카드 크기 창으로 줄이면 해결).
- `ShareLaunch` 복원 판단은 `lastNonConfigurationInstance`에 기대며 Activity 수준 자동 테스트(Robolectric·instrumentation)가 없다. `NetworkSignals`의 전이 판단도 JVM 테스트가 없다(`ForegroundSignals`의 판단은 `ForegroundTransitionsTest`로 검증).
- `AccountPresenterOwnerTest`는 internal 생성자 때문에 reflection으로 Presenter를 만든다(생성자 시그니처가 바뀌면 runtime에 실패).
- 웹뷰 "방금 삭제했어요"는 `rememberSaveable`이라 프로세스 종료 복원 뒤에도 남을 수 있고, 일부만 지워진 경우에도 성공으로 보일 수 있다.
- 홈 목록은 lazy가 아니다(`Column` + `verticalScroll`). 대기 300개에서 jank 5.6%·PSS 122MB(에뮬레이터). 실기기 profiler 뒤 정한다([C3 성능 확인](c3-performance-checks.md#c3-측정-결과-2026-10-07)).
- 이번 에뮬레이터 DNS 문제로 온라인 SAVED 카드 화면과 네트워크 복구 자동 전송을 Task 7에서 다시 찍지 못했다(Task 5에서는 확인).
