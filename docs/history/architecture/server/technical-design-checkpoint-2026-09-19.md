# 기술 설계 재개 체크포인트 (2026-09-19)

> 상태: **진행 중** · 날짜: 2026-09-19 · 영역: **서버·인프라·AI**

이 문서는 [2026-09-15 인프라 설계 체크포인트](technical-design-checkpoint-2026-09-15.md)의 후속 결정과 최신 재개 지점을 기록한다. 이전 체크포인트의 Neon PostgreSQL, Firebase Authentication, Apple·Google 로그인과 초기 규모 가정은 그대로 유지한다.

## 이번 논의에서 확정한 기준

### 기본 인프라와 LLM 비용

- 월 **30,000원** 상한은 Neon, Firebase, Ktor API, Worker, Queue 등 기본 인프라 비용에 적용한다.
- 외부 LLM API 사용료는 이 상한에서 우선 제외하고 별도로 추적한다.
- 예산에서 제외한다는 것이 외부 LLM을 제한 없이 사용한다는 뜻은 아니다. 외부 LLM 호출과 비용은 최소화한다.
- MVP의 category 분류와 purpose 연결은 OpenAI API의 `gpt-5.6-luna`를 기본으로 사용한다.
- AI는 category와 purpose를 한 번의 Structured Output으로 받고, 로컬 on-device·서버 자체 호스팅 LLM은 MVP 범위에서 제외한다.
- 외부 API 사용량은 일별 1,000원·월별 10,000원의 hard cap과 각 80% 알림으로 제어한다.

비용 범위는 [ADR-006: LLM 비용과 실행 전략](../ai/ADR-006-llm-cost-and-execution-strategy.md), 기본 모델은 [ADR-010](../ai/ADR-010-openai-low-cost-model-strategy.md), 호출·상태·보호 정책은 [ADR-011](../ai/ADR-011-ai-classification-control-policy.md)을 따른다.

## 현재 확정된 인프라 기준

- PostgreSQL은 Neon, 인증은 Firebase Authentication을 사용한다.
- 첫 출시 로그인 수단은 Apple과 Google이다.
- Ktor API는 유휴 상태에서도 바로 응답할 수 있게 유지한다.
- 상품 분석 Worker는 유휴 시 중단되거나 시작이 지연돼도 허용한다.
- 주요 사용자는 한국 거주자로 가정하고 Neon, API와 Worker를 Singapore에 배치한다.
- Ktor API와 상품 분석 Worker의 호스팅으로 Google Cloud Run을 사용한다.
- API는 Cloud Run service와 minimum instance 1을 초기 기준으로 삼고, Worker는 별도 배포 단위에서 scale-to-zero를 허용한다.
- 상품 분석 Queue는 Cloud Tasks를 사용하고, transactional outbox가 인증된 HTTP Cloud Run Worker service에 작업을 전달한다.
- Cloud Tasks는 native dead-letter queue를 제공하지 않으므로 장기 실패 상태는 Neon의 `AnalysisJob`에 보존한다.
- API가 commit 직후 outbox를 즉시 발행하고, Cloud Scheduler가 1분마다 미발행 outbox를 복구한다.
- 분석은 최대 3회, 10초부터 최대 10분까지 exponential backoff로 재시도하며 전체 retry 기간은 30분이다.
- 3회 또는 30분 중 하나라도 먼저 도달하면 Worker가 추가 실행을 막고 retryable 오류는 `FAILED_RETRYABLE`로 전환한다.
- 기본 인프라 월 30,000원은 절대 차단 한도가 아니라 **평균 월 비용 상한**이다.
- 상품 저장부터 분석 완료까지의 p95 목표 시간은 **5분 이내**다.
- 초기 Cloud Run API는 request-based billing, 1 vCPU·1 GiB·concurrency 20·minimum instances 1·maximum instances 3으로 둔다.
- 초기 Worker는 request-based billing, 1 vCPU·1 GiB·concurrency 1·minimum instances 0·maximum instances 5로 둔다.
- Cloud Tasks queue는 초당 최대 1 dispatch, 최대 5개 동시 dispatch로 시작한다.
- MVP 초기 운용 환경은 local과 production만 둔다. cloud development·staging은 production 통합 검증 위험이 반복될 때 재검토한다.
- local은 Docker PostgreSQL, Firebase Auth Emulator, Cloud Tasks·OpenAI adapter fake와 fixture HTTP server를 조합해 테스트한다.
- production은 전용 Google Cloud project 하나에 Firebase, Cloud Run, Cloud Tasks, Scheduler와 Secret Manager를 두고, 별도의 production Neon project·database를 사용한다.
- production secret은 Secret Manager에 두고 API·Worker Cloud Run identity에 필요한 secret read만 준다. Cloud Tasks·Scheduler는 전용 OIDC 호출 identity, CI/CD는 GitHub Actions OIDC federation을 사용한다.
- DB schema 변경은 Git migration 파일로 관리한다. local 빈 PostgreSQL에서 검증하고 CI/CD가 production Neon에 한 번 적용한 뒤 API·Worker를 배포한다. runtime은 migration을 실행하지 않는다.
- PostgreSQL migration은 Flyway와 versioned SQL 파일로 관리한다. Gradle task가 local·CI에 적용하고 CI/CD의 production migration 단계만 `validate`·`migrate`를 실행한다.
- production migration은 전용 Neon DB role로 `validate → migrate → deploy` 순서로 실행한다. destructive·대량 변환 변경 전에는 Neon branch·snapshot을 만들고, 실패하면 앱 배포를 중단하며 자동 rollback 대신 forward migration을 사용한다.
- 평균 월 30,000원은 GCP 20,000원 budget alert와 Neon 10,000원 목표 비용으로 나눠 관측한다. 50%·80%·100% alert에서 원인을 점검·조정하며 billing·서비스를 자동 중단하지 않는다.
- Worker request timeout은 90초, Cloud Tasks HTTP task deadline은 105초로 시작하며 representative URL 부하 시험으로 p95 5분·timeout·retry 기준을 검증한다.
- JS-rendered 사이트는 일반 extraction 실패·저품질일 때만 Playwright를 한 번 보조 실행한다. Playwright는 별도 scale-to-zero browser Worker와 전용 Queue에서 실행하고, 실패는 `PARTIAL`·직접 보완으로 넘긴다. browser Worker는 2 vCPU·2 GiB·concurrency 1·maximum instances 2, browser Queue는 초당 1 dispatch·동시 2개로 시작한다.

