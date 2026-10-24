# B8 후보 이동과 영향 확인 삭제 설계

> 2026-10-24 · 정책 Q1~Q3 확정 · builder·사용자 계약 검토 반영 · 설계 검토용

## 의도와 범위

B7(PR #20) 병합 지점 `dcee4d5`에서 목적 후보 추가(PUR-07/08), 사용자 category 삭제(CAT-05/06), 목적만 삭제(PUR-05/06)를 순서대로 구현한다. 각 묶음은 검증·커밋을 분리한다. C5 화면 정책, B9 중복 판단, B10 archive lifecycle, B11 배포는 제외한다.

근거: [구현 순서](../../architecture/server/mvp-api-implementation-order.md), [inventory](../../architecture/server/mvp-api-inventory.md), [구매 후보 정리](../../product/organize-candidates.md), [B7 인계](../../history/architecture/server/b7-item-mutations-implementation-2026-10-14.md).

## 보존할 규칙

- JDBC 단일 PostgreSQL transaction. owner 구조 잠금 → 목적 UUID SQL 순서 → 상품 UUID SQL 순서 → 필요한 job 순서. category 메타데이터 잠금은 owner 뒤, 목적/상품 앞에 둔다. owner lock은 기존 생성/편집/Worker와 공유한다.
- 목적의 멤버가 바뀌는 상품마다 `PurposeMembership.recordTransition`을 호출한다. 제거는 활동 시각을 바꾸지 않고 추가만 CANDIDATE_ADDED를 기록한다. purpose version은 메타데이터 CAS, membershipVersion은 연결 변화다.
- owner 조건을 모든 자원 조회·갱신에 적용한다. 다른 owner ID의 존재/현재 version/목적명/영향 item을 노출하지 않는다.
- 영향/목록/facets/count는 같은 REPEATABLE READ snapshot에서 계산한다. 최종 쓰기는 owner 잠금 뒤 현재 상태로 다시 계산한다.
- ACTIVE만 이동·삭제 영향 대상. ARCHIVED/DELETED 상품 및 archive snapshot은 바꾸지 않는다. soft delete로 진단 참조·receipt FK를 보존한다.
- 목적 삭제는 purpose_source=USER, purpose_id=null. category/review/metadata/분석 상태를 임의 확정하지 않는다.
- category 삭제는 custom_category_id/category_source를 비우고 CUSTOM_CATEGORY_DELETED 누락 사유, CATEGORY override, item version을 기록한다. 사용자 재지정은 B7 PATCH가 처리한다.
- 삭제 transaction은 analysis/job 상태를 보존한다. 후속 Worker 동작은 단계별로 다르다. PENDING은 삭제 후 새 item version으로 claim하여 정상 실행할 수 있다. 삭제 전에 claim한 RUNNING은 stale finish 또는 lease 만료 복구에서 job CANCELLED·상품 FAILED_RETRYABLE로 전이한다. 모든 경로에서 CATEGORY override와 USER 목적 해제를 보존하고, 새 분석에도 삭제된 참조/대체 목적을 자동 재연결하지 않는다. 아래 검증 행렬을 GENERAL/BROWSER 각각에 적용한다.

## 접근안

1. **채택 제안:** B7 owner 잠금·공통 읽기 mapper를 재사용하고 B8 query/command를 별도 서비스로 둔다. 경합은 owner 단위로 직렬화되지만 기존 invariant를 그대로 보존한다.
2. 상품 CAS만으로 bulk 갱신: owner 잠금과 다른 경로가 되어 membership/Worker 경합 검증 부담이 늘어난다.
3. 상품별 HTTP 편집 반복: 부분 성공·응답 유실 시 이동 상태가 갈리므로 원자 이동 요구에 맞지 않는다.

기술 선택은 구현자 제안이며 제품 정책의 사용자 승인과 구분한다.

## B8a 계약 제안

### PUR-07 GET /v1/purposes/{id}/candidate-items

query: `categoryId` 또는 `parentId`(동시 지정 거절), `cursor`, `limit`(기본 20, 최대 100), `includeCategoryFacets`(기본 false).

- owner ACTIVE 목적만 허용. 현재 목적 소속은 제외한다.
- **Q1 확정:** PROCESSING 제외, READY/PARTIAL/FAILED_RETRYABLE/FAILED_TERMINAL 및 수동 완료 허용한다. 이름 유무로 추가 제외하지 않는다. PROCESSING 수정 금지 제품 규칙이 근거다.
- categoryId는 공개 세부 ID 또는 owner의 살아 있는 custom UUID, parentId는 공개 상위 ID. 미지정은 필터 생략이며 별도 문자열로 나타내지 않는다.
- 정렬 created_at DESC,id DESC keyset. cursor는 owner·대상 purpose·category/parent filter와 묶으며 includeCategoryFacets·limit에는 묶지 않는다.
- 응답: `items: WishlistItemDto[]`(ReadCardDto wrapper·anchorCursor·deletionImpact 없음, 현재 purpose·version 포함), `totalCount`, `nextCursor`, 선택적 `categoryFacets`/`unassignedCategoryCount`.
- facets는 현재 목적 제외·추가 가능 상태의 전체 pool로 계산하며 현재 category filter·page로 줄이지 않는다. `categoryFacets:[{categoryId,parentId,count}]`는 categoryId 문자열 오름차순이며 공개/custom 세부 category를 포함한다. `unassignedCategoryCount`는 category 참조가 모두 null인 pool의 건수다. 두 필드는 includeCategoryFacets=true일 때만 함께 제공하며 0건 facet은 생략한다.
- **Q3 확정:** 필터 전환 선택 유지·초기 필터는 C5로 이관한다. 서버는 선택을 저장하지 않고 명시 필터만 적용한다.

### PUR-08 POST /v1/purposes/{id}/candidate-moves

header: UUID `Idempotency-Key`.
body: `{expectedVersion, items:[{id,expectedVersion,expectedPurposeId}]}`. expectedPurposeId는 null도 명시한다.

- **Q1 확정:** 서로 다른 item 1~100개. 중복 ID·잘못된 UUID·version·필드·타입은 422.
- owner 잠금 후 receipt를 먼저 확인한다. fingerprint는 대상·expectedVersion·ID순 정렬한 전체 선택(기존 purpose/version 포함)을 담는다. 입력 순서는 의미 없다.
- 이미 대상 목적에 속한 item을 새 요청으로 보내면 409 상태 충돌로 전체 거절한다.
- 첫 요청: 대상 ACTIVE·선택 전체의 owner/존재(없는/DELETED는 404) 검사 → 관련 목적 SQL ID순 잠금 → 선택 상품 SQL ID순 잠금 → owner/존재 재검사 → 대상 version → 상품 SQL ID순으로 version·상태·기존 목적 검사 → 일괄 변경. ARCHIVED는 존재 검사 통과 후 상태 충돌이다. 혼합 요청도 전체 존재 검사를 먼저 하므로 다른 owner item과 owner의 stale item이 섞이면 404가 우선한다.
- 하나라도 충돌하면 상품/목적/receipt 모두 rollback. owner item의 version·상태·기존 목적 충돌은 모두 `details:{itemId,currentVersion}`를 제공한다. 대상 version 충돌은 `details:{currentVersion}`이며 다른 owner는 빈 details의 404다. code와 오류 순서는 [공개 오류 표](../../architecture/server/candidate-move-deletion-api.md#오류-형식과-검사-순서)를 따른다.
- 목적 USER 연결과 B7 ReviewAfterEdit 규칙을 적용한다. category AI 검토는 남겨둔다. 상품별 version +1; membership은 이전/대상에 상품별 +1.
- 응답 200: 이동 item ID·새 version·purposeId와 영향 목적의 count·membershipVersion. replay header `Idempotency-Replayed:true`.
- replay는 이동을 다시 실행하지 않는다. 저장 결과를 반환하는 receipt payload가 필요하다. 같은 key 다른 payload는 409. 삭제/후속 이동 뒤 replay도 이전 성공 결과이며 현재 상태 조회가 아님을 계약에 명시한다.
- 신규 이동 key retention은 계정 수명 보존으로 확정했다.

## B8b/B8c 공통 삭제 계약 제안

GET `/{resource}/{id}/deletion-impact`: query cursor/limit(20/100), 응답 `{version,impactToken,affectedCount,items,nextCursor}`. items는 후보 조회와 같은 `WishlistItemDto[]`이며 이름 누락도 목록과 count에 포함한다. 정렬은 `createdAt DESC,id DESC`, 다음 페이지는 `(created_at,id) < (?,?)` 배타 경계다. fingerprint의 ID순 정렬과 별개다. limit+1건으로 다음 페이지 유무를 계산하고 마지막 반환 상품의 위치로 cursor를 만든다. 끝 페이지는 nextCursor=null. 현재 resource 표시 정보는 기존 상세 계약을 사용한다.

토큰 fingerprint는 owner·종류·resource ID·resource version·membershipVersion(목적)·전체 ACTIVE 영향 상품의 ID/version을 안정 정렬해 SHA-256으로 계산한다. page/cursor와 무관하다. 영향 페이지 cursor는 owner·자원 종류·ID·전체 fingerprint와 묶는다. 다음 페이지 조회 때 fingerprint가 다르면 409로 전체 재확인을 요구한다. 토큰은 권한 증명이 아니며 삭제의 owner 인증과 현재 상태 검증이 항상 필요하다.

**Q2 확정:** DELETE JSON body `{expectedVersion,impactToken}`를 사용한다. 시간 만료는 두지 않고 상태가 바뀌면 무효화한다. 성공한 동일 token/expectedVersion 재전송은 204이며 계정 수명 동안 성공 fingerprint를 보존한다. 다른 token으로 이미 삭제된 자원에 접근하면 404. preview token 자체는 저장하지 않아 조회만으로 DB가 증가하지 않는다.

최종 DELETE 순서:

1. 인증·path/body 검증 후 transaction에서 owner 구조 잠금을 획득한다.
2. **살아 있는 자원 조회보다 먼저** `(owner_id,kind,resource_id)` 성공 receipt를 확인한다. token과 expectedVersion이 모두 일치하면 쓰기 없이 204. receipt가 있지만 입력이 다르면 404다. 다른 owner receipt는 조회하지 않는다.
3. receipt가 없을 때만 살아 있는 owner 자원을 조회한다. 없으면 404. 기존 CategoryRepository.find/PurposeRepository.find는 이 단계에서만 사용한다.
4. category metadata(해당 시) → 관련 목적 SQL ID순 → 전체 ACTIVE 영향 item SQL ID순으로 잠근다.
5. 전체 fingerprint/version을 재계산한다. 불일치는 409 `CATEGORY_DELETION_IMPACT_CONFLICT` 또는 `PURPOSE_DELETION_IMPACT_CONFLICT`, details는 빈 object다.
6. 일치하면 상품 연결 변경·자원 soft-delete·성공 receipt를 한 transaction으로 commit하고 204. replay와 최초 삭제 모두 body가 없다.

### CAT-06

custom_categories.deleted_at 설정·version +1. 영향 상품의 custom 참조와 category_source 제거, CATEGORY override, CUSTOM_CATEGORY_DELETED, version +1, updated_at 변경. purpose membership은 변하지 않는다. archived와 deleted 진단 참조는 보존한다. 기존 AI category prediction 진단은 지우지 않는다.

### PUR-06

purposes.lifecycle_status=DELETED·version +1. 영향 상품 purpose_id=null/purpose_source=USER·version +1. review/category/analysis는 보존한다. 상품별 recordTransition(from,null)로 membershipVersion을 올리고 activity는 유지한다. 빈 목적도 삭제한다.

## 구현 경계

- B8 읽기 서비스: 후보 pool·facets·삭제 영향 조회, 기존 WishlistReadRepository.load와 media signer 재사용.
- B8 이동 서비스: owner/목적/상품 잠금과 bulk CAS·receipt.
- B8 삭제 서비스: 전체 영향 fingerprint와 최종 재검증·soft delete.
- HTTP: 엄격한 parser/query/cursor, 인증 우선, IO dispatcher와 CancellationException 전파. 기본 오류 envelope·필드 정렬은 B7과 동일.
- migration: 이동 결과 replay와 삭제 성공 기록용 owner-scoped receipt 추가. 기존 B2/B3/B7 receipt 계약 변경 없음.

## 검증과 완료 기준

실제 PostgreSQL/Testcontainers에서 각 하위 묶음별로 아래를 검증하고 커밋한다. 유닛 mock만으로 완료 판정하지 않는다.

- B8a: 현재 목적 제외·모든 상태·owner·category/parent filter·전체 facets·같은 시각 keyset·다른 scope cursor·상한·duplicate IDs; 한 item stale로 전체 rollback; 이전/대상 count·membership/activity; 다른 payload key; 동일 key 동시 replay; B7 PATCH와 이동, 두 방향 이동 경합.
- B8b: 같은 createdAt·limit 변경·배타 경계·마지막 페이지·전체 페이지 합집합=affectedCount, 빈/이름 없는 영향 count, page 밖 item 편집·새 연결로 token 무효화, archived 제외, owner 비노출, 삭제 replay, HOME 보완·PATCH 재지정, AI 반영과 삭제 양쪽 순서·새 분석에도 재연결 없음.
- B8c: 같은 생성 시각 전체 페이지 누락/중복 없음·빈 목적, 사용자 해제·review/category 보존, page 밖 변화 409, 다른 owner, replay, 이동/편집/Worker와 삭제 경합, 삭제 목적 및 대체 목적 AI 재연결 없음.

### 삭제 후 Worker 검증 행렬

각 행을 CAT-06/PUR-06 × GENERAL/BROWSER에서 검증한다. 분석용 fixture는 실제 claim/finish/reconciler 경로를 사용한다.

| 순서 | 기대 동작과 검증 |
| --- | --- |
| PENDING → 삭제 → 첫 claim → finish | 삭제는 PROCESSING/PENDING 유지. claim.expectedItemVersion은 삭제 후 version. 정상 분석이 가능하며 category 삭제는 CATEGORY override·null/source null·CUSTOM_CATEGORY_DELETED 보존, 목적 삭제는 USER+null·대체 목적 미연결 보존. 분석 성공 상태는 남은 category 조건에 따라 READY/PARTIAL이며 무조건 실패로 단정하지 않는다 |
| RUNNING claim → 삭제 → finish | 삭제 직후 PROCESSING/RUNNING 유지. 이전 claim은 pending 결과를 반영하지 않고 ACK, job CANCELLED·상품 FAILED_RETRYABLE. override/해제 유지 |
| RUNNING claim → 삭제 → Worker 미복귀 → lease 만료 → reconcileExpired | 실제 lease를 만료시켜 AnalysisJobReconciler 호출. stale version 때문에 job CANCELLED·상품 FAILED_RETRYABLE, stale job의 retry outbox 추가 없음. 두 번째 복구는 상태를 다시 바꾸지 않음. 늦은 finish는 ACK·데이터 불변 |
| Worker finish → 새 영향 조회 → 삭제 | 최신 영향 token으로 삭제 가능. 이전 token은 item version 변화로 409. 삭제 후 참조 해제·홈 보완/목적 USER+null 확인 |
| 위 RUNNING 실패 뒤 명시적 재분석 → claim/finish | 새 generation에서도 CATEGORY override 또는 USER+null 유지. 삭제 category/목적과 대체 목적 자동 재연결 없음 |

- HTTP: 여섯 route 공개 조립·401/400/422/404/409·replay header·서명 이미지·IO·취소.
- 최종 전체 `./gradlew test`, opt-in 외부 테스트 skip와 환경 제약 기록. docs의 계약/inventory/순서/이력 및 해당 INDEX 갱신.

## 세션 미결정 질문

사용자가 이 세션에서 Q1(PROCESSING 제외·최대 100개), Q2(DELETE JSON·상태 기반 무효화·동일 token 204·계정 수명 retention), Q3(화면 정책 C5 이관)에 제안대로 답했다. 나머지는 기존 계약의 구현자 설계 선택이다.
