# C3 목록·입력 성능과 계층 경계

C1의 데모 규모로 실제 목록의 메모리·입력 비용을 판단하지 않는다. C3 첫 목록을 붙일 때 다음 검증을 기능 완료 조건에 포함한다. 현재 구현은 [디자인 시스템](design-system.md)을 따른다.

## 공통 시나리오

- 같은 데이터로 20/100/300개 상품, 3개 탭, 상세 깊이 1/3/5를 비교한다. 이미지가 없는 상태와 실제 이미지 로딩·교체 상태를 따로 측정한다.
- 탭 왕복·push/pop 20회 뒤 화면 수, 공유 요소 등록 수, 이미지 closure·참조, 메모리가 안정되는지 확인한다. 화면 종료·항목 삭제 뒤 해제가 필요하다.
- 한글 39/40자 근처 조합·삭제·커서 이동·선택 붙여넣기와 emoji ZWJ/국기/결합 문자·분해 자모를 실제 기기에서 확인한다. Android API 26과 현재 API의 ICU 결과를 비교한다. 호스트 JDK의 grapheme 테스트 결과만으로 실기기 동작을 보장하지 않는다.
- 회전·글자 크기 변경·프로세스 재생성 뒤 기존 화면이 같은 상태를 복원하고, 새 화면은 이전 entry의 스크롤·입력 상태를 받지 않아야 한다.

## iOS

현재 `WLNavHost`는 모든 탭·스택 칸을 한 ZStack에서 유지한다. 스크롤·입력 상태와 공유 요소의 원래 자리를 보존하는 장점이 있으나 실제 목록에서는 탭 수와 깊이에 따라 layout·Observation 갱신·메모리 비용이 커진다.

Instruments Allocations·Time Profiler와 SwiftUI 갱신 추적을 사용해 다음을 기록한다.

| 대상 | 확인할 값 |
| --- | --- |
| 숨은 탭·뒤 스택 | 살아 있는 뷰/이미지 수, 숨은 화면의 body·layout 갱신, 스택 깊이별 메모리 |
| 입력 | 한 번의 키 입력당 responder 탐색·알림 dispatch 수/시간, 여러 칸·겹친 overlay 입력, 포커스 전환 뒤 cache 정확성 |
| 텍스트·가격 | UILabel 선택 비율, UIFont·통화 소수 자릿수 cache hit, 본문 줄 높이·스크롤 중 formatter 생성 |
| 공유 요소 | 이미지 교체·항목 제거 후 registry 해제, 전환 중 한 source만 숨기는지 |

새 입력 탐침은 window별 약한 focused-input cache와 공통 알림 관찰을 사용한다. 이 최적화의 효과와 포커스 cache invalidation도 실제 화면에서 검증한다. 문제가 남으면 native field identity 연결을 먼저 개선한다. 모든 화면을 무조건 파괴하는 방식은 스크롤 복원·공유 요소 원래 자리 보존을 깨므로, 필요 시 UI 상태를 별도 소유하고 보이는 탭/전환 이웃만 렌더링하는 방식으로 바꾼다.

## Android

Compose recomposition/layout trace와 프레임·할당 profiling으로 다음을 확인한다.

- 칩·카드 하나를 누를 때 전체 항목 수만큼 animateFloat가 만들어지거나 진행하지 않는지, active shared key의 전환만 움직이는지.
- source key matching·registry 등록 자체의 N개 비용과 lazy 항목 dispose 뒤 정리. 원래 key가 재등장할 때 pop이 이어지는지.
- 공유 면 draw에서 painter 생성·shape outline·shadow bitmap 비용. 토큰의 blur/y/alpha와 색·모서리를 유지하면서 재사용되는지.
- 시트 drag 중 매 delta coroutine 생성이나 composition 재실행, anchor 스크롤 중 상태 갱신이 없는지.

반복 횟수와 결과는 기기/OS·항목 수·이미지 크기·글자 배율과 함께 기록한다. 메모리와 프레임 예산은 C3의 실제 기기 baseline을 얻은 뒤 정하며, 아직 수치가 없는데 임의의 통과 기준을 만들지 않는다.

## C3 측정 결과 (2026-10-07)

C3 실제 목록은 홈의 분석 대기·분류 중 행이다. 이미지가 없고 탭·상세 깊이도 없다. 그래서 위 공통 시나리오 가운데 "N개 목록 스크롤"만 측정했다. 이 값은 baseline이며 통과 기준이 아니다. 실행 방법과 화면 비교는 [C3 검증 기록](../../history/architecture/client/c3-verification-2026-10-07.md)에 있다.

- **방법**
  - debug 시연 hook `wl.fake.pendingCount`로 미귀속 행 N개(20/100/300)를 만든다.
  - FHomeLoggedOut 할 일 카드를 펼친다.
  - 위로 5회, 아래로 5회 스크롤한다.
  - 다크 모드, 이미지 없음.
- **목록 구현:** 두 플랫폼 모두 lazy 목록이 아니다. Android는 `Column` + `verticalScroll`, iOS는 `ScrollView` 안 `VStack`이며, 펼치면 N행을 모두 구성한다.

### Android

에뮬레이터 API 36(`sdk_gphone64_arm64`), 1080×2400 · 420dpi, Skia(OpenGL, emulation), 글자 배율 1.0. `dumpsys gfxinfo app.wishlist.android reset` 뒤 스크롤하고 `framestats`, `dumpsys meminfo`를 기록했다.

