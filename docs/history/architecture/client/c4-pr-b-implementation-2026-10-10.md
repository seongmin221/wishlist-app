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

- Android: intent data scheme 확인이 문법 검사 없는 거부 목록, adapter의 androidTest 없음(에뮬레이터 수동 확인만), Compose wiring 단위 테스트 없음, 열리는 중이거나 줄 서 있던 확인창은 화면이 사라져도 닫지 못함(열기는 `mayLaunchConfirmed`로 막힘).
- iOS: 1초보다 긴 누름 뒤 탭은 확인창, WebKit 기록 스와이프는 실제 손가락 확인 필요, 빈 `window.open()`은 열지 않음(실기기 결제 팝업 확인), WebKit 제한 포트는 빈 화면, 유니버설 링크 https 탭은 확인창 없이 앱으로 넘어감(Android와의 차이), `about:blank`로 갈 때 자물쇠·host 유지.
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
