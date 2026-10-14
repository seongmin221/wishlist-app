# B7 상품 사용자 변경 구현 이력

> 2026-10-14 · 진행 중 · [spec](../../../superpowers/specs/2026-10-14-b7-item-mutations-design.md) · [계획](../../../superpowers/plans/2026-10-14-b7-item-mutations.md) · [결정](../../product-planning/mvp/decisions/b7-item-mutation-policy-2026-10-14.md)

## 실행 기록

회귀 전 시각 확인: host `date -u`와 `postgres:16-alpine` container `date -u`가 2초 안에서 일치(2026-10-14 06:20 UTC).

### Task 1: V19 migration

- RED(`DatabaseMigrationTest`, 11개 중 2개 실패): `all Flyway migrations…`는 버전 목록에 19가 없어 assertion 실패, `V19 upgrade…`는 `wishlist_item_id` 컬럼이 없어 SQL 오류.
- GREEN: V19와 `ReceiptTarget.WISHLIST_ITEM` 추가 뒤 11개 통과.

### Task 2: 입력 파서와 변경 계산

- RED: `ItemChangePlanTest`·`WishlistItemMutationRequestParserTest`는 `ItemChanges`·`ItemParseResult` 등 신규 symbol이 없어 compile 실패(test compile 오류 198개).
- GREEN: `ItemChanges.kt`(변경 계산·`ReviewAfterEdit`·`ItemTextRules`)와 `WishlistItemMutationRequestParser.kt` 뒤 11개·6개 통과.
- 계획 대비 결정: 구조 오류(object 아님·알 수 없는 key·PATCH 편집 필드 없음)는 B3 파서처럼 `fields`를 비운다. 값 오류만 필드 이름을 담는다.

### Task 3: 공통 잠금·참조 검증과 ITEM-04

- RED: 테스트를 먼저 쓴 뒤 구현 파일 두 개(`ItemMutationRepository`·`ItemMutationService`)를 작업 트리에서 빼고 test compile을 실행해 `ItemMutationService`·`ItemMutationException` 미정의 compile 실패를 확인했다. 구현을 테스트와 같은 단계에 써 두었기 때문에 RED를 이 방식으로 확인했다.
- GREEN: `ItemMutationServiceTest` 7개 통과(실제 PostgreSQL). no-op·한 번의 version 증가, 목적 membership/활동, 사진 교체·해제의 `unreferenced_since`, 오류 순서(stale version이 ARCHIVED 목적·PROCESSING보다 먼저), 변경된 텍스트·참조만 검사, rollback, 삭제된 category 재지정, PARTIAL/FAILED_RETRYABLE PATCH 뒤 수동 완료·재분석 허용 유지, 결정 3의 review 전이.
- 구현 메모: 목적은 lifecycle과 관계없이 owner 범위에서 ID순 `for update`로만 잠그고, 사용 가능 판정은 상품 잠금과 version·상태 확인 뒤에 한다. media는 상품 잠금 뒤 ID순 `for update`, 다른 상품 참조는 별도 문장으로 확인한다.

### Task 4·5: ITEM-07 직접 보완·ITEM-08 검토 결정

- 두 명령은 Task 3의 공통 `mutate` 경로를 그대로 쓰므로 진입 메서드(`completeManually`·`decideReview`)를 잠시 빼고 test compile을 실행해 미정의 compile 실패 RED를 확인한 뒤 되돌렸다. 계획의 두 커밋을 한 커밋으로 합쳤다.
- GREEN: `ItemMutationServiceTest` 10개 통과. PARTIAL·FAILED_RETRYABLE·FAILED_TERMINAL 수동 완료(분석 상태·failureCode·predicted 진단 유지, 완료 뒤 EDIT/DELETE만), 줄바꿈 섞인 230자 추출명을 그대로 보내도 완료, READY·PROCESSING·완료 반복 409 STATE. CONFIRM/DEFER, 같은 값은 AI 출처 유지, DEFERRED가 HOME-02 검토 그룹에서 빠지고 이후 이름만 편집해도 돌아오지 않음, PARTIAL·수동 완료·NOT_REQUIRED·PROCESSING·ARCHIVED 409 STATE.
- 계획의 "확정 상품에 Worker가 목적을 채우지 않음"은 B3의 `PurposeFinishTest.reviewed user and override purposes are never filled or replaced`가 CONFIRMED/DEFERRED + UNASSIGNED fixture로 이미 검증한다. B7 명령은 이 보호를 바꾸지 않으므로 같은 fixture 테스트를 복제하지 않았다.

