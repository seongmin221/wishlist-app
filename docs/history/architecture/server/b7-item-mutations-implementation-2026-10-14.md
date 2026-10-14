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
