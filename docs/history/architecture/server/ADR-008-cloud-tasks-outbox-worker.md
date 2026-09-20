# ADR-008: Transactional outbox와 Cloud Tasks로 분석 작업을 전달한다

> 상태: **확정** · 날짜: 2026-09-19 · 영역: **server·infrastructure**

## 맥락

Ktor API가 Neon에 `WishlistItem(PROCESSING)`을 저장한 뒤 별도 Queue 전송에 실패하면 분석 작업이 유실될 수 있다. 반대로 Queue 전송 후 DB transaction이 rollback되면 존재하지 않는 항목의 작업이 실행될 수 있다. 또한 상품 분석은 여러 구독자에게 방송하는 event보다 특정 Worker가 수행해야 하는 독립 작업에 가깝다.

Cloud Tasks, Pub/Sub와 Neon job table polling을 비교했다.

## 결정

- 상품 분석 작업 전달에는 **transactional outbox + Cloud Tasks + 인증된 HTTP Cloud Run Worker service**를 사용한다.
- Cloud Tasks Queue와 Worker는 Singapore 리전에 둔다.
- API는 `WishlistItem`, `AnalysisJob`과 `OutboxEvent`를 하나의 Neon transaction에 저장한다.
- Outbox dispatcher는 미발행 event를 Cloud Tasks task로 생성하고 성공한 event를 발행 완료로 기록한다.
- Cloud Tasks는 OIDC service account token을 사용해 unauthenticated access를 허용하지 않는 Worker endpoint를 호출한다.
- task payload는 `eventId`, `jobId`, `wishlistItemId`와 `analysisVersion` 같은 식별자 중심으로 작게 유지하고, Worker가 Neon에서 현재 상태를 다시 조회한다.
- `eventId` 또는 `jobId`를 task 이름과 Worker idempotency key의 기반으로 사용한다. Cloud Tasks의 task 생성 중복 방지만 믿지 않고 Worker가 완료 여부와 현재 `analysisVersion`을 검증한다.
- Worker는 HTTP 요청을 처리하는 별도 Cloud Run service로 배포하고 유휴 시 scale-to-zero를 허용한다.

## 이유와 trade-off

- 작업마다 대상 endpoint와 실행을 명시할 수 있어 하나의 상품 분석을 하나의 Worker에 전달하는 현재 요구와 맞는다.
- Queue 단위 dispatch rate와 동시 실행 수를 제한해 Neon, 대상 쇼핑몰과 LLM에 가해지는 부하를 제어할 수 있다.
- retry 횟수, 기간과 backoff를 작업 또는 Queue 수준에서 제어할 수 있다.
- 초기 월 20,000건 규모는 현재 Cloud Tasks 무료 operation 범위 안에서 운영할 가능성이 높다. 가격은 구현 시점에 다시 확인한다.

Cloud Tasks도 at-least-once 전달이므로 중복 실행 가능성은 남는다. 또한 Pub/Sub와 달리 native dead-letter queue로 실패 task를 옮기지 않는다. retry 조건을 모두 소진한 task는 삭제되므로 `AnalysisJob`에 시도·실패 상태를 보존하고 모니터링과 수동·자동 복구 경로를 별도로 설계해야 한다.

## 선택하지 않은 대안

- **Pub/Sub**: 여러 독립 구독자와 event fan-out이 필요할 때 유리하지만 현재는 분석 Worker 하나를 명시적으로 실행하는 작업이어서 도입하지 않는다.
- **Neon job table polling**: 외부 Queue를 줄일 수 있지만 scale-to-zero Worker를 깨우기 위한 Scheduler, polling 부하와 retry·동시 claim 구현을 직접 책임져야 한다.
- **Cloud Run Jobs**: 긴 batch나 종료형 작업에는 적합하지만 상품 한 건마다 전달되는 현재 분석의 기본 실행 형태로는 사용하지 않는다.

## 후속 설계

- outbox dispatcher와 retry·장기 실패 복구는 후속 [ADR-009](ADR-009-outbox-retry-recovery.md)에서 확정했다.
- Queue dispatch rate, 동시 실행 수와 Worker 최대 instance
- 사용자 재분석 요청과 운영자 복구 흐름

## 재검토 조건

- 분석 완료 event를 여러 독립 서비스가 동시에 소비해야 한다.
- 작업량이 Cloud Tasks Queue 한도 또는 비용 가정을 크게 넘는다.
- 개별 분석이 HTTP task 처리 시간 안에 끝나지 않아 장기 실행 형태가 필요하다.
- native dead-letter와 replay가 운영상 필수 요구가 된다.