### Task 6: ITEM-05 삭제와 deletionImpact

- RED 1: `ItemDeleteTest`가 `DeleteResult`·`delete` 미정의로 compile 실패.
- RED 2(snapshot): impact를 먼저 기존 autocommit 상세 조회 위에 붙여 실행했다. 두 경합 테스트가 예측대로 실패했다 — 이동만: 기대 candidateCount 3, 실제 2(N-1). 추가만: 기대 3, 실제 4(N+1).
- GREEN: `GetWishlistItemService.get`을 `inTransaction(readOnly = true)`(REPEATABLE READ)로 바꾼 뒤 `ItemDeleteTest` 6개 통과. 영향 테스트 `WishlistItemViewMapperTest` 3·`WishlistDetailRoutesTest` 8·`WishlistRoutesTest` 6·`CreateWishlistItemServiceTest` 10·`HomeRoutesTest` 1·`WishlistReadRoutesTest` 3·`MediaUploadFlowTest` 1·`AnalysisRuntimeFlowTest` 4·`MediaDisplayRoutesTest` 1·`HomeActionRoutesTest` 4·`PurposeRoutesTest` 4 통과.
- 기존 테스트 변경: `WishlistReadRoutesTest`는 상세 JSON과 카드 JSON이 같다고 검사했다. 상세에만 `deletionImpact`가 붙는 것이 B7 계약이므로, 상세에 key가 있고 카드에는 없음을 확인한 뒤 그 key를 뺀 나머지가 같은지 비교하도록 바꿨다.
- 구현 메모: impact는 `WishlistItem.deletionImpact`(기본 null)에 붙이고 DTO는 `@EncodeDefault(NEVER)`로 null일 때 key를 내지 않는다. 그래서 route 함수 시그니처를 바꾸지 않고 생성·replay·상세·변경 응답이 같은 mapper를 쓴다. 삭제는 진행 중 stage(`GENERAL_/BROWSER_` PENDING·RUNNING)만 `transitionAnalysisJob(CANCELLED)`로 바꾼다.

### Task 7: ITEM-06 재분석

- RED: `ReanalysisServiceTest`가 `ReanalysisService` 미정의로 compile 실패.
- 첫 실행 6개 중 2개 실패. 둘 다 테스트 코드 오류였다. job 목록 문자열의 시각에 `:`가 있어 `substringBeforeLast`가 잘못 잘랐고, test helper `claimJob`이 generation 1로 고정돼 있어 generation 2 job을 claim하지 못했다. 테스트를 고친 뒤 6개 통과. 구현은 바꾸지 않았다.
- GREEN 범위: 새 generation·job(attempt 0, first_attempt null)·outbox(`analysis-{job}-{gen}`)·receipt 원자 저장과 dispatch, replay에 job·outbox·dispatch 추가 없음, 다른 상품 같은 key 409, 삭제 뒤 replay 404, READY·PARTIAL·FAILED_TERMINAL·PROCESSING·수동 완료·ARCHIVED 409와 receipt 없음, owner별 60초 10건(11번째 429·Retry-After 1~60, replay 미집계, 다른 owner 무관, 창이 지나면 허용), dispatch 일반 예외는 성공 유지·`CancellationException`은 전파되고 commit된 attempt를 같은 key replay로 받음, 이전 generation 실행의 Complete/Partial/Retryable/Terminal finish와 claim 무시, USER 이름·category와 목적 USER+null이 새 generation 성공 뒤에도 유지.
- 구현 메모: `WishlistItemRepository.insertInitialAnalysis`를 generation 인자를 받는 `insertAnalysis`로 일반화했다(생성 경로는 generation 1로 같은 SQL). `CreateWishlistItemServiceTest` 10개로 회귀를 확인했다.

