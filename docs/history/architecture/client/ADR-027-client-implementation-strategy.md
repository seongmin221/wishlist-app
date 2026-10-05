# ADR-027: 클라이언트는 계약 우선 fake와 KMP Presenter로 기능 단위 구현한다

> 상태: **확정** · 날짜: 2026-10-05 · 영역: **client**

## 맥락

초기 셋업으로 Android·SwiftUI 앱과 UI 없는 KMP `shared`가 연결됐다. MVP 화면 52개(라이트·다크 보드 104개)와 모션 명세는 `design/handoff`에 확정돼 있다. 반면 서버는 상품 생성 API 하나만 있고, 나머지 36개 API는 B1~B11 묶음 순서로 구현 중이다. 초기 셋업에서는 DB·HTTP·인증·DI와 Swift 비동기 연결 방식을 기능 도입 때 정하기로 미뤘다.

## 결정

- **서버 의존:** 서버 API 목록과 확정 계약에서 DTO를 옮겨 쓰고, KMP repository 인터페이스 뒤에 fake를 둬 화면을 먼저 완성한다. 서버 묶음이 끝나면 API ID 단위로 Remote 구현으로 바꾼다. fake는 서버 상태 규칙(version 409, 분석 중에는 삭제만 허용, idempotency, 목적 삭제 전환, 아카이브 전체 복원)을 재현하고, 같은 계약 테스트를 Fake와 Remote 양쪽에 실행한다.
- **진행 단위:** 기반 단계(C0~C2) 뒤에 서버 묶음 순서와 맞춘 기능 단계(C3~C12)로 진행한다. 각 단계는 KMP → Android → iOS를 같은 단계 안에서 끝낸다.
- **KMP 스택:** Ktor client, kotlinx.serialization, SQLDelight, Koin, SKIE, multiplatform-settings를 쓴다. 테스트는 kotlin.test, Turbine, Ktor MockEngine이다. 인증은 플랫폼별 Firebase 공식 SDK가 KMP `AuthTokenProvider`를 구현한다.
- **화면 상태:** KMP `presentation` 계층의 Presenter(`StateFlow<State>` + intent)가 화면의 비즈니스 상태를 소유한다. Android `ViewModel`과 iOS `@Observable`은 Presenter 수명과 순수 UI 상태만 다루는 얇은 래퍼다.
- **모션:** 핸드오프 모션 명세대로 시트, 탭 전환, 공유 요소 전환을 직접 구현하고 시스템 기본 전환은 쓰지 않는다. 토큰은 `tokens.json`에서 두 플랫폼 상수로 생성한다.

## 이유와 trade-off

- 서버를 기다리지 않고 화면과 예외 경로(409, 실패, 빈 상태)를 먼저 검증할 수 있다. 대신 서버 계약이 바뀌면 DTO·매퍼와 fake를 고쳐야 한다. 그래서 확정되지 않은 필드는 `CONTRACT-PENDING(API-ID)`로 표시하고 매퍼 한 곳에만 둔다.
- 서버 순서만 따르는 방식은 재작업이 적지만 대기 시간이 길다. 로컬 DB만으로 먼저 만드는 방식은 서버가 계산하는 `requiredAction`·version 규칙과 어긋나 채택하지 않았다.
- 기능 단위로 두 플랫폼을 함께 진행하면 Swift에서 KMP API가 불편한 문제를 일찍 발견한다. 기능 하나를 끝내는 시간은 늘어난다.
- Presenter를 KMP에 두면 "변경 사항을 버릴까요?" 판단, 홈 조치 우선순위, 보류 항목 미재노출 같은 제품 규칙이 한 곳에 있다. 대신 Swift에서 Kotlin 타입을 다루는 비용이 생기고, 이를 SKIE로 줄인다.
- Room KMP + KMP-NativeCoroutines는 Kotlin 버전을 따라가기 안정적이지만 Swift 쪽 연결 코드가 더 많아 채택하지 않았다.

## 재검토 조건

- SKIE가 사용 중인 Kotlin 버전을 지원하지 않으면 KMP-NativeCoroutines로 바꾼다.
- iOS 자체 라우터 spike(C1)가 시스템 스와이프 뒤로·VoiceOver 요구를 만족하지 못하면 `NavigationStack` + 커스텀 전환으로 내려가고, 디자인 결정에 차이를 기록한다.
- Presenter 공유가 플랫폼 관례와 계속 충돌하면 해당 화면만 플랫폼 상태로 옮기되, 제품 규칙은 `domain`에 남긴다.

## 관련 문서

- [KMP 클라이언트 MVP 구현 로드맵 설계](../../../superpowers/specs/2026-10-05-client-implementation-roadmap-design.md)
- [Client 구조](../../../architecture/client/README.md), [KMP 구조](../../../architecture/client/kmp.md)
- [WishlistItem 상태와 API 계약](../../../architecture/wishlist-item-state-api.md)
