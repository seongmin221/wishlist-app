# B8 후보 이동과 영향 확인 삭제 API

> 2026-10-24 · 계약 확정·구현 전 · [제품 정책](../../history/product-planning/mvp/decisions/b8-candidate-move-deletion-policy-2026-10-24.md) · [설계](../../superpowers/specs/2026-10-24-b8-candidate-move-deletions-design.md)

## 후보 조회 PUR-07

`GET /v1/purposes/{id}/candidate-items`

- 인증 owner의 ACTIVE 목적. ACTIVE, non-PROCESSING item 중 현재 목적 소속을 제외한다. 이름 유무·review로 제한하지 않는다.
- `categoryId`(공개 세부 ID 또는 살아 있는 owner custom UUID)와 `parentId`(공개 상위 ID)는 함께 지정할 수 없다. 필터 생략은 전체다.
- `limit` 기본 20·최대 100, `cursor`, `includeCategoryFacets` 기본 false. 알 수 없는/중복 query와 잘못된 값은 400.
- `items`는 기존 상품 카드 표현이며 현재 purpose와 version을 포함한다. `totalCount`는 필터 적용 결과다.
- `categoryFacets`는 요청했을 때만 제공하며 전체 추가 가능 pool의 `{categoryId,parentId,count}` 목록이다. category 미지정은 별도 count로 제공한다. 현재 filter/page와 무관하다.
- 정렬 createdAt DESC,id DESC. nextCursor는 owner·목적·필터에 묶인 위치 힌트다. limit/facets 변경은 허용한다.

## 후보 이동 PUR-08

`POST /v1/purposes/{id}/candidate-moves`, UUID `Idempotency-Key` 필수.

```json
{"expectedVersion":1,"items":[{"id":"55555555-5555-4555-8555-555555555555","expectedVersion":3,"expectedPurposeId":null}]}
```

- 1~100개, 중복 ID 불가, expectedPurposeId 필수 nullable. version은 양의 정수다.
- 대상 목적 version과 전체 item version/기존 목적/상태를 한 transaction에서 검사한다. 새 요청의 이미 대상 소속은 409다.
- 하나라도 충돌하면 상품·목적·receipt 전체 rollback. 다른 owner/없는 자원은 404. 해당 owner item version 충돌만 ID·현재 version을 반환한다.
- 사용자 연결은 USER 출처이며 B7 편집 review 규칙을 따른다. 각 item version +1, 이전/대상 membershipVersion은 상품별 +1. 추가 목적만 활동 시각이 바뀐다.
- 200: `items:[{id,version,purposeId}]`, `purposes:[{id,candidateCount,membershipVersion}]`.
- 같은 key·같은 payload(선택 순서 무관)는 저장 당시 성공 결과를 반환하고 `Idempotency-Replayed:true`. 이후 이동/삭제가 있어도 다시 이동하지 않는다. 현재 상태를 뜻하지 않으므로 필요하면 별도 조회한다. 같은 key·다른 payload는 409 IDEMPOTENCY_KEY_REUSED.

## 영향 조회 CAT-05/PUR-05

`GET /v1/custom-categories/{id}/deletion-impact`, `GET /v1/purposes/{id}/deletion-impact`.

query `limit` 기본 20·최대 100, `cursor`. 응답 `{version,impactToken,affectedCount,items,nextCursor}`. ACTIVE 영향 전체 count이며 이름 누락도 포함한다. ARCHIVED/DELETED는 제외한다. 모두 동일 snapshot이다.

impactToken은 owner·자원 종류/ID·version·목적 membershipVersion·전체 영향 item ID/version의 SHA-256 fingerprint다. 특정 page의 영향만 나타내지 않는다. 시간 만료는 없으며 현재 상태가 바뀌면 무효다. cursor는 같은 fingerprint에 묶고 다음 page 조회 중 영향이 달라지면 409 재확인을 요구한다.

## 삭제 CAT-06/PUR-06

`DELETE /v1/custom-categories/{id}`, `DELETE /v1/purposes/{id}`.

```json
{"expectedVersion":1,"impactToken":"64자리 SHA-256 hex"}
```

owner 잠금 뒤 전체 영향·version을 다시 검사하고 일치할 때만 204. 변화는 409 `{CATEGORY|PURPOSE}_DELETION_IMPACT_CONFLICT`. 인증 owner의 성공한 동일 token/version 재전송은 204이며, 다른 token의 이미 삭제된 자원은 404. 빈 자원도 삭제할 수 있다.

category는 soft-delete하고 ACTIVE 참조 상품의 custom 참조/category_source를 비운다. CUSTOM_CATEGORY_DELETED 누락 사유와 CATEGORY override, item version +1을 기록한다. 상품의 purpose/review/metadata/analysis는 보존한다. READY 재지정은 B7 PATCH다.

목적은 DELETED로 전환하고 상품은 보존한다. purpose_id=null·purpose_source=USER·item version +1, membershipVersion은 제거 상품별 +1. review/category는 보존하며 AI가 삭제 목적이나 대체 목적을 자동 연결하지 못한다. 제거만으로 활동 시각을 바꾸지 않는다.

모든 신규 성공 receipt는 계정 수명 동안 보존한다. archived snapshot 통합 검증은 B10이다. 기존 ITEM-05의 version 없는 삭제 계약은 바뀌지 않는다.
