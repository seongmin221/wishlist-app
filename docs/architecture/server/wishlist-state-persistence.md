# 상품 상태 저장 기반

> 구현 범위: B0 Task 3~7 · V8/V9 migration·claim·쓰기 guard · 공개 조회 연결은 B1

## 상품 상태와 출처

V8은 기존 상품 테이블에 review 상태, 수동 완료 시각, 현재 category/purpose와 출처, category 누락 사유, 이름/이미지 출처, 사용자 덮어쓰기 필드, current generation을 추가한다. 목적 자원이 아직 없으므로 legacy predicted purpose는 진단 값으로 보존하고 현재 purpose로 승격하지 않는다.

analysis/review/lifecycle와 값 출처는 enum 허용 목록으로 제한하고 version/current generation은 양수여야 한다. category가 있으면 출처는 AI/USER이고 누락 사유는 null이어야 한다. AI purpose에는 ID가 필요하며 USER+null은 사용자의 명시적 목적 해제다. 덮어쓰기 필드는 NAME/BRAND/IMAGE/CATEGORY/PURPOSE만 허용한다. V11은 custom category 테이블과 (owner_id,custom_category_id) 복합 FK를 추가했다. 공용 category_id와 custom_category_id는 동시 지정할 수 없으며 동일한 출처·누락 사유 CHECK를 공유한다. purpose/media의 owner FK는 후속 자원 구현에서 추가한다.

신규 생성은 같은 transaction에서 current generation 1, analysis job generation 1, outbox를 저장한다. 분석 전 category 누락 사유는 EXTRACTION_UNRESOLVED다.

## V7 데이터 업그레이드

- current generation은 기존 job generation의 최댓값이며 job이 없으면 1이다.
- READY의 predicted category를 현재 category와 AI 출처로 옮긴다. 이름과 category가 있으면 review PENDING, 나머지는 NOT_REQUIRED다.
- category가 없으면 AI_ABSTAINED를 유지하고 AI_UNUSABLE_RESPONSE/AI_INVALID_CANDIDATE/AI_USAGE_OUT_OF_RANGE는 AI_RESPONSE_UNUSABLE로 묶으며 나머지는 EXTRACTION_UNRESOLVED다.
- 기존 이름/이미지가 있으면 출처를 AI로 기록한다.
- 원본 상품 ID·owner·key·상태·version·시각·metadata·예측 진단, job과 outbox, 예산 reservation/window/alert는 보존한다.

## 분석 실행 필드

V9는 analysis job에 execution token, lease 만료 시각, claim 당시 상품 version을 추가한다. job generation은 양수여야 하며 기존 `(wishlist_item_id, generation)` uniqueness를 유지한다. `(stage, lease_until, id)` index는 만료 실행 복구 조회를 준비한다.

legacy GENERAL_RUNNING/BROWSER_RUNNING은 execution token과 claimed version을 null로 두고 lease를 migration 시각으로 만료시킨다. 기존 실행을 유효한 claim으로 취급하지 않는다. 실제 claim과 쓰기 guard는 Task 4에서 구현했고 중간 결과와 Worker의 claim 전달은 Task 5에서 연결했고 최종 상태 정책은 Task 6, 만료 실행 복구는 Task 7에서 구현했다.

## 사용자 범위 조회

`WishlistItemRepository.findOwned(ownerId, itemId)`는 owner와 item ID를 SQL에서 함께 조건으로 사용한다. 다른 사용자의 항목과 없는 항목은 모두 null이다. 전체 상품 projection의 `.storedState`에 상태 enum·현재 generation·출처·nullable 시각·override 집합을 담는다. 내부에서는 ARCHIVED/DELETED도 읽을 수 있으며 공개 GET의 DELETED 404는 `GetWishlistItemService` 경계에서 적용한다.

B0 Task 4~7에서 중간·최종 반영과 복구에 현재 상태·출처·실행 보호를 연결했다. B1 repository는 생성 SQL과 owner/key 조회 및 원본 URL·metadata·시각을 포함한 공통 projection을 담당한다. 상태만 반환하는 미사용 API는 제거했고 HTTP presenter는 [B1 조회 계약](wishlist-item-read-api.md)의 `app.http.WishlistItemViewMapper`다.


## 원자 claim과 쓰기 보호

