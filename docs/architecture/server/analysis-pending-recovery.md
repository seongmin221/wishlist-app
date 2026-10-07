# B5 PENDING 작업 복구 설계

> 2026-10-05 · 리뷰 반영 · B5 구현 대상, 현재 Scheduler/runtime 미구현

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

재예약 식별자와 PENDING 발견 index는 B5 migration에서 추가한다. 구체적인 정체 시간·backoff 설정은 queue 정책과 함께 확정하고 설정 경계 테스트를 둔다. 후보별 transaction/실패 격리, batch 상한, 다음 scan 진행과 관측 지표를 적용한다. 현재 RUNNING reconciler는 매번 정렬의 첫 batch를 읽으므로 그 batch 전체가 계속 실패/잠기면 뒤 후보가 검사되지 않을 수 있다. B5에서는 여러 Scheduler 인스턴스에도 유지되는 순환 cursor 또는 다음 검사 시각을 두고 앞 batch 전체 실패/잠금 후 뒤 후보의 진행을 검증한다. 단순히 job 먼저 잠그는 방식으로 owner→item→job 순서를 깨지 않는다.

## 생성 직후 발행과 backlog

B0 API의 `dispatchPending(1)`은 방금 생성한 event가 아닌 가장 오래된 event를 골랐다. **B1에서 생성 transaction이 반환한 event ID를 `dispatchEvent(id)`로 발행하도록 연결했다.** Scheduler의 batch 발행과 claim/lease 규칙은 공유한다. 지정 발행 실패/종료 거부도 저장 응답과 durable outbox를 보존한다. B1 회귀에는 오래된 retry backlog가 있어도 새 상품의 event만 발행하는 경우를 포함한다. maintenance runtime과 오래된 PENDING 복구는 B5에서 연결한다. Worker도 retry/fallback 후 제한된 발행을 시도할 수 있지만 진행 보장은 Scheduler가 담당한다.

Retryable의 HTTP 503 재전달과 retry outbox는 같은 job에 전달 기회를 중복 제공한다. 원자 claim이 같은 실행의 중복 분석을 막지만, 두 task가 서로 다른 PENDING 시점에 도착하면 다음 attempt를 소비할 수 있다. 현재 lane별 3회·30분 한도를 유지하며 B5의 generation 전체 한도와 발행 관측에서 이 경로를 포함한다. ACK만 반환하도록 바꾸면 maintenance가 아직 없는 현재 runtime에서 진행 보장이 약해지므로 이번 보완에서는 503을 유지한다.

## 필수 회귀

- 105초 delivery 마감 후 중복 ACK→원래 실행 Retryable인 경우에도 durable outbox와 후속 실행 유지.
- 최대 queue retry 소진 뒤 PENDING 재예약; 미발행 browser fallback은 기존 outbox 발행.
- 살아 있는 task/backlog와 task 조회 장애는 중복 재예약·상품 실패를 만들지 않음.
- 동시 Scheduler와 claim/finish/edit/delete 경합에서 재예약 1건, 옛 발견 무효, generation 전체 3회·30분 예산 보존.
- 삭제/보관/수동 완료 상품은 job만 취소; 예산 정산·사용자 값·item version 보호.
- B1에서 연결한 신규 event 지정 발행을 유지하고, backlog 복구·RPC 제한 시간·후보 1개 실패 후 다음 후보 진행을 검증한다. 생성의 즉시 발행은 [B1 조회 계약](wishlist-item-read-api.md)을 따른다.
