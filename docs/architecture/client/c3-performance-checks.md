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
