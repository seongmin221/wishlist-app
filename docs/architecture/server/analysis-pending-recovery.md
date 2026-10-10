# B5 PENDING 작업 복구 설계

> 2026-10-05 설계 · 2026-10-10 B5 구현 · Scheduler OIDC 실호출은 B11

## 복구 대상

RUNNING lease 복구만으로 PROCESSING 상품의 진행을 보장할 수 없다. PENDING은 Cloud Tasks가 재시도를 소진하거나 delivery 마감 이후 중복 요청의 ACK로 task를 제거한 경우, browser fallback/outbox의 미발행, 발행 뒤 task 유실로도 남을 수 있다.

B0 보완은 유효 실행의 Retryable→PENDING 전환에 재시도 outbox를 같은 transaction으로 생성한다. 따라서 원래 task가 사라져도 발행할 작업이 남는다. 이 outbox를 실제로 발행하는 maintenance runtime과 오래된 PENDING 검사기는 B5에서 연결한다.

## maintenance 처리 순서

1. batch 제한 안에서 미발행/만료 발행 lease outbox를 발행한다. event의 결정적 task 이름으로 중복 create를 안전하게 처리한다.
2. 만료 RUNNING을 기존 owner→item→job 잠금·현재 실행 재검증으로 복구한다.
3. GENERAL_PENDING/BROWSER_PENDING의 정체 후보를 읽는다. DB 시각을 사용하고 기준 시간은 dispatch deadline·queue backoff·허용 backlog 대기를 반영한 설정으로 둔다. 오래됐다는 이유만으로 살아 있는 queue task를 실패 처리하지 않는다.
4. 미발행 outbox가 있으면 그 event를 발행 대상으로 유지한다. 이미 발행된 최신 task는 transaction 밖에서 queue 존재/상태를 확인한다. 살아 있는 queued/in-flight task는 유지하고 조회 실패는 기록 후 다음 검사로 넘긴다.
5. task 소진/유실이 확인된 후보만 owner→item→job 잠금 뒤 owner/관계/current generation/lifecycle/manual/version/PENDING stage와 발견 당시 updatedAt·최신 outbox identity를 다시 확인한다. 확인 사이 claim이나 retry가 진행됐으면 무변경으로 건너뛴다.
6. 유효하고 generation 전체 재시도 예산이 남으면 PENDING을 유지하며 복구 식별자를 증가시키고 새 outbox를 같은 transaction에 저장한다. UNIQUE 제약으로 동시 Scheduler가 한 번만 재예약하게 한다. task 이름은 이전 이름을 재사용하지 않는다. attempt는 재예약이 아닌 새 claim에서만 증가한다.
7. 예산 소진이면 현재 상품만 FAILED_RETRYABLE로 최종 전이한다. 삭제/보관/수동 완료/이전 generation이면 job을 취소하고 새 outbox를 만들지 않는다. 예산 비용 정산은 별도로 유지한다.

재예약 식별자(`recovery_seq`)·다음 검사 시각(`recovery_check_at`)·발견 index는 V17에서 추가했다. 확정 값과 구현은 아래 "B5 구현" 절에 있다.

## 생성 직후 발행과 backlog

B0 API의 `dispatchPending(1)`은 방금 생성한 event가 아닌 가장 오래된 event를 골랐다. **B1에서 생성 transaction이 반환한 event ID를 `dispatchEvent(id)`로 발행하도록 연결했다.** Scheduler의 batch 발행과 claim/lease 규칙은 공유한다. 지정 발행 실패/종료 거부도 저장 응답과 durable outbox를 보존한다. B1 회귀에는 오래된 retry backlog가 있어도 새 상품의 event만 발행하는 경우를 포함한다. maintenance runtime과 오래된 PENDING 복구는 B5에서 연결한다. Worker도 retry/fallback 후 제한된 발행을 시도할 수 있지만 진행 보장은 Scheduler가 담당한다.

