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
