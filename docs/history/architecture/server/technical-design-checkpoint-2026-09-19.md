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

호스팅 선택의 근거와 재검토 조건은 [ADR-007: Cloud Run 호스팅](ADR-007-cloud-run-hosting.md)을 따른다.
Queue와 작업 전달 선택은 [ADR-008: Cloud Tasks와 outbox](ADR-008-cloud-tasks-outbox-worker.md)를 따른다.
Dispatcher, retry와 장기 실패 복구 정책은 [ADR-009](ADR-009-outbox-retry-recovery.md)를 따른다.

## 정확한 재개 지점

Queue와 작업 전달·재시도 정책까지 정해졌으므로 다음 논의는 resource와 비용 설정부터 시작한다.

> Cloud Run API와 Worker의 CPU, memory, concurrency, 최대 instance 및 Cloud Tasks dispatch rate를 정하고 예상 월 비용이 30,000원 안에 들어오는지 계산한다.

Worker는 `eventId` 또는 `jobId`와 `analysisVersion`으로 중복·늦은 결과를 차단한다. Resource 설정은 Ktor JVM memory와 월 약 20,000건 분석 가정을 기준으로 보수적으로 시작한다.

## 이후 기술 설계 순서

1. Cloud Run API·Worker의 CPU, memory, concurrency, 최대 instance, Cloud Tasks dispatch rate와 예상 월 비용을 확정한다.
2. 로컬·개발·운영 환경, secret, Firebase service account와 DB migration 운용 경계를 확정한다.
3. JS-rendered 사이트에 Playwright를 적용할 조건과 실행 위치를 확정한다.
4. OpenAI API의 실제 토큰 사용량·품질·latency가 cap과 평가 기준을 충족하는지 검증한다.
5. 인프라 설계 문서를 최종 검토한 뒤 구현 계획을 작성한다.

## 다음 세션 시작 문구

> `docs/history/architecture/server/technical-design-checkpoint-2026-09-19.md`를 기준으로 인프라 설계를 재개하자. Cloud Run, transactional outbox, Cloud Tasks와 3회·30분 재시도 정책은 확정됐다. API·Worker resource, concurrency, 최대 instance, Queue dispatch rate와 월 비용의 초기값을 제안해줘.

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
