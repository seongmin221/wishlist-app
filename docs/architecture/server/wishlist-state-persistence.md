# 상품 상태 저장 기반

> 구현 범위: B0 Task 3~6 · V8/V9 migration·claim·쓰기 guard · 공개 조회 연결은 B1

## 상품 상태와 출처

V8은 기존 상품 테이블에 review 상태, 수동 완료 시각, 현재 category/purpose와 출처, category 누락 사유, 이름/이미지 출처, 사용자 덮어쓰기 필드, current generation을 추가한다. 목적 자원이 아직 없으므로 legacy predicted purpose는 진단 값으로 보존하고 현재 purpose로 승격하지 않는다.

analysis/review/lifecycle와 값 출처는 enum 허용 목록으로 제한하고 version/current generation은 양수여야 한다. category가 있으면 출처는 AI/USER이고 누락 사유는 null이어야 한다. AI purpose에는 ID가 필요하며 USER+null은 사용자의 명시적 목적 해제다. 덮어쓰기 필드는 NAME/BRAND/IMAGE/CATEGORY/PURPOSE만 허용한다. category/purpose/media 테이블과 owner FK는 해당 자원 구현 묶음에서 추가한다.

신규 생성은 같은 transaction에서 current generation 1, analysis job generation 1, outbox를 저장한다. 분석 전 category 누락 사유는 EXTRACTION_UNRESOLVED다.

## V7 데이터 업그레이드

- current generation은 기존 job generation의 최댓값이며 job이 없으면 1이다.
- READY의 predicted category를 현재 category와 AI 출처로 옮긴다. 이름과 category가 있으면 review PENDING, 나머지는 NOT_REQUIRED다.
- category가 없으면 AI_ABSTAINED를 유지하고 AI_UNUSABLE_RESPONSE/AI_INVALID_CANDIDATE/AI_USAGE_OUT_OF_RANGE는 AI_RESPONSE_UNUSABLE로 묶으며 나머지는 EXTRACTION_UNRESOLVED다.
- 기존 이름/이미지가 있으면 출처를 AI로 기록한다.
- 원본 상품 ID·owner·key·상태·version·시각·metadata·예측 진단, job과 outbox, 예산 reservation/window/alert는 보존한다.

## 분석 실행 필드

V9는 analysis job에 execution token, lease 만료 시각, claim 당시 상품 version을 추가한다. job generation은 양수여야 하며 기존 `(wishlist_item_id, generation)` uniqueness를 유지한다. `(stage, lease_until, id)` index는 만료 실행 복구 조회를 준비한다.

legacy GENERAL_RUNNING/BROWSER_RUNNING은 execution token과 claimed version을 null로 두고 lease를 migration 시각으로 만료시킨다. 기존 실행을 유효한 claim으로 취급하지 않는다. 실제 claim과 쓰기 guard는 Task 4에서 구현했고 중간 결과와 Worker의 claim 전달은 Task 5에서 연결했고 최종 상태 정책은 Task 6, 만료 실행 복구는 Task 7에서 진행한다.

## 사용자 범위 조회

`WishlistItemStateRepository.findOwned(ownerId, itemId)`는 owner와 item ID를 SQL에서 함께 조건으로 사용한다. 다른 사용자의 항목과 없는 항목은 모두 null이다. 내부에서는 ARCHIVED/DELETED도 읽을 수 있으며 공개 상세 조회 정책은 B1에서 적용한다. 읽은 row를 상태 enum, 출처, nullable 시각과 override 집합으로 매핑한다.

현재 Worker의 상태 반영 경로에 새 필드를 모두 연결한 단계는 아니다. B0 나머지 Task를 통과한 뒤 공개 DTO 조회에 연결한다.


## 원자 claim과 쓰기 보호

`AnalysisClaimRepository.claim(jobId, generation, lane)`는 먼저 job의 item ID를 잠금 없이 찾고, transaction에서 item→job 순서로 `FOR UPDATE`한 뒤 관계와 상태를 다시 검증한다. 현재 generation의 ACTIVE·PROCESSING·수동 미완료 상품과 해당 lane의 PENDING job만 실행 가능하다. BROWSER는 기존 fallback 플래그도 필요하다. owner는 요청에서 받지 않고 잠근 상품 row에서 얻는다.

유효한 claim은 새 UUID token, DB 시각 기준 120초 lease, 현재 item version을 저장하고 해당 lane의 attempt만 한 번 증가시킨다. 다른 요청은 이미 RUNNING인 job을 Ignored로 처리하므로 중복 attempt가 없다. lane별 3회 또는 첫 시도로부터 30분을 소진하면 Exhausted를 반환하고 같은 잠금 아래 현재 상품을 FAILED_RETRYABLE로 바꾸며 version을 한 번 증가시킨다. 오래된 generation이나 비활성·수동 완료 상품에는 이 소진 처리를 적용하지 않는다.

claim을 다시 발급할 때 assignment/purpose/failure 임시 결과를 지운다. GENERAL은 임시 metadata도 지우고 BROWSER는 일반 추출 metadata를 유지한다. generation의 candidate snapshot은 두 lane 모두 유지한다.

