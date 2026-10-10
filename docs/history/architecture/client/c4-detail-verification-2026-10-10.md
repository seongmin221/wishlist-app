# C4 상세·분석 중·로컬 대기 화면 확인 (2026-10-10)

> C4 Task 10(Android 절)·Task 11(iOS 절)·Task 16(PR B 웹뷰 절). 에뮬레이터 `emulator-5554`(API 36, 앱 문구 한국어), DEBUG Fake. 캡처는 [c4-shots](c4-shots/)(1200px로 줄임). 비교 기준은 보드 `FProductDetail{L,D}`·`FProductProcessing{L,D}`와 [C4 설계](../../../superpowers/specs/2026-10-09-client-c4-product-detail-design.md) §3 D8·D9·D12.

## 확인한 흐름

| 흐름 | 결과 | 캡처 |
| --- | --- | --- |
| 로그인 뒤 "분류 중" 줄 → 상세(분석 중) | 가로 밀기, 탭 바 없음. 222 카드 + 분석 중 타일 + "상품 정보 추출 중", host 제목, 안내, "방금 저장" | `android-processing-dark-justnow.png` |
| 5초 뒤 foreground(화면 끄고 켜기) → 상세 당겨서 새로고침 → 상세(READY) | 같은 화면이 상세로 바뀜. 사진 없음 → 자리표시, 브랜드·가격 없음 → 줄 숨김, 카테고리 "신발", 목적 미지정, "오늘 저장" | `android-ready-dark.png`, `android-ready-light.png` |
| (리뷰 수정 뒤) 분석 중 상세 → 6초 뒤 당겨서 새로고침만 | DEBUG runtime이 상세 GET 앞에 Fake 분석을 진행해 같은 화면이 바로 상세로 바뀜(D7) | `android-pull-before-processing-dark.png`, `android-pull-after-ready-dark.png` |
| ITEM-01 15초 지연 + 대기 1개 → 줄 → 로컬 대기("보내는 중") → 전송 완료 | 대기 타일 "보내는 중", "방금 저장 · 이 기기에만 있어요", ⋯ 있음. 응답 뒤 `replaceTop`으로 분석 중 상세로 교체(cross-fade) | `android-local-sending-light.png`, `android-movedto-processing-light.png` |
| 로그아웃 → 대기 1개 → "분석 대기" 줄 → 로컬 대기 → ⋯ → 삭제 → 확인 | "분석 대기" 타일, ⋯ 메뉴 "삭제", D8 확인창(대상 줄 + 글머리표 2 + 취소·삭제 빨강). 삭제 뒤 홈으로 닫히고 줄이 사라짐 | `android-local-pending-light.png`, `android-local-menu-light.png`, `android-delete-dialog-{light,dark}.png` |

## 보드와 다른 점

- 분석 중·완료 상세의 ⋯가 없다(D2, C8에서 붙임). 보드에는 있다.
- 보드는 버튼 top 52·좌우 16인데 2026-10-07 결정(안전 영역 + 12, 좌우 20)을 따랐다.
- (리뷰 수정 뒤 같음) 완료 상세의 사진은 보드·2026-10-04 결정처럼 1:1 칸(모서리 20) 안 여백 56에 원래 비율로 넣는다(`ProductPhoto(contentScale = Fit, imagePadding = 56.dp)`, 자리표시는 칸 전체). Fake·seed 상품에 사진 주소가 없어 실제 사진 캡처는 없다(자리표시만 확인).
- 정보 보완 안내(INCOMPLETE)는 보드 FProductFill의 경고 카드 모양에 "다시 분석" 버튼 없이 안내 한 줄과 host만 둔다(D10, 보완은 C8).

## 확인하지 못한 것

- **로그아웃 시 상세 닫힘:** 설정은 홈 탭 첫 화면에서만 열리고 상세가 그 위를 덮어, 상세가 스택에 있는 채로 로그아웃할 UI 경로가 없다. 정리 규칙은 Task 7 navigator 테스트로 검증되어 있다.
- **로그인 뒤 로컬 삭제:** 로그인 상태에서는 대기 줄이 바로 전송되어 PENDING으로 남지 않는다(네트워크를 끄는 조작은 하지 않았다). 로그인 전 삭제만 확인했다.
- **목적 색 점:** Fake 분석 결과는 목적이 없고 목적이 있는 seed 상품은 C4 홈에서 들어갈 길이 없다(C5 목록). 표시값은 `FakeItemRepositoryTest`로, 색 매핑은 `DetailTextTest`로 확인했다.
- 영어(en-US) 문구 화면은 찍지 않았다(앱이 한국어로 떴다). 키는 JVM 테스트와 `values-en`으로 확인.

