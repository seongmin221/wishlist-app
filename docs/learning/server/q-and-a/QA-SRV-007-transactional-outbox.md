# QA-SRV-007: Transactional outbox는 무엇인가

> 상태: **학습 Q&A** · 날짜: 2026-09-19

## 질문

Transactional outbox는 무엇이며, 이 프로젝트의 Ktor API와 Cloud Run Worker 사이에서 왜 필요한가?

## 짧은 답변

Transactional outbox는 애플리케이션 데이터 변경과 “나중에 전달할 작업”을 같은 PostgreSQL transaction에 함께 저장하는 패턴이다. API가 Neon의 `WishlistItem`만 저장한 뒤 Queue 전송에 실패해 분석 작업이 영원히 사라지는 문제를 막는다.

`transactional`이라는 이름은 PostgreSQL의 데이터 변경과 outbox 행 기록이 한 transaction이라는 뜻이다. PostgreSQL과 외부 Queue 전송 자체가 하나의 분산 transaction으로 묶인다는 뜻은 아니다.

## outbox가 없을 때 생기는 틈

API가 아래 순서로 처리한다고 가정한다.

1. Neon에 `WishlistItem(PROCESSING)`을 저장한다.
2. Queue에 분석 작업을 보낸다.

두 시스템에 대한 쓰기는 원자적이지 않아서 다음 문제가 생긴다.

- DB commit 후 Queue 전송 전에 API가 죽으면 항목은 `PROCESSING`인데 Worker 작업은 없다.
- Queue 전송 후 DB transaction이 rollback되면 Worker가 아직 존재하지 않는 항목의 작업을 받는다.

Queue를 먼저 호출하거나 DB를 먼저 호출하는 것만으로는 두 실패를 모두 없앨 수 없다.

## outbox를 사용한 흐름

```text
Ktor API의 한 Neon transaction
  ├─ WishlistItem(PROCESSING) 저장
  ├─ AnalysisJob(PENDING) 저장
  └─ OutboxEvent(ANALYSIS_REQUESTED) 저장
                  ↓ commit
Outbox dispatcher가 미발행 행 조회
                  ↓
Queue에 작업 발행
                  ↓
발행 성공을 outbox에 기록
                  ↓
Cloud Run Worker가 작업 처리
```

API transaction이 commit되면 상품과 작업 전달 의도가 함께 남고, rollback되면 둘 다 남지 않는다. Queue가 일시적으로 실패해도 dispatcher가 미발행 outbox 행을 다시 찾아 재시도할 수 있다.

일반적인 outbox 행에는 다음 정보가 들어간다.

- 고유 event ID와 event type
- 대상 `WishlistItem` 또는 `AnalysisJob` ID
- Worker에 전달할 최소 payload
- 생성 시각과 발행 완료 시각
- 발행 시도 횟수와 마지막 오류

## 중복 전달은 왜 가능한가

dispatcher가 Queue 발행에는 성공했지만 `published_at`을 기록하기 전에 죽을 수 있다. 재시작한 dispatcher는 같은 outbox 행을 다시 발행한다. 따라서 transactional outbox는 보통 **at-least-once delivery**를 만들며 exactly-once delivery를 보장하지 않는다.

Worker는 event ID나 job ID를 idempotency key로 사용하고, 이미 완료한 작업인지 확인해야 한다. 삭제되거나 더 새 분석이 시작된 항목에는 늦게 도착한 결과를 적용하지 않도록 `analysis_version`과 현재 상태도 함께 검증한다.

## 후속 결정과 남은 항목

- Queue는 Cloud Tasks, 실행 대상은 인증된 HTTP Cloud Run Worker service로 확정했다.
- outbox dispatcher를 상시 service, scheduled job 또는 다른 방식으로 실행할지
- 미발행 행 claim과 동시 실행을 어떻게 제어할지
- 발행 retry/backoff와 장기 실패 행 처리
- Cloud Tasks retry와 native dead-letter queue가 없는 조건의 장기 실패 복구 정책
- Worker의 idempotency key와 상태·version 검증 규칙

## 관련 설계

- [Server 구조](../../../architecture/server/overview.md)
- [WishlistItem 상태 모델과 API 계약](../../../architecture/wishlist-item-state-api.md)
- [2026-09-19 인프라 설계 체크포인트](../../../history/architecture/server/technical-design-checkpoint-2026-09-19.md)
