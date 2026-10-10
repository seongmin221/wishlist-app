# QA-SRV-016: 재시도 예산을 generation 전체로 세고 Retryable을 ACK로 끝내는 이유는?

> 상태: **학습 Q&A** · 날짜: 2026-10-10 · 관련: [ADR-009](../../../history/architecture/server/ADR-009-outbox-retry-recovery.md), [B5 결정](../../../history/product-planning/mvp/decisions/b5-analysis-runtime-policy-2026-10-09.md)

## 질문

일반 Worker와 browser Worker가 각자 3회씩 재시도하면 안 되는가? 일시적 실패에 503을 돌려 Cloud Tasks가 다시 보내게 하는 것이 자연스럽지 않은가?

## 짧은 답변

ADR-009의 "실제 Worker 실행 최대 3회·30분"은 외부 사이트 장애 때 비용과 부하의 상한이다. lane마다 따로 세면 한 상품이 최대 6회 실행되고, browser 단계에서 30분이 새로 시작될 수 있다. 그래서 두 lane의 attempt를 합쳐 3회, 가장 이른 첫 시도부터 30분으로 판정한다. browser fallback 첫 실행도 1회로 센다.

503 재전달을 쓰면 같은 실패에 전달 기회가 두 개 생긴다. 하나는 Cloud Tasks의 원래 task 재전달이고, 다른 하나는 서버가 같은 transaction에 저장한 retry outbox다. 두 task가 서로 다른 PENDING 시점에 도착하면 attempt를 두 번 쓴다. 합산 3회에서는 이 한 번이 browser fallback 기회를 직접 빼앗는다. maintenance가 outbox 발행과 PENDING 복구로 진행을 보장하게 되었으므로, durable outbox를 저장한 경우에는 204로 원래 task를 끝낸다.

## backoff는 어떻게 유지하는가

retry outbox에 `not_before = 지금 + min(10초 × 2^(attempts−1), 600초)`를 저장하고, dispatcher가 이를 Cloud Tasks `scheduleTime`으로 넘긴다. Scheduler가 1분마다 발행해도 task는 예약 시각에 전달된다.

## 예외

durable 기록이 없는 RETRY는 503을 유지한다. claim 전에 처리 시간이 끝난 경우, executor가 포화된 경우, 90초 응답 상한을 넘긴 경우다. 이때는 Cloud Tasks 재전달만이 진행 수단이다. 90초 timeout 뒤 늦게 끝난 실행이 retry outbox를 만들면 재전달과 겹칠 수 있다. claim이 동시 실행을 막으므로 남는 위험은 추가 attempt 1회뿐이다.

## 발행된 task가 claim 전에 계속 사라지면?

Worker 인증 설정 오류처럼 claim 전에 실패하면 attempt가 늘지 않아 예산이 영원히 남는다. 그래서 PENDING 재예약 횟수(`recovery_seq`)를 job당 누적 3회로 제한하고, 넘으면 FAILED_RETRYABLE로 끝낸다.