| N | 프레임 | jank(deadline 놓침) | legacy jank | p50 / p90 / p95 / p99 | 느린 UI thread | TOTAL PSS | Java heap PSS | Native heap PSS |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 20 | 453 | 12 (2.65%) | 0% | 17 / 19 / 19 / 20 ms | 0 | 94,364 KB | 24,800 KB | 11,448 KB |
| 100 | 683 | 26 (3.81%) | 40.85% | 19 / 25 / 29 / 40 ms | 2 | 102,740 KB | 30,092 KB | 13,940 KB |
| 300 | 661 | 37 (5.60%) | 17.40% | 17 / 25 / 32 / 42 ms | 5 | 122,341 KB | 43,180 KB | 20,156 KB |

### iOS

iPhone 17 Pro 시뮬레이터 iOS 26.5(Apple M4 Pro 호스트), Dynamic Type Large.

- `xcrun xctrace record --template "Time Profiler" --attach <pid>`는 시작한 뒤 끝나지 않았다. 이 맥은 `DevToolsSecurity`가 꺼져 있어 taskport 승인 대기로 보인다. Time Profiler·Allocations 기록은 사용자 승인 후 다시 한다.
- 대신 XCTest `measure`(CPU·Memory·`scrollingAndDecelerationMetric`, 3회 평균)로 측정했다.
- 이 CPU 값에는 XCUITest가 스와이프마다 앱 접근성 트리를 읽는 비용이 들어 있다. 숫자는 같은 방법끼리만 비교한다.

| N | 절대 물리 메모리(평균 / 최고) | CPU 시간(10회 스크롤) | CPU instructions | 스크롤 drag+감속 시간 | 테스트 전체 시간 |
| --- | --- | --- | --- | --- | --- |
| 20 | 66,008 / 66,150 KB | 1.65 s | 9.9 G | 2.58 s | 61 s |
| 100 | 90,458 / 90,912 KB | 8.03 s | 76.5 G | 2.58 s | 130 s |
| 300 | 150,353 / 154,176 KB | 31.07 s | 481.7 G | 2.56 s | 222 s |

스크롤 hitch 비율은 시뮬레이터에서 나오지 않았다.

### 관찰

- 두 플랫폼 모두 메모리가 N에 비례해 늘었다. iOS CPU는 N보다 빠르게 늘었다(20→300에서 약 19배).
- 원인은 lazy 아닌 목록일 가능성이 있고, iOS는 접근성 트리 비용과 섞여 있다. 프로파일러로 나누어 보아야 한다.
- 대기 행이 수백 개가 되는 일이 실제로 흔한지, lazy 목록으로 바꿀지는 실기기 Instruments·Android profiler 결과를 본 뒤 정한다. 지금은 기준선만 둔다.
- Android N=100의 legacy jank 40.85%가 N=300(17.40%)보다 높은 것은 한 번만 측정한 값이라 원인을 설명하지 못했다. deadline 기준 jank는 N에 따라 늘었다. 다시 잴 때 반복 측정한다.

### 이번에 측정하지 않은 항목

- **입력:** C3 화면에는 입력 칸이 없다. 위 iOS·Android 입력 항목(키 입력당 responder 탐색, 한글 IME 조합, grapheme)은 입력 화면이 생기는 C5/C6에서 측정한다.
- **이미지 로딩·교체:** C3 행에는 사진이 없다. 사진이 들어오는 단계에서 측정한다.
- **탭 왕복·push/pop·공유 요소 registry, 숨은 탭 비용:** C3는 홈·설정·로그인만 있고 상세가 없다. 상세와 공유 요소 전환이 실제로 생기는 단계에서 측정한다.
- **실기기:** 아직 측정하지 않았다.

## Gradle 모듈 경계 검토

현재 Android `:android`는 application 한 모듈이며 Kotlin `internal`은 feature와 core를 격리하지 못한다. C1에서 폴더 경계와 route codec/provider 계약을 먼저 정리하고, C3 첫 실제 feature를 넣기 전에 core 분리를 진행할지를 검토한다.

추천 분리 방향은 다음과 같다.

| 모듈 | 책임·허용 의존 |
| --- | --- |
| `:core:designsystem` | 토큰·입력·글자·overlay; feature를 참조하지 않음 |
| `:core:navigation` | navigator·shared transition; design system 참조, feature 목적지 타입은 모름 |
| `:feature:wishlist` 등 | 실제 화면·경로·codec; 두 core와 `:shared`의 비즈니스 API 참조 |
| application | feature 등록·route rendering·DI·variant 데모 조립 |

분리하면 host 전용 완료 API를 module 내부로 제한하고 feature→core 역의존을 컴파일러가 막을 수 있다. 지금 즉시 나누면 아직 없는 feature를 위한 Gradle 설정과 공개 API만 늘어날 수 있다. C2 Presenter/domain 계약과 C3 첫 feature 경계가 확정될 때 이동 범위·공개 API·모듈별 테스트 시간을 비교해 결정한다. 그 전에는 `finishTransition`을 feature에서 호출하지 않고 route codec/renderer를 앱이 연결하는 규칙을 유지한다.

**C3 결정(C3-D10, 2026-10-07):** C3에서는 나누지 않았다. C3 화면은 로그인·홈·설정·공유 카드 네 개뿐이라 지금 분리하면 Gradle 설정과 공개 API만 늘어난다. `feature/*` 폴더 경계와 "feature는 `finishTransition`을 호출하지 않는다" 규칙을 유지하고, 카테고리 feature가 들어오는 C5에서 위 표대로 다시 검토한다. Android 쪽 배치는 [Android 구조](android.md#공유-수신로그인홈설정-c3)에 있다.
