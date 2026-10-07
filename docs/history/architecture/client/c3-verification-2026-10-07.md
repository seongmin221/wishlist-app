# C3 화면 비교·예외 경로·성능 측정 (Task 7)

> 2026-10-07 · 브랜치 `seongmin221/client-c3-share-save` · 코드 기준 `de01ded` · 스크린샷은 저장소에 넣지 않고 PR에 첨부한다

## 환경

| 항목 | Android | iOS |
| --- | --- | --- |
| 기기 | 에뮬레이터 `sdk_gphone64_arm64`, Android 16(API 36), 1080×2400 · 420dpi(411×914dp), 렌더러 Skia(OpenGL, `emulation`) | iPhone 17 Pro 시뮬레이터 iOS 26.5, 402×874pt @3x |
| 보드와 화면 크기 | 보드 390×844. 에뮬레이터 화면 크기는 바꾸지 않았다(전역 설정이라 다른 앱에 영향). 비교 이미지는 높이 844로 맞춰 나란히 놓았다 | 크기를 그대로 쓰고 높이 844로 맞춰 비교 |
| 글자 배율 | `font_scale` 1.0 | Dynamic Type Large(기본) |
| 언어 | 앱 언어 ko-KR(`cmd locale set-app-locales`) | 시스템 한국어 |
| 모드 | `cmd uimode night yes/no`로 전환한 뒤 원래 값 `yes`로 되돌림 | `simctl ui appearance light/dark` |
| 앱 | debug APK(`de01ded`) | 기본 서명 Debug(`-derivedDataPath /tmp/wishlist-signed-dd`, app group 포함) |

호스트는 Apple M4 Pro · macOS 26.5.1이다. 화면은 adb(입력 전 `mCurrentFocus`가 `app.wishlist.android`인지 확인)와 저장소 밖의 XCUITest harness로 조작했다. 상태는 각 앱 debug DB의 `app_state`·`local_submission`·`item_cache`를 앱을 멈춘 상태에서 바꿔 맞췄다(첫 실행·로그인 전·Google·Apple).

## 시연 hook (debug 전용)

`feature(kmp): 디버그 시연 hook 추가`(`4c58fe6`). `SharedRuntime.debugControls()`는 DEBUG에서만 `DebugControls`를 돌려주고, RELEASE와 close 뒤에는 null을 돌려준다.

- `delayNextItem01(ms)`: Fake ITEM-01 다음 요청 한 번만 session 검사 전에 기다리게 한다.
- `createUnboundPending(N)`: ready가 될 때까지 기다린 뒤 미귀속 PENDING 행 N개를 저장한다. 각 행은 새 key와 서로 다른 URL을 쓰고, sharedAt은 1분 간격이며 마지막 행이 현재 시각이다. 저장 뒤 flush를 요청한다.
- 앱 연결
  - Android: debug `MainActivity`의 launch intent extra `--el wl.fake.delayItem01`, `--ei wl.fake.pendingCount`
  - iOS: DEBUG launch argument `-wl.fake.delayItem01`, `-wl.fake.pendingCount`
  - 두 플랫폼 모두 cold start에서만 적용된다(Android `am start -S`, iOS `simctl launch --terminate-running-process`).
- Release 확인
  - iOS Release app 바이너리에 `wl.fake` 문자열이 0건이다.
  - release `Shared.h`에는 `DebugControls`가 남는다. DEBUG와 RELEASE가 같은 Kotlin binary를 쓰기 때문이며, KDoc에 DEBUG 전용이라고 적었다.
  - release `VariantStartup.onMainLaunch`는 아무것도 하지 않는다.
- `RuntimeDebugControlsTest`는 4개다. RED는 `debugControls` 참조가 컴파일되지 않는 상태였다. GREEN 결과는 host 360, simulator 357이다.

## 화면 비교

보드 PNG(`design/handoff/screens/shots`)와 앱 화면을 나란히 놓고 라이트·다크를 각각 비교했다. 펼친 홈 상태는 PNG가 없다. 그래서 보드 HTML 사본에서 `open`(FHome은 `o3`)만 true로 바꿔 headless Chrome으로 390×844 그림을 만들어 비교했다. 보드 PNG와 HTML이 다르면 HTML 수치를 기준으로 삼았다. 예를 들어 설정 계정 카드는 PNG에서 약 151, HTML에서 4+72+56+4=136이고, 앱은 약 135다. 굵기처럼 브라우저 렌더링에 따라 달라지는 값은 비교에서 뺐다.

