# QA-SRV-009: Outbox 발행·재시도·장기 실패는 어떻게 운영하는가

> 상태: **학습 Q&A** · 날짜: 2026-09-19

## 질문

Outbox dispatcher를 어떻게 실행하고, Cloud Tasks 재시도와 끝내 실패한 분석 작업은 어떻게 처리해야 하는가?

## 짧은 답변

이 프로젝트에는 **즉시 발행 + 주기적 복구를 결합한 하이브리드 방식**이 적합하다. API는 Neon transaction commit 직후 Cloud Tasks 생성을 한 번 시도하고, 실패하거나 빠뜨린 outbox는 Cloud Scheduler가 dispatcher endpoint를 주기적으로 호출해 다시 발행한다.

```text
API commit
  ├─ 즉시 Cloud Tasks 생성 시도 ── 성공 → 빠른 분석 시작
  └─ OutboxEvent는 DB에 유지
                    ↓
Cloud Scheduler가 주기적으로 dispatcher 호출
                    ↓
남은 OutboxEvent를 찾아 재발행
```

## Dispatcher 선택지

| 방식 | 장점 | 단점 |
| --- | --- | --- |
| API 요청 안에서만 발행 | 가장 빠르고 구현이 단순함 | 발행 실패 후 다시 시도할 주체가 없어 이것만으로는 불충분함 |
| Scheduler만 주기적으로 발행 | 누락 복구가 단순하고 Worker를 상시 실행하지 않아도 됨 | 모든 작업이 다음 주기까지 기다림 |
| 즉시 발행 + Scheduler 복구 | 평소에는 빠르고 장애 시에도 결국 복구됨 | 두 경로의 중복 실행을 안전하게 처리해야 함 |
| 상시 dispatcher | 지연이 가장 작음 | idle 비용과 운영 대상이 늘어 초기 규모에는 과함 |

하이브리드 방식에서는 dispatcher가 여러 번 실행돼도 같은 행을 안전하게 처리하도록 outbox claim, lease와 고유 task 이름을 사용한다. Cloud Scheduler 자체도 드물게 같은 일정을 중복 실행할 수 있으므로 dispatcher는 idempotent해야 한다.

## Cloud Tasks 재시도 판단

- Worker가 **2xx**를 반환하면 Cloud Tasks는 작업이 끝났다고 판단한다.
- timeout이나 **2xx가 아닌 응답**은 retry 대상으로 본다.
- 일시적인 네트워크·대상 사이트 장애는 실패 정보를 저장한 뒤 non-2xx를 반환해 재시도한다.
- 재시도해도 해결되지 않는 terminal 오류는 `FAILED_TERMINAL`을 저장하고 2xx를 반환해 반복 실행을 멈춘다.
- 자동 재시도를 모두 소진하면 `WishlistItem`은 `FAILED_RETRYABLE`이 되고 사용자가 새 generation으로 재분석할 수 있다.

확정한 정책은 최초 시도를 포함해 최대 3회, 10초부터 시작하는 exponential backoff, 최대 간격 10분, 전체 retry 기간 30분이다. 3회 또는 30분 중 하나라도 먼저 도달하면 추가 분석을 막는다.

Cloud Tasks 자체는 `maxAttempts`와 `maxRetryDuration`을 둘 다 설정하면 두 조건이 모두 충족될 때까지 재시도할 수 있다. Queue에는 같은 값을 설정하되, 정확한 3회·30분 독립 상한은 `AnalysisJob` attempt count·deadline과 Worker 사전 검증으로 강제한다.

## 장기 실패 복구

Cloud Tasks는 native dead-letter queue가 없고 retry 한도가 끝난 task를 삭제한다. 따라서 Neon의 `AnalysisJob`을 지속적인 진실의 원본이자 논리적 dead-letter 기록으로 사용한다.

- Worker가 시작·실패·성공과 시도 횟수, 마지막 오류를 `AnalysisJob`에 기록한다.
- 별도 reconciler가 너무 오래 queue 대기 또는 실행 중인 job을 주기적으로 찾는다.
- 안전하게 재실행할 수 있으면 새 dispatch attempt로 다시 Queue에 넣는다.
- 자동 한도를 넘으면 `FAILED_RETRYABLE` 또는 `FAILED_TERMINAL`로 전환한다.
- Cloud Tasks queue depth와 응답 코드별 task attempt에 alert를 둔다.

## 현재 결정

```text
API commit 후 best-effort 즉시 발행
  + Cloud Scheduler가 1분 간격으로 outbox 복구
  + AnalysisJob reconciler가 stale·장기 실패 작업 점검
```

Cloud Scheduler는 인증된 HTTP endpoint를 OIDC로 호출할 수 있고, 현재 billing account당 3개 job까지 무료다. 주기와 retry 수치는 구현 직전에 공식 가격·제약과 부하 테스트로 다시 확인한다.

이 정책의 결정 근거와 재검토 조건은 [ADR-009](../../../history/architecture/server/ADR-009-outbox-retry-recovery.md)에 기록했다.

## 공식 자료

- [Cloud Scheduler HTTP target 인증](https://docs.cloud.google.com/scheduler/docs/http-target-auth)
- [Cloud Scheduler 개요와 at-least-once 동작](https://docs.cloud.google.com/scheduler/docs/overview)
- [Cloud Scheduler 가격](https://cloud.google.com/scheduler/pricing)
- [Cloud Tasks HTTP 성공·재시도 조건](https://docs.cloud.google.com/tasks/docs/dual-overview)
- [Cloud Tasks Queue retry 설정](https://docs.cloud.google.com/tasks/docs/configuring-queues)
- [Cloud Tasks observability](https://docs.cloud.google.com/tasks/docs/monitor)

## 관련 문서

- [Transactional outbox Q&A](QA-SRV-007-transactional-outbox.md)
- [Cloud Run Queue 선택지](QA-SRV-008-queue-options.md)
- [ADR-008: Cloud Tasks와 outbox](../../../history/architecture/server/ADR-008-cloud-tasks-outbox-worker.md)