### Task 8: HTTP route와 조립

- RED: `WishlistItemMutationRoutesTest`가 `wishlistItemMutationRoutes`·`ItemMutationHandlers` 미정의로 compile 실패.
- GREEN: 6개 통과, `ApiRouteExposureTest` 3개 통과. 다섯 route의 성공 응답(사진 서명·`imageMediaId`·`deletionImpact`), 401→400→422→404→409 순서와 code·`currentVersion`·정렬된 `fields`, 구조 오류의 빈 details, 삭제 204 반복·다른 owner 404·ARCHIVED 409, 재분석 key 누락 400·body 422·replay header·key 재사용 409·한도 429와 `Retry-After`, 다섯 route 모두 IO dispatcher 실행과 취소 전파, `apiRoutes` 조립.
- 계획 대비: 새 route의 공개 노출은 `ApiRouteExposureTest`를 고치지 않고 새 테스트의 `public api registers the mutation routes`가 `apiRoutes` 조립으로 확인한다.

### Task 9: 경합·읽기 일관성·전체 흐름

- RED: 경합 테스트의 latch용 `ItemMutationService(afterMediaLock = …)` seam이 없어 compile 실패. seam 추가 뒤 `ItemMutationConcurrencyTest` 7개 통과: 같은 version PATCH·PATCH+review는 하나만 성공하고 나머지는 VERSION 409(`currentVersion` 4), 동시 삭제 두 개는 변경 한 번, 같은 key 재분석 두 개는 Started+Replayed와 job·outbox 하나씩, 재분석과 삭제 경합 뒤 진행 중 job 없음, 같은 새 media를 두 상품이 연결하면 하나만 성공, 사진 연결과 media 정리의 세 순서(편집이 먼저 잠금·정리가 먼저 lease·발견 뒤 lease 전 연결) 모두 "DELETING인데 참조"가 없음, 동시 목적 이동의 membershipVersion 일치.
- 읽기 일관성(`ItemMutationReadConsistencyTest` 2개)과 전체 흐름(`ItemMutationFlowTest` 1개)은 기존 조회 코드를 새 변경 결과로 확인하는 검증 테스트라 첫 실행에서 통과했다. 실패를 만들 신규 symbol이 없어 별도 RED는 없다.
  - ITEM-02 category·목적·미지정 목록과 totalCount, HOME-01 group count·preview, HOME-02, PUR-01 활동순·후보 수·미리보기, PUR-03 membershipVersion·후보 수, CAT-01 BROWSE count가 확인·편집·목적 해제·수동 완료·재분석·삭제 뒤 실제 데이터와 일치.
  - B4 anchor 복구 반복: ITEM-05로 anchor 삭제, ITEM-04 category 변경으로 anchor 범위 이동, HOME-02 검토 그룹 anchor 확정 뒤 다음 항목으로 복구(`anchorResolved=false`).
  - 전체 흐름: 비로그인 401 → 로그인 뒤 과거 `clientCreatedAt`으로 생성·응답 유실 재전송(같은 item, 공유 시각 유지) → AI 장애로 합산 3회 소진 FAILED_RETRYABLE → 재분석(replay 포함) → READY·검토 대기 → DEFER(검토 그룹 0) → 사진 연결·stale version 409·사진 해제 → 삭제 두 번 204 → 404, 홈 count 0. 기기 LocalSubmission 자체는 클라이언트 묶음의 범위다.
- 기존 테스트 변경: `MediaUploadFlowTest`는 B7 전이라 SQL로 사진을 연결·해제했다. 이를 ITEM-04 PATCH로 바꾸고, 해제 직후에는 정리되지 않고(24시간 유예) 유예를 25시간 앞당긴 뒤 정리됨을 확인한다. 통과.
