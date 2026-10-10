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

규칙은 [디자인 결정](../../../design/decisions.md)과 [QA-CLI-015](../../../learning/client/q-and-a/QA-CLI-015-webview-security.md)에 정리했다.

## 계획과 달라진 점

- 확인창 반복 차단(`ExternalPromptGate`)은 계획에 없었고 Task 14 리뷰에서 더했다. 확인창은 한 번에 하나, 취소 뒤에는 main frame이 다른 URL로 갈 때까지 탭 없는 외부 요청을 버린다. iOS도 같은 벡터로 맞췄다.
- 진입점은 순수 helper를 새로 만들지 않고 이미 테스트된 `WebViewRoute.of`(Android `WebViewRouteCodecTest`)·`WebPageURL(string:)`(iOS `WebViewRouteTests`)을 그대로 쓴다. 그래서 Task 16에 새 단위 테스트는 없다.
- Task 16 기기 확인 중 Android 웹뷰가 로딩·실패 중에 위쪽 바를 덮는 문제를 찾아 고쳤다(`clipToBounds`). Task 14 리뷰 수정 뒤 에뮬레이터를 다시 돌리지 않아 놓친 부분이다.

## 남긴 점(task 리뷰에서 미룬 minor)

- Android: intent data scheme 확인이 문법 검사 없는 거부 목록, catch 범위(`RuntimeException`까지 넓힐지), Android adapter의 기기 테스트 없음(androidTest 없음, 실제 `intent:` 링크는 실기기 확인), Compose wiring 단위 테스트 없음, URL 기준 침묵은 `?n=` 같은 쿼리만 다른 페이지 로드로 풀릴 수 있음, 실패 덮개 위 제목 줄에 Chromium 오류 페이지 제목("웹페이지를 사용할 수 없음")이 보임.
- iOS: 1초보다 긴 누름 뒤 탭은 확인창, WebKit 기록 스와이프는 실제 손가락 확인 필요, 빈 `window.open()`은 열지 않음(실기기 결제 팝업 확인), WebKit 제한 포트는 빈 화면, 유니버설 링크 https 탭은 확인창 없이 앱으로 넘어감(Android와의 차이), `about:blank`로 갈 때 자물쇠·host 유지.
- 공통: "링크를 복사했어요" pill(카드색)의 흰 페이지 위 대비, 실기기 확인 항목 두 가지(아래).

## 실기기 확인 항목(미확인)

- D16 결제 확인창 빈도(국내 결제가 탭 뒤 JS로 다시 이동해 결제마다 확인창이 뜨는지): **미확인 · 실기기**.
- 새 창을 같은 웹뷰에 열 때 `window.opener` 손실로 PG 팝업 결제가 끝나지 않는지: **미확인 · 실기기**.

## 검증(2026-10-10, 로컬)

| 항목 | PR A 끝(`92a140b`) | Task 16 |
| --- | --- | --- |
| shared Android host / iOS simulator | 496 / 493 | 496 / 493 (실패·skip 0) |
| Android unit debug / release | 127 / 127 | 169 / 169 |
| iOS XCTest | 163 | 198 |
| assembleDebug·assembleRelease·lintDebug(0 errors, 경고 26 기존)·`linkReleaseFrameworkIosArm64`·iOS Release simulator build | 통과 | 통과 |
| `gen_tokens.py --check`·`test_gen_tokens.py` | 통과 | 통과·8개 |

화면 확인은 [C4 화면 확인 기록](c4-detail-verification-2026-10-10.md)의 PR B 절에 있다. 플랫폼 CI job은 꺼져 있어 로컬 결과가 근거다.