`AnalysisClaimRepository.claim(jobId, generation, lane)`는 먼저 job의 item ID를 잠금 없이 찾고, transaction에서 owner→item→job 순서로 `FOR UPDATE`한 뒤 관계와 상태를 다시 검증한다. 현재 generation의 ACTIVE·PROCESSING·수동 미완료 상품과 해당 lane의 PENDING job만 실행 가능하다. BROWSER는 기존 fallback 플래그도 필요하다. owner는 요청에서 받지 않고 잠근 상품 row에서 얻는다. 같은 job/generation/lane의 PENDING이 삭제된 상품에 속하면 owner→item→job 잠금 안에서 CANCELLED로 바꾸고 분석/추가 outbox 없이 ACK한다.

유효한 claim은 새 UUID token, DB 시각 기준 120초 lease, 현재 item version을 저장하고 해당 lane의 attempt만 한 번 증가시킨다. 다른 요청은 이미 RUNNING인 job을 Ignored로 처리하므로 중복 attempt가 없다. lane별 3회 또는 첫 시도로부터 30분을 소진하면 Exhausted를 반환하고 같은 잠금 아래 현재 상품을 FAILED_RETRYABLE로 바꾸며 version을 한 번 증가시킨다. 오래된 generation이나 비활성·수동 완료 상품에는 이 소진 처리를 적용하지 않는다.

claim을 다시 발급할 때 assignment/purpose/failure 임시 결과를 지운다. GENERAL은 임시 metadata도 지우고 BROWSER는 일반 추출 metadata를 유지한다. generation의 candidate snapshot은 두 lane 모두 유지한다.

`AnalysisWriteGuard.lockCurrent(connection, claim)`는 명시적 transaction을 요구한다. owner→item→job 잠금 뒤 owner·item 관계·generation·token·lane RUNNING·상품 상태·수동 완료·현재 version과 claimed version을 검증한다. lease는 owner/item/job 잠금을 얻은 뒤 읽은 DB `clock_timestamp()`와 비교한다. 잠금 대기 전에 시각을 읽으면 대기 중 만료를 놓칠 수 있기 때문이다.

false 결과는 현재 실행을 취소하거나 token을 수정하지 않는다. true/false 모두 transaction commit/rollback과 잠금 해제는 호출자 책임이다. guard 뒤의 DB 쓰기는 같은 transaction에서 수행하며 외부 네트워크 호출 중에는 connection이나 잠금을 유지하지 않는다. 중간 쓰기 경로와 기존 Worker 최종 transaction 입구에는 Task 5에서 적용했다. Task 6의 AnalysisResultRepository는 최종 상태·출처 정책과 token 해제를 같은 guard transaction에 둔다.


## 중간 결과와 외부 호출 경계

Task 5의 AnalysisPendingResultRepository는 guard와 source URL 읽기, 임시 metadata/assignment/failure 저장, 후보 snapshot 읽기·최초 저장을 같은 transaction에 둔다. 후보 공급은 DB/local 조회이며 원격 호출을 하지 않는다. 최초 snapshot의 ID·label은 재claim에서도 그대로 유지하고 GENERAL 재추출의 임시 metadata와 구분한다.

추출/rendering/AI는 AnalysisClaim을 전달한다. 외부 호출 동안 DB connection이나 잠금을 유지하지 않으며, 응답 뒤의 저장은 새 guard transaction을 거친다. 저장 guard의 invalid claim은 ProcessingOutcome.Stale이다. DB 쓰기가 없는 추출 Partial/NeedsBrowser/Terminal은 잠정 결과로 반환하고 Worker의 final transaction에서 검증한다. BrowserRenderProcessor는 source URL guard 후 rendering 데이터만 반환하며, BrowserWorkerService가 metadata를 한 번 guarded 저장한 뒤 AI를 호출한다. 렌더 도중 token이 바뀌면 이 저장에서 차단해 분류를 호출하지 않는다.

예산 예약·IN_FLIGHT lease·기본 만료 복구는 DB 시각을 사용한다. 예약 생성 시각은 window 선택에 사용한 DB 시각으로 저장해 정산의 일/월 window와 일치시킨다. 예산 reserve는 guard 후 window를 DAILY→MONTHLY 순서로 잠그고 새 reservation을 생성하기 전에 lease를 재검증한다. request ID 재사용도 유효 claim과 같은 job/generation을 요구한다. reserve가 Stale이면 gateway를 호출하지 않는다. 예약 후 gateway 직전에 다시 확인하고, 이 검사 뒤 무효화와의 좁은 race는 응답 저장에서 차단한다.

