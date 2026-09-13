# 기술 설계 재개 체크포인트 (2026-09-13)

> 상태: **진행 중** · 날짜: 2026-09-13 · 영역: **클라이언트·서버·인프라**

다음 세션에서 기술 설계를 바로 이어갈 수 있도록 현재 완료 범위와 정확한 재개 지점을 기록한다.

## 현재까지 완료한 범위

- MVP의 핵심 제품 정책 논의는 마무리했다.
- `WishlistItem`의 로컬 제출, 서버 상품, 분석 작업과 Product cache 책임을 분리했다.
- 분석·검토·생명주기를 독립된 상태 축으로 확정했다.
- 생성·재분석 idempotency, optimistic concurrency, 삭제 우선과 늦은 Worker 결과 차단 규칙을 확정했다.
- 분석 실패를 `FAILED_RETRYABLE`과 `FAILED_TERMINAL`로 나누고 사용자 공개 원인과 내부 진단을 분리했다.
- push, realtime과 주기적 polling을 MVP에서 제외했다. 앱 신규 실행, foreground 복귀와 사용자 새로고침 때 필요한 범위를 한 번 갱신한다.
- 카테고리 목록은 전체 상품이 아니라 안정적인 anchor 주변 앞뒤 20개를 조회하고 스크롤 위치를 유지한다.
- 서버가 홈의 `requiredAction`을 계산하며 KMP가 기기의 LocalSubmission을 `분석 대기`로 합성한다.
- 상태 전이, API, 오류·동시성 및 테스트 기준을 구현 가능한 설계 문서로 정리했다.

기준 문서는 [WishlistItem 상태 모델과 API 계약](../architecture/wishlist-item-state-api.md)이며, 선택의 근거와 대안은 [ADR-005](ADR-005-wishlist-item-state-api.md)를 따른다.

## 현재 구현 상태

- 제품·아키텍처·결정·학습 문서만 존재한다.
- 앱과 서버 구현은 아직 시작하지 않았다.
- 상세 구현 계획은 작성하지 않았으며 현재 단계에서 요구하지 않는다.
- 서버 MVP는 Kotlin/JVM + Ktor, 클라이언트 UI는 iOS SwiftUI와 Android Jetpack Compose, 공통 domain/data 계층은 KMP를 사용한다.

## 다음 세션의 첫 논의

### 인프라 선택 기준의 우선순위 확정

PostgreSQL, Auth, Queue와 Hosting을 개별 제품명부터 고르지 않는다. 먼저 다음 가치 사이의 우선순위를 정한다.

- AWS와 서버 운영 학습
- MVP 출시 속도
- 초기·유휴 비용 최소화
- 운영 복잡도와 장애 대응 부담 최소화
- Kotlin/Ktor API와 Worker를 분리 실행할 수 있는 정도

다음 세션의 첫 질문은 아래와 같다.

> 인프라 조합을 선택할 때 AWS 학습, 빠른 출시, 낮은 비용, 적은 운영 부담 중 무엇을 가장 우선할 것인가?

## 이후 기술 설계 순서

1. 우선순위와 예상 사용자·작업량을 바탕으로 인프라 제약 확정
2. PostgreSQL과 Auth 후보 비교·선택
3. Ktor API와 Worker Hosting 후보 비교·선택
4. Queue와 retry/dead-letter 정책, transactional outbox 또는 동등한 작업 전달 보장 방식 확정
5. 로컬·개발·운영 환경, secret과 migration 운용 경계 확정
6. JS-rendered 사이트에 Playwright를 적용할 조건과 실행 위치 확정
7. 위 설계가 끝난 뒤 필요할 때만 구현 계획 작성

공급자별 가격·제약·지원 기능은 바뀔 수 있으므로 실제 논의 시점의 공식 자료를 확인한 뒤 결정한다.

## 다음 세션 시작 문구

아래처럼 요청하면 이 지점부터 이어갈 수 있다.

> `docs/history/TECHNICAL-DESIGN-CHECKPOINT-2026-09-13.md`를 기준으로 현재 상태를 확인하고, 인프라 선택의 우선순위부터 기술 설계 논의를 재개하자.

## 관련 문서

- [제품 기능 및 스펙](../product-spec.md)
- [전체 서비스 구조](../architecture/README.md)
- [Server 구조](../architecture/server/overview.md)
- [Client 구조](../architecture/client/README.md)
- [WishlistItem 상태 모델과 API 계약](../architecture/wishlist-item-state-api.md)
- [ADR-005: WishlistItem 상태와 API 경계](ADR-005-wishlist-item-state-api.md)
- [이전 제품 기획 체크포인트](PRODUCT-PLANNING-CHECKPOINT-2026-09-05.md)