## iOS (Task 11)

> 시뮬레이터 iPhone 17 Pro `AFBA9C17-206B-4EA6-A508-EF6E0CE2D7B0`(iOS 26.5, 앱 문구 한국어), DEBUG Fake, `CODE_SIGNING_ALLOWED=NO` debug 빌드. 조작은 Orca에 들어 있는 serve-sim(`tap`·`gesture`)으로 하고 결과는 serve-sim `/ax` 접근성 트리와 `simctl io screenshot`(1200px로 줄임)으로 봤다. 캡처는 `c4-shots/ios-*.png`.

| 흐름 | 결과 | 캡처 |
| --- | --- | --- |
| `-wl.fake.pendingCount 1` → "분류 중" 줄 → 5초 안에 열기 | 분석 중 상세: 222 카드 + 분석 중 타일 "상품 정보 추출 중", host 제목, 안내, "방금 저장", ⋯ 없음 | `ios-processing-dark.png` |
| 같은 화면에서 6초 뒤 당겨서 새로고침만 | DEBUG 상세 GET이 Fake 분석을 진행해 같은 화면이 완료 상세로 바뀜(D7, Ruling 8) | `ios-pull-after-ready-dark.png` |
| 5초 지난 뒤 줄 열기 | 완료 상세: 1:1 칸 자리표시(가방), 카테고리 "신발", "목적 미지정", "방금 저장" | `ios-ready-light.png`, `ios-ready-dark.png` |
| 분석 중 상세 → 6초 → 홈 버튼 → 앱 다시 열기 | `scenePhase` 배경 → `.active` 복귀가 `refresh()` → 완료 상세 | (ax만) |
| 완료 상세에서 왼쪽 가장자리 끌어서 뒤로 | 홈으로 돌아오고 남은 화면 없음(할 일 0개) | `ios-after-backswipe-home-light.png` |
| 로컬 대기에서 15% 끌다 놓기 | 되돌아오고 화면이 그대로 반응함(⋯ 메뉴 열림), 뒤로 버튼으로 홈 | (ax만) |
| `-wl.fake.delayItem01 15000 -wl.fake.pendingCount 1` → "보내는 중" 줄 | 로컬 대기 "보내는 중", "방금 저장 · 이 기기에만 있어요", ⋯ 메뉴 "삭제"는 흐림(D17) | `ios-local-sending-light.png`, `ios-local-sending-menu-disabled-light.png` |
| 응답 뒤 | `MovedTo` → `replaceTop`으로 분석 중 상세로 교체 | `ios-movedto-processing-light.png` |
| 로그아웃 → 대기 1개 → "분석 대기" 줄 | 줄 VoiceOver: "example.com, 방금 저장 · 이 기기에만 있어요"(힌트 상세 보기) · 버튼 "원본 열기" | `ios-home-loggedout-rows-light.png` |
| 줄 → 로컬 대기 → ⋯ → 삭제 → 확인 | "분석 대기" 타일, 메뉴 "삭제", D8 확인창(대상 줄 + 글머리표 2 + 취소·삭제 빨강), 삭제 뒤 홈으로 닫히고 줄이 사라짐 | `ios-local-pending-light.png`, `ios-local-pending-dark.png`, `ios-local-menu-light.png`, `ios-delete-dialog-{light,dark}.png`, `ios-after-delete-home-dark.png` |

보드와 다른 점은 Android와 같다(⋯ 없음 D2, 위쪽 바 2026-10-07 결정, 사진 fit + 56).

확인 중 고친 것:

- Task 9 자리표시 가방 아이콘이 삼각형으로 그려졌다(`addLines`가 첫 점에서 새 부분 경로를 시작해 첫 변이 빠짐). 경로를 바로잡았다.
- 새 `trash` 아이콘도 같은 이유로 선이 빠져 바로잡았다.

확인하지 못한 것:

- **실제 사진:** Fake·seed 상품에 사진 주소가 없다. 다운샘플·캐시·중복 요청은 `RemoteImageTests`로 확인했다.
- **로그아웃 시 상세 닫힘·로그인 뒤 로컬 삭제·목적 색 점:** Android와 같은 이유로 UI 경로가 없다. 닫힘 규칙은 `WLNavigatorTests`·`ItemDetailPresenterOwnerTests.testCloseRules…`, 색은 `DetailTextTests`.
- **새로고침 실패 안내·삭제 실패 안내:** Fake에서 실패를 만들 조작이 없다. "한 번만" 규칙은 owner 테스트로 확인했다.
- 영어 화면은 찍지 않았다(문구는 `DetailTextTests`가 en 표로 확인).

