# ADR-017: DB schema 변경은 별도 migration 단계로 관리한다

> 상태: **확정** · 날짜: 2026-09-20 · 영역: **server·database**

## 맥락

Wishlist API·Worker는 `WishlistItem`, `AnalysisJob`, outbox와 taxonomy 관련 PostgreSQL schema에 의존한다. 앱 코드와 production Neon DB schema이 다른 버전이면 요청 처리와 background worker가 실패할 수 있다. Cloud Run은 여러 instance가 동시에 시작할 수 있으므로 runtime에서 각 instance가 schema를 변경하게 해서는 안 된다.

## 결정

- DB schema 변경은 순서가 있는 migration 파일로 Git에서 관리한다.
- 이미 production에 적용된 migration 파일은 수정하지 않는다. 변경이 필요하면 새 forward migration을 추가한다.
- local Docker PostgreSQL의 빈 DB에 전체 migration을 적용하고 repository·통합 테스트를 실행한다.
- production Neon migration은 CI/CD 배포 흐름의 별도 단계가 **한 번만** 수행한다. migration 성공 후 API·Worker revision을 배포한다.
- API·Worker runtime service account에는 schema 변경 권한을 주지 않으며, 앱 시작 시 migration을 실행하지 않는다.
- destructive change(컬럼 삭제·이름 변경·기존 데이터가 있는 `NOT NULL` 추가 등)는 `expand → migrate → contract` 순서로 나눈다. 즉 호환되는 새 구조를 추가하고, 앱을 전환·데이터를 옮긴 뒤, 이전 구조는 나중 migration에서 제거한다.
- migration 실패 시 배포를 중단한다. 자동 rollback에 의존하지 않고, backup·상태를 확인한 뒤 새 forward migration 또는 호환 가능한 앱 revision으로 복구한다.

## 이유와 trade-off

별도 migration 단계는 모든 환경의 schema를 재현 가능하게 만들고, Cloud Run startup 경쟁으로 DB가 손상되는 위험을 줄인다. destructive 변경을 단계화하면 새·구버전 서버가 잠시 공존하는 배포에서도 장애를 줄일 수 있다.

대신 deployment pipeline에 migration 실행·검증 단계가 추가되고, 큰 데이터 변경은 여러 release에 걸쳐 진행해야 한다. 초기 MVP에는 이 추가 절차가 production 직접 수정의 위험보다 작다.

## 후속 결정

- Kotlin/Ktor와 Gradle에 연결할 migration 도구와 파일 규칙을 정한다.
- migration 실행 전용 CI/CD identity, Neon backup 확인, failure runbook을 정한다.
- 평균 월 비용 30,000원의 관측·alert 기준, Worker request timeout과 Cloud Tasks task deadline을 정한다.
