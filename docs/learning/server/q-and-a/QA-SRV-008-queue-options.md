# QA-SRV-008: Cloud Run 분석 작업 Queue에는 어떤 선택지가 있는가

> 상태: **학습 Q&A** · 날짜: 2026-09-19

## 질문

Transactional outbox에서 Cloud Run Worker로 분석 작업을 전달할 때 어떤 선택지가 있고 각각의 장단점은 무엇인가?

## 짧은 답변

주요 선택지는 Cloud Tasks, Pub/Sub와 Neon job table polling이다. 현재 요구에는 **Cloud Tasks + outbox + HTTP 방식 Cloud Run Worker**가 가장 단순하게 맞는다. 상품 하나의 분석은 여러 구독자에게 알리는 사건보다, 특정 Worker가 수행해야 하는 하나의 명령에 가깝기 때문이다.

| 선택지 | 쉬운 비유 | 장점 | 단점 |
| --- | --- | --- | --- |
| Cloud Tasks | 처리할 일을 담당자에게 한 장씩 전달 | 대상 endpoint, 예약 시각, retry, 전송 속도와 동시 실행 수를 직접 제어하기 좋음 | 한 작업을 여러 소비자에게 전달하는 fan-out에는 부적합하고 중복 전달에 대비해야 함 |
| Pub/Sub | 게시판에 사건을 올리면 구독자들이 각자 수신 | 여러 소비자, 대규모 event 전달과 느슨한 결합에 유리 | 현재처럼 소비자 하나인 작업에는 topic·subscription 개념과 운영 설정이 더 많고 생산자가 실행을 세밀하게 통제하기 어려움 |
| Neon job table polling | DB의 할 일 목록을 Worker가 주기적으로 확인 | 상품과 job을 한 transaction에 저장할 수 있고 외부 Queue가 없어 단순함 | 잠든 Cloud Run Worker를 스스로 깨울 수 없어 Scheduler나 상시 instance가 필요하고 polling, retry, dead-letter, 동시 claim을 직접 구현해야 함 |

## Cloud Run Jobs는 별도 선택지인가

Cloud Run Jobs는 Queue가 아니라 “시작하면 끝날 때까지 실행하는 container 형태”다. 긴 batch나 migration에는 적합하지만, 월 20,000개의 짧은 상품 분석을 하나씩 전달하는 기본 Queue로 쓰기에는 실행 관리가 무겁다. 향후 한 작업이 HTTP 제한보다 매우 길어지거나 여러 상품을 batch로 처리할 때 다시 검토할 수 있다.

## 현재 결정

```text
Neon transaction
  → WishlistItem + AnalysisJob + OutboxEvent
  → outbox dispatcher
  → Cloud Tasks
  → 인증된 HTTP 요청
  → scale-to-zero Cloud Run Worker service
```

Cloud Tasks는 작업별 retry와 예약 실행, Queue 단위 rate·concurrency 제한을 제공한다. 현재 월 20,000건 수준은 공식 가격의 월 100만 billable operation 무료 구간보다 충분히 작다. 다만 재시도와 관리 API 호출도 operation으로 계산되며 가격은 구현 전에 다시 확인한다.

Cloud Tasks와 Pub/Sub 모두 at-least-once 전달이므로 Worker idempotency는 어느 쪽을 선택해도 필요하다. 향후 하나의 상품 분석 완료 event를 알림, 분석 데이터, 추천 등 여러 소비자가 동시에 받아야 한다면 Pub/Sub를 별도 event bus로 추가하는 편이 자연스럽다.

2026-09-19에 이 구성을 채택했다. 결정 근거와 재검토 조건은 [ADR-008](../../../history/architecture/server/ADR-008-cloud-tasks-outbox-worker.md)에 기록했다.

## 공식 자료

- [Cloud Tasks와 Pub/Sub 선택 기준](https://docs.cloud.google.com/pubsub/docs/choosing-pubsub-or-cloud-tasks)
- [Cloud Tasks Queue rate와 retry](https://docs.cloud.google.com/tasks/docs/configuring-queues)
- [Cloud Tasks 가격](https://cloud.google.com/tasks/pricing)
- [Pub/Sub 개요](https://docs.cloud.google.com/pubsub/docs/overview)
- [Cloud Run service와 job 비교](https://docs.cloud.google.com/run/docs/overview/what-is-cloud-run)

## 관련 문서

- [Transactional outbox Q&A](QA-SRV-007-transactional-outbox.md)
- [2026-09-19 인프라 설계 체크포인트](../../../history/architecture/server/technical-design-checkpoint-2026-09-19.md)