## PR B 원본 링크 웹뷰 (Task 16)

> Android 에뮬레이터 `emulator-5554`(API 36, 한국어, 다크 기본) · iOS 시뮬레이터 iPhone 17 Pro `AFBA9C17-206B-4EA6-A508-EF6E0CE2D7B0`(iOS 26.5). DEBUG Fake. 시험 페이지는 scratchpad의 `python3 -m http.server 8781`(상품 페이지·두 번째 페이지·`target=_blank`·`tel:`·탭 1.5초 뒤 JS `tel:`·3초 뒤 스크립트 `market:`·`document.cookie`를 쓰고 보이는 쿠키 페이지). 기준은 보드 `FWebView{L,D}`·`FWebViewExternal`과 [C4 설계](../../../superpowers/specs/2026-10-09-client-c4-product-detail-design.md) §4·D13~D16.

**시험 환경의 제약(커밋하지 않은 임시 변경, 확인 뒤 되돌림):**

- Android 에뮬레이터는 DNS가 안 된다(`example.com` → `net::ERR_NAME_NOT_RESOLVED`). 시험 페이지는 `adb reverse tcp:8781`의 `http://localhost`인데, 앱 manifest에 cleartext 허용이 없어(debug 전용 설정도 없음) 깨끗한 빌드에서는 막히고 실패 덮개가 보인다. 이것도 확인했다. 시험 페이지 확인 동안만 커밋하지 않은 `src/debug/AndroidManifest.xml`(`usesCleartextTraffic`)을 두었다가 지우고 깨끗한 빌드를 다시 설치했다. release 설정은 건드리지 않았다. 시험 URL은 공유(`ACTION_SEND` → `ShareReceiverActivity`)로 로그인 전 대기 줄을 만들어 넣었다.
- iOS 시뮬레이터는 실제 네트워크가 되고 `http://127.0.0.1`도 ATS 예외 없이 열린다. 로그인 전 대기 줄의 URL을 시험 페이지로 바꾸려고 `DebugControls`의 DEBUG 대기 줄 URL을 잠깐 바꿨다가 되돌렸다(커밋 없음). 조작은 serve-sim(`tap`)과 `/ax` 접근성 트리로 했다.

| 흐름 | Android | iOS | 캡처 |
| --- | --- | --- | --- |
| 상세 "원본 보기" → 웹뷰 | 가로 밀기, 탭 바 없음. DNS 실패 → "페이지를 열 수 없어요 · 다시 시도" 덮개, 자물쇠 + `example.com`. 닫기 → 상세로 돌아옴 | `example.com` "Example Domain"이 열림, 자물쇠 + 도메인 / 제목 | `android-web-from-detail-{dark,light}.png`, `ios-web-from-detail-{light,dark}.png` |
| 로그인 전 홈 줄 "원본" → 웹뷰 | 시험 페이지가 열림(제목 "상품 페이지") | 같음(VoiceOver 이름 "원본 열기") | — |
| 링크 → 뒤로/앞으로 | 두 번째 페이지 → 뒤로(앞으로 켜짐) → 앞으로(다시 꺼짐). 시스템 뒤로는 기록 먼저 | 같음 | — |
| `target=_blank` | 같은 웹뷰에 열림(서버 로그 `page2.html?from=blank`) | 같음 | — |
| 탭 없는 스크립트 외부 scheme | 3초 뒤 `market:` → FWebViewExternal("외부 앱을 열까요?" · "다른 앱이 열려요" · "직접 누르지 않았다면 취소해 주세요" · 취소/열기) | 탭 1.5초 뒤 JS `tel:`(`.other`) → 같은 확인창 | `android-web-from-home-confirm-dark.png` |
| 사용자 탭 외부 scheme | `tel:` 탭 → 확인창 없이 전화 앱이 열림. 전화 앱은 건드리지 않고 `am start`로 돌아오니 페이지·기록 유지 | `tel:` 탭 → 확인창 없음(시뮬레이터에 전화 앱이 없어 아무 일도 없음) | — |
| 공유 → 링크 복사 | 시스템 클립보드 표시에 현재 페이지 URL(`…/index.html?market`) | "링크를 복사했어요" 안내, `simctl pbpaste` = 현재 URL | — |
| 쿠키와 "웹뷰 데이터 삭제" | 쿠키 페이지 첫 방문 "(없음)" → `wl_test=hello`, 다시 방문 때 처음부터 `wl_test=hello`. 닫기 → 설정 → 웹뷰 데이터 삭제 → "방금 삭제했어요" → 다시 열기 → "(없음)" | 같음 | — |

