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
