# 상품 상태 저장 기반

> 구현 범위: B0 Task 3~4 · V8/V9 migration·claim·쓰기 guard · 공개 조회 연결은 B1

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

legacy GENERAL_RUNNING/BROWSER_RUNNING은 execution token과 claimed version을 null로 두고 lease를 migration 시각으로 만료시킨다. 기존 실행을 유효한 claim으로 취급하지 않는다. 실제 claim과 쓰기 guard는 Task 4에서 구현했고 processor/Worker 연결은 Task 5~6, 만료 실행 복구는 Task 7에서 진행한다.

## 사용자 범위 조회

`WishlistItemStateRepository.findOwned(ownerId, itemId)`는 owner와 item ID를 SQL에서 함께 조건으로 사용한다. 다른 사용자의 항목과 없는 항목은 모두 null이다. 내부에서는 ARCHIVED/DELETED도 읽을 수 있으며 공개 상세 조회 정책은 B1에서 적용한다. 읽은 row를 상태 enum, 출처, nullable 시각과 override 집합으로 매핑한다.

현재 Worker의 상태 반영 경로에 새 필드를 모두 연결한 단계는 아니다. B0 나머지 Task를 통과한 뒤 공개 DTO 조회에 연결한다.


## 원자 claim과 쓰기 보호

`AnalysisClaimRepository.claim(jobId, generation, lane)`는 먼저 job의 item ID를 잠금 없이 찾고, transaction에서 item→job 순서로 `FOR UPDATE`한 뒤 관계와 상태를 다시 검증한다. 현재 generation의 ACTIVE·PROCESSING·수동 미완료 상품과 해당 lane의 PENDING job만 실행 가능하다. BROWSER는 기존 fallback 플래그도 필요하다. owner는 요청에서 받지 않고 잠근 상품 row에서 얻는다.

유효한 claim은 새 UUID token, DB 시각 기준 120초 lease, 현재 item version을 저장하고 해당 lane의 attempt만 한 번 증가시킨다. 다른 요청은 이미 RUNNING인 job을 Ignored로 처리하므로 중복 attempt가 없다. lane별 3회 또는 첫 시도로부터 30분을 소진하면 Exhausted를 반환하고 같은 잠금 아래 현재 상품을 FAILED_RETRYABLE로 바꾸며 version을 한 번 증가시킨다. 오래된 generation이나 비활성·수동 완료 상품에는 이 소진 처리를 적용하지 않는다.

claim을 다시 발급할 때 assignment/purpose/failure 임시 결과를 지운다. GENERAL은 임시 metadata도 지우고 BROWSER는 일반 추출 metadata를 유지한다. generation의 candidate snapshot은 두 lane 모두 유지한다.

`AnalysisWriteGuard.lockCurrent(connection, claim)`는 명시적 transaction을 요구한다. item→job 잠금 뒤 owner·item 관계·generation·token·lane RUNNING·상품 상태·수동 완료·현재 version과 claimed version을 검증한다. lease는 두 잠금을 얻은 뒤 읽은 DB `clock_timestamp()`와 비교한다. 잠금 대기 전에 시각을 읽으면 대기 중 만료를 놓칠 수 있기 때문이다.

false 결과는 현재 실행을 취소하거나 token을 수정하지 않는다. true/false 모두 transaction commit/rollback과 잠금 해제는 호출자 책임이다. guard 뒤의 DB 쓰기는 같은 transaction에서 수행하며 외부 네트워크 호출 중에는 connection이나 잠금을 유지하지 않는다. 실제 중간·최종 쓰기 경로에 이 guard를 적용하는 작업은 Task 5~6이다.