**확인 중 고친 것:** Android에서 그린 내용이 없는 WebView(로딩 중·Chromium 오류 페이지)가 창 전체에 배경을 칠해 위쪽 바(닫기·도메인·제목)와 상태 표시줄 아이콘이 보이지 않았다(다크에서는 검정, 실패 뒤에는 흰 면). 상세·홈 어느 진입점에서도 같았고, 페이지가 그려지면 정상이었다. 웹뷰 칸에 `clipToBounds()`를 두어 고치고 같은 흐름을 다시 확인했다(`693fedf`).

**확인하지 못한 것:**

- **로그아웃 시 웹뷰 닫힘·로그인 시 유지:** 웹뷰는 탭 바를 숨기고 홈 스택 위를 덮어, 웹뷰가 열린 채 로그아웃하거나 로그인할 UI 경로가 없다(상세와 같은 이유). 규칙은 `WebViewRoute`·`AppDestination.web`의 `accountScoped = true`(`WebViewRouteCodecTest`·`WebViewRouteTests`)와 `AppRouteCodecTest.signingInKeepsAccountScopedRoutes`·`WLNavigatorTests.testSigningInKeepsAccountScopedRoutes`·`testLeavingAnAccountDropsAccountScopedRoutesInEveryTab`로 확인했다.
- **D16 결제 확인창 빈도:** 미확인 · 실기기.
- **새 창의 `window.opener` 손실(PG 팝업):** 미확인 · 실기기.
- iOS WKWebView 기록 스와이프(합성 터치로는 안 됨), "외부 브라우저로 열기"(앱을 떠남)는 누르지 않았다. (Android 실제 `intent:` 링크는 아래 최종 리뷰 절에서 확인했다.)
- ~~Android 실패 덮개 위 제목 줄에 Chromium 오류 페이지 제목("웹페이지를 사용할 수 없음")이 보인다.~~ 최종 리뷰에서 고쳤다(`WebPageState.titleLine`이 실패 중 null, 도메인만).

### 실제 `intent:` 링크(PR B 최종 리뷰, 2026-10-10)

> `emulator-5554`(API 36), 최종 리뷰 수정을 넣은 debug 빌드. 조작 전 `mCurrentFocus` = `app.wishlist.android/.MainActivity` 확인, 다른 앱은 건드리지 않았다. 시험 페이지는 scratchpad `python3 -m http.server 8782`의 `intent.html`(링크 세 개)·`fallback.html`, `adb reverse tcp:8782`, 공유(`ACTION_SEND` → `ShareReceiverActivity`)로 만든 로컬 대기 상세의 "원본 보기"로 열었다. Task 16과 같이 커밋하지 않은 `src/debug/AndroidManifest.xml`(`usesCleartextTraffic`)을 두었다가 지우고, reverse·서버를 끄고 깨끗한 debug 빌드를 다시 설치했다. 세 링크 모두 사용자 탭(확인창 없이 바로 실행 경로)이라 실제 `Intent.parseUri` → `AndroidIntentTarget` → `startSafely`를 탄다.

| 링크(탭) | 결과 |
| --- | --- |
| (a) `intent://x#Intent;scheme=wltest;component=app.wishlist.android/.MainActivity;end` | **component가 지워져 시작되지 않음.** logcat `ActivityTaskManager: START u0 {act=android.intent.action.VIEW cat=[android.intent.category.BROWSABLE] dat=wltest://x/... xflg=0x4} … result code=-91`(해석 불가, `cmp=` 없음). `dumpsys activity activities` 앞뒤 모두 `Hist #0: ActivityRecord{254715469 … MainActivity t232}` 하나뿐, 포커스 그대로, 페이지 유지 |
| (b) `intent://x#Intent;scheme=wltest;package=com.example.none;S.browser_fallback_url=http%3A%2F%2F10.0.2.2%3A8782%2Ffallback.html;end` | `START … pkg=com.example.none (has extras) … result code=-91` → `ActivityNotFoundException` → fallback이 웹뷰 안에 열림(서버 로그 `GET /fallback.html`, 위쪽 바 `10.0.2.2` / "대체 페이지", 본문 "FALLBACK OK"). 아래쪽 뒤로 → `intent.html`로 돌아옴 |
| (c) `intent://x#Intent;scheme=wltest;launchFlags=q;end` | 앱 비정상 종료 없음: 앱 PID 4333 그대로, logcat에 `START`·`AndroidRuntime`·`FATAL` 없음(fallback이 없어 Drop), 페이지 유지 |

