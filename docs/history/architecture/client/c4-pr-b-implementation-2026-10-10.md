# C4 PR B 구현 기록 (2026-10-10)

> 브랜치 `client/c4-webview`(PR A 마지막 커밋 `92a140b` 위에서 시작, D20). 설계 [spec](../../../superpowers/specs/2026-10-09-client-c4-product-detail-design.md) §4·D6·D13~D16, [plan](../../../superpowers/plans/2026-10-09-client-c4-product-detail.md) Task 13~16. PR B는 원본 링크 웹뷰와 "원본 보기"·홈 "원본" 교체다. PR A 기록은 [c4-pr-a-implementation](c4-pr-a-implementation-2026-10-10.md).

## 작업 요약

| Task | 내용 | 커밋 |
| --- | --- | --- |
| 13 | 웹뷰 route(http/https + host만, 계정 범위)와 탐색 판정 순수 함수(`WebNavigationPolicy`), Android `intent:` 정화(`IntentSanitizer`). 리뷰 수정: `parseUri` 예외, iframe `data:`·`blob:` 차단, scheme 문법, `about:blank` 판정, intent data의 `file:`·`content:`·`javascript:` | `7d21e2c`, `b00c6f8`, `76fea15`, `c9ad68d` |
| 14 | Android 웹뷰 화면(FWebView·FWebViewShare·FWebViewExternal), holder 수명, 외부 앱 실행. 리뷰 수정: iframe fallback의 탭 조건, 확인창 반복 차단(`ExternalPromptGate`), detach guard, flag 정리 | `0afbb15`, `48f5e01` |
| 15 | iOS 같은 화면, `WLEntryOwners.web`. 리뷰 수정: 꾸밀 수 있는 `.linkActivated` → 실제 터치 규칙, JS 창 자동 열기 false 명시, 닫을 때 미디어 정지 | `a06c731`, `3ce48c2` |
| 16 | 상세·로컬 대기 "원본 보기"와 로그인 전 홈 "원본"을 웹뷰 push로 교체(웹 주소가 아니면 버튼 흐림·비활성), Android detach의 부모 없는 view context 복귀, 로딩·실패 중 웹뷰가 위쪽 바를 덮던 문제(`clipToBounds`), 기기 확인·문서 | `880f595`, `f9d00be`, `75efb39`, `693fedf`, 이 기록의 커밋 |

## 판단(Ruling)

11. **iframe의 `data:`·`blob:` 문서는 막는다.** 설계 §4가 `data:`·`blob:`을 "하위 리소스(iframe 아님)에만" 허용한다고 적었으므로 brief보다 설계를 따랐다. iframe의 `about:blank`·`about:srcdoc`은 안에서 연다. 대가: `data:`·`blob:` 문서를 iframe에 띄우는 일부 쇼핑몰 화면은 그 부분이 비어 보일 수 있다.
12. **iOS "사용자 탭"은 `.linkActivated` + 최근 1초 안의 실제 터치다.** 시뮬레이터에서 스크립트 `a.click()`이 `.linkActivated`로 와서 확인창 없이 `tel:`을 실행했다(D16 우회). 웹뷰 컨테이너에 다른 동작을 막지 않는 터치 기록 인식기를 두고, 터치 없는 `.linkActivated`는 확인창으로 보낸다. Android `hasGesture()`(Chromium user activation)와 같은 뜻이다. 대가: 1초보다 오래 누른 뒤 뗀 탭도 확인창이 뜬다(안전한 쪽).

13. **확인창 취소 뒤 침묵은 main frame host 기준이다.** URL 기준이면 페이지가 `?n=2`처럼 쿼리만 바꿔 다시 열어 침묵을 풀고 확인창을 반복할 수 있다. 위쪽 바와 같은 host(소문자·`www.` 뺌, `DisplayFormat.host`)가 바뀔 때만 푼다. 대가: 같은 쇼핑몰 안에서 다른 상품 페이지로 옮겨도 탭 없는 외부 요청은 계속 버려진다(탭은 늘 연다).

