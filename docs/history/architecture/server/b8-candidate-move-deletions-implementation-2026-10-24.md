# B8 후보 이동과 영향 확인 삭제 구현 이력

> 2026-10-24 · B8a·B8b·B8c 구현 완료 · [spec](../../../superpowers/specs/2026-10-24-b8-candidate-move-deletions-design.md) · [계획](../../../superpowers/plans/2026-10-24-b8-candidate-move-deletions.md) · [계약](../../../architecture/server/candidate-move-deletion-api.md) · [결정](../../product-planning/mvp/decisions/b8-candidate-move-deletion-policy-2026-10-24.md)

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

### Task 2: B8b 사용자 category 삭제(CAT-05·CAT-06, V21)

- RED: `CategoryDeletionTest`·`StructureDeletionConcurrencyTest`·HTTP·parser·V21 테스트를 먼저 작성해 `DeletionKind`·`DeletionImpactService`·`StructureDeletionService`·`DeletionQueryParseResult` 등 미정의 symbol로 125개 compile 오류를 확인했다.
- GREEN(실제 PostgreSQL): `CategoryDeletionTest` 5·`StructureDeletionConcurrencyTest` 3·`CandidateRoutesTest` 5·`CandidateRequestParserTest` 7·`DatabaseMigrationTest` 13 통과. 첫 실행에서 경합 테스트 1개가 실패했는데 라운드마다 다른 category 삭제 receipt도 생기는 것을 세지 않은 테스트 산술 오류였다. 해당 category의 receipt만 세도록 고쳤고 구현은 바꾸지 않았다.
  - 영향 조회: 이름 없는 상품·PROCESSING 상품 포함, ARCHIVED/DELETED 제외, 빈 category 0건, 다른 owner·삭제됨·없음 404. 관계없는 편집은 token 불변, 영향 상품 편집·새 연결은 token 변경. 같은 created_at 5건+1건을 limit 2, 1→3→100, 100으로 순회해 누락·중복 없이 합집합=affectedCount, 끝 cursor null. 첫 page를 읽은 뒤 page 밖 상품을 편집하면 다음 cursor가 409.
  - 삭제: 영향 상품의 custom 참조·category_source 제거, `CUSTOM_CATEGORY_DELETED`, CATEGORY override, version +1·updated_at 변경. 목적·review·분석 상태·predicted 진단·job 보존, ARCHIVED 상품 전체 JSON 불변, membership 불변, category soft delete·version +1. 삭제 뒤 CATEGORY_REASSIGNMENT·홈 정보 보완 그룹, PATCH 재지정. 잘못된 token/version 409 뒤 변화 없음, page 밖 편집·새 연결 409.
  - replay: 같은 token·version 재전송은 변화 없이 성공하고 receipt 1개 유지. 다른 token·다른 version·다른 owner 404, 삭제 뒤 영향 조회 404. HTTP에서 실제 DELETE → 상세 404 → 같은 DELETE 204(빈 body)·상품 불변을 확인했다. 같은 삭제 동시 2건은 모두 성공하고 상품 version은 한 번만 오른다. 삭제와 B7 편집 경주는 하나만 성공한다.
  - Worker(GENERAL/BROWSER 각각): PENDING → 삭제 → 첫 claim이 삭제 후 version 2를 들고 실행, 삭제된 category를 결과로 받아도·공개 C026을 받아도 참조가 돌아오지 않고 PARTIAL(남은 category 없음)·메타데이터 반영·대체 job 없음. RUNNING → 삭제 → 이전 claim finish는 job CANCELLED·상품 FAILED_RETRYABLE·pending 결과 미반영. 반대 순서(USER custom category, Worker READY 먼저)는 이전 token 409 뒤 새 영향으로 삭제되고 분석 상태는 READY 유지. RUNNING → 삭제 → lease 만료 → 실제 `AnalysisJobReconciler.reconcileExpired`는 1건 복구(CANCELLED·FAILED_RETRYABLE·retry outbox 없음), 두 번째 복구 0건, 늦은 finish는 데이터 불변. 이어서 `ReanalysisService`로 새 generation을 claim·finish해도 참조 미복귀·PARTIAL, PATCH 재지정 가능.
- 테스트 강도 확인(변형 후 원복): receipt 조회를 살아 있는 category 조회 뒤로 옮기면 삭제 서비스 테스트와 HTTP replay 테스트가, CATEGORY override를 빼면 PENDING claim 테스트가 실패했다.

### Task 3: B8c 목적만 삭제(PUR-05·PUR-06)

