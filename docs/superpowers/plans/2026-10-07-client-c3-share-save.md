# Client C3 Share & Save Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 시스템 공유로 받은 상품 링크를 잃지 않고 로컬에 보관하고, fake 로그인 뒤 같은 key로 ITEM-01 전송·재전송해 서버 항목으로 바꾸며, 로그인 안내·로그인 전 홈·로그인 뒤 "분류 중" 카드·설정을 두 플랫폼에서 같은 Presenter로 완성한다.

**Architecture:** KMP `SubmissionCoordinator` 하나가 공유 수신·iOS inbox 가져오기·binding 영속·전송·결과 반영·오류 분류를 소유하고, 플랫폼은 실행·foreground·네트워크 복구·로그인·새로고침 신호만 준다. 인증은 KMP `AuthFacade` 뒤의 fake 구현이며 나중에 Firebase로 교체한다. iOS Share Extension은 Shared.framework 없이 app group `inbox/`에 공유 1건 = JSON 파일 1개를 쓰고, 본 앱이 가져와 SQLite에 넣는다(다중 프로세스 DB 공유 없음). Android 공유 Activity는 같은 프로세스의 runtime으로 즉시 수신·전송한다.

**Tech Stack:** Kotlin 2.3.21, AGP 9.0.0, Gradle 9.3.0, SQLDelight 2.4.1, Koin 4.2.2, Ktor 3.4.3, SKIE, kotlin.test·coroutines-test·Turbine. Android Compose(API 26+), iOS SwiftUI(17+) + Share Extension(app extension target), Xcode 26.6.

