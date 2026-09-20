# Server 의사결정

## 초기 결정

- **확정**: 상품 저장 API는 비동기 분석 완료를 기다리지 않고 `PROCESSING` 상태를 즉시 응답한다.
- **확정**: Product metadata extraction과 AI classification은 background worker에서 처리한다.
- **확정**: Product와 사용자별 WishlistItem을 분리한다.
- **제안**: 초기 백엔드는 modular monolith로 시작하고 API와 Worker는 논리적 역할만 분리한다.
- **확정**: MVP 서버 프레임워크는 Ktor를 사용한다. Spring Boot는 재검토 가능한 대안이다.
- **확정**: PostgreSQL은 Neon, 인증은 Firebase Authentication을 사용한다.
- **확정**: API와 Worker는 Cloud Run의 Singapore 리전에 배포한다.
- **확정**: Transactional outbox와 Cloud Tasks가 인증된 HTTP Cloud Run Worker service에 분석 작업을 전달한다.
- **확정**: Outbox는 즉시 발행과 1분 Scheduler 복구를 결합하고, 분석은 최대 3회·30분 동안 재시도한다.
- **검토 예정**: Queue·Worker 용량 설정과 예상 월 비용.

- [ADR-002: MVP 서버 구현 언어는 Kotlin을 우선한다](ADR-002-kotlin-server-language.md)
- [ADR-003: MVP 서버 프레임워크로 Ktor를 채택한다](ADR-003-select-ktor.md)
- [ADR-007: API와 Worker 호스팅으로 Cloud Run을 채택한다](ADR-007-cloud-run-hosting.md)
- [ADR-008: Transactional outbox와 Cloud Tasks로 분석 작업을 전달한다](ADR-008-cloud-tasks-outbox-worker.md)
- [ADR-009: Outbox 발행과 분석 재시도·장기 실패 복구 정책을 확정한다](ADR-009-outbox-retry-recovery.md)

## 기술 설계 체크포인트

- [2026-09-19 인프라 설계 체크포인트](technical-design-checkpoint-2026-09-19.md) — 진행 중
- [2026-09-15 인프라 설계 체크포인트](technical-design-checkpoint-2026-09-15.md) — 대체됨
- [2026-09-13 기술 설계 체크포인트](technical-design-checkpoint-2026-09-13.md) — 대체됨

추가 결정은 `ADR-번호-제목.md` 형식으로 이 폴더에 기록한다.