| 보드 | iOS | Android | 차이·판단 |
| --- | --- | --- | --- |
| FLogin L/D | 같음 | 같음 | 버튼 묶음은 아래 안전 영역 위 40(보드는 화면 아래 40). 두 플랫폼이 같고 홈 표시줄을 피하는 배치라 유지한다 |
| FHomeLoggedOut 접힘 L/D | 같음 | 같음 | 제목 위치는 `WLTopBar` 규칙 `safeTop + 20`을 따른다(보드는 절대 위치, design-system 규칙) |
| FHomeLoggedOut 펼침 L/D | 같음 | 같음 | 행 64(10+44+10), 원본 링크, "N분 전 저장 · 이 기기에만 있어요". 데이터만 다르다(example.com, 분 단위) |
| FHome 분류 중 L/D | 같음 | 같음 | 헤더 "할 일 1개"와 분류 중 카드·행 "상품 정보 추출 중"이 같다. 보드의 분류·목적 확인 카드, 정보 보완 카드, 비교 중인 목적은 C3 범위가 아니다. 행의 삭제 버튼은 C4/C8로 미뤘다(Ruling 15) |
| FSettings L/D | 같음 | 같음 | 오픈소스 라이선스 행은 숨겼다(Task 5·6 결정). 버전은 실제 값 0.1.0이다 |
| FSettingsLoggedOut L/D | 같음 | 차이 | Android 안내 문구가 "기기에 / 서도"처럼 단어 중간에서 줄을 바꾼다. 보드는 keep-all이다(**알려진 미해결**, 디자인 시스템 전체 문제라 사용자와 정한다) |
| FSettingsLogout L/D | 차이 | 차이 | 보드는 취소와 로그아웃 버튼 폭이 1:1(`flex: 1 1 0`)이다. 두 앱은 `WLButtonPair`의 디자인 시스템 규칙인 1:1.4를 따른다. C1 부품 규칙과 C3 보드가 충돌하므로 고치지 않고 사용자 확인 항목으로 둔다 |
| FSettingsWebviewClear L/D | 차이 | 차이 | 위와 같은 1:1.4 차이가 있다. 삭제 버튼의 위험 빨강과 네 줄 글머리표는 같다. 찍을 때 뒤 화면이 로그아웃 상태 설정이었다(보드는 로그인 상태) |
| FShareSaved L/D | 보드 차이(기록) | 미촬영 | iOS(SAVED_OPEN_APP)는 iOS 26이 확장을 전체 높이의 불투명 시스템 시트로 감싸고 위쪽 Safari를 어둡게 한다. 시트 안에 보드의 "다른 앱" 바탕(#E9E9E9 / #2A2A2A)을 칠해 카드 대비를 보드와 맞췄다(Ruling 17, `58aaa78`). 둘째 줄은 C3-D9대로 "앱을 열면 정보를 가져와요"다. Android 온라인 카드는 에뮬레이터 DNS가 실패해 네트워크가 VALIDATED가 아니었고, 앱이 규칙대로 OFFLINE 카드를 보였다. 따라서 저장 카드는 찍지 못했다(needs user) |
| FShareSavedLocal L/D | 같음(시트 차이는 위와 같음) | 같음 | Android 카드 뒤는 우리 홈 화면이다. 투명 Activity라 실제로는 공유한 앱이 보인다. 라이트에서 카드 아래로 보이는 어두운 띠는 뒤 홈의 탭 바다 |
| FShareSavedOffline L/D | 없음(iOS 카드 없음) | 수정 후 같음 | 제목과 보조 줄 간격을 4에서 6으로 맞췄다(`de01ded`). 카드 80dp, 아래 40dp |
| 링크 없음 카드 L/D | 같음 | 같음 | 보드가 없다. 실패 타일(경고)·"링크를 찾지 못했어요 / 상품 페이지에서 다시 공유해 주세요"가 FShareSaved 카드 구조와 같다 |
| 저장 실패 카드 D | 같음 | 같음 | 보드가 없다. "저장하지 못했어요 / 다시 공유해 주세요". Android는 DB 파일을 읽기 전용으로 바꿔 띄웠고, 첫 실행 로그인 화면이 함께 보였다(app_state 읽기도 실패했기 때문). iOS는 inbox 폴더를 쓰기 금지로 바꿔 띄웠다. 두 경우 모두 권한을 되돌렸다 |

이번 Task의 수정은 `bugfix: iOS 공유 카드 바탕 대비 보완`(`58aaa78`)과 `bugfix: Android 공유 카드 제목 간격 보완`(`de01ded`)이다.

## 예외 경로 (Review Focus 1·2)

판정 근거로 각 단계의 DB를 읽었다. Android는 `run-as`로 복사한 사본, iOS는 시뮬레이터 container 파일을 읽었다. Fake 서버(`FakeStore`)는 프로세스 메모리에 있으므로, "서버 항목 하나"는 그 계정 캐시에 같은 key 항목이 하나 생긴 것과 key 멱등성으로 판단했다.

**전송 중 계정 전환.** 두 플랫폼 모두 Google 로그인 상태에서 `pendingCount 1`로 행을 만들었다. 이 행은 flush에서 Google에 귀속된 뒤 SUBMITTING이 된다. ITEM-01은 30000ms 지연을 주었다. 지시값 5000ms로 먼저 해 본 Android 실행에서는 adb 조작(단계마다 uiautomator 확인 약 2초)이 끝나기 전에 지연이 끝나 Google에서 전송이 완료됐고, 그래서 판정에서 뺐다.

| 단계 | Android | iOS |
| --- | --- | --- |
| 시작 | 행 `fake-google-0001` SUBMITTING | 같음 |
| 로그아웃 | 행이 binding을 유지한 채 SUBMITTING으로 남음 | 같음 |
| Apple 로그인, 지연이 Apple 상태에서 끝남 | 행은 Google·SUBMITTING. Apple 캐시 0건, 홈 "할 일 0개" | 같음 |
| Google 재로그인 | 행이 사라지고 Google 캐시에 같은 key 항목 1건(PROCESSING), 홈 "할 일 1개"·example.com | 같음 |

**전송 중 강제 종료.** ITEM-01에 5000ms 지연을 주었다.

| 단계 | Android(`am force-stop`) | iOS(`simctl terminate`) |
| --- | --- | --- |
| 종료 직전 | Google SUBMITTING | 같음 |
| 종료 뒤 | 프로세스 없음, 행 그대로 | 같음. 6초 뒤에도 그대로라 앱 없이는 재전송하지 않는다 |
| 인자 없이 재실행, 5초 뒤 | LAUNCH flush가 SUBMITTING을 PENDING으로 되돌리고 같은 key로 재전송. 행 삭제, Google 캐시 1건 | 같음 |

- 오프라인 카드와 "연결되면 보내요" 행은 Android에서 확인했다. 네트워크 복구 뒤 자동 전송은 이번 에뮬레이터 DNS 실패로 확인하지 못했다(Task 5 smoke에서는 확인했다).
- iOS inbox를 import한 뒤 파일 삭제 전에 종료되는 경로는 이번에 기기에서 끊어 보지 않았다. `InboxReaderTests.testFileDeletedOnlyAfterImport`와 `reimportingSameRecordIsNoOp`가 다룬다.

## 성능 (기준선, 통과 기준 없음)

측정 대상은 FHomeLoggedOut 펼침 목록이다(`pendingCount` N, 미귀속 행, 다크). C3 행에는 이미지가 없다. 위·아래 스크롤을 각각 5회씩 했다. 결과와 해석은 [C3 목록·입력 성능](../../../architecture/client/c3-performance-checks.md#c3-측정-결과-2026-10-07)에 있다. 요약하면 다음과 같다.

| N | Android 프레임(전체 / jank % / p50·p90·p99 ms) | Android PSS(Java·Native) KB | iOS 메모리(XCTest 절대 물리) KB | iOS CPU 시간(10회 스크롤) s |
| --- | --- | --- | --- | --- |
| 20 | 453 / 2.65 / 17·19·20 | 94,364 (24,800 · 11,448) | 66,008 | 1.65 |
| 100 | 683 / 3.81 / 19·25·40 | 102,740 (30,092 · 13,940) | 90,458 | 8.03 |
| 300 | 661 / 5.60 / 17·25·42 | 122,341 (43,180 · 20,156) | 150,353 | 31.07 |

iOS는 `xcrun xctrace record`(Time Profiler, attach)가 기록을 시작한 뒤 끝나지 않았다. 이 맥은 `DevToolsSecurity`가 꺼져 있어 taskport 권한 승인을 기다리는 것으로 보인다. 그래서 XCTest `measure`(CPU·Memory·스크롤 signpost, 3회)로 대신 측정했다. 이 값에는 XCUITest가 매 스와이프마다 앱의 접근성 트리를 읽는 비용이 들어 있어 Instruments 수치와 같지 않다. Instruments 측정은 사용자 승인이 필요하다.

## 미실행·인계

- **needs user**
  - Android FShareSaved(온라인) 촬영: 에뮬레이터 DNS를 고친 뒤 다시 찍는다.
  - iOS xctrace Time Profiler·Allocations: `DevToolsSecurity -enable` 또는 GUI 승인이 필요하다.
  - 실기기 측정
- **사용자 확인**
  - Android keep-all 줄바꿈(디자인 시스템 전체)
  - 확인창 버튼 비율: 보드 1:1, `WLButtonPair` 1:1.4
- 입력·IME 성능 항목은 C3에 입력 칸이 없어 C5/C6으로 넘긴다.
- IOS_TEST 첫 실행에서 `SharedInteropTests.testPresenterStateFlowDeliversInitialLoadingAndItemThenCollectorCancels`가 SQLite `sqlite3_column_type` 안에서 SIGSEGV로 한 번 실패했다. 같은 class를 10회 반복하면 통과했고, 전체를 다시 실행해도 116/116 통과했다. 원인과 수정은 아래 Task 7c 절에 있다.

## 런타임 종료 중 DB 조회 충돌 (Task 7c)

- **원인:** `CloseGuard`는 graph 조회와 ready 게시만 보호했다. gated facade는 진입 때 ready를 한 번 읽을 뿐이고, `CachedGetItemRepository`는 graph의 `SqlLocalStore`를 직접 쓴다. 그래서 `presenter.close()`(협조적 취소) 직후 `runtime.close()`가 main 스레드에서 driver를 닫는 동안 io 스레드는 아직 `selectItem` 안에 있을 수 있었다(use-after-free). 위 테스트는 retry를 보낸 뒤 이미 참인 상태(같은 item, loading=false)를 기다려 retry가 끝나기 전에 끝나므로 이 경합을 만들었다. 같은 DB 파일을 여는 두 driver(설치된 앱과 테스트 runtime)는 원인이 아니다(최악이 SQLITE_BUSY).
- **수정:** `SqlLocalStore`의 모든 DB 작업이 runtime의 `CloseGuard`를 `StoreLease`로 잡는다. close 뒤 시작하는 작업은 DB를 건드리지 않고 `UNAVAILABLE/RUNTIME_NOT_READY`이고, 정리는 마지막 작업이 나갈 때 그 스레드에서 실행된다. lease는 lock이 아니라 계수기라 lock 순서는 그대로다. 공개 API는 바뀌지 않았다. 계약은 [KMP 문서의 자원 수명](../../../architecture/client/kmp.md)에 있다.
- **테스트:** `RuntimeCloseLeaseTest`(3개)가 실제 스레드 io dispatcher에서 driver 안에 멈춘 query와 close를 경합시킨다. 수정 전에는 3개 모두 두 runtime에서 실패했다(driver가 query 중에 닫힘, close 뒤 graph store가 `LOCAL_STORE_FAILURE`). `SharedInteropTests`의 retry 대기는 다른 계정으로 바꾼 뒤 retry가 만든 NOT_FOUND를 기다리도록 고쳤다. `RuntimeDebugControlsTest`에 close 뒤 null 검사를 더했다.
- **결과:** host 364, simulator 361, Android unit 87, IOS_TEST 116을 3회 연속 통과했다.