**Spec:** [확정 로드맵 C3 행](../specs/2026-10-05-client-implementation-roadmap-design.md#단계), [C2 계획 후속 단계 인계](2026-10-07-client-c2-kmp-core.md#후속-단계-인계), [KMP 알려진 한계](../../architecture/client/kmp.md#알려진-한계와-인계-단계), [상품 저장](../../product/save-a-product.md), [상품 상태·API 계약](../../architecture/wishlist-item-state-api.md), [QA-CLI-002](../../learning/client/q-and-a/QA-CLI-002-share-receipt-feedback.md), [C3 성능 확인 목록](../../architecture/client/c3-performance-checks.md), 보드 `design/handoff/screens/boards/{FLogin,FHomeLoggedOut,FShareSaved,FShareSavedLocal,FShareSavedOffline,FSettings,FSettingsLoggedOut,FSettingsLogout,FSettingsWebviewClear}{L,D}.dc.html`, [화면 동작](../../../design/handoff/interactions/screens.md#로그인--설정--공유-수신--웹뷰), [모션 6. 공유 저장 카드](../../../design/handoff/interactions/motion.md#6-공유-저장-카드).

## Global Constraints

- 상태: **승인 v1 · Task 0~7c 완료, Task 8 Step 1·2·4 완료, 최종 리뷰·수정 완료, PR 생성 (2026-10-09)**. 설계 결정 C3-D1~D10은 이번 세션 대화에서 사용자가 승인했다. 실행 중 정한 판단은 아래 [실행 중 결정(ruling) 요약](#실행-중-결정ruling-요약)에 있다.
- 작업 공간 `/Users/user/orca/workspaces/wishlist-app/client-c3-share-save`, 브랜치는 Orca가 만든 `seongmin221/client-c3-share-save`에서 사용자 확인 뒤 관례 이름 `client/c3-share-save`로 바꿨다(2026-10-09). PR base `develop`. 시작 HEAD `321835d6c2e916a1af2a8ebfdd9ec5b32fb4f063`(PR #10 merge). merge는 사용자 승인 없이 하지 않는다.
- C2 공간(`…/client-c2-kmp-core`)과 서버 B 공간은 수정·삭제하지 않는다.
- Kotlin **2.3.21**, Android **API 26+**, iOS **17+**, JDK **17**. 공유 코드에 Compose/SwiftUI 의존성 없음. 새 라이브러리 의존성 추가 없음(필요해 보이면 구현 전에 사용자 확인).
- 인증은 **fake만**. Firebase·Apple·Google 실제 연결, iOS 확장 직접 전송 활성화, ITEM-01 실서버 연결은 Apple Developer 가입 뒤 별도 "인증 연결" 단계.
- RELEASE는 fake를 포함하지 않는다. RELEASE `AuthFacade`는 `UNAVAILABLE`을 돌려주며 로그인 전 상태로만 동작한다(로컬 공유 저장은 동작).
- 같은 로컬 공유의 재전송은 같은 UUID key, 새로운 공유는 같은 URL이라도 새 key. **최초 POST 전에** 미귀속 pending의 binding을 영속 commit한다. binding은 한 번 정해지면 바뀌지 않는다. 현재 계정에 묶인 항목만 보낸다.
- 갱신은 신규 실행·foreground·네트워크 복구·로그인·당겨서 새로고침. polling·push·오프라인 편집 큐 없음.
- 미확정 wire 필드·오류는 `CONTRACT-PENDING(<API-ID>)`를 DTO/매퍼에만 둔다. domain은 JSON·HTTP를 참조하지 않는다.
- domain·Presenter 상태 전이·LocalStore·Coordinator는 TDD. `commonTest`는 Android host와 iOS simulator 모두 실제 실행하고 `NO-SOURCE`/`SKIPPED`를 통과로 기록하지 않는다.
- 커밋: `feature(kmp|android|ios): 한글 설명` / `bugfix: …` / `docs: …` + 빈 줄 + 짧은 본문. 이슈 번호가 생기면 `[#번호]` 접두사.
- 새 문구는 아래 [문구 표](#문구-표)의 한국어·영어를 그대로 쓴다(영어 지원 유지 여부는 별도 제품 결정이며, lint의 `MissingTranslation`을 막기 위해 영어도 넣는다).
- 시스템 시트·탭·push 전환은 쓰지 않는다. 카드·확인창·push는 C1 부품(`WLConfirmDialog`, `WLNavigator` push slide, 토큰 곡선)을 쓴다.
- Android 에뮬레이터 `emulator-5554`(en-US) 조작 전 `adb shell dumpsys window | grep mCurrentFocus`로 `app.wishlist.android` 포커스를 확인하고 다른 앱은 건드리지 않는다.

## Review Focus

1. **계정 전환·로그아웃이 전송 중에 일어날 때:** binding은 유지되고 응답은 버려지며, 다른 계정은 그 항목을 보지도 보내지도 않는다. 같은 key가 두 owner로 POST되지 않는다(Task 3 `accountSwitchDuringPostKeepsBindingAndNeverSendsFromOtherOwner`).
2. **프로세스 중단:** `SUBMITTING` 저장 뒤·accept 전, iOS inbox import 뒤·파일 삭제 전에 앱이 죽어도 URL을 잃거나 서버 항목이 중복되지 않는다(Task 3 `staleSubmittingIsResentWithSameKey`, `reimportingSameRecordIsNoOp`; Task 6 `InboxReaderTests.testFileDeletedOnlyAfterImport`).
3. **지저분한 공유 글:** 한글 문장+URL, 끝 문장부호·괄호, URL 여러 개, URL 없음, 2048자 초과, 대문자 scheme, 전각 공백 — Kotlin parser와 Swift extractor가 같은 결과(Task 3 `ShareTextParserTest` 표와 Task 6 `ShareTextExtractorTests`의 **같은 벡터**).
4. **신호 중복:** foreground·네트워크 복구·당겨서 새로고침·공유가 동시에 와도 flush는 하나만 돌고 같은 key가 동시에 두 번 POST되지 않는다. 같은 글을 두 번 공유하면 key가 둘이다(Task 3 `concurrentTriggersRunSingleFlightWithRerun`, `sameTextSharedTwiceCreatesTwoKeys`).
5. **C2 개발 DB:** 개발 기기에 남은 v1 DB(ISO 텍스트 시각, `ACCEPTED` 상태 포함)가 crash 없이 v2로 열리고, 같은 초 안의 순서가 맞다(Task 1 `migratesV1RowsWithMillisecondOrder`).

## 선택한 결정

| 번호 | 결정 사항 | 선택한 안 | 대안·영향 |
| --- | --- | --- | --- |
| C3-D1 | 공유 확장의 서버 직접 전송 | **C안**: iOS 확장은 app group `inbox/`에 공유 1건 = JSON 파일 1개를 쓰고, 나중에 background URLSession으로 같은 key POST를 시스템에 맡긴다. 앱은 실행·foreground 때 가져와 같은 key로 재전송해 결과를 받는다(멱등 replay). **C3에서는 확장 전송 자리만 두고 끈다.** Android는 같은 프로세스라 수신 즉시 전송 | A(확장이 Shared.framework·Firebase로 직접 전송): 다중 프로세스 DB·확장 수명·Keychain 비용. B(로컬만): 공유만으로 분석이 시작되지 않음 |
| C3-D2 | 실제 인증 연결 범위 | C3는 fake `AuthFacade`만. Firebase·Apple·Google, 확장 전송, ITEM-01 실서버는 가입 뒤 "인증 연결" 단계 | Android만 실제 Google: 플랫폼이 갈리고 범위 증가. 전부 실제: 가입 전이라 막힘 |
| C3-D3 | 로그인 뒤 홈 | FHome 틀에 "분류 중" 카드만 실제 데이터(이 계정의 미전송 + 캐시의 `PROCESSING`). 다른 할 일 카드는 C7까지 숨김. 다시 보내기 버튼 없이 당겨서 새로고침이 재전송+갱신 | C1 데모 유지: C3에서 전송 결과를 화면으로 볼 수 없음 |
| C3-D4 | 링크 없는 공유 | 첫 http(s) 링크를 꺼냄. 없거나 2048자 초과면 실패 카드 "링크를 찾지 못했어요 / 상품 페이지에서 다시 공유해 주세요", 저장 안 함 | 조용히 닫기: 제품 원칙("잃지 않는다") 위반 |
| C3-D5 | 작은 기본값 a~k | a 원본=시스템 브라우저(C4에서 웹뷰) · b 라이선스 줄 숨김(C12) · c 버전=앱 버전 · d 로그아웃=그 계정 캐시 삭제, 그 계정 미전송은 보존·숨김 · e 로컬 대기 삭제 UI는 C4/C8 · f 첫 실행 안내 1회, 건너뛰면 다시 자동 표시 안 함 · g 로그인 성공 시 미귀속 전체 binding 먼저 commit 후 전송 · h 웹뷰 데이터 삭제는 플랫폼 기본 저장소 전체, "방금 삭제했어요"는 화면 수명 동안만 · i Apple/Google 서로 다른 fake 계정 · j 대기 줄 오래된 순, 상대 시각, `www.` 뗀 host · k 새 문구 영어 리소스 포함 | — |
| C3-D6 | 전송 소유 | KMP `SubmissionCoordinator`(single-flight, rerun 표시). 플랫폼은 신호만 | 플랫폼별 WorkManager/BGTask: 규칙 중복, 제품 범위 초과 |
| C3-D7 | 로컬 schema | v2 migration(`1.sqm`): `local_submission.shared_at_us INTEGER`(정렬 `(shared_at_us, key)`), 상태 `PENDING/SUBMITTING/FAILED`, `retry_after_us`; `app_state(key,value)` 표 추가(fake 계정·첫 실행 안내) | 그 자리 수정: 개발 기기의 C2 DB가 열리지 않음. multiplatform-settings: C5에서 도입 예정이라 지금은 추가하지 않음 |
| C3-D8 | 오류 분류 | NETWORK·TIMEOUT·SERVER·INVALID_RESPONSE·UNAVAILABLE → `PENDING`+오류 기록 · RATE_LIMITED → `PENDING`+`retry_after`(기본 60초) · SESSION_CHANGED → binding 유지 `PENDING` · UNAUTHENTICATED → 이번 flush 중단 `PENDING` · VALIDATION·CONFLICT → `FAILED`(자동 재전송 없음, 줄 문구 "보낼 수 없는 링크예요") | — |
| C3-D9 | 카드 종류 | 공유한 순간 상태로 정함. Android: 로그인 전 LOCAL / 로그인+온라인 SAVED / 로그인+오프라인 OFFLINE / INVALID / 저장 실패 STORE_FAILED. iOS: 로그인 전 LOCAL / 로그인 SAVED_OPEN_APP("앱을 열면 정보를 가져와요") / INVALID / STORE_FAILED | — |
| C3-D10 | Android 모듈 분리 | C3에서는 나누지 않는다. feature 폴더 경계와 "feature는 `finishTransition`을 호출하지 않는다" 규칙 유지, C5 카테고리 feature 때 재검토 | 지금 분리: 작은 화면 4개에 Gradle 설정·공개 API만 증가 |

**실행 기본값(구현 중 바꿀 수 있으나 바꾸면 사용자에게 보고):**
- STORE_FAILED 카드 문구는 "저장하지 못했어요 / 다시 공유해 주세요"(보드에 없음, 드문 경우).
- fake 계정: GOOGLE = `fake-google-0001` / `user@example.com`, APPLE = `fake-apple-0001` / `apple@example.com`.
- app group id `group.app.wishlist`. 서명 없는 시뮬레이터에서 동작하지 않으면 Task 0에서 멈추고 보고한다.
- DEBUG fake 분석: 새로고침 때 `PROCESSING`이 된 지 5초 이상인 항목을 READY로 완료한다(FakeStore는 timer 없이 명시 호출만).

## 문구 표

키는 Android `strings.xml` 이름이고, iOS는 같은 키를 `.`로 바꾼다(예: `share_saved_title` → `share.saved.title`). 보드에 있는 문구는 보드가 기준이다.

| 키 | 한국어 | English |
| --- | --- | --- |
| `login_title` | 사고 싶은 상품을\n링크 하나로 모아 두세요 | Keep what you want to buy\nwith just a link |
| `login_point_share` | 공유하면 바로 저장돼요 | Share to save it right away |
| `login_point_fetch` | 로그인하면 상품 정보를 알아서 가져와요 | Sign in and we'll fetch product details |
| `login_point_devices` | 다른 기기에서도 같은 목록을 볼 수 있어요 | See the same list on your other devices |
| `login_apple` | Apple로 계속하기 | Continue with Apple |
| `login_google` | Google로 계속하기 | Continue with Google |
| `login_later` | 나중에 하기 | Not now |
| `home_logged_out_caption` | 로그인 전 | Not signed in |
| `home_login_card_title` | 로그인하면 정보를 가져와요 | Sign in to fetch details |
| `home_login_card_fill` | 저장한 링크의 이름·사진·가격을 채워요 | We fill in names, photos and prices |
| `home_login_card_devices` | 다른 기기에서도 볼 수 있어요 | Available on your other devices |
| `home_login_button` | 로그인 | Sign in |
| `home_todo` | 할 일 | To do |
| `home_todo_count` | 할 일 %1$d개 | %1$d to-do / %1$d to-dos (plural) |
| `home_pending_title` | 분석 대기 | Waiting for analysis |
| `home_pending_subtitle` | 로그인하면 바로 정보를 가져와요 | Details arrive as soon as you sign in |
| `home_pending_meta` | %1$s 저장 · 이 기기에만 있어요 | Saved %1$s · Only on this device |
| `home_original` | 원본 | Original |
| `home_processing_title` | 분류 중 | Sorting |
| `home_processing_subtitle` | 상품 정보를 가져오고 있어요 | Fetching product details |
| `row_sending` | 보내는 중 | Sending |
| `row_waiting_network` | 연결되면 보내요 | Sends when you're online |
| `row_retrying` | 잠시 후 다시 보내요 | Retrying soon |
| `row_needs_sign_in` | 다시 로그인하면 보내요 | Sends after you sign in again |
| `row_failed` | 보낼 수 없는 링크예요 | This link can't be sent |
| `row_processing` | 상품 정보 추출 중 | Extracting product info |
| `time_just_now` | 방금 | just now |
| `time_minutes` | %1$d분 전 | %1$d min ago |
| `time_hours` | %1$d시간 전 | %1$d hr ago |
| `time_yesterday` | 어제 | yesterday |
| `time_days` | %1$d일 전 | %1$d days ago |
| `settings_title` | 설정 | Settings |
| `settings_account` | 계정 | Account |
| `settings_signed_in_google` | Google로 로그인됨 | Signed in with Google |
| `settings_signed_in_apple` | Apple로 로그인됨 | Signed in with Apple |
| `settings_logout` | 로그아웃 | Sign out |
| `settings_login_hint` | 로그인하면 저장한 상품의 정보를 가져오고 다른 기기에서도 볼 수 있어요 | Sign in to fetch details of saved items and see them on other devices |
| `settings_login` | 로그인 | Sign in |
| `settings_original_links` | 원본 링크 | Original links |
| `settings_webview_clear` | 웹뷰 데이터 삭제 | Clear web view data |
| `settings_webview_clear_hint` | 쇼핑몰 로그인과 방문 기록 | Store sign-ins and history |
| `settings_webview_cleared` | 방금 삭제했어요 | Just cleared |
| `settings_app_info` | 앱 정보 | App info |
| `settings_version` | 버전 | Version |
| `logout_title` | 로그아웃할까요? | Sign out? |
| `logout_line_device` | 이 기기의 로그인만 풀려요 | Only this device is signed out |
| `logout_line_kept` | 저장한 상품은 계정에 남아요 | Saved items stay in your account |
| `logout_line_resume` | 다시 로그인하면 이어서 볼 수 있어요 | Sign in again to pick up where you left off |
| `dialog_cancel` | 취소 | Cancel |
| `webview_clear_title` | 웹뷰 데이터를 삭제할까요? | Clear web view data? |
| `webview_clear_line_login` | 쇼핑몰 로그인이 풀려요 | You'll be signed out of stores |
| `webview_clear_line_cookies` | 방문 기록과 쿠키가 지워져요 | History and cookies are removed |
| `webview_clear_line_kept` | 저장한 상품은 그대로예요 | Saved items stay as they are |
| `webview_clear_line_irreversible` | 되돌릴 수 없어요 | This can't be undone |
| `webview_clear_confirm` | 삭제 | Clear |
| `share_saved_title` | 위시리스트에 저장했어요 | Saved to your wishlist |
| `share_saved_fetching` | 정보를 가져오는 중이에요 | Fetching details |
| `share_saved_open_app` | 앱을 열면 정보를 가져와요 | Details arrive when you open the app |
| `share_saved_open_app` | 앱을 열면 정보를 가져와요 | Details arrive when you open the app |
| `share_local_title` | 이 기기에 저장했어요 | Saved on this device |
| `share_local_line` | 로그인하면 정보를 가져와요 | Sign in to fetch details |
| `share_offline_line` | 다음에 앱을 열면 보내요 | We'll send it next time you open the app |
| `share_invalid_title` | 링크를 찾지 못했어요 | No link found |
| `share_invalid_line` | 상품 페이지에서 다시 공유해 주세요 | Share again from the product page |
| `share_failed_title` | 저장하지 못했어요 | Couldn't save |
| `share_failed_line` | 다시 공유해 주세요 | Please share again |

## 파일 구조와 책임

`S` = `client/shared/src/commonMain/kotlin/app/wishlist/shared`, `T` = `client/shared/src/commonTest/kotlin/app/wishlist/shared`, `A` = `client/android/src/main/kotlin/app/wishlist/android`, `I` = `client/ios`.

| 경로 | 책임 |
| --- | --- |
| `client/localdb/src/commonMain/sqldelight/app/wishlist/shared/data/local/Wishlist.sq`, `1.sqm` | v2 schema·query, v1→v2 migration |
| `S/model/LocalSubmission.kt` | `LocalSubmission`(sharedAt, status PENDING/SUBMITTING/FAILED, retryAfter) |
| `S/repository/LocalStore.kt`, `S/data/local/SqlLocalStore.kt`, `S/data/local/LazyDriver.kt` | 저장 계약 확장(import·bind·mark·processing·app_state), I/O dispatcher에서 driver 열기 |
| `S/core/AuthFacade.kt`, `S/data/fake/FakeAuthFacade.kt`, `S/di/UnavailableAuthFacade.kt` | 공개 인증 facade, fake·RELEASE 구현. `MutableAuthSession`은 internal로 |
| `S/domain/ShareTextParser.kt`, `S/domain/DisplayFormat.kt` | 공유 글 링크 추출·검증, host 표기·상대 시각 분류 |
| `S/submission/SubmissionCoordinator.kt`, `SubmissionModels.kt`, `SubmissionErrorPolicy.kt` | receive·importInbox·flush·refreshProcessing·view |
| `S/data/fake/FakeStore.kt`(수정), `S/data/fake/DebugAnalysisDriver.kt` | 시간 경과 항목의 명시적 완료(DEBUG) |
| `S/presentation/AccountPresenter.kt`, `HomePresenter.kt`, `HomeState.kt` | 로그인·첫 실행·로그아웃 상태, 홈 두 상태와 줄 모델 |
| `S/di/SharedRuntime.kt`, `SharedModules.kt`, `RuntimeFacades.kt`(수정) | 새 facade 연결, bootstrap 변경·예외 처리 |
| `A/feature/home/*`, `A/feature/login/*`, `A/feature/settings/*`, `A/share/*`, `A/platform/*` | Android 화면·owner·공유 Activity·신호·웹뷰 삭제 |
| `I/Wishlist/Features/{Home,Login,Settings}/*`, `I/Wishlist/Platform/*`, `I/ShareExtension/*`, `I/AppGroupShared/*` | iOS 화면·owner·inbox reader·신호, 확장 target, 앱·확장 공용 Swift |

## 검증 명령 약어

C2 계획의 [검증 명령 약어](2026-10-07-client-c2-kmp-core.md#검증-명령-약어)를 그대로 쓴다. 이 맥에서는 `/usr/libexec/java_home -v 17`이 25를 돌려주므로 쓰지 않는다.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
# client/
./gradlew -Porg.gradle.java.installations.paths="$JAVA_HOME" :shared:testAndroidHostTest :shared:iosSimulatorArm64Test
```

- **KMP_TEST** = 위 두 task. RED는 `:shared:testAndroidHostTest --tests "*<Class>*"`로 확인하고, GREEN 때 두 runtime 전체를 실행해 test 수·fail·skip을 XML(`shared/build/test-results/*/*.xml`)에서 센다.
- **ANDROID_CHECK** = `:android:testDebugUnitTest :android:testReleaseUnitTest :android:assembleDebug :android:assembleRelease :android:lintDebug`.
- **IOS_TEST** = `xcrun simctl spawn <udid> defaults write com.apple.Accessibility ApplicationAccessibilityEnabled -bool true` 후 `xcodebuild test -project client/ios/Wishlist.xcodeproj -scheme Wishlist -destination 'platform=iOS Simulator,id=<udid>' -derivedDataPath client/ios/DerivedData CODE_SIGNING_ALLOWED=NO`.
- 시뮬레이터: iPhone 17 Pro `AFBA9C17-206B-4EA6-A508-EF6E0CE2D7B0`(실행 때 `xcrun simctl list devices`로 다시 확인).
- **Baseline(2026-10-07, 이 공간):** host 246 · simulator 243 · Android unit 66/66 · XCTest 81, 실패·skip 0, iOS Release simulator build 성공, `gen_tokens --check` 통과.

---

## Task 0: iOS app group·Share Extension target spike

**Files:**
- Create: `I/ShareExtension/ShareViewController.swift`, `I/ShareExtension/Info.plist`, `I/ShareExtension/ShareExtension.entitlements`, `I/Wishlist/Wishlist.entitlements`
- Modify: `I/Wishlist.xcodeproj/project.pbxproj`(ShareExtension target, Embed App Extensions phase, 두 target의 `CODE_SIGN_ENTITLEMENTS`)

| 만들 것 | 검증할 것 | 하지 않을 것 |
| --- | --- | --- |
| 빌드되는 빈 확장 target(app group 파일 쓰기 1개) | 서명 없는 시뮬레이터에서 확장과 앱이 같은 app group 폴더를 보는지 | 카드 UI·inbox 형식·KMP 연결 |

- [x] **Step 1: pbxproj 편집 도구 결정.** `gem list xcodeproj`가 비어 있으면 사용자에게 `gem install --user-install xcodeproj`(개발 기기 도구, 저장소에 추가하지 않음) 설치를 확인받는다. 거절하면 기존 pbxproj 구조를 따라 손으로 편집한다(새 UUID 24자리, PBXNativeTarget `productType = "com.apple.product-type.app-extension"`, PBXCopyFilesBuildPhase `dstSubfolderSpec = 13`). 편집 스크립트는 scratchpad에 두고 커밋하지 않는다.
- [x] **Step 2: target 추가.** bundle id `app.wishlist.ios.share`, deployment 17.0, Swift 5 언어 모드는 앱 target과 같게. Info.plist:

```xml
<key>NSExtension</key>
<dict>
  <key>NSExtensionPointIdentifier</key><string>com.apple.share-services</string>
  <key>NSExtensionPrincipalClass</key><string>$(PRODUCT_MODULE_NAME).ShareViewController</string>
  <key>NSExtensionAttributes</key>
  <dict>
    <key>NSExtensionActivationRule</key>
    <dict>
      <key>NSExtensionActivationSupportsWebURLWithMaxCount</key><integer>1</integer>
      <key>NSExtensionActivationSupportsText</key><true/>
    </dict>
  </dict>
</dict>
```

두 entitlements에 `com.apple.security.application-groups = [group.app.wishlist]`.
- [x] **Step 3: spike 코드.** `ShareViewController.viewDidLoad`에서 `FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: "group.app.wishlist")`에 `spike.txt`를 쓰고 `completeRequest`. 앱 `WishlistApp.init`(DEBUG)에서 같은 URL을 `print`.
- [x] **Step 4: 실행.** `xcodebuild build … CODE_SIGNING_ALLOWED=NO` 후 시뮬레이터에 설치하고 Safari에서 `https://example.com` 공유 → 위시리스트 선택. `xcrun simctl get_app_container <udid> app.wishlist.ios groups`로 group 경로를 찾아 `spike.txt` 존재를 확인한다. 공유 시트 조작은 사용자에게 부탁하거나 computer-use 사용을 확인받는다.
- [x] **Step 5: 판정·보고.** 성공이면 spike print/파일 코드를 지우고 target·entitlements만 남겨 커밋. 실패(nil container 또는 서로 다른 경로)면 **구현을 멈추고** 원인·대안(ad-hoc "Sign to Run Locally" 서명 빌드 사용, 또는 확장 검증을 가입 뒤로 미루고 C3는 Android+iOS 앱만)을 사용자에게 보고한다.
- [x] **Step 6: 회귀·커밋.** IOS_TEST 81 통과. `git commit -m "feature(ios): 공유 확장 target과 app group 연결"` + 본문(서명 없는 시뮬레이터 확인 결과).

## Task 1: C2 인계 LocalStore 수정과 v2 migration

**Files:**
- Modify: `client/localdb/src/commonMain/sqldelight/app/wishlist/shared/data/local/Wishlist.sq`
- Create: `client/localdb/src/commonMain/sqldelight/app/wishlist/shared/data/local/1.sqm`
- Modify: `S/model/LocalSubmission.kt`, `S/repository/LocalStore.kt`, `S/data/local/SqlLocalStore.kt`, `S/di/SharedModules.kt`, `S/di/SharedRuntime.kt`, `S/di/RuntimeFacades.kt`
- Create: `S/data/local/LazyDriver.kt`
- Test: `T/data/local/LocalStoreContractTest.kt`, `T/data/local/LocalStoreFixtures.kt`, `T/data/local/SchemaMigrationTest.kt`(신규), `T/model/ModelInvariantTest.kt`, `T/di/SharedModulesTest.kt`

**Interfaces:**
- Produces:

```kotlin
// S/model/LocalSubmission.kt
enum class SubmissionStatus { PENDING, SUBMITTING, FAILED }

data class LocalSubmission(
    val clientSubmissionId: String,
    val sourceUrl: String,
    val sharedAt: Instant,
    val accountBinding: String? = null,
    val submissionStatus: SubmissionStatus = SubmissionStatus.PENDING,
    val lastSubmissionError: ClientError? = null,
    val retryAfter: Instant? = null,
) { val sharedAtIso: String get() = sharedAt.toString() }

// S/repository/LocalStore.kt (추가분; 기존 upsertItem·cachedItem·accept·removeCachedItem·clearCurrentCache 유지)
interface LocalStore {
    /** 현재 세션 기준 저장. 같은 key·같은 URL → 기존 행 유지(no-op Success). 같은 key·다른 URL → CONFLICT/SUBMISSION_KEY_REUSED. */
    suspend fun saveSubmission(submission: LocalSubmission): ClientResult<Unit>
    /** iOS inbox 기록용: 공유 시점 binding을 그대로 받는다(현재 계정과 달라도 됨). key 가드는 saveSubmission과 같다. */
    suspend fun importSubmission(submission: LocalSubmission): ClientResult<Unit>
    /** 로그인 전: binding null만. 로그인: 그 계정 binding만(미귀속은 bind 전까지 포함). 정렬 (sharedAt µs, key). */
    suspend fun pending(): ClientResult<List<LocalSubmission>>
    /** 하나의 transaction: SUBMITTING→PENDING, binding null → snapshot 계정. 이후 그 계정의 전체 대기 목록 반환. */
    suspend fun prepareFlush(snapshot: SessionSnapshot): ClientResult<List<LocalSubmission>>
    suspend fun markSubmission(snapshot: SessionSnapshot, id: String, status: SubmissionStatus,
        error: ClientError?, retryAfter: Instant?): ClientResult<Unit>
    suspend fun processingItems(snapshot: SessionSnapshot): ClientResult<List<WishlistItem>>
    suspend fun readAppState(key: String): ClientResult<String?>
    suspend fun writeAppState(key: String, value: String?): ClientResult<Unit>
    // + 기존 메서드
}
```

`markSubmission`·`prepareFlush`는 binding이 snapshot 계정과 다른 행을 건드리지 않는다(`ACCOUNT_BINDING_MISMATCH`). `accept`는 추가로 `Uuid.parse(item.clientSubmissionId) == Uuid.parse(submissionId)`가 아니면 `VALIDATION/SUBMISSION_ITEM_MISMATCH`이고 아무것도 쓰지 않는다.

| 만들 것 | 검증할 것 | 하지 않을 것 |
| --- | --- | --- |
| v2 schema·migration·LocalStore 확장·LazyDriver·bootstrap 예외 처리 | 정수 정렬, key 가드, accept 일치, migration, driver를 I/O에서 첫 사용 때만 열기, seed 실패 시 ready+오류 노출 | Coordinator·Auth(다음 task) |

- [x] **Step 1: 실패하는 테스트 작성.** `LocalStoreContractTest`에 추가(기존 harness `h.store`, `submission(...)` fixture를 `sharedAt` 인자로 확장):

```kotlin
@Test fun pendingOrdersBySubMillisecondInstantThenKey() = runStoreTest { h ->
    val base = Instant.parse("2026-10-07T00:00:00Z")
    h.store.saveSubmission(submission(id = UUID_B, sharedAt = base + 500.milliseconds)).successValue()
    h.store.saveSubmission(submission(id = UUID_C, sharedAt = base)).successValue()
    h.store.saveSubmission(submission(id = UUID_A, sharedAt = base + 500.milliseconds)).successValue()
    assertEquals(listOf(UUID_C, UUID_A, UUID_B), h.store.pending().successValue().map { it.clientSubmissionId })
}
@Test fun sameKeyDifferentUrlIsRejectedAndOriginalKept() = runStoreTest { h ->
    h.store.saveSubmission(submission(id = UUID_A, url = "https://a.example/1")).successValue()
    assertEquals(ErrorKind.CONFLICT, h.store.saveSubmission(submission(id = UUID_A, url = "https://a.example/2")).failureKind())
    assertEquals("https://a.example/1", h.store.pending().successValue().single().sourceUrl)
}
@Test fun sameKeySameUrlIsNoOpKeepingStatus() = runStoreTest { h ->
    h.login("A")
    h.store.saveSubmission(submission(id = UUID_A, binding = "A")).successValue()
    h.store.markSubmission(h.snapshot(), UUID_A, SubmissionStatus.FAILED, ClientError(ErrorKind.VALIDATION), null).successValue()
    h.store.saveSubmission(submission(id = UUID_A, binding = "A")).successValue()
    assertEquals(SubmissionStatus.FAILED, h.store.pending().successValue().single().submissionStatus)
}
@Test fun acceptRejectsItemOfAnotherSubmission() = runStoreTest { h ->
    h.login("A")
    h.store.saveSubmission(submission(id = UUID_A, binding = "A")).successValue()
    val wrong = itemFixture(clientSubmissionId = UUID_B)
    assertEquals("SUBMISSION_ITEM_MISMATCH", h.store.accept(h.snapshot(), UUID_A, wrong).failureCode())
    assertNull(h.store.cachedItem(h.snapshot(), wrong.id).successValue())
    assertEquals(1, h.store.pending().successValue().size)
}
@Test fun acceptMatchesKeyCaseInsensitively() = runStoreTest { h ->
    h.login("A")
    h.store.saveSubmission(submission(id = UUID_A, binding = "A")).successValue()
    h.store.accept(h.snapshot(), UUID_A, itemFixture(clientSubmissionId = UUID_A.uppercase())).successValue()
    assertTrue(h.store.pending().successValue().isEmpty())
}
@Test fun prepareFlushResetsSubmittingAndBindsUnboundOnlyToSnapshotAccount() = runStoreTest { h ->
    h.store.saveSubmission(submission(id = UUID_A)).successValue()            // unbound
    h.login("B"); h.store.saveSubmission(submission(id = UUID_B, binding = "B")).successValue()
    h.login("A")
    h.store.importSubmission(submission(id = UUID_C, binding = "A", status = SubmissionStatus.SUBMITTING)).successValue()
    val ready = h.store.prepareFlush(h.snapshot()).successValue()
    assertEquals(setOf(UUID_A, UUID_C), ready.map { it.clientSubmissionId }.toSet())
    assertTrue(ready.all { it.accountBinding == "A" && it.submissionStatus == SubmissionStatus.PENDING })
    h.login("B")
    assertEquals(listOf(UUID_B), h.store.pending().successValue().map { it.clientSubmissionId })
}
@Test fun importKeepsShareTimeBindingOfOtherAccount() = runStoreTest { h ->
    h.login("A")
    h.store.importSubmission(submission(id = UUID_A, binding = "B")).successValue()
    assertTrue(h.store.pending().successValue().isEmpty())
    h.login("B")
    assertEquals(1, h.store.pending().successValue().size)
}
@Test fun processingItemsReturnsOnlyActiveProcessingOfAccount() = runStoreTest { h -> /* upsert READY, PROCESSING, DELETED+PROCESSING for A and PROCESSING for B; expect only A's active PROCESSING */ }
@Test fun appStateRoundTripsAndDeletes() = runStoreTest { h ->
    h.store.writeAppState("k", "v").successValue()
    assertEquals("v", h.store.readAppState("k").successValue())
    h.store.writeAppState("k", null).successValue()
    assertNull(h.store.readAppState("k").successValue())
}
```

`SchemaMigrationTest`(Review Focus 5):

```kotlin
@Test fun migratesV1RowsWithMillisecondOrder() {
    val path = newTestDbPath()
    openRawDriver(path).use { raw ->            // schema 없이 연 driver; v1 DDL을 그대로 실행하고 user_version=1
        raw.execute(null, V1_LOCAL_SUBMISSION_DDL, 0)
        raw.execute(null, V1_ITEM_CACHE_DDL, 0)
        raw.execute(null, "INSERT INTO local_submission VALUES ('$UUID_A','https://a.example','2026-10-07T00:00:00.500Z',NULL,'PENDING',NULL,NULL,NULL,NULL,NULL,NULL)", 0)
        raw.execute(null, "INSERT INTO local_submission VALUES ('$UUID_B','https://b.example','2026-10-07T00:00:00Z','A','ACCEPTED',NULL,NULL,NULL,NULL,NULL,NULL)", 0)
        raw.execute(null, "PRAGMA user_version = 1", 0)
    }
    openTestDriver(path).use { driver ->        // WishlistDatabase.Schema로 열어 1 → 2 migrate
        val rows = WishlistDatabase(driver).wishlistQueries.selectUnboundSubmissions().executeAsList()
        // B는 binding A라 unbound 목록에서 빠지고, A는 500ms µs 값 보존
        assertEquals(1_791_331_200_500_000L, rows.single().shared_at_us)
        val b = WishlistDatabase(driver).wishlistQueries.selectSubmission(UUID_B).executeAsOne()
        assertEquals("PENDING", b.status)       // ACCEPTED는 같은 key 재전송으로 안전하게 복구
        assertEquals(1_791_331_200_000_000L, b.shared_at_us)
    }
    deleteTestDb(path)
}
```

`openRawDriver`는 `TestDriver.kt` expect에 추가한다(JDBC: `JdbcSqliteDriver("jdbc:sqlite:$path")`, Native: `NativeSqliteDriver(DatabaseConfiguration(name, version = 1, create = {}, upgrade = {_,_,_->}, extendedConfig = …basePath))`). 기대 µs 값은 구현 전에 `Instant.parse(...).toEpochMicroseconds()`로 다시 계산해 상수를 확인한다.

`SharedModulesTest`에 추가:

```kotlin
@Test fun facadeLookupDoesNotOpenDriverOnCallerThread() = runTest {
    val probe = RuntimeResourcesProbe()
    val runtime = createRuntime(releaseBindings(), probe = probe)
    runtime.getItemRepository(); runtime.localStore()
    assertEquals(0, probe.drivers.size)                    // facade 조회만으로 열지 않는다
    runtime.localStore().pending()
    assertEquals(1, probe.drivers.size)                    // 첫 실제 사용 때 io dispatcher에서 연다
    runtime.close()
}
@Test fun debugSeedFailureStillPublishesReadyAndReportsError() = runTest {
    val runtime = createRuntime(debugBindings(), seedOverride = { ClientResult.Failure(ClientError(ErrorKind.VALIDATION)) })
    runtime.startDebugSession()
    assertTrue(runtime.ready.value)
    assertEquals(ErrorKind.VALIDATION, runtime.bootstrapFailure.value?.kind)
    runtime.close()
}
@Test fun unexpectedBootstrapExceptionIsReportedNotThrown() = runTest { /* seedOverride가 throw IllegalStateException → ready=true, bootstrapFailure.kind=UNAVAILABLE, code=BOOTSTRAP_FAILURE */ }
```

`ModelInvariantTest`의 `createdAtIso`·`serverItemId` 사용을 `sharedAtIso`·제거로 바꾼다.

- [x] **Step 2: RED 확인.** `:shared:testAndroidHostTest --tests "*LocalStoreContractTest*" --tests "*SchemaMigrationTest*" --tests "*SharedModulesTest*"` → 컴파일 실패 또는 assertion 실패를 확인하고 원인을 기록.
- [x] **Step 3: schema 구현.** `Wishlist.sq`의 `local_submission`을 아래로 바꾸고 `app_state`·query를 추가한다(`item_cache`는 유지):

```sql
CREATE TABLE local_submission (
    client_submission_id TEXT NOT NULL PRIMARY KEY,
    source_url TEXT NOT NULL,
    shared_at_us INTEGER NOT NULL,
    account_binding TEXT,
    status TEXT NOT NULL,
    retry_after_us INTEGER,
    error_kind TEXT,
    error_code TEXT,
    error_request_id TEXT,
    error_current_version INTEGER,
    error_retry_after_seconds INTEGER
);
CREATE TABLE app_state (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL);

insertSubmissionIfAbsent:
INSERT OR IGNORE INTO local_submission VALUES ?;
selectVisibleSubmissions:
SELECT * FROM local_submission WHERE account_binding IS NULL OR account_binding = :accountId
ORDER BY shared_at_us, client_submission_id;
selectUnboundSubmissions:
SELECT * FROM local_submission WHERE account_binding IS NULL ORDER BY shared_at_us, client_submission_id;
selectBoundSubmissions:
SELECT * FROM local_submission WHERE account_binding = :accountId ORDER BY shared_at_us, client_submission_id;
resetSubmitting:
UPDATE local_submission SET status = 'PENDING' WHERE status = 'SUBMITTING' AND (account_binding IS NULL OR account_binding = :accountId);
bindUnbound:
UPDATE local_submission SET account_binding = :accountId WHERE account_binding IS NULL;
updateSubmissionState:
UPDATE local_submission SET status = ?, retry_after_us = ?, error_kind = ?, error_code = ?, error_request_id = ?,
  error_current_version = ?, error_retry_after_seconds = ? WHERE client_submission_id = ? AND account_binding = ?;
selectProcessingItems:
SELECT * FROM item_cache WHERE account_id = ? AND analysis_status = 'PROCESSING' AND lifecycle_status = 'ACTIVE'
ORDER BY created_at, item_id;
selectAppState:
SELECT value FROM app_state WHERE key = ?;
upsertAppState:
INSERT OR REPLACE INTO app_state VALUES (?, ?);
deleteAppState:
DELETE FROM app_state WHERE key = ?;
```

기존 `insertSubmission`(INSERT OR REPLACE)은 삭제한다. `1.sqm`:

```sql
CREATE TABLE local_submission_v2 (
    client_submission_id TEXT NOT NULL PRIMARY KEY, source_url TEXT NOT NULL, shared_at_us INTEGER NOT NULL,
    account_binding TEXT, status TEXT NOT NULL, retry_after_us INTEGER, error_kind TEXT, error_code TEXT,
    error_request_id TEXT, error_current_version INTEGER, error_retry_after_seconds INTEGER
);
INSERT INTO local_submission_v2
SELECT client_submission_id, source_url,
       CAST(strftime('%s', created_at) AS INTEGER) * 1000000
         + CAST(ROUND((CAST(strftime('%f', created_at) AS REAL) - CAST(strftime('%S', created_at) AS INTEGER)) * 1000) AS INTEGER) * 1000,
       account_binding,
       CASE status WHEN 'SUBMITTING' THEN 'PENDING' WHEN 'ACCEPTED' THEN 'PENDING' ELSE status END,
       NULL, error_kind, error_code, error_request_id, error_current_version, error_retry_after_seconds
FROM local_submission;
DROP TABLE local_submission;
ALTER TABLE local_submission_v2 RENAME TO local_submission;
CREATE TABLE app_state (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL);
```

v1 데이터는 개발 기기에만 있으므로 ms 정밀도 변환을 허용한다(C2 v1은 ms 미만을 쓰지 않았다는 근거가 없으므로, 변환 정밀도 한계를 kmp.md에 적는다).
- [x] **Step 4: SqlLocalStore·모델 구현.** `toRow/toModel`은 `sharedAt.toEpochMicroseconds()`/`Instant.fromEpochMicroseconds` 대신 존재하는 API를 확인해 `epochSeconds*1_000_000 + nanosecondsOfSecond/1000`으로 계산(ms 미만 µs 절삭, 서버 정밀도와 같음). saveSubmission·importSubmission은 transaction 안에서 `selectSubmission` → 없으면 `insertSubmissionIfAbsent`, 있으면 URL 다르면 CONFLICT, 같으면 Success. saveSubmission의 binding 규칙(현재 계정과 같거나 null)은 유지하고 importSubmission은 생략. `prepareFlush`는 `gatedForAccount`에서 `resetSubmitting(account)` → `bindUnbound(account)` → `selectBoundSubmissions(account)`를 한 transaction으로. `ClientError` 컬럼 매핑은 기존과 같다.
- [x] **Step 5: LazyDriver.** `PlatformResources.openDriver`를 감싸 첫 suspend 사용 때 `withContext(io)`에서 연다:

```kotlin
internal class LazyDriver(private val open: () -> SqlDriver, private val io: CoroutineDispatcher) {
    private val mutex = Mutex()
    @Volatile private var driver: SqlDriver? = null   // kotlin.concurrent.Volatile
    suspend fun get(): SqlDriver = driver ?: mutex.withLock {
        driver ?: withContext(io) { open() }.also { driver = it }
    }
}
```

`SqlLocalStore`는 `LazyDriver`를 받고 `database`를 첫 사용 때 만든다(`private suspend fun db(): WishlistDatabase`). ResourceRegistry 등록은 실제로 열렸을 때만 한다. `CachedGetItemRepository`는 LocalStore만 쓰므로 그대로.
- [x] **Step 6: bootstrap 예외 처리.** `SharedRuntime`에 `val bootstrapFailure: StateFlow<ClientError?>`(공개)와 scope `CoroutineExceptionHandler`를 추가한다. seed 실패·예외 모두 `ready=true` + `bootstrapFailure` 설정(`ErrorKind.UNAVAILABLE`, code `BOOTSTRAP_FAILURE`; 실패 결과면 그 error). 테스트 seam `seedOverride: (suspend () -> ClientResult<Unit>)?`은 `assembleSharedRuntime` internal 인자로.
- [x] **Step 7: GREEN.** KMP_TEST 전체 통과, 새 test 이름과 두 runtime 건수 기록.
- [x] **Step 8: 커밋.** `git commit -m "bugfix: 로컬 대기 정렬·key 가드·accept 검사와 DB 열기 시점 수정"` + 본문(v2 migration, LazyDriver, bootstrap 오류 노출).

## Task 2: fake AuthFacade·app_state·로그인 전 시작과 계정별 seed

**Files:**
- Create: `S/core/AuthFacade.kt`, `S/data/fake/FakeAuthFacade.kt`, `S/di/UnavailableAuthFacade.kt`
- Modify: `S/core/AuthSession.kt`(`MutableAuthSession`을 `internal`), `S/di/SharedRuntime.kt`, `S/di/SharedModules.kt`, `S/data/fake/FakeStore.kt`(seed는 계정별 그대로), Android `src/debug/.../DebugSessionBootstrap.kt`, iOS `Debug/DebugSessionBootstrap.swift`
- Test: `T/core/AuthFacadeTest.kt`(신규), `T/di/SharedModulesTest.kt`, iOS `WishlistTests/SharedInteropTests.swift`

**Interfaces:**
- Consumes: Task 1 `LocalStore.readAppState/writeAppState/clearCurrentCache`.
- Produces:

```kotlin
// S/core/AuthFacade.kt
enum class AuthProvider { APPLE, GOOGLE }
data class AuthAccount(val accountId: String, val email: String, val provider: AuthProvider)

interface AuthFacade {
    /** null = 로그인 전. 앱 시작 시 저장된 계정 복원이 끝나면 [restored] = true. */
    val account: StateFlow<AuthAccount?>
    val restored: StateFlow<Boolean>
    suspend fun signIn(provider: AuthProvider): ClientResult<AuthAccount>
    /** 현재 계정 캐시를 지우고(미전송은 보존) 로그아웃. */
    suspend fun signOut(): ClientResult<Unit>
    suspend fun hasSeenFirstRunLogin(): Boolean
    suspend fun markFirstRunLoginSeen()
}

// SharedRuntime 추가 공개 API
fun auth(): AuthFacade
```

app_state 키: `auth.account`(`"<provider>|<accountId>|<email>"`), `onboarding.login.seen`(`"1"`).

| 만들 것 | 검증할 것 | 하지 않을 것 |
| --- | --- | --- |
| fake/RELEASE AuthFacade, 앱 재시작 복원, DEBUG 로그인 전 시작 + 로그인한 계정 namespace에 seed | 순서(계정 저장→세션 변경→seed), 로그아웃 순서(캐시 삭제→저장 삭제→세션 null), 재로그인 generation 증가, Swift에 `changeAccount` 미노출 | 미전송 flush(Task 3이 signIn 뒤 연결) |

- [x] **Step 1: 실패하는 테스트.** `AuthFacadeTest`:

```kotlin
@Test fun signInPersistsAccountChangesSessionAndSeedsThatNamespace() = runAuthTest { h ->
    val account = h.auth.signIn(AuthProvider.GOOGLE).successValue()
    assertEquals(AuthAccount("fake-google-0001", "user@example.com", AuthProvider.GOOGLE), account)
    assertEquals("fake-google-0001", h.session.state.value.accountId)
    assertTrue(h.fakeStore.categories().successValue().isNotEmpty())     // seed가 그 계정에 들어감
    assertEquals("GOOGLE|fake-google-0001|user@example.com", h.store.readAppState("auth.account").successValue())
}
@Test fun restoreOnStartPublishesRestoredAccount() = runAuthTest(preset = mapOf("auth.account" to "APPLE|fake-apple-0001|apple@example.com")) { h ->
    h.auth.restored.first { it }
    assertEquals(AuthProvider.APPLE, h.auth.account.value?.provider)
    assertEquals("fake-apple-0001", h.session.state.value.accountId)
}
@Test fun corruptStoredAccountRestoresAsSignedOut() = runAuthTest(preset = mapOf("auth.account" to "garbage")) { h ->
    h.auth.restored.first { it }
    assertNull(h.auth.account.value)
    assertNull(h.store.readAppState("auth.account").successValue())
}
@Test fun signOutClearsCacheKeepsBoundPendingAndNullsSession() = runAuthTest { h ->
    h.auth.signIn(AuthProvider.GOOGLE).successValue()
    h.store.upsertItem(h.session.state.value, itemFixture()).successValue()
    h.store.saveSubmission(submission(id = UUID_A, binding = "fake-google-0001")).successValue()
    h.auth.signOut().successValue()
    assertNull(h.session.state.value.accountId)
    h.auth.signIn(AuthProvider.GOOGLE).successValue()
    assertNull(h.store.cachedItem(h.session.state.value, itemFixture().id).successValue())
    assertEquals(listOf(UUID_A), h.store.pending().successValue().map { it.clientSubmissionId })
}
@Test fun reSignInSameAccountBumpsGeneration() = runAuthTest { h -> /* signIn, signOut, signIn → generation 3 */ }
@Test fun firstRunFlagPersists() = runAuthTest { h -> /* false → mark → true; 새 harness 같은 DB → true */ }
@Test fun releaseAuthIsUnavailableAndSignedOut() = runTest {
    val auth = UnavailableAuthFacade()
    assertEquals(ErrorKind.UNAVAILABLE, auth.signIn(AuthProvider.GOOGLE).failureKind())
    assertTrue(auth.restored.value); assertNull(auth.account.value)
}
```

`SharedModulesTest`의 기존 debug bootstrap 테스트(`debug-board-owner`로 시작)를 **로그인 전 시작**으로 갱신: `startDebugSession()` 후 `ready=true`, `session.accountId == null`. RELEASE에서 `auth()`가 `UnavailableAuthFacade`인지 확인. `SharedInteropTests.swift`에 `MutableAuthSession` 타입이 Swift에 보이지 않음을 컴파일로 보장할 수 없으므로, `Shared.h`에서 `SharedMutableAuthSession`이 없는지 Step 5에서 grep으로 확인한다.
- [ ] **Step 2: RED 확인** (`--tests "*AuthFacadeTest*" --tests "*SharedModulesTest*"`). — **실행 기록: 구현을 먼저 써서 첫 RED를 기록하지 못했다(GREEN만 확인). fix round 1의 새 테스트는 RED 7건을 기록했다(`9e9a6ad`).**
- [x] **Step 3: 구현.** `FakeAuthFacade(session: MutableAuthSession, store: LocalStore, seed: suspend () -> ClientResult<Unit>, scope)`. 복원은 `startDebugSession`이 호출하는 `restore()`에서: 읽기→파싱 실패면 삭제→`changeAccount(id)`→seed→`restored=true`. signIn: `writeAppState` → `changeAccount` → seed(실패하면 계정은 유지하고 `bootstrapFailure`처럼 결과로 반환하지 않고 로그만; seed는 데모용). signOut: `clearCurrentCache()` → `writeAppState(auth.account, null)` → `changeAccount(null)`. fake 계정 표는 Global Constraints 실행 기본값. `startDebugSession`은 `changeAccount(DEBUG_ACCOUNT_ID)`를 더 이상 하지 않고 `auth.restore()` 뒤 ready를 publish한다. `DEBUG_ACCOUNT_ID` 상수와 그 테스트를 제거한다. RELEASE `auth()`는 `UnavailableAuthFacade`(restored=true, account=null). `MutableAuthSession`을 `internal class`로 바꾸고, 공개 `SharedRuntime.session: AuthSession`은 유지.
- [x] **Step 4: 플랫폼 bootstrap 주석 갱신.** Android/iOS `DebugSessionBootstrap`의 설명을 "로그인 전 시작, 저장된 fake 계정 복원 → 그 계정 seed → ready"로 바꾼다(호출은 같음).
- [x] **Step 5: GREEN·확인.** KMP_TEST, ANDROID_CHECK, IOS_TEST. `grep -c "MutableAuthSession" client/shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework/Headers/Shared.h` = 0.
- [x] **Step 6: 커밋.** `feature(kmp): fake 인증 facade와 계정별 데모 seed 연결` + 본문.

## Task 3: 공유 글 parser·SubmissionCoordinator·fake 분석 진행

**Files:**
- Create: `S/domain/ShareTextParser.kt`, `S/domain/DisplayFormat.kt`, `S/submission/SubmissionModels.kt`, `S/submission/SubmissionErrorPolicy.kt`, `S/submission/SubmissionCoordinator.kt`, `S/data/fake/DebugAnalysisDriver.kt`
- Modify: `S/data/fake/FakeStore.kt`(`completeDueAnalyses`), `S/di/SharedRuntime.kt`, `S/di/SharedModules.kt`, `S/data/fake/FakeAuthFacade.kt`(signIn 뒤 `requestFlush(SIGNED_IN)`)
- Test: `T/domain/ShareTextParserTest.kt`, `T/domain/DisplayFormatTest.kt`, `T/submission/SubmissionCoordinatorTest.kt`, `T/submission/SubmissionErrorPolicyTest.kt`, `T/data/fake/DebugAnalysisDriverTest.kt`

**Interfaces:**
- Consumes: Task 1 LocalStore, Task 2 `AuthFacade`, C2 `CreateItemRepository`·`GetItemRepository`(Cached decorator).
- Produces:

```kotlin
// S/domain/ShareTextParser.kt
sealed interface ParsedShare {
    data class Link(val url: String) : ParsedShare
    data object NoLink : ParsedShare
    data object TooLong : ParsedShare
}
object ShareTextParser { fun parse(text: String?): ParsedShare }

// S/domain/DisplayFormat.kt
object DisplayFormat {
    /** 소문자 host, 앞의 "www." 제거. host를 못 찾으면 원문 앞 40자. */
    fun host(url: String): String
    /** kotlinx-datetime이 없으므로 달력 날짜는 플랫폼이 준 UTC offset(초)으로 계산한다. */
    fun relative(from: Instant, now: Instant, utcOffsetSeconds: Int): RelativeTime
}
sealed interface RelativeTime {
    data object JustNow : RelativeTime                 // < 1분
    data class Minutes(val value: Int) : RelativeTime  // < 60분
    data class Hours(val value: Int) : RelativeTime    // < 24시간 그리고 같은 달력 날짜
    data object Yesterday : RelativeTime               // 달력상 전날
    data class Days(val value: Int) : RelativeTime     // 달력 날짜 차이 ≥ 2
}

// S/submission/SubmissionModels.kt
enum class ShareCardKind { SAVED, SAVED_OPEN_APP, LOCAL, OFFLINE, INVALID, STORE_FAILED }
enum class FlushTrigger { LAUNCH, FOREGROUND, NETWORK_RESTORED, SIGNED_IN, USER_REFRESH, SHARE_RECEIVED }
data class InboxRecord(val clientSubmissionId: String, val sourceUrl: String, val sharedAtIso: String, val accountBinding: String?)
/** deletable: 파일을 지워도 되는 key(가져왔거나 영구히 잘못된 기록). retained: DB 실패로 다음에 다시 시도. */
data class InboxImportResult(val deletable: List<String>, val retained: List<String>)
data class SubmissionView(
    val accountId: String?,
    val local: List<LocalSubmission>,     // pending() 결과
    val processing: List<WishlistItem>,   // 로그인 때 processingItems()
    val flushing: Boolean,
)

// S/submission/SubmissionCoordinator.kt (SharedRuntime.submissions()로 공개)
class SubmissionCoordinator internal constructor(/* store, create, get, session, clock, ids, scope, ready, beforeRefresh */) {
    val view: StateFlow<SubmissionView>
    /** Android 공유 Activity: 저장 후 카드 종류 반환, 로그인+online이면 flush 요청. ready를 최대 1500ms 기다린다. */
    suspend fun receiveShared(text: String?, online: Boolean): ShareCardKind
    suspend fun importInbox(records: List<InboxRecord>): InboxImportResult
    /** fire-and-forget. 실행 중이면 rerun만 표시. */
    fun requestFlush(trigger: FlushTrigger)
    /** flush → (DEBUG beforeRefresh) → 캐시 PROCESSING 항목마다 ITEM-03 → view 갱신. 당겨서 새로고침이 기다린다. */
    suspend fun refresh(trigger: FlushTrigger)
}
```

flush 알고리즘(세션 snapshot은 시작 때 한 번 읽고, 모든 store 호출에 그 snapshot을 넘긴다):

```text
snapshot.accountId == null → view만 갱신하고 끝
ready = store.prepareFlush(snapshot)            // reset SUBMITTING + bind unbound (POST보다 먼저 commit)
for s in ready where s.status == PENDING && (s.retryAfter == null || s.retryAfter <= now), 오래된 순:
    store.markSubmission(snapshot, s.id, SUBMITTING, null, null) 실패 → 중단
    result = create(CreateItemCommand(s.id, s.sourceUrl, s.sharedAt))
    Success → store.accept(snapshot, s.id, item)     // SESSION_CHANGED면 아래 정책
    Failure → SubmissionErrorPolicy.decide(error, now) → (status, retryAfter, stopFlush)
              store.markSubmission(snapshot, …)      // SESSION_CHANGED면 store도 거절 → 그대로 두면 다음 flush의 prepareFlush가 PENDING으로 되돌림
              stopFlush면 중단
끝나면 rerun 표시가 있으면 한 번 더
```

SESSION_CHANGED 때는 어떤 store 쓰기도 성공하지 않으므로 행은 `SUBMITTING`+원래 binding으로 남고, 원래 계정의 다음 `prepareFlush`가 `PENDING`으로 되돌린다(다른 계정의 `resetSubmitting`은 binding 조건 때문에 건드리지 않는다).

`SubmissionErrorPolicy.decide(error: ClientError, now: Instant): Decision(status, retryAfter, stopFlush)` — C3-D8 표 그대로. RATE_LIMITED는 `now + (retryAfterSeconds ?: 60).seconds`.

| 만들 것 | 검증할 것 | 하지 않을 것 |
| --- | --- | --- |
| parser·표기·coordinator·오류 정책·DEBUG 분석 진행 | Review Focus 1~4, 카드 종류, import 멱등, retryAfter, 첫 POST 전 binding commit | 플랫폼 신호·화면 |

- [x] **Step 1: parser 벡터 테스트(RED).** `ShareTextParserTest` — 이 표는 Task 6 Swift 테스트와 **같은 입력·기대값**이다:

```kotlin
private val vectors = listOf(
    "https://www.musinsa.com/products/123" to ParsedShare.Link("https://www.musinsa.com/products/123"),
    "[무신사] 오버핏 셔츠 https://musinsa.com/p/1 지금 확인하세요" to ParsedShare.Link("https://musinsa.com/p/1"),
    "링크: https://coupang.com/vp/2." to ParsedShare.Link("https://coupang.com/vp/2"),
    "(https://ohou.se/p/3)" to ParsedShare.Link("https://ohou.se/p/3"),
    "https://a.example/x_(y)" to ParsedShare.Link("https://a.example/x_(y)"),
    "첫 https://a.example/1 둘째 https://b.example/2" to ParsedShare.Link("https://a.example/1"),
    "HTTPS://A.EXAMPLE/Path" to ParsedShare.Link("HTTPS://A.EXAMPLE/Path"),
    "상품　https://a.example/1　끝" to ParsedShare.Link("https://a.example/1"),
    "ftp://a.example/1" to ParsedShare.NoLink,
    "https://" to ParsedShare.NoLink,
    "그냥 글이에요" to ParsedShare.NoLink,
    "" to ParsedShare.NoLink,
    null to ParsedShare.NoLink,
    "https://a.example/" + "a".repeat(2048 - 18) to ParsedShare.Link("https://a.example/" + "a".repeat(2048 - 18)),
    "https://a.example/" + "a".repeat(2048 - 17) to ParsedShare.TooLong,
)
@Test fun vectors() = vectors.forEach { (input, expected) -> assertEquals(expected, ShareTextParser.parse(input), "input=$input") }
```

규칙: 정규식 `(?i)https?://[^\s<>"'　]+`의 첫 매치, 끝에서 `.,;:!?` 와 짝 없는 `)` `]` `}` `>` `」` `』` `'` `"`를 반복 제거, scheme 뒤 host(첫 `/` `?` `#` 전) 비어 있으면 NoLink, `length > 2048`이면 TooLong. 원문 대소문자와 percent-encoding은 보존(정규화는 서버 몫).

`DisplayFormatTest`: `host("https://www.Musinsa.com/p")=="musinsa.com"`, `host("https://ohou.se")=="ohou.se"`, `host("not a url")=="not a url"`; relative: 30초→JustNow, 59분→Minutes(59), 같은 날 5시간→Hours(5), 어제 23:59 vs 오늘 00:01→Yesterday(offset +9h), 같은 두 시각을 offset 0으로 계산하면 결과가 달라짐을 단언, 2일→Days(2), 미래 시각(시계 역행)→JustNow.
- [x] **Step 2: coordinator 테스트(RED).** `SubmissionCoordinatorTest` harness: 실제 SQLite `SqlLocalStore` + `ScriptedCreate`(호출 기록, 응답 대본, 대기 gate) + C2 `FakeStore`. 필수 테스트:

```kotlin
@Test fun loggedOutShareIsLocalUnboundAndNotSent()          // LOCAL, binding null, create 호출 0
@Test fun loggedInOnlineShareIsSavedAndSent()               // SAVED, flush 후 local 비고 cache에 PROCESSING
@Test fun loggedInOfflineShareIsOfflineAndKeptPending()     // OFFLINE, binding=A, create 호출 0 (online=false면 flush 요청 안 함)
@Test fun invalidAndTooLongShareSaveNothing()               // INVALID ×2, pending 비어 있음
@Test fun storeFailureReturnsStoreFailed()                  // 닫힌 driver → STORE_FAILED
@Test fun notReadyWaitsThenStoreFailedAfter1500ms()         // ready=false 유지 → virtual time 1500ms 뒤 STORE_FAILED
@Test fun sameTextSharedTwiceCreatesTwoKeys()               // RF4
@Test fun bindingIsCommittedBeforeFirstPost()               // ScriptedCreate가 호출 시점에 store를 읽어 binding=A 확인
@Test fun signInSendsAllUnboundOldestFirst()                // 3개 unbound → signIn → create 순서 = sharedAt 오름차순
@Test fun accountSwitchDuringPostKeepsBindingAndNeverSendsFromOtherOwner() { // RF1
    // A 로그인, POST를 gate로 붙잡음 → signOut → signIn(APPLE=B) → gate 해제
    // 기대: create 호출 key 집합에 B owner 호출 없음, 행 binding=A 유지, B의 view.local 비어 있음
    // A 재로그인 → flush → 같은 key 재호출 1회, FakeStore A에 항목 1개(replay)
}
@Test fun staleSubmittingIsResentWithSameKey()              // RF2: 행을 SUBMITTING으로 직접 둔 뒤 새 coordinator flush → 같은 key POST
@Test fun responseLossThenResendDoesNotDuplicate()          // 첫 POST는 FakeStore에 생성되지만 결과를 NETWORK로 바꿔 반환 → 재전송 replay, A 항목 1개
@Test fun concurrentTriggersRunSingleFlightWithRerun()      // RF4: gate 중 requestFlush ×3 → 동시 create 최대 1, flush 총 2회
@Test fun networkErrorKeepsPendingWithError()               // PENDING, lastSubmissionError.kind=NETWORK
@Test fun rateLimitedSkipsUntilRetryAfter()                 // retryAfterSeconds=30 → 29초 뒤 flush 호출 0, 31초 뒤 1
@Test fun validationAndConflictBecomeFailedAndAreNotResent()// FAILED, 다음 flush 호출 0
@Test fun unauthenticatedStopsFlush()                       // 첫 항목 401 → 두 번째 POST 없음, 둘 다 PENDING
@Test fun importInboxIsIdempotentAndKeepsShareTimeBinding() // 같은 record 두 번 → deletable 두 번, 행 1개, binding 유지
@Test fun reimportingSameRecordIsNoOp()                     // RF2
@Test fun importRejectsBadRecordsAsDeletable()              // UUID 아님, sharedAt 파싱 실패, NoLink URL → deletable, 행 없음
@Test fun importSameKeyDifferentUrlIsDeletableAndOriginalKept()
@Test fun importStoreFailureIsRetained()                    // 닫힌 driver → retained
@Test fun refreshUpdatesProcessingFromItem03()              // cache PROCESSING → FakeStore에서 READY로 완료 → refresh 후 view.processing 비어 있음
@Test fun viewHidesOtherAccountsLocalItems()                // A의 미전송은 B view에 없음, 로그아웃 view에는 unbound만
```

`SubmissionErrorPolicyTest`: C3-D8 표의 모든 `ErrorKind`를 열거해 기대 Decision을 단언(새 kind가 생기면 컴파일 경고 대신 테스트가 실패하도록 `ErrorKind.entries` 전체를 순회).

`DebugAnalysisDriverTest`: PROCESSING 4초 → 완료 안 됨, 5초 → READY(name=host 기반, categoryId=seed 첫 leaf), DELETED는 건드리지 않음, 다른 계정 항목 건드리지 않음.
- [x] **Step 3: RED 확인.** 각 test 클래스 필터 실행, 실패 원인 기록.
- [x] **Step 4: 구현.** 위 Interfaces와 알고리즘대로. single-flight는 `Mutex` + `AtomicBoolean rerun`(kotlinx `MutableStateFlow<Boolean>` CAS) 없이 `Channel<FlushTrigger>(CONFLATED)`를 하나의 consumer coroutine이 소비하는 방식으로 구현해도 된다(어느 쪽이든 테스트 기준을 지킨다). `view`는 각 쓰기 후와 `session.state` 변경 시 `store.pending()`+`processingItems()`로 다시 계산. receive의 key는 `ids.newId()`, `sharedAt = clock.now()`, binding = 현재 `session.state.value.accountId`. 카드: binding null→LOCAL, INVALID/TooLong→INVALID, online→SAVED(+`requestFlush(SHARE_RECEIVED)`), 아니면 OFFLINE. `SAVED_OPEN_APP`은 iOS 확장이 Swift에서만 쓰지만 같은 enum으로 둔다. `FakeStore.completeDueAnalyses(now, minAge, outcome: (WishlistItem) -> AnalysisOutcome)`는 기존 `completeAnalysis` 경로를 재사용하고 timer를 두지 않는다. `DebugAnalysisDriver`는 DEBUG DI에서만 `beforeRefresh`로 주입. `FakeAuthFacade.signIn` 성공 끝에 `coordinator.requestFlush(SIGNED_IN)`(순환 의존을 피하려 DI에서 `onSignedIn: () -> Unit` 콜백으로 연결).
- [x] **Step 5: GREEN.** KMP_TEST 전체, 건수 기록.
- [x] **Step 6: 커밋.** `feature(kmp): 공유 수신·로컬 대기 전송 조정기 구현` + 본문.

## Task 3 이후 사용자 확인 지점

Task 0~3 결과(app group spike 판정, 상태 전이 테스트 목록과 두 runtime 건수, Review Focus 1~4 증거, 변경된 공개 API 요약)를 보고하고, 화면 task로 넘어가기 전에 사용자 확인을 받는다.

## Task 4: AccountPresenter·HomePresenter

**Files:**
- Create: `S/presentation/AccountPresenter.kt`, `S/presentation/HomePresenter.kt`, `S/presentation/HomeState.kt`
- Modify: `S/di/SharedRuntime.kt`(`accountPresenter()`, `homePresenter()`)
- Test: `T/presentation/AccountPresenterTest.kt`, `T/presentation/HomePresenterTest.kt`

**Interfaces:**
- Consumes: Task 2 `AuthFacade`, Task 3 `SubmissionCoordinator`, `DisplayFormat`.
- Produces:

```kotlin
data class AccountState(
    val restored: Boolean,
    val account: AuthAccount?,
    val showFirstRunLogin: Boolean,   // restored && account == null && !seen
    val signingIn: AuthProvider?,
    val error: ClientError?,
)
class AccountPresenter : Presenter {
    val state: StateFlow<AccountState>
    fun signIn(provider: AuthProvider)
    fun skipFirstRunLogin()           // markFirstRunLoginSeen
    fun signOut()
    override fun close()
}

enum class RowStatus { LOCAL_ONLY, SENDING, WAITING_NETWORK, FAILED, PROCESSING }
data class HomeRow(val key: String, val host: String, val sourceUrl: String, val savedAt: RelativeTime, val status: RowStatus)
sealed interface HomeState {
    data object Loading : HomeState
    data class LoggedOut(val pending: List<HomeRow>) : HomeState            // 오래된 순
    data class LoggedIn(val processing: List<HomeRow>, val refreshing: Boolean) : HomeState
}
class HomePresenter : Presenter {
    val state: StateFlow<HomeState>
    fun refresh()                     // USER_REFRESH, refreshing 표시 후 해제
    fun onForeground()                // FOREGROUND refresh, refreshing 표시 없음
    override fun close()
}
```

LoggedIn 줄: local `SUBMITTING`→SENDING, `PENDING`→WAITING_NETWORK, `FAILED`→FAILED, 캐시 `PROCESSING`→PROCESSING. 정렬은 local(오래된 순) 다음 processing(`createdAt` 오래된 순). `savedAt`은 Presenter가 `clock.now()`와 `PlatformResources.utcOffsetSeconds(now)`로 계산하고 `refresh`·state 갱신 때 다시 계산한다. `PlatformResources`에 `val utcOffsetSeconds: (Instant) -> Int`를 추가한다(Android `TimeZone.getDefault().getOffset(epochMillis) / 1000`, iosMain `NSTimeZone.localTimeZone.secondsFromGMTForDate(...)`, 테스트는 고정값). 막 binding된 뒤 아직 시도하지 않은 `PENDING`도 WAITING_NETWORK 문구로 보이며, flush가 곧 SENDING으로 바꾼다(별도 상태를 두지 않는다).

| 만들 것 | 검증할 것 | 하지 않을 것 |
| --- | --- | --- |
| 두 Presenter와 상태 | 첫 실행 표시 조건, 로그인 중 중복 탭 무시, 로그아웃 후 상태, 줄 상태 매핑·정렬, close 뒤 intent 무시, 계정 전환 시 이전 줄이 섞이지 않음 | 플랫폼 UI 상태(펼침·확인창·wvDone) |

- [x] **Step 1: 실패하는 테스트(Turbine).** AccountPresenterTest: `firstRunShownOnlyWhenRestoredSignedOutAndNotSeen`, `skipMarksSeenAndHides`, `signInTwiceWhileSigningInCallsOnce`, `signInFailureSetsErrorAndClearsSigningIn`(RELEASE facade), `signOutReturnsToSignedOutWithoutFirstRun`, `closedPresenterIgnoresIntents`. HomePresenterTest: `loggedOutShowsUnboundOldestFirstWithLocalOnly`, `loggedInMapsLocalAndProcessingStatuses`, `refreshTogglesRefreshingAndResendsPending`, `accountSwitchNeverEmitsPreviousAccountRows`(B로 바뀐 뒤 첫 LoggedIn에 A key 없음), `relativeTimeRecomputedOnRefresh`, `closedPresenterStopsCollecting`.
- [x] **Step 2: RED 확인.**
- [x] **Step 3: 구현.** C2 `ItemDetailPresenter` 패턴(생성자 dispatcher, `SupervisorJob` scope, `close()` 멱등). 홈 state는 `combine(auth.account, coordinator.view)`에서 계정 일치하는 view만 사용.
- [x] **Step 4: GREEN.** KMP_TEST.
- [x] **Step 5: 커밋.** `feature(kmp): 로그인·홈 Presenter 구현`.

## Task 5: Android 화면·공유 Activity·신호

**Files:**
- Create: `A/feature/login/LoginScreen.kt`, `A/feature/home/HomeScreen.kt`, `A/feature/home/HomeLoggedOutContent.kt`, `A/feature/home/HomeLoggedInContent.kt`, `A/feature/home/HomeRowText.kt`, `A/feature/settings/SettingsScreen.kt`, `A/feature/session/AccountPresenterOwner.kt`, `A/feature/home/HomePresenterOwner.kt`, `A/share/ShareReceiverActivity.kt`, `A/share/ShareCard.kt`, `A/platform/NetworkSignals.kt`, `A/platform/ForegroundSignals.kt`, `A/platform/WebViewDataCleaner.kt`, `A/ui/AppRoutes.kt`(설정 route·codec)
- Modify: `A/WishlistApplication.kt`(신호 시작, `LAUNCH` refresh), `A/ui/WishlistApp.kt`(첫 실행 로그인 overlay), `A/ui/AppRoute.kt`(Home 탭 = HomeScreen, debug/release 공통), `src/debug/.../ui/VariantRoutes.kt`(Home 탭 데모 제거, 카테고리·목적은 유지), `src/release/.../ui/VariantRoutes.kt`, `client/android/src/main/AndroidManifest.xml`, `res/values/strings.xml`, `res/values-en/strings.xml`, `res/values/themes.xml`(`Theme.Wishlist.ShareCard`)
- Test: `client/android/src/test/kotlin/app/wishlist/android/share/ShareIntentTextTest.kt`, `…/feature/home/HomeRowTextTest.kt`, `…/feature/session/AccountPresenterOwnerTest.kt`

| 만들 것 | 검증할 것 | 하지 않을 것 |
| --- | --- | --- |
| FLogin·FHomeLoggedOut·FHome(분류 중만)·FSettings 4상태·공유 카드 4+1종, 신호, 웹뷰 삭제 | intent 글 추출(EXTRA_TEXT·EXTRA_SUBJECT 합치기), 문구 매핑, owner 수명, 에뮬레이터 실제 SQLite·공유 동작 | 오픈소스 라이선스 화면, 웹뷰 화면 |

- [x] **Step 1: 실패하는 단위 테스트.** `ShareIntentText.from(intent)`: `ACTION_SEND` text/plain의 `EXTRA_TEXT`가 우선, 없으면 `EXTRA_SUBJECT`, 둘 다 있고 TEXT에 링크가 없으면 `"$subject $text"`. `HomeRowText`: `RelativeTime`·`RowStatus` → 문구 표 키(문자열 리소스 id) 매핑 전수. `AccountPresenterOwner`: `onCleared`에서 Presenter close.
- [x] **Step 2: RED → 구현 → GREEN** (`:android:testDebugUnitTest --tests …`).
- [x] **Step 3: 공유 Activity.** Manifest:

```xml
<activity
    android:name=".share.ShareReceiverActivity"
    android:exported="true"
    android:excludeFromRecents="true"
    android:noHistory="true"
    android:taskAffinity=""
    android:theme="@style/Theme.Wishlist.ShareCard">
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/plain" />
    </intent-filter>
</activity>
```

테마: `windowIsTranslucent=true`, `windowBackground=@android:color/transparent`, `windowNoTitle`, `windowAnimationStyle=@null`, `backgroundDimEnabled=false`. Activity는 `lifecycleScope`에서 `runtime.submissions().receiveShared(text, NetworkSignals.isOnline(context))` → 카드 표시 → 340ms `standard` 올라옴, 1500ms 유지, 260ms `accelerate` 내려감(아래 40dp·좌우 16dp, 버튼 없음) → `finish()` + `overridePendingTransition(0, 0)`(API 34+는 `overrideActivityTransition`). 카드 문구는 receive 결과로 정하므로 receive가 끝난 뒤 카드를 올린다. receive는 ready 대기 상한 1500ms 안에 끝나며(넘으면 STORE_FAILED), 로컬 저장은 수 ms라 실제 지연은 거의 없다.
- [x] **Step 4: 신호.** `NetworkSignals`: `ConnectivityManager.registerDefaultNetworkCallback`의 `onAvailable`/capabilities `VALIDATED` 전이에서 `requestFlush(NETWORK_RESTORED)`, `isOnline()`은 active network의 `INTERNET`+`VALIDATED`. `ForegroundSignals`: `ProcessLifecycleOwner`가 의존성에 없으면 추가하지 않고 `Application.ActivityLifecycleCallbacks`로 started 수 0→1 전이에서 `HomePresenterOwner`가 아닌 coordinator `refresh(FOREGROUND)`. `ACCESS_NETWORK_STATE` 권한을 Manifest에 추가.
- [x] **Step 5: 화면.** 각 보드 HTML의 px·색·문구를 토큰으로 옮긴다. 홈 오른쪽 위 원형 버튼 → `navigator.push(SettingsRoute, slide)`. 첫 실행 로그인은 `WishlistApp` 위 전체 화면 레이어(탭 바 없음), 홈 카드·설정의 "로그인"은 같은 화면을 push slide로 연다. 로그아웃(먹색 주 버튼)·웹뷰 삭제(빨강 `#C62828` 주 버튼) 확인창은 `WLConfirmDialog`. 당겨서 새로고침은 기존 의존성 안에서(`material3` `PullToRefreshBox`) 구현하고 없으면 사용자 확인. "원본"은 `Intent.ACTION_VIEW`. 버전은 `BuildConfig.VERSION_NAME`. 라이선스 줄 숨김. 웹뷰 삭제: `CookieManager.getInstance().removeAllCookies(null)`, `flush()`, `WebStorage.getInstance().deleteAllData()`, `WebView(context).apply { clearCache(true); destroy() }`(main thread).
- [x] **Step 6: ANDROID_CHECK.**
- [x] **Step 7: 에뮬레이터 smoke(실제 SQLite 첫 사용).** 포커스 확인 후 debug 설치·실행. 다음을 순서대로 확인하고 스크린샷을 scratchpad에 저장:
  1. 첫 실행 FLogin → 나중에 하기 → FHomeLoggedOut(대기 0).
  2. `adb shell am start -n app.wishlist.android/.share.ShareReceiverActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "[무신사] 셔츠 https://www.musinsa.com/p/1"` → "이 기기에 저장했어요" 카드.
  3. 앱 강제 종료(`am force-stop`) 후 재실행 → 대기 1줄 유지(**Android Context SQLite 실기기 확인**).
  4. 링크 없는 글 공유 → 실패 카드, 행 증가 없음.
  5. 로그인(Google) → 분류 중 카드에 1줄(보내는 중→정보를 가져오는 중) → 5초 뒤 당겨서 새로고침 → 줄 사라짐.
  6. `adb shell svc wifi disable; svc data disable` 상태로 공유 → OFFLINE 카드 → 홈 "연결되면 보내요" → 네트워크 복구 → 자동 전송.
  7. 설정 → 로그아웃 확인 → 로그인 전 설정 → Apple로 로그인 → 이전 Google 계정의 미전송 항목이 보이지 않음.
  8. 설정 → 웹뷰 데이터 삭제 → "방금 삭제했어요".
- [x] **Step 8: 커밋.** `feature(android): 공유 수신·로그인·로그인 전 홈·설정 화면 구현` + 본문(smoke 결과).

## Task 6: iOS 화면·Share Extension·inbox·신호

**Files:**
- Create: `I/AppGroupShared/AppGroup.swift`(group id, defaults 키, inbox 경로), `I/AppGroupShared/InboxRecordFile.swift`(Codable v1), `I/ShareExtension/ShareTextExtractor.swift`, `I/ShareExtension/ShareInboxWriter.swift`, `I/ShareExtension/ShareCardView.swift`, `I/ShareExtension/ShareDirectSender.swift`(protocol + `DisabledShareDirectSender`), `I/ShareExtension/Localizable.xcstrings`, `I/Wishlist/Platform/ShareInboxReader.swift`, `I/Wishlist/Platform/SessionMirror.swift`(계정 → app group defaults), `I/Wishlist/Platform/NetworkSignals.swift`, `I/Wishlist/Platform/WebViewDataCleaner.swift`, `I/Wishlist/Features/Login/LoginScreen.swift`, `I/Wishlist/Features/Home/{HomeScreen,HomeLoggedOutView,HomeLoggedInView,HomeRowText}.swift`, `I/Wishlist/Features/Settings/SettingsScreen.swift`, `I/Wishlist/Features/Session/{AccountPresenterOwner,HomePresenterOwner}.swift`
- Modify: `I/ShareExtension/ShareViewController.swift`, `I/ShareExtension/Info.plist`(UIAppFonts), `I/Wishlist/WishlistApp.swift`(scenePhase → import+refresh, LAUNCH), `I/Wishlist/ContentView.swift`(첫 실행 로그인 레이어, Home 탭 = HomeScreen), `I/Wishlist/Resources/Localizable.xcstrings`, `project.pbxproj`(새 파일·두 target 멤버십: `WishlistTokens.swift`·`WLTypography.swift`·`WLText` 정의 파일·서체 파일·`AppGroupShared/*`는 확장에도)
- Test: `I/WishlistTests/ShareTextExtractorTests.swift`(확장 소스 파일을 테스트 target에도 포함), `InboxWriterReaderTests.swift`, `HomeRowTextTests.swift`, `AccountPresenterOwnerTests.swift`

| 만들 것 | 검증할 것 | 하지 않을 것 |
| --- | --- | --- |
| 확장 카드·inbox 쓰기, 앱 가져오기·삭제, 신호, 화면 4종 | Kotlin과 같은 parser 벡터, 원자적 쓰기, import 뒤에만 삭제, owner 수명, 시뮬레이터 Safari 공유 동작 | 확장 background 전송 활성화, Keychain 공유 |

- [x] **Step 1: 실패하는 XCTest.** `ShareTextExtractorTests`는 Task 3 벡터 표를 **같은 순서·값**으로 옮긴다(`NSRegularExpression` 패턴 `(?i)https?://[^\s<>"'\u{3000}]+`, 같은 trailing 제거, 2048 기준은 `String.utf16.count`가 아니라 Kotlin `String.length`와 같은 UTF-16 길이 `(url as NSString).length`). `InboxWriterReaderTests`(임시 디렉터리 주입):

```swift
func testWriteIsAtomicAndReadable()          // write → 디렉터리에 <key>.json 하나, 임시 파일 없음, decode 일치
func testFileDeletedOnlyAfterImport()        // RF2: importer가 retained 반환 → 파일 유지, deletable → 삭제
func testCorruptFileIsReportedDeletable()    // "{" 파일 → 읽기 결과 corrupt key 목록, 삭제
func testUnknownVersionIsKept()              // v:2 파일 → 건드리지 않음(미래 확장 호환)
```

- [x] **Step 2: RED → 구현.** inbox JSON v1:

```json
{"v":1,"clientSubmissionId":"<lowercase uuid>","sourceUrl":"<extracted>","sharedAt":"2026-10-07T01:02:03.456Z","accountBinding":null}
```

쓰기는 `Data.write(to: tmp, options: .atomic)` 후 `FileManager.moveItem`(`inbox/.tmp-<key>` → `inbox/<key>.json`). 확장 흐름: `extensionContext.inputItems`의 `NSItemProvider`에서 `UTType.url` 우선, 없으면 `UTType.plainText` → 추출 → 카드 종류(app group defaults `wl.session.accountBinding` 존재 → `SAVED_OPEN_APP`, 없으면 `LOCAL`, 링크 없음 → `INVALID`, 쓰기 실패 → `STORE_FAILED`) → 카드 340ms `standard` 올라옴·1500ms·260ms `accelerate` → `completeRequest(returningItems: nil)`. 확장 view 배경은 투명. `DisabledShareDirectSender.send(record:)`는 아무것도 하지 않으며 문서 주석에 활성화 조건(가입·Keychain 공유·토큰 만료 정책)을 적는다. 앱: `SessionMirror`가 `AccountPresenterOwner.account` 변화를 `UserDefaults(suiteName:)`에 쓴다. `ShareInboxReader`는 파일 목록을 이름순으로 읽어 `runtime.submissions().importInbox(records:)` 호출 후 `deletable`만 삭제하고, 이어서 `refresh(trigger:)`. scenePhase `.active` 진입마다 reader → refresh(첫 진입은 LAUNCH, 이후 FOREGROUND). `NetworkSignals`: `NWPathMonitor` `.satisfied` 전이 → `requestFlush(.networkRestored)`.
- [x] **Step 3: 화면.** Android와 같은 보드 기준. 홈 오른쪽 위 → `WLNavigator` push slide로 설정. 첫 실행 로그인은 `ContentView`의 탭 셸 위 레이어. 당겨서 새로고침은 `ScrollView.refreshable`(시스템 indicator 허용; 디자인 결정과 다르면 기록). "원본"은 `openURL`. 버전 `CFBundleShortVersionString`. 웹뷰 삭제 `WKWebsiteDataStore.default().removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast)`. VoiceOver: 카드 등장 시 `UIAccessibility.post(.announcement, title+line)`, 펼치기 화살표 버튼 라벨.
- [x] **Step 4: GREEN.** IOS_TEST(새 테스트 포함 건수 기록), Release simulator build.
- [ ] **Step 5: 시뮬레이터 확인.** Safari에서 상품 URL 공유 → 위시리스트 → 카드(로그인 전 "이 기기에 저장했어요") → 앱 열기 → 대기 1줄. 로그인 → 분류 중 → 5초 뒤 새로고침 → 사라짐. 로그인 상태 공유 → "앱을 열면 정보를 가져와요" → 앱 foreground → 전송. Notes 앱에서 링크 없는 글 공유 → 실패 카드. 공유 시트 조작 방법(사용자 수동 또는 computer-use)은 Task 0에서 정한 방식을 따른다. — **부분 완료: Safari 공유(로그인 전·로그인 뒤)·가져오기·전송·새로고침은 확인했다. Notes 앱 공유는 자동화하지 못해 2048자 초과 URL로 INVALID 카드를 확인했다.**
- [x] **Step 6: 커밋.** `feature(ios): 공유 확장·로그인·로그인 전 홈·설정 화면 구현` + 본문.

## Task 7: 화면 비교·예외 경로·성능 측정

**Files:**
- Create: `docs/history/architecture/client/c3-verification-<실행 날짜>.md`, 스크린샷은 커밋하지 않고 PR에 첨부
- Modify(debug 전용 시연 hook): Android `src/debug/.../di/VariantStartup.kt`와 `MainActivity` debug 경로에서 launch intent extra `wl.fake.delayItem01`(ms)·`wl.fake.pendingCount`(N) 처리, iOS `Debug/DebugSessionBootstrap.swift`에서 같은 이름의 launch argument 처리. KMP에는 internal debug 진입점 `SharedRuntime.debugControls()`(DEBUG만, RELEASE는 null)로 `delayNext`·미귀속 대기 N개 생성을 노출한다
- Modify: `docs/architecture/client/c3-performance-checks.md`(측정 결과·입력 항목 C5/C6 이관)

| 만들 것 | 검증할 것 | 하지 않을 것 |
| --- | --- | --- |
| 두 플랫폼 라이트·다크 스크린샷 비교표, 예외 경로 체크, 대기 목록 20/100/300 측정 | 대상 보드 9종 + 실패 카드, 오프라인·계정 전환·강제 종료 복구·DB 실패 카드, 목록 프레임·메모리 | 임의 통과 기준 수치(실측 baseline만 기록) |

- [ ] **Step 1: 스크린샷.** 390×844 기준(에뮬레이터는 해당 해상도 AVD 또는 크기 조정, 시뮬레이터는 iPhone 17 Pro를 그대로 쓰고 차이를 표기). 라이트·다크 각각 FLogin, FHomeLoggedOut(접힘·펼침), FHome 분류 중, FSettings, FSettingsLoggedOut, FSettingsLogout, FSettingsWebviewClear, FShareSaved, FShareSavedLocal, FShareSavedOffline(Android), iOS SAVED_OPEN_APP, 실패 카드. 핸드오프 `shots/*.png`와 나란히 놓고 차이를 표로 기록(차이를 고치면 해당 플랫폼 task 범위의 `bugfix` 커밋). — **부분 완료: 13개 보드 × 라이트·다크를 비교했다(`91729a7`). Android 온라인 FShareSaved는 에뮬레이터 DNS 문제로 찍지 못했다(사용자 확인 필요).**
- [x] **Step 2: 예외 경로.** Review Focus 1·2를 실제 앱에서: 전송 중(`FakeStore.delayNext(ITEM_01, 5000)`을 DEBUG 메뉴 없이 쓰려면 debug 전용 intent extra나 launch argument `-wl.fake.delayItem01 5000`을 추가) 로그아웃→다른 계정 로그인→원래 계정 재로그인, 전송 중 강제 종료 후 재실행.
- [ ] **Step 3: 성능.** debug launch argument `-wl.fake.pendingCount N`(N=20/100/300, 로그인 전 미귀속 행 생성)으로 FHomeLoggedOut 펼침 목록을 연다. Android: `adb shell dumpsys gfxinfo app.wishlist.android framestats` 스크롤 10회·`dumpsys meminfo`. iOS: Instruments Time Profiler·Allocations(사용자 승인 후) 또는 `xcrun xctrace`. 결과를 기기/OS·항목 수·글자 배율과 함께 기록. 입력 관련 항목은 C5/C6으로 넘긴다고 적는다. — **부분 완료: Android gfxinfo·meminfo와 iOS XCTest `measure`로 baseline을 기록했다. Instruments·xctrace는 `DevToolsSecurity` 승인 대기라 미실행.**
- [x] **Step 4: 커밋.** `docs: C3 화면 비교·예외 경로·성능 측정 기록`.

## Task 8: 전체 검증·문서·draft PR

**Files:**
- Modify: `client/README.md`, `docs/architecture/client/{INDEX,kmp,android,ios,server-integration-status,c3-performance-checks}.md`, `docs/superpowers/plans/INDEX.md`, 로드맵 C3 행·미결정 표(공유 확장 결정 반영), `docs/learning/client/q-and-a/INDEX.md`
- Create: `docs/history/architecture/client/ADR-030-share-receipt-mode.md`(C3-D1 결정·대안·영향), `docs/learning/client/q-and-a/QA-CLI-012-ios-share-starts-analysis.md`, history·Q&A INDEX 갱신

| 만들 것 | 검증할 것 | 하지 않을 것 |
| --- | --- | --- |
| 최종 로컬 검증, 문서, develop 대상 draft PR | 전체 명령 건수·fail/skip, 문서 링크, `git diff --check`, 브랜치 이름 확인 | merge |

- [x] **Step 1: 전체 검증.** C2 Task 10 Step 2 명령 세트 전부(토큰 테스트·check, Gradle 전체 + `linkReleaseFrameworkIosArm64`, IOS_TEST, iOS Release build). 건수·로그 경로 기록. 하나라도 실패·미실행이면 완료로 표시하지 않는다.
- [x] **Step 2: 문서.** kmp.md: Coordinator·AuthFacade·schema v2·알려진 한계 표에서 C3 항목 해결 표시(해결 안 된 것은 사유와 다음 단계). android.md·ios.md: 공유 수신 구조, 신호, app group, 확장 target, 모듈 분리 보류(C3-D10). server-integration-status: ITEM-01 fake 사용 경로와 "인증 연결" 단계 인계. Q&A: "iOS는 공유만으로 분석이 시작되나? — B는 앱을 열 때 시작, C는 background URLSession으로 확장 종료 뒤에도 전송, 비용(Keychain·토큰 만료)". 로드맵 C3 행 상태와 미결정 표의 공유 확장 항목을 "C3에서 C안으로 결정"으로 갱신하고 "인증 연결" 단계를 C12 전 별도 행으로 추가할지 사용자에게 확인.
- [ ] **Step 3: 리뷰.** 선택한 실행 방식의 전체 브랜치 독립 리뷰, 중요 결함 수정·재검증.
- [x] **Step 4: 커밋.** `docs: C3 공유 저장 구조와 검증 결과 기록`.
- [x] **Step 5: 브랜치·PR.** 사용자에게 `client/c3-share-save`로 이름을 바꿀지 확인 → push → base develop draft PR. 설명: 결정 C3-D1~D10, 범위, 로컬 검증 건수·환경·미실행, 스크린샷 비교, "인증 연결" 인계. merge는 하지 않는다.

## C3 완료 기준

- [ ] 대상 보드 9종(라이트·다크)과 실패 카드가 두 플랫폼에서 동작하고 스크린샷 비교가 PR에 있다. — **화면 비교는 끝났고(Android 온라인 FShareSaved 제외) PR 첨부는 Step 5에서 한다.**
- [x] 로그인 전 공유 → 로그인 → 전송 → `PROCESSING` → 새로고침 후 완료까지 fake로 두 플랫폼 시연 가능. (Android Task 5 smoke, iOS Task 6 시뮬레이터)
- [x] Review Focus 1~5가 테스트로 고정돼 Android host·iOS simulator에서 통과. (Task 8: host 364 · simulator 361 · XCTest 116, 실패·skip 0)
- [x] kmp.md 알려진 한계의 C3 항목(정렬·accept 검사·key 가드·DB 열기 시점·seed 실패·`changeAccount` 노출)이 해결되거나 사유와 함께 이관됨. (6개 모두 해결: `fff233a`·`108a07f`·`c791412`)
- [x] Android Context SQLite 실제 동작(강제 종료 후 유지)을 에뮬레이터에서 확인. (Task 5 smoke 3단계)
- [x] iOS 확장이 서명 없는 시뮬레이터에서 app group inbox를 쓰고 앱이 가져온다(Task 0이 실패했으면 사용자와 합의한 대체 기준). — **`CODE_SIGNING_ALLOWED=NO`에서는 app group이 없다(Task 0). 대체 기준(Ruling 4: 팀 없는 기본 "Sign to Run Locally" 서명)으로 확장 쓰기·앱 가져오기를 확인했다(Task 0 수동 공유, Task 6). 사용자가 이 대체 기준을 그대로 받아들였다(2026-10-09).**
- [x] 문서·INDEX·로드맵·server-integration-status 최신화, draft PR 생성. — **로드맵에 "인증 연결" 단계 행은 추가하지 않고 C3 행 인계로 둔다(사용자 결정 2026-10-09).**

## 실행 중 결정(ruling) 요약

실행 중 계획이 정하지 않았거나 계획과 달라진 판단이다. 근거와 비용은 실행 기록(ledger)에 있다.

| Ruling | 판단 |
| --- | --- |
| 1 | `HomePresenter.onForeground()`를 만들지 않는다. 앱 수준 foreground 신호가 `submissions().refresh(FOREGROUND)`를 부르고 HomePresenter는 `view`만 구독한다(같은 refresh 중복 방지) |
| 2 | Kotlin `ShareCardKind`에서 `SAVED_OPEN_APP`을 빼고 Swift 확장 자체 enum에 둔다(확장은 Shared를 링크하지 않음) |
| 3 | Task 0은 `xcodeproj` gem을 설치하지 않고 pbxproj를 손으로 편집한다. 공유 시트 수동 조작은 사용자에게 한 번 요청하고 그동안 Task 1을 진행한다 |
| 4 | app group 확인·공유 시연 빌드는 기본 "Sign to Run Locally"(팀 없음), IOS_TEST·CI는 `CODE_SIGNING_ALLOWED=NO`. app group 코드는 container를 주입받고 nil이면 inbox만 끈다 |
| 5 | 계획 코드의 `LazyDriver`를 고쳐 첫 open이 취소돼도 driver를 한 번만 연다(driver를 io 블록 안에서 저장) |
| 6 | Task 1 리뷰 minor 중 bootstrap `CoroutineExceptionHandler` 범위·Android open 실패 driver 누수·문서/KDoc 문구를 fix round에 넣는다 |
| 7 | 로그인 중 signIn은 signOut 경로를 먼저, seed 예외 처리, 순서 고정 테스트를 Task 2 fix round에 넣는다 |
| 8 | RELEASE `UnavailableAuthFacade.hasSeenFirstRunLogin()`은 true(실제 인증 전까지 첫 실행 로그인 안내 숨김) |
| 9 | ITEM-01 `NOT_FOUND`는 PENDING + 오류 기록(URL을 잃지 않음) |
| 10 | flush는 NETWORK·TIMEOUT·RATE_LIMITED 뒤에도 멈춘다(오프라인에서 timeout N번 직렬 방지) |
| 11 | SUBMITTING commit과 POST 사이 계정 전환 경합을 internal snapshot-aware create(`create(command, expected)`)로 막는다. 공개 `CreateItemRepository`는 그대로 |
| 12 | coordinator의 refresh 대기 hang, 새는 `CancellationException`, view 게시 원자성 minor를 Task 3 fix round에 넣는다 |
| 13 | 로그인 뒤 홈 머리 보조 줄은 보드의 "할 일 N개"(새 키 `home_todo_count`, N = 분류 중 줄 수) |
| 14 | `row_processing`은 보드 FHome의 "상품 정보 추출 중" / "Extracting product info" |
| 15 | 로그인 뒤 분류 중 줄에는 오른쪽 동작이 없다(보드의 삭제는 C4/C8, Android가 넣었던 원본 제거) |
| 16 | Task 7을 7a(리뷰 bugfix 묶음)와 7b(화면 비교·예외 경로·성능·iOS 표시)로 나눈다 |
| 17 | iOS 확장 표시는 사용자 답 전까지 권장안 A: 시스템 시트를 받아들이고 시트 안을 보드 바탕색(#E9E9E9 / #2A2A2A)으로 칠한다, 그림자 없음 |
| 18 | runtime close와 진행 중 DB query의 SIGSEGV 경합을 Task 8 전에 Task 7c로 고친다(store lease) |

## 후속 단계 인계(초안)

| 단계 | 범위 |
| --- | --- |
| 인증 연결(가입 뒤) | Firebase 프로젝트·Apple/Google 로그인을 `AuthFacade` 뒤에 연결, `AuthTokenProvider` 실제 token, iOS 확장 background URLSession 전송 활성화(Keychain 공유 access group, 토큰 만료 시 앱 전송으로 대체), ITEM-01 실서버(local 서버 + Auth Emulator) 검증, 실기기 공유 확장 확인, 개발자 팀 서명 |
| C4 | "원본"을 웹뷰로 교체, 로컬 대기·FAILED 항목 상세와 삭제 UI 검토 |
| C5/C6 | 한글 IME·입력 성능 확인(C3에 입력칸 없음), multiplatform-settings 도입 시 `app_state` 이전 여부 검토, Android 모듈 분리 재검토 |
| C7 | FHome 나머지 할 일 카드, 목록 API로 다른 기기 항목 표시 |
| C12 | 오픈소스 라이선스 화면 |