규칙은 [디자인 결정](../../../design/decisions.md)과 [QA-CLI-015](../../../learning/client/q-and-a/QA-CLI-015-webview-security.md)에 정리했다.

## 계획과 달라진 점

- 확인창 반복 차단(`ExternalPromptGate`)은 계획에 없었고 Task 14 리뷰에서 더했다. 확인창은 한 번에 하나, 취소 뒤에는 main frame이 다른 URL로 갈 때까지 탭 없는 외부 요청을 버린다. iOS도 같은 벡터로 맞췄다.
- 진입점은 순수 helper를 새로 만들지 않고 이미 테스트된 `WebViewRoute.of`(Android `WebViewRouteCodecTest`)·`WebPageURL(string:)`(iOS `WebViewRouteTests`)을 그대로 쓴다. 그래서 Task 16에 새 단위 테스트는 없다.
- Task 16 기기 확인 중 Android 웹뷰가 로딩·실패 중에 위쪽 바를 덮는 문제를 찾아 고쳤다(`clipToBounds`). Task 14 리뷰 수정 뒤 에뮬레이터를 다시 돌리지 않아 놓친 부분이다.

## 최종 리뷰 반영

- Android: 웹뷰가 닫힌 뒤에도 앱 전체 overlay에 남은 FWebViewExternal의 열기가 닫힌 페이지로 외부 앱을 열던 문제 — `ExternalPromptGate.close()`(holder `onCleared`)와 `mayLaunchConfirmed()`로 막고, 화면이 사라질 때 자기 확인창이 맨 위면 `OverlayHostState.dismissDialog(spec)`로 닫는다.
- 두 플랫폼: 취소 침묵을 host 기준으로(Ruling 13).
- Android: 실패 중 제목 줄 숨김(도메인만, D13), `onPageStarted`는 웹 URL만 바에 반영(`WebPageState.started`), `parseUri` 예외는 `RuntimeException` 전체를 fallback/Drop으로.
- iOS: `createWebViewWith`는 `WebPageURL`(http/https + host)만 연다.
- 실제 `intent:` adapter를 에뮬레이터에서 확인했다([화면 확인 기록](c4-detail-verification-2026-10-10.md) PR B 절).

## 남긴 점(task 리뷰에서 미룬 minor)

- Android: intent data scheme 확인이 문법 검사 없는 거부 목록, adapter의 androidTest 없음(에뮬레이터 수동 확인만), Compose wiring 단위 테스트 없음, 열리는 중이거나 다른 overlay 아래 있는 확인창은 화면이 사라져도 닫지 못함(열기는 `isClosed`로 막힘, 3차 리뷰에서 유지로 결정).
- iOS: WebKit 기록 스와이프는 실제 손가락 확인 필요, 빈 `window.open()`은 열지 않음(실기기 결제 팝업 확인), WebKit 제한 포트는 빈 화면, 유니버설 링크 https 탭은 확인창 없이 앱으로 넘어감(Android와의 차이), `about:blank`로 갈 때 자물쇠·host 유지.
- 공통: "링크를 복사했어요" pill(카드색)의 흰 페이지 위 대비, 실기기 확인 항목 두 가지(아래).

## 실기기 확인 항목(미확인)

- D16 결제 확인창 빈도(국내 결제가 탭 뒤 JS로 다시 이동해 결제마다 확인창이 뜨는지): **미확인 · 실기기**.
- 새 창을 같은 웹뷰에 열 때 `window.opener` 손실로 PG 팝업 결제가 끝나지 않는지: **미확인 · 실기기**.

## 검증(2026-10-10, 로컬)