`AnalysisWriteGuard.lockCurrent(connection, claim)`는 명시적 transaction을 요구한다. item→job 잠금 뒤 owner·item 관계·generation·token·lane RUNNING·상품 상태·수동 완료·현재 version과 claimed version을 검증한다. lease는 두 잠금을 얻은 뒤 읽은 DB `clock_timestamp()`와 비교한다. 잠금 대기 전에 시각을 읽으면 대기 중 만료를 놓칠 수 있기 때문이다.

false 결과는 현재 실행을 취소하거나 token을 수정하지 않는다. true/false 모두 transaction commit/rollback과 잠금 해제는 호출자 책임이다. guard 뒤의 DB 쓰기는 같은 transaction에서 수행하며 외부 네트워크 호출 중에는 connection이나 잠금을 유지하지 않는다. 중간 쓰기 경로와 기존 Worker 최종 transaction 입구에는 Task 5에서 적용했다. Task 6의 AnalysisResultRepository는 최종 상태·출처 정책과 token 해제를 같은 guard transaction에 둔다.


## 중간 결과와 외부 호출 경계

Task 5의 AnalysisPendingResultRepository는 guard와 source URL 읽기, 임시 metadata/assignment/failure 저장, 후보 snapshot 읽기·최초 저장을 같은 transaction에 둔다. 후보 공급은 DB/local 조회이며 원격 호출을 하지 않는다. 최초 snapshot의 ID·label은 재claim에서도 그대로 유지하고 GENERAL 재추출의 임시 metadata와 구분한다.

추출/rendering/AI는 AnalysisClaim을 전달한다. 외부 호출 동안 DB connection이나 잠금을 유지하지 않으며, 응답 뒤의 저장은 새 guard transaction을 거친다. invalid claim은 ProcessingOutcome.Stale이며 browser의 nullable rendering은 null을 반환한다. Worker는 실제 claim을 발급하고 final transaction 입구에서 다시 guard를 확인해 stale partial/fallback도 반영하지 않는다.

예산 reserve는 guard 후 window를 DAILY→MONTHLY 순서로 잠그고 새 reservation을 생성하기 전에 lease를 재검증한다. request ID 재사용도 유효 claim과 같은 job/generation을 요구한다. reserve가 Stale이면 gateway를 호출하지 않는다. 예약 후 gateway 직전에 다시 확인하고, 이 검사 뒤 무효화와의 좁은 race는 응답 저장에서 차단한다.

예약·정산은 서로 다른 책임이다. 실제 gateway의 유효 usage는 실행 권한을 잃어도 SETTLED로 기록한다. usage가 없는 non-retry 응답은 maximum settlement, usage 없는 retry/호출 실패는 IN_FLIGHT lease reconciliation을 유지한다. gateway를 보내기 전 무효화된 RESERVED는 reconciliation에서 해제한다. 정산은 reservation/window만 잠그고 item/job를 뒤늦게 잠그지 않는다.


## 최종 결과와 재시도

일반·browser Worker는 AnalysisResultRepository.finish를 공유한다. item→job 잠금과 guard 뒤에 최종 item, job stage, execution token 해제를 같은 transaction으로 commit한다. stale 결과는 item/job/outbox를 바꾸지 않고 ACK한다. 최종 성공·부분·실패는 item version을 한 번 올린다. 현재 실행의 재시도와 browser fallback은 item version을 유지한다.

READY에는 사용 가능한 현재 category가 필요하다. Complete여도 현재 category가 없으면 PARTIAL과 AI_INVALID_CANDIDATE 진단을 남겨 RUNNING에 갇히지 않게 한다. 현재 category가 있으면 누락 사유는 null이다. 새 분류의 predicted 값은 진단으로 저장하고 현재 값과 구분한다.

NAME/IMAGE/CATEGORY/PURPOSE는 USER 출처 또는 userOverrideFields 중 하나만 있어도 보호하며 null도 사용자 선택으로 보존한다. PURPOSE의 USER+null은 명시적 해제다. 성공은 보호되지 않은 새 metadata를 반영하고, 부분·실패는 기존 nonnull metadata를 우선 보존하며 빈 필드만 채운다. 보호된 category의 기존 누락 사유도 유지한다.

CONFIRMED/DEFERRED는 유지한다. 사용 가능한 이름·category가 있고 실제 미확정 AI category 또는 nonnull AI purpose 연결이 있으면 PENDING이다. USER category를 유지하고 목적만 AI로 연결해도 검토 대상이다. 연결되지 않은 AI 예측 진단만으로 PENDING을 만들지 않는다.

일반 NeedsBrowser는 BROWSER_PENDING, fallback flag, token 해제, browser outbox 1건을 원자적으로 저장한다. 양쪽 Worker의 infrastructure exception은 Retryable로 처리한다. 현재 실행이 한도 안이면 lane PENDING으로 token을 해제하고 RETRY/HTTP 503을 반환한다. 한도 소진은 FAILED_RETRYABLE로 최종 반영한다. stale·완료는 ACK/HTTP 204다. CancellationException은 다시 던지고 남은 RUNNING lease의 복구는 Task 7에서 처리한다.