호스팅 선택의 근거와 재검토 조건은 [ADR-007: Cloud Run 호스팅](ADR-007-cloud-run-hosting.md)을 따른다.
Queue와 작업 전달 선택은 [ADR-008: Cloud Tasks와 outbox](ADR-008-cloud-tasks-outbox-worker.md)를 따른다.
Dispatcher, retry와 장기 실패 복구 정책은 [ADR-009](ADR-009-outbox-retry-recovery.md)를 따른다.

## 정확한 재개 지점

Queue와 작업 전달·재시도, 일반·browser Worker의 초기 resource·queue 용량, 환경·local 의존성, production 계정·secret·service identity, migration 안전·비용 관측·timeout과 OpenAI 출시 평가 기준까지 정해졌다. 다음 논의는 evaluation corpus 작성 방식과 부하 시험 계획부터 시작한다.

> OpenAI corpus는 11개 상위 taxonomy, 목적 연결·미지정, 애매·저품질, JS-rendered와 비정상 입력을 포함한 180개 metadata snapshot으로 구성하고, 첫 실험 전 60개 holdout을 고정한다. 일반 20개 burst, JS 4개 동시, 30분 혼합 안정성 시험을 fake·실제 외부 서비스로 나눠 수행한다. 다음으로 전체 설계 문서를 검토한다.

Worker는 `eventId` 또는 `jobId`와 `analysisVersion`으로 중복·늦은 결과를 차단한다. 실제 분석 시간, p95 완료 시간, queue depth, retry, CPU·memory와 평균 월 비용을 부하 시험·출시 후에 관측하고, 필요하면 Worker 최대 instance와 Cloud Tasks 동시 dispatch를 함께 조정한다. Worker request timeout과 Cloud Tasks task deadline은 extraction·LLM latency 측정 후에 정한다.

## 이후 기술 설계 순서

1. corpus·부하 시험을 구현해 실제 OpenAI token·품질·latency와 worker resource 기준을 검증한다.
2. 인프라 설계 문서를 최종 검토한 뒤 구현 계획을 작성한다.

## 다음 세션 시작 문구

> `docs/history/architecture/server/technical-design-checkpoint-2026-09-19.md`를 기준으로 인프라 설계를 재개하자. OpenAI 분류의 180개 평가 corpus와 출시 기준을 정했다. 다음으로 corpus 구성과 일반·browser Worker 부하 시험 시나리오를 제안해줘.

## 관련 프로젝트 문서

- [AI 구조](../../../architecture/ai/overview.md)
- [Server 구조](../../../architecture/server/overview.md)
- [2026-09-15 인프라 설계 체크포인트](technical-design-checkpoint-2026-09-15.md)
- [ADR-006: LLM 비용과 실행 전략](../ai/ADR-006-llm-cost-and-execution-strategy.md)
- [ADR-010: OpenAI 저비용 모델 전략](../ai/ADR-010-openai-low-cost-model-strategy.md)
- [ADR-011: AI 분류 호출·상태·보호 정책](../ai/ADR-011-ai-classification-control-policy.md)
- [ADR-007: Cloud Run 호스팅](ADR-007-cloud-run-hosting.md)
- [ADR-008: Cloud Tasks와 outbox](ADR-008-cloud-tasks-outbox-worker.md)
- [ADR-009: Outbox 발행과 retry·복구](ADR-009-outbox-retry-recovery.md)
- [ADR-012: Cloud Run과 Cloud Tasks의 초기 용량](ADR-012-cloud-run-initial-capacity.md)
- [ADR-013: MVP 환경 경계](ADR-013-environment-boundary.md)
- [ADR-014: local 통합 테스트 의존성](ADR-014-local-integration-test-dependencies.md)
- [ADR-015: production project와 database 경계](ADR-015-production-project-and-database-boundaries.md)
- [ADR-016: production secret과 service identity](ADR-016-production-secrets-and-identities.md)
- [ADR-017: DB migration 운용](ADR-017-database-migration-operations.md)
- [ADR-018: Flyway SQL migration](ADR-018-flyway-sql-migrations.md)
- [ADR-019: production migration 안전 절차](ADR-019-production-migration-safety.md)
- [ADR-020: 비용 관측과 alert](ADR-020-cost-observability-and-alerts.md)
- [ADR-021: 분석 Worker와 Cloud Tasks timeout](ADR-021-analysis-request-timeouts.md)
- [ADR-022: Playwright best-effort fallback](ADR-022-playwright-best-effort-fallback.md)
- [ADR-023: browser Worker 분리](ADR-023-browser-worker-isolation.md)
- [ADR-024: browser Worker 초기 용량](ADR-024-browser-worker-capacity.md)