| 항목 | PR A 끝(`92a140b`) | Task 16 | 최종 리뷰 반영 |
| --- | --- | --- | --- |
| shared Android host / iOS simulator | 496 / 493 | 496 / 493 (실패·skip 0) | 변경 없음(shared 미수정) |
| Android unit debug / release | 127 / 127 | 169 / 169 | 173 / 173 |
| iOS XCTest | 163 | 198 | 199 |
| assembleDebug·assembleRelease·lintDebug(0 errors, 경고 26 기존)·`linkReleaseFrameworkIosArm64`·iOS Release simulator build | 통과 | 통과 | assemble·lint(0 errors, 경고 26) 통과 |
| `gen_tokens.py --check`·`test_gen_tokens.py` | 통과 | 통과·8개 | — |

화면 확인은 [C4 화면 확인 기록](c4-detail-verification-2026-10-10.md)의 PR B 절에 있다. 플랫폼 CI job은 꺼져 있어 로컬 결과가 근거다.

## PR #16 `/code-review` 반영(2026-10-10)

PR A(#15)의 2·3차 리뷰 수정을 병합한 뒤 리뷰했다. 9건 중 8건을 고쳤다.

- **대기 확인창 유실 → 외부 앱 확인이 영구히 막힘(두 플랫폼):** `showDialog`가 대기열 한 칸(`pending`)에 넣은 확인창이 뒤 `show*`나 `dismissAll`로 버려지면 `onDismissed`가 불리지 않았다. 그래서 `ExternalPromptGate.prompting`이 true로 남았다. 이제 버려질 때 `WLDialogSpec.onDropped`가 불리고, 웹뷰는 `onPromptNotShown`으로 되돌린다(취소로 보지 않아 페이지가 막히지 않는다). `dismissDialog`는 대기 중인 같은 확인창도 버린다.
- **iOS 웹뷰가 닫혀도 확인창이 남음:** `dismissDialog(spec)`을 iOS에도 두었다. `WebViewModel.close()`가 `dismissPrompt`로 자기 확인창을 닫는다.
- **iOS 가장자리 뒤로 막기가 다시 나타날 때 풀림:** `onAppear`에서 `canGoBack`으로 다시 막는다.
- **Android 렌더러가 죽은 뒤 뒤로가 먹지 않음:** 새 view에는 기록이 없으므로 `canGoBack`·`canGoForward`를 false로 둔다.
- **Android `syncHistory`가 `about:blank`로 주소를 덮음:** `started()`처럼 웹 URL만 받는다.
- **Android 새 로드에서 진행 선이 안 보임:** `started()`가 progress를 0으로 되돌린다.
- **정리:** `browser_fallback_url` 상수를 하나(`BROWSER_FALLBACK_URL`)로 합쳤다. 흐림 0.4를 designsystem의 `DISABLED_ALPHA`(iOS `wlDisabledOpacity`)로 옮겨 버튼·메뉴·설정·홈·상세가 같이 쓴다.

고치지 않은 것: Android main frame `onReceivedError`의 중단 필터. WebView는 `ERR_ABORTED`를 `onReceivedError`로 보내지 않는다. 오류 코드로 거르면 실제 실패(`ERROR_UNKNOWN`)까지 숨길 수 있다.

검증: Android unit debug·`assembleDebug`·`lintDebug`, iOS XCTest 201개가 통과했다(실패 0, overlay 대기 버림·진행 선 재시작 테스트 추가).

## PR #16 3차 `/code-review` 반영(2026-10-12)

PR A(#15)가 develop에 merge된 뒤 PR B를 develop 위로 rebase하고 다시 리뷰했다. 9건 중 7건을 고쳤다.

- **Android 첫 WebView가 앱 context로 만들어짐:** `attach()`가 `create()` 뒤에 base를 Activity로 바꿨다. 이제 먼저 바꾼다(테마·창 token을 생성 때 읽는다).
- **iOS 미디어 링크가 실패로 덮임:** 미디어를 플레이어가 맡을 때 오는 WebKit 204를 102처럼 실패로 보지 않는다. 판정은 `WebLoadError.isPageFailure`로 빼 XCTest로 확인한다.
- **iOS 실패 중 이전 제목:** `titleLine`이 실패 때 nil이다(Android와 같다).
- **Android 회전이 취소로 처리됨:** Activity 재생성으로 사라진 확인창은 답하지 않은 것으로 보고 침묵을 걸지 않는다. 결제 페이지의 재시도가 막히지 않는다(D16).
- **`about:blank`가 침묵을 풂(두 플랫폼):** 웹이 아닌 main frame 이동은 침묵을 풀지 않는다. 다른 host를 거쳐 돌아오는 경우는 실제 페이지 이동이 필요하고 매번 확인창이 뜨므로 Ruling 13대로 둔다.
- **iOS 스크롤 터치도 탭으로 인정(Ruling 12 보완):** 손가락 하나가 10pt 안에서 떼어졌을 때만 탭으로 기록한다. 스크롤 직후 스크립트 클릭은 확인창이다. 1초 창은 손을 뗀 때부터 세므로 "1초보다 긴 누름 뒤 탭은 확인창" 한계도 사라졌다.
- **정리:** `mayLaunchConfirmed()`를 지우고 `isClosed`를 쓴다. iOS 가장자리 뒤로 막기는 `onAppear` + `onChange`(initial 없이)로 한 번씩만 부른다.

고치지 않은 것:
- **닫힌 웹뷰 위에 남는 확인창:** `dismissDialog`가 열리는 중이거나 다른 overlay 아래에 있으면 닫지 못한다. 닫히는 순간 탭 없는 외부 요청이 겹칠 때만 생기고 열기는 막혀 있어 알려진 한계로 둔다.
- **웹 URL·탐색 정책·gate·페이지 상태의 플랫폼별 중복:** shared `commonMain` 이전은 후속 이슈 #18로 분리했다(IPv6 `http://[::1` 처리 차이 포함).

검증: develop rebase 뒤 shared Android host 501·iOS simulator 498, Android unit debug·release 175, iOS XCTest 201, `gen_tokens.py --check`가 통과했다. 수정 뒤 Android unit debug·release 175·`assembleDebug`·`lintDebug`, iOS XCTest 204개가 통과했다(실패 0). 기기 확인은 [C4 화면 확인 기록](c4-detail-verification-2026-10-10.md#pr-16-3차-리뷰-수정-기기-확인2026-10-13)에 있다.

## PR #16 4차 리뷰 반영(2026-10-13)

10건 중 7건을 고쳤다.

- **iOS 닫힌 뒤 웹뷰 이동:** `close()`가 delegate를 떼서, 퇴장 모션 중 페이지의 이동·새 창을 WebKit이 정책 확인 없이 허용했다. delegate를 남기고 `isClosed`로 막는다(시작·실패·프로세스 종료 콜백도 무시).
- **iOS 탭 한 번에 외부 앱 여러 번:** 탭으로 외부 앱을 열면 `lastTouch`를 비운다. 같은 1초 안의 스크립트 클릭은 확인창이다.
- **Android 렌더러 크래시 뒤 새로고침 무반응:** 새 view에는 URL이 없어 `reload()`가 아무것도 하지 않았다. `reload()`가 URL 없는 view에 `page.url`을 열고, `retry()`는 실패 표시를 지우고 `reload()`를 부른다(중첩 if 정리 포함).
- **Android `WebViewRoute.copy()`로 검증 우회:** `@ConsistentCopyVisibility`로 `copy()`도 private으로 둔다.
- **`IntentSanitizer`의 `resolvable`:** 실제 코드는 늘 `true`였다. 인자를 없애고, 웹 URL fallback 고르기를 `External.fallbackUrl`로 옮겨 JVM 테스트에 남겼다. "앱 없음"은 `launch` 한 곳에서 처리한다. `ParsedIntentUri`는 하드닝을 JVM에서 확인하는 순수 parser라 그대로 둔다.

고치지 않은 것:
- **Android 끊긴 로드의 실패 표시:** 실기기에서 세 경우를 확인했다. 응답 전 새로고침으로 끊긴 이동, 본문을 받는 중 "중지", 느린 이동 중 스크립트의 다른 이동이다. 셋 다 실패 덮개가 뜨지 않았다(WebView는 `ERR_ABORTED`를 `onReceivedError`에 넘기지 않는다). 오류 코드로 거르면 실제 실패를 숨길 수 있어 그대로 둔다.
- **열리는 중에 뒤로 가면 확인창이 남음:** 3차 리뷰에서 알려진 한계로 두기로 했다(열기는 막혀 있다).
- **플랫폼별 중복:** #18.

검증: Android unit debug·release 173(`resolvable` 테스트 2개를 지움)·`assembleDebug`·`lintDebug`, iOS XCTest 205개가 통과했다(실패 0). 실기기 확인은 [C4 화면 확인 기록](c4-detail-verification-2026-10-10.md#pr-16-4차-리뷰-기기-확인2026-10-13)에 있다.

## PR #16 5차 리뷰 반영(2026-10-13)

10건 중 7건을 고쳤다.

- **탭 한 번에 외부 앱 여러 개(Android), 내부 링크 탭이 남김(iOS):** 탭 한 번은 판정 한 번이다. gesture로 판정된 첫 이동이 종류와 상관없이 탭을 쓴다. iOS는 `decide`에서 `lastTouch`를 비우고, Android는 `WebTapBudget`이 `hasGesture()`를 탭마다 한 번만 넘긴다(웹뷰 `OnTouchListener`가 slop 안의 손가락 하나 탭만 새 탭으로 친다). 4차에는 iOS만 외부 앱을 열 때 비워서 두 플랫폼의 D16이 달랐다.
  - 결정: 탭한 http 링크가 서버 redirect로 외부 앱에 가는 경우도 확인창을 거친다. redirect만 예외로 두면 플랫폼마다 redirect 판정과 상태 추적이 늘고, 스크립트가 redirect하는 주소로 이동해 우회하는 틈이 생긴다.
- **iOS 클래스 주석:** `close()`가 delegate를 지운다던 문장을 실제 동작(남겨 두고 `isClosed`로 취소)으로 고쳤다.
- **iOS 닫힌 뒤 `stop`·`goBack`·`goForward`:** `isClosed` 가드를 더했다.
- **새로고침이 플랫폼마다 다름:** iOS 하단 새로고침은 실패한 URL을 무시하고 이전 페이지를 다시 열었다. 다시 시도와 새로고침을 `reload()` 하나로 합쳐 실패한 URL을 연다. Android는 `retry()`를 없애고 `reload()`만 쓴다. 두 플랫폼 모두 실패 덮개를 미리 지우지 않는다(새 로드 시작이 지운다, 미리 지우면 빈 화면이 잠깐 보인다).
- **Android 첫 로드 직전 새로고침:** `view.url == null`은 크래시 뒤 빈 view만이 아니라 첫 로드 직전에도 참일 수 있다. `emptyAfterCrash` 플래그로 바꿨다.
- **열리는 중인 확인창이 닫히는 화면에 남음(3·4차에 알려진 한계로 둔 건):** 두 플랫폼 `OverlayHostState.dismissDialog`가 열리는 중인 확인창에 `closeWhenOpened`를 표시하고, `onOpened`가 바로 닫기를 시작한다.

고치지 않은 것:
- **이전 요청의 늦은 오류가 정상 페이지를 덮음(Android):** `request.url`과 로드 중 URL 비교는 redirect 뒤의 실제 실패를 숨긴다(`onPageStarted`는 redirect 전, `onReceivedError`는 redirect 뒤 주소). 새 이동에 밀려난 요청은 `ERR_ABORTED`로 끝나고 보고되지 않음을 4차에 실기기로 확인했다.
- **`ParsedIntentUri`와 플랫폼 파서의 판정 차이(fallback 안의 인코딩 안 된 `#`):** 리뷰의 전제가 틀렸다. SDK 36 `Intent.parseUriInternal`도 `lastIndexOf("#")`로 자르고, 그 자리가 `#Intent;`가 아니면 구형 형식(`getIntentOld`)으로 넘어가 `browser_fallback_url`을 읽지 않는다. 두 파서 모두 새 형식으로 읽지 않으니 판정이 같다.
- **플랫폼별 중복:** #18.

검증: Android unit debug·release 179(`WebTapBudgetTest` 5, overlay 1 추가)·`assembleDebug`·`lintDebug`, iOS XCTest 207개(탭을 페이지 링크가 쓰는 경우, 열리는 중 `dismissDialog` 추가)가 통과했다(실패 0).

## PR #16 6차 리뷰 반영(2026-10-13)

8건 중 7건을 고쳤고, 1건(iOS 보조 기술)은 확인창을 유지하기로 했다.

- **Android 키보드·접근성 회귀:** `WebTapBudget`이 손가락 탭으로만 다시 채워져, TalkBack·Enter·D-pad로 누른 두 번째 외부 링크부터 확인창이 떴다. key down(뒤로·Esc 제외)과 접근성 `ACTION_CLICK`(WebView 하위 클래스가 Chromium의 `AccessibilityNodeProvider`를 감싸 알림)도 새 탭으로 친다.
- **다시 시도 무반응·첫 로드 실패 후 다시 시도 무효·크래시 플래그 잔류(Android):** 원인이 같았다. WebView는 응답 헤더가 와야 `onPageStarted`를 불러 그 전까지 로드가 보이지 않았다. holder가 `loadUrl`·`reload`를 부르는 순간 `requested()`로 로딩을 표시하고, 그동안 다시 시도는 꺼진다(`canRetry`). 로드 중에는 새로고침이 중지로 바뀌어 요청이 두 번 갈 수 없으므로 `view.url == null` 분기를 되살리고 `emptyAfterCrash`를 없앴다. 리뷰 제안(`!page.loading`)만으로는 첫 로드 직전에 `loading`이 아직 false라 중복을 막지 못한다.
- **`#fragment` 링크의 플랫폼 차이:** iOS를 Android에 맞췄다. 같은 페이지 fragment 이동은 탭을 쓰지 않는다. 두 플랫폼 모두 "페이지 아무 곳이나 탭하면 앱 하나"를 이미 허용하므로(스크립트 `a.click()`·user activation), 앵커 탭도 그 탭과 같다.
- **문서:** D16 스펙과 QA-CLI-015에 탭 한 번 규칙, redirect 결정, 보조 기술 동작을 반영했다.
- **테스트:** 새로고침 판단(`WebReloadStep`), 로딩 표시(`requested`·`canRetry`), 터치·키·접근성 입력에서 탭 판정까지(`WebTapBudget.onTouch` 등, 리스너는 이벤트를 넘기기만 한다)를 JVM 테스트로, iOS는 `makeWebView`로 기록용 웹뷰를 넣어 통합 `reload()`(실패 URL 열기, 닫힌 뒤 무시)와 fragment 판정을 XCTest로 확인한다.

고치지 않은 것:
- **iOS VoiceOver·Full Keyboard Access:** 웹 링크가 보조 기술로 눌렸는지 알 공개 API가 없다. 보조 기술이 켜져 있을 때 `.linkActivated`만 믿으면 그 사용자에게 스크립트 `a.click()` 우회(Ruling 12 이전)가 다시 열려, 외부 링크마다 확인창을 유지한다(알려진 한계).

검증: Android unit debug·release 183·`assembleDebug`·`lintDebug`, iOS XCTest 211개가 통과했다(실패 0). TalkBack·키보드로 누르는 실기기 확인은 하지 않았다.
