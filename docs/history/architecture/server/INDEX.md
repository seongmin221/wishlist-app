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
- **확정**: API는 1 vCPU·1 GiB·concurrency 20·minimum instance 1·maximum instance 3, Worker는 1 vCPU·1 GiB·concurrency 1·maximum instance 5로 시작한다. Cloud Tasks는 초당 1 dispatch·동시 5개로 시작한다.
- **확정**: MVP는 local과 production 환경만 운용한다. cloud staging은 production 통합 검증 위험이 반복될 때 재검토한다.
- **확정**: local은 Docker PostgreSQL, Firebase Auth Emulator, Cloud Tasks·OpenAI adapter fake, fixture HTTP server로 테스트한다.
- **확정**: production은 전용 GCP/Firebase project 하나와 별도의 production Neon project·database를 사용한다.
- **확정**: production secret은 Secret Manager에 두고 API·Worker·자동 호출자·CI/CD identity의 권한을 역할별로 최소화한다.
- **확정**: DB schema는 Git migration 파일로 관리하며 CI/CD의 별도 단계가 production Neon에 적용한 뒤 앱을 배포한다.
- **확정**: PostgreSQL migration은 Flyway와 versioned SQL 파일을 사용한다.
- **확정**: production migration은 전용 DB role로 실행하며 destructive·대량 변경 전 Neon branch·snapshot을 만들고 실패 시 앱 배포를 중단한다.
- **확정**: 평균 월 30,000원 비용은 GCP 20,000원 budget alert와 Neon 10,000원 목표 비용으로 나눠 관측하며 자동 서비스 중단은 하지 않는다.
- **확정**: Worker request timeout은 90초, Cloud Tasks task deadline은 105초로 시작하고 부하 시험으로 조정한다.
- **확정**: JS-rendered 사이트는 일반 extraction 실패·저품질일 때만 Playwright를 한 번 보조 실행하며, 실패는 직접 보완으로 넘긴다.
- **확정**: Playwright는 scale-to-zero browser Worker와 별도 Queue에서 실행해 일반 Worker와 격리한다.
- **확정**: browser Worker는 2 vCPU·2 GiB·concurrency 1·maximum instance 2, browser Queue는 초당 1 dispatch·동시 2개로 시작한다.
- **검토 예정**: 실제 부하·비용 측정에 따른 용량 조정, Worker·Cloud Tasks timeout과 비용 alert 기준.

- [ADR-002: MVP 서버 구현 언어는 Kotlin을 우선한다](ADR-002-kotlin-server-language.md)
- [ADR-003: MVP 서버 프레임워크로 Ktor를 채택한다](ADR-003-select-ktor.md)
- [ADR-007: API와 Worker 호스팅으로 Cloud Run을 채택한다](ADR-007-cloud-run-hosting.md)
- [ADR-008: Transactional outbox와 Cloud Tasks로 분석 작업을 전달한다](ADR-008-cloud-tasks-outbox-worker.md)
- [ADR-009: Outbox 발행과 분석 재시도·장기 실패 복구 정책을 확정한다](ADR-009-outbox-retry-recovery.md)
- [ADR-012: Cloud Run과 Cloud Tasks의 초기 용량을 균형형으로 시작한다](ADR-012-cloud-run-initial-capacity.md)
- [ADR-013: MVP는 local과 production 환경만 운용한다](ADR-013-environment-boundary.md)
- [ADR-014: local은 실제 PostgreSQL과 service adapter의 emulator·fake를 조합한다](ADR-014-local-integration-test-dependencies.md)
- [ADR-015: production은 전용 GCP/Firebase project와 전용 Neon database를 사용한다](ADR-015-production-project-and-database-boundaries.md)
- [ADR-016: production secret과 service identity를 역할별 최소 권한으로 분리한다](ADR-016-production-secrets-and-identities.md)
- [ADR-017: DB schema 변경은 별도 migration 단계로 관리한다](ADR-017-database-migration-operations.md)
- [ADR-018: Flyway와 versioned SQL 파일로 PostgreSQL migration을 관리한다](ADR-018-flyway-sql-migrations.md)
- [ADR-019: production migration은 전용 계정·사전 복구 지점·배포 중단으로 보호한다](ADR-019-production-migration-safety.md)
- [ADR-020: 평균 월 30,000원 비용은 단계적 alert와 지표 관측으로 관리한다](ADR-020-cost-observability-and-alerts.md)
- [ADR-021: Worker 분석은 90초, Cloud Tasks deadline은 105초로 시작한다](ADR-021-analysis-request-timeouts.md)
- [ADR-022: JS-rendered 상품 페이지는 Playwright를 제한적 보조 경로로 지원한다](ADR-022-playwright-best-effort-fallback.md)
- [ADR-023: Playwright는 별도 browser Worker에서 실행한다](ADR-023-browser-worker-isolation.md)
- [ADR-024: browser Worker는 2 vCPU·2 GiB와 동시 2개로 시작한다](ADR-024-browser-worker-capacity.md)

## 기술 설계 체크포인트

- [2026-09-19 인프라 설계 체크포인트](technical-design-checkpoint-2026-09-19.md) — 진행 중
- [2026-09-15 인프라 설계 체크포인트](technical-design-checkpoint-2026-09-15.md) — 대체됨
- [2026-09-13 기술 설계 체크포인트](technical-design-checkpoint-2026-09-13.md) — 대체됨

추가 결정은 `ADR-번호-제목.md` 형식으로 이 폴더에 기록한다.