- RED: `PurposeDeletionTest` 5·HTTP·경합 테스트를 먼저 추가했다. 공통 서비스의 목적 분기가 `TODO("B8c")`이고 route가 없어서 6개가 `NotImplementedError`·404로 실패했다. `impact pages with tied createdAt…`는 Task 2의 공통 영향 조회가 이미 kind를 받으므로 첫 실행에서 통과했다(새 symbol 없음).
- GREEN(실제 PostgreSQL): B8 전체 `app.candidates.*` 27·`app.http.Candidate*` 13·`DatabaseMigrationTest` 13 통과.
  - 삭제: 영향 상품(이름 없음·PROCESSING 포함) 4개가 `purpose_id=null`·`purpose_source=USER`·version +1. category·누락 사유·review·분석 상태·override 보존, ARCHIVED 상품 불변, job 보존. 목적 DELETED·version +1·membershipVersion은 제거 상품별 +1(4), 활동 시각·종류 불변, PUR-03 404·PUR-01 목록 제외. 빈 목적 삭제 가능, ARCHIVED 목적 404. 삭제 목적으로의 PUR-08 이동 404, B7 PATCH 재연결 `PURPOSE_NOT_AVAILABLE`.
  - token: page 밖 PUR-08 추가·B7 목적 해제·목적 메타데이터 수정이 각각 이전 token을 409로 만들고, 다음 cursor도 409. 같은 created_at 페이지 경계·limit 변경·끝 cursor는 category와 같다.
  - replay: 같은 token·version 재전송은 204·상품/목적/receipt 불변. 다른 token·version·owner 404. HTTP에서 실제 DELETE → PUR-03 404 → 같은 DELETE 204(빈 body), 상품 상세는 `purpose.id=null`·`source=USER`.
  - Worker(GENERAL/BROWSER 각각): PENDING → 삭제 → 첫 claim(version 2)·v3 snapshot으로 삭제 목적 또는 대체 목적을 판단해도 USER+null 유지, 분석은 READY, category는 AI C026으로 독립 반영, review PENDING, 대체 목적 membership 불변. RUNNING → 삭제 → finish는 CANCELLED·FAILED_RETRYABLE. 별도 RUNNING → 삭제 → lease 만료 → reconciler 1건(retry outbox 없음)·두 번째 0건·늦은 finish 불변. 두 상품 모두 새 generation 재분석에서 대체 목적을 판단해도 USER+null, 대체 목적 후보 수 0.
  - 경합: 목적 삭제와 PUR-08 추가 경주는 하나만 성공(삭제 승리 시 이동 404·membership 2·후보 0, 이동 승리 시 삭제 409·후보 2·CANDIDATE_ADDED). 목적 삭제와 B7 편집 경주도 하나만 성공하고 membership이 결과와 일치.
- 테스트 강도 확인(변형 후 원복): 삭제 상품의 목적 출처를 USER 대신 UNASSIGNED로 바꾸면 삭제·PENDING claim·RUNNING/lease 테스트 3개가 실패했다(AI가 대체 목적을 채움).

## 계획 대비 결정

- 이동 body의 원소 오류(원소가 object가 아님·알 수 없는/누락된 원소 key·ID/version/expectedPurposeId 값)는 계약의 "원소의 필드 오류는 `items`"에 따라 `fields:["items"]`로 묶는다. 구조 오류로 비우는 것은 최상위 body(object 아님·최상위 key 불일치)뿐이다.
- 일괄 상품 잠금은 B7 `ItemMutationRepository.lockAll`(한 문장 `order by id for update`)로 추가했다. 기존 함수는 바꾸지 않았다.
- 이동 receipt는 `candidate_mutation_receipts`(V20)에 대상 목적 FK와 schema v1 결과 JSON을 둔다. 응답의 `replayed`는 저장하지 않는다.
- facet은 살아 있는 custom category 또는 공개 category에 연결된 항목만 센다. 삭제된 custom category를 아직 참조하는 ACTIVE 항목은 B8b 이후 생기지 않으므로 별도 facet을 만들지 않았다.
- 삭제 receipt(`structure_deletion_receipts`, V21)는 owner/kind/resource당 하나이며 resource FK 대신 kind check와 token 형식 check를 둔다. category와 목적 두 종류를 한 표에 두기 때문이다.
- impactToken canonical은 `v1|owner|kind|id|version|membershipVersion(category는 -)|id:version,…`(ID 오름차순)의 SHA-256 hex다. 영향 cursor는 owner tag·kind·ID·token·위치를 담아 다음 page에서 현재 token과 비교한다.
- 삭제 서비스는 category 행을 `CategoryRepository.find(lock)`로 잠근 뒤 영향 상품의 목적을 ID순으로, 상품을 `lockAll`로 잠근다. 최종 token은 잠긴 상품 version으로 다시 계산한다.
- category 삭제는 review를 바꾸지 않는다(설계의 "review 보존"). AI 목적 때문에 PENDING이던 상품은 재지정 뒤에도 검토 대기로 남는다.
- 목적 삭제는 review를 확정하지 않는다. AI 목적 때문에 PENDING이던 상품은 category가 AI이면 검토 대기로 남는다.
- 목적 삭제의 목적 잠금은 삭제 대상 하나뿐이다(영향 상품은 모두 그 목적 소속). `PurposeRepository.find`는 receipt가 없을 때만 부른다.
- 경합 재현용 test seam `CandidateMoveService(afterItemLock)`을 추가했다. 기본값은 no-op이다.
