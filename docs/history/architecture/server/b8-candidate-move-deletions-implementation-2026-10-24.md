# B8 후보 이동과 영향 확인 삭제 구현 이력

> 2026-10-24 · 진행 중(B8a 완료) · [spec](../../../superpowers/specs/2026-10-24-b8-candidate-move-deletions-design.md) · [계획](../../../superpowers/plans/2026-10-24-b8-candidate-move-deletions.md) · [계약](../../../architecture/server/candidate-move-deletion-api.md) · [결정](../../product-planning/mvp/decisions/b8-candidate-move-deletion-policy-2026-10-24.md)

## 실행 기록

회귀 전 시각 확인: host `date -u`와 `postgres:16-alpine` container `date -u`가 같은 초(2026-10-24 11:41:21 UTC). 기준선은 인계 시점 28개(ItemMutationServiceTest 11·PurposeListAndEditTest 5·CategoryServiceTest 12) 통과다.

### Task 1: B8a 후보 조회·원자 이동(PUR-07·PUR-08, V20)

- RED: 서비스·HTTP·migration 테스트를 먼저 작성한 뒤 test compile을 실행해 `CandidateReadService`·`CandidateMoveService`·`CandidateFilter`·`MoveSelection`·`CandidateRequestParser`·`CandidateCursorCodec` 등 미정의 symbol로 346개 compile 오류를 확인했다.
- GREEN(실제 PostgreSQL): `CandidateReadTest` 3·`CandidateMoveTest` 4·`CandidateMoveConcurrencyTest` 3·`CandidateRequestParserTest` 5·`CandidateRoutesTest` 3·`DatabaseMigrationTest` 12 통과.
  - 조회: 현재 목적 소속·PROCESSING·ARCHIVED·DELETED·다른 owner 제외, READY/PARTIAL/FAILED_RETRYABLE/FAILED_TERMINAL/수동 완료/이름 없음 포함. 공개·custom·상위 filter, facet은 filter와 무관한 전체 pool(categoryId 문자열 오름차순·0건 생략·unassigned 0 포함). 같은 created_at 5건을 limit 2, 1→3→100, 100으로 순회해 SQL UUID DESC와 일치·누락/중복 없음·끝 cursor null.
  - 이동: 3개 원자 이동, item version +1, 이전/대상 membershipVersion 상품별 +1, 추가 목적만 활동 시각 변경, USER 출처, ReviewAfterEdit(AI category 남으면 PENDING 유지). version/PROCESSING/ARCHIVED/이미 대상 소속/소속 불일치/대상 version 충돌 각각에서 상품 전체 JSON·목적·receipt 불변. 충돌 두 개면 SQL UUID 순서의 첫 항목. 다른 owner/없는/DELETED item이 owner stale item과 섞이면 404·빈 details가 우선.
  - replay: 선택 순서를 바꿔도 같은 결과, 후속 이동 뒤에도 저장 당시 결과를 반환하고 다시 옮기지 않음. key 재사용 다섯 변형(선택·대상 version·item version·expectedPurposeId·대상) 모두 409.
  - 경합: 같은 key 동시 요청은 이동 1회+replay 1회. 이동이 상품 잠금을 쥔 동안 B7 PATCH가 실제로 lock 대기(`pg_stat_activity` 확인)한 뒤 VERSION 409(currentVersion 4). 이동과 PATCH 경주는 하나만 성공. 공유 상품이 있는 반대 방향 bulk 이동 6회는 교착 없이 하나만 성공하고 count·membershipVersion이 정확.
  - HTTP: 후보 원소가 상세 응답(`deletionImpact` 제외)과 key 순서까지 같은 `WishlistItemDto`, facet key는 요청 시에만 존재, 서명 이미지, cursor scope(목적·filter·owner) 400, 401→400→422→404→409와 details, replay header·동일 body, 두 route의 IO dispatcher와 취소 전파, `apiRoutes` 공개 조립.
- 테스트 강도 확인(변형 후 원복): 전체 존재 검사를 item 충돌 뒤로 옮기면 `missing deleted or foreign items…`가, facet pool에서 현재 목적 제외를 빼면 `filters narrow the page…`가 실패했다.
- 영향 회귀: `ItemMutation*`·`WishlistItemMutationRoutesTest`·`ApiRouteExposureTest` 통과(아래 커밋 전 실행).

## 계획 대비 결정

- 이동 body의 원소 오류(원소가 object가 아님·알 수 없는/누락된 원소 key·ID/version/expectedPurposeId 값)는 계약의 "원소의 필드 오류는 `items`"에 따라 `fields:["items"]`로 묶는다. 구조 오류로 비우는 것은 최상위 body(object 아님·최상위 key 불일치)뿐이다.
- 일괄 상품 잠금은 B7 `ItemMutationRepository.lockAll`(한 문장 `order by id for update`)로 추가했다. 기존 함수는 바꾸지 않았다.
- 이동 receipt는 `candidate_mutation_receipts`(V20)에 대상 목적 FK와 schema v1 결과 JSON을 둔다. 응답의 `replayed`는 저장하지 않는다.
- facet은 살아 있는 custom category 또는 공개 category에 연결된 항목만 센다. 삭제된 custom category를 아직 참조하는 ACTIVE 항목은 B8b 이후 생기지 않으므로 별도 facet을 만들지 않았다.
- 경합 재현용 test seam `CandidateMoveService(afterItemLock)`을 추가했다. 기본값은 no-op이다.