예약·정산은 서로 다른 책임이다. 토큰 검사와 본문·헤더 구성은 RESERVED에서 수행하고 전송 직전 callback에서 IN_FLIGHT로 전환한다. **HTTP timeout은 callback의 pool/DB 대기와 commit이 끝난 뒤 남은 처리 예산으로 계산한다.** 그때 마감이면 client.send에 들어가지 않고 LlmRequestNotSent를 반환 경로에 전달해 IN_FLIGHT도 RELEASED로 해제한다. 전송 전 토큰 검사 실패·stale·예외로 끝난 RESERVED 역시 즉시 해제한다. 프로세스 중단으로 해제가 실행되지 않으면 RESERVED 만료 복구가 해제한다. markInFlight commit 결과나 client.send 진입 뒤 전송 상태를 확신할 수 없는 오류는 보수적 IN_FLIGHT 만료 정산을 유지한다.

해제 실패는 원래 예외의 suppressed로 보존한다. 원래 예외가 없을 때만 해제 예외를 단독으로 전달한다. callback 없이 usage 또는 Assigned/Abstained 응답을 반환한 gateway는 계약 위반이다. 최대 비용으로 정산하고 결과 저장 전에 예외로 실패시켜 무료 분류 성공을 막는다.

실제 전송한 gateway의 유효 usage는 실행 권한을 잃어도 SETTLED로 기록한다. 전송 후 usage가 없는 non-retry 응답은 maximum settlement, usage 없는 retry/호출 실패는 IN_FLIGHT lease reconciliation을 유지한다. 정산은 reservation/window만 잠그고 item/job를 뒤늦게 잠그지 않는다. 시각 관련 테스트도 DB clock_timestamp로 lease를 만료시킨 뒤 기본 복구 경로를 호출하며 JVM 시각과 섞지 않는다.


## 최종 결과와 재시도

일반·browser Worker는 AnalysisResultRepository.finish를 공유한다. owner→item→job 잠금 뒤 owner·job 관계·generation·lane·token·claimed version으로 실행 identity를 검증하고 현재 상품 상태와 lease를 확인한다. 다른 실행·generation·삭제·보관·수동 완료·만료 lease의 결과는 item/job/outbox를 바꾸지 않고 ACK한다. 예외적으로 같은 실행 identity와 현재 ACTIVE·PROCESSING·수동 미완료 상품의 version만 달라졌다면 Stale outcome에서도 job CANCELLED·상품 FAILED_RETRYABLE·version +1을 원자 저장한다. 사용자 필드·진단·outbox를 보존하며 반복 finish는 추가 변경하지 않는다. 이 경로는 maintenance가 없는 B5 이전에도 실행된다. 최종 성공·부분·실패는 item version을 한 번 올린다. 현재 실행의 재시도와 browser fallback은 item version을 유지한다.

READY에는 사용 가능한 현재 category가 필요하다. Complete여도 현재 category가 없으면 PARTIAL과 AI_INVALID_CANDIDATE 진단을 남겨 RUNNING에 갇히지 않게 한다. 현재 category가 있으면 누락 사유는 null이다. 새 분류의 predicted 값은 진단으로 저장하고 현재 값과 구분한다.

단, 유효 AI category가 반환됐지만 사용자 보호 CATEGORY가 비어 있어 적용하지 못한 경우에는 PARTIAL을 유지하되 AI_INVALID_CANDIDATE를 기록하지 않는다. 사용자 categoryMissingReason을 유지하고 유효 predicted 값은 진단으로 저장한다. 사용자 선택 때문에 비어 있는 값을 AI 실패로 설명하지 않는다.

NAME/IMAGE/CATEGORY/PURPOSE는 USER 출처 또는 userOverrideFields 중 하나만 있어도 보호하며 null도 사용자 선택으로 보존한다. PURPOSE의 USER+null은 명시적 해제다. 성공은 보호되지 않은 새 metadata를 반영하고, 부분·실패는 기존 nonnull metadata를 우선 보존하며 빈 필드만 채운다. 보호된 category의 기존 누락 사유도 유지한다.

CONFIRMED/DEFERRED는 유지한다. 사용 가능한 이름·category가 있고 실제 미확정 AI category 또는 nonnull AI purpose 연결이 있으면 PENDING이다. USER category를 유지하고 목적만 AI로 연결해도 검토 대상이다. 연결되지 않은 AI 예측 진단만으로 PENDING을 만들지 않는다.

일반 NeedsBrowser는 BROWSER_PENDING, fallback flag, token 해제, browser outbox 1건을 원자적으로 저장한다. 양쪽 Worker의 infrastructure exception은 Retryable로 처리한다. 현재 실행이 한도 안이면 lane PENDING·token 해제·새 retry outbox를 같은 transaction에 저장하고 RETRY/HTTP 503을 반환한다. task 이름에 실행 token을 넣어 이전 task와 구분한다. 원래 task가 delivery 마감/중복 ACK로 사라져도 발행할 event가 남는다. 한도 소진은 FAILED_RETRYABLE로 최종 반영한다. stale·완료는 ACK/HTTP 204다. CancellationException은 다시 던지고 남은 RUNNING lease의 복구는 Task 7에서 처리한다.