B5에서 유효 실행의 Retryable은 retry outbox를 저장한 뒤 HTTP 204로 원래 task를 끝낸다. 503 재전달과 retry outbox가 함께 살아 있으면 서로 다른 PENDING 시점에 도착해 합산 3회 예산을 이중으로 쓸 수 있기 때문이다. 진행은 durable outbox와 maintenance가 보장한다. 503은 durable 기록이 없는 claim 전 마감·executor 포화·90초 timeout에만 남는다. 90초 timeout 뒤 늦게 끝난 실행이 retry outbox를 만들면 재전달과 겹칠 수 있으며, 이 경우 추가 attempt 1회가 남는 위험으로 기록한다.

## 필수 회귀

- 105초 delivery 마감 후 중복 ACK→원래 실행 Retryable인 경우에도 durable outbox와 후속 실행 유지.
- 최대 queue retry 소진 뒤 PENDING 재예약; 미발행 browser fallback은 기존 outbox 발행.
- 살아 있는 task/backlog와 task 조회 장애는 중복 재예약·상품 실패를 만들지 않음.
- 동시 Scheduler와 claim/finish/edit/delete 경합에서 재예약 1건, 옛 발견 무효, generation 전체 3회·30분 예산 보존.
- 삭제/보관/수동 완료 상품은 job만 취소; 예산 정산·사용자 값·item version 보호.
- B1에서 연결한 신규 event 지정 발행을 유지하고, backlog 복구·RPC 제한 시간·후보 1개 실패 후 다음 후보 진행을 검증한다. 생성의 즉시 발행은 [B1 조회 계약](wishlist-item-read-api.md)을 따른다.

## B5 구현

[B5 결정](../../history/product-planning/mvp/decisions/b5-analysis-runtime-policy-2026-10-09.md)과 [B5 spec](../../superpowers/specs/2026-10-09-b5-analysis-runtime-recovery-design.md)을 따른다.

- `maintenance` 역할의 `POST /internal/maintenance/run`이 한 번에 outbox backlog 발행(100건) → 만료 RUNNING 복구 → 오래된 PENDING 복구 → LLM 예산 정리를 실행한다. 단계별로 실패를 격리한다. 발행은 30초까지만 쓰고 복구 단계는 50초 deadline을 공유하며, budget 정리는 항상 실행한다. 실패 단계가 있으면 500과 report를 반환한다.
- backlog 발행은 이번 실행에서 실패한 event만 제외하고 계속한다. retry outbox의 `not_before`는 Cloud Tasks `scheduleTime`으로 넘겨 ADR-009 backoff(10초부터 2배, 최대 600초)를 유지한다.
- PENDING 발견: `updated_at` 5분 경과, `recovery_check_at` 도래, 최신 outbox가 발행됐거나 없음. 미발행 event는 발행 단계의 대상이라 발견 batch(50)를 차지하지 않는다.
- `TaskGateway.status`(getTask, 5초)로 transaction 밖에서 확인한다. ALIVE는 5분 뒤, 조회 실패는 1분 뒤 다시 본다. MISSING만 owner→item→job 잠금 뒤 stage·`updated_at`·최신 outbox ID를 재검증한다.
- 결과: 비활성·이전 generation은 job CANCELLED, generation 예산 소진 또는 `recovery_seq` 누적 3회는 `failExhausted`(읽은 metadata 반영 + FAILED_RETRYABLE), 그 외는 `recovery_seq` 증가와 새 outbox(`…-pending-{seq}`) 저장. task_name UNIQUE로 동시 실행도 1건이다.
- RUNNING·PENDING 모두 잠김(skip-locked)이나 예외로 건너뛴 후보는 `recovery_check_at`을 1분 뒤로 미뤄 앞 batch가 뒤 후보를 막지 않게 한다. 재검증에서 상태가 바뀐 후보는 미루지 않고 다음 실행에서 새 상태로 다시 평가한다.
- 발행이 계속 실패하는 동안의 PENDING은 실행된 적이 없어 30분 예산이 시작되지 않으므로 PROCESSING에 머문다. outbox 실패 로그로 관측한다.