## 만료 실행 복구

AnalysisJobReconciler.reconcileExpired는 updated_at 대신 DB clock_timestamp와 lease_until을 비교한다. RUNNING 후보를 기본 100개(설정 1~1000)까지 잠금 없이 발견한 뒤 각 후보를 별도 transaction에서 owner→item→job 순서로 SKIP LOCKED한다. owner/item/job 잠금을 얻은 뒤 발견 당시의 관계·generation·stage·token·lease·claimed version을 재검증하고 DB 시각으로 만료를 다시 확인한다. 실행이 바뀌거나 행이 사용 중이면 이번 스캔에서 건너뛴다. 후보 하나의 오류는 job ID·예외 타입을 기록하고 다음 후보로 진행하며 취소/interrupt는 재전파한다. 반환값은 commit한 복구·취소·한도 실패 전이 수다.

ACTIVE·PROCESSING·현재 generation·수동 미완료·claimed version 일치인 실행만 재시도하거나 한도 실패로 반영한다. 무효 실행은 CANCELLED로 token/lease/claimed version을 해제한다. 상품이 여전히 현재 generation의 ACTIVE·PROCESSING·수동 미완료라면 version 불일치 또는 browser fallback 불일치로 실행을 취소할 때 상품도 FAILED_RETRYABLE로 바꾸고 version을 한 번 증가시킨다. 편집된 필드와 outbox는 보존해 PROCESSING 정체와 옛 결과 덮어쓰기를 막는다. 삭제·보관·수동 완료·다른 generation·이미 종료된 상품은 변경하지 않는다. V9가 만료시킨 legacy RUNNING은 token/claimed version이 null이므로 기존 version 검증을 우회하고 재claim에서 새 identity를 받는다. identity와 lease가 모두 null인 중단된 legacy도 복구한다.

한도 내 복구는 lane PENDING, 실행 identity 해제, recovery outbox 1건을 한 transaction으로 저장한다. task_name에는 generation·시도 횟수·옛 token(legacy는 새 UUID)을 넣는다. 복구에서는 attempt나 item version을 올리지 않으며 다음 claim에서 해당 lane attempt가 증가한다. 재claim 이후 옛 token의 중간/최종 쓰기는 모두 무효다.

lane별 3회 또는 첫 시도에서 30분을 소진하면 job FAILED와 identity 해제, 현재 상품 FAILED_RETRYABLE 및 version +1을 함께 저장한다. 다음 스캔은 이미 전이된 job을 처리하지 않는다. 정상 finish가 먼저 commit하면 복구는 건너뛰고, 복구가 먼저 commit하면 옛 finish는 stale ACK다. 이 경로는 AI budget reservation/window를 수정하지 않으며 실제 사용량 정산은 별도 책임으로 유지한다.

claim·finish·reconciler의 lane별 재시도 한도는 AnalysisJobTransitions의 hasRetryBudget을 공유한다. job stage/실행 identity 해제와 잠근 상품의 FAILED_RETRYABLE 갱신도 공용 helper에 둔다. B5의 generation 전체 한도를 추가할 때 이 lane별 규칙과 구분한다.

오래된 PENDING·queue retry 소진·미발행 fallback의 실제 발행/복구는 [B5 설계](analysis-pending-recovery.md)에 따라 연결한다.

## B2 category 후보 보호

V11 app_users를 잠금 기준으로 사용한다. 기존 owner는 backfill하고 신규 owner는 공통 helper가 lazy insert 뒤 FOR UPDATE한다. B1 생성과 Worker claim/staging/finish/recovery는 owner를 먼저 잠근다. custom snapshot v2는 owner·version·구조화된 이름/설명/예시를 저장하고 동일 connection에서 공급한다. 재사용과 최종 반영에서 stale를 검사하며 잘못된 구조는 유료 호출 없이 최종 replacement로 넘긴다. replacement는 기존 시도 횟수·최초 시각을 승계한다. CONFIRMED/DEFERRED의 기존 category·purpose와 USER/override 연결은 유효한 새 AI 결과에도 유지한다. 기존 B0 계약의 null·UNASSIGNED 목적 슬롯 신규 AI 연결은 유지한다. 세부 사항은 [B2 category 계약](category-management-api.md)을 따른다.
