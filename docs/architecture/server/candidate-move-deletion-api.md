# B8 후보 이동과 영향 확인 삭제 API

> 2026-10-24 · 정책 확정·사용자 검토 반영 · PUR-07/08(V20)·CAT-05/06(V21 receipt) 구현, PUR-05/06 구현 전 · [구현 이력](../../history/architecture/server/b8-candidate-move-deletions-implementation-2026-10-24.md) · [제품 정책](../../history/product-planning/mvp/decisions/b8-candidate-move-deletion-policy-2026-10-24.md) · [설계](../../superpowers/specs/2026-10-24-b8-candidate-move-deletions-design.md)

## 후보 조회 PUR-07

`GET /v1/purposes/{id}/candidate-items`

- 인증 owner의 ACTIVE 목적. ACTIVE, non-PROCESSING item 중 현재 목적 소속을 제외한다. 이름 유무·review로 제한하지 않는다.
- `categoryId`(공개 세부 ID 또는 살아 있는 owner custom UUID)와 `parentId`(공개 상위 ID)는 함께 지정할 수 없다. 필터 생략은 전체다.
- `limit` 기본 20·최대 100, `cursor`, `includeCategoryFacets` 기본 false. 알 수 없는/중복 query와 잘못된 값은 400.
- `items`의 원소는 `WishlistItemDto`다. `ReadCardDto`의 item wrapper와 anchorCursor는 없으며 상세 전용 deletionImpact도 생략한다. 나머지 optional 필드의 null/default 직렬화는 기존 ApiJson·WishlistItemViewMapper를 따른다. 현재 purpose와 version을 포함한다. `totalCount`는 필터 적용 결과다.
- `includeCategoryFacets=true`일 때 `categoryFacets:[{categoryId,parentId,count}]`와 최상위 `unassignedCategoryCount`를 함께 제공한다. false/생략이면 두 key 모두 생략한다. facets는 전체 추가 가능 pool에서 건수가 양수인 공개/custom 세부 category를 categoryId 문자열 오름차순으로 제공한다. unassignedCategoryCount는 category 참조가 모두 null인 건수(0 포함)다. 현재 filter/page와 무관하다.
- 정렬 `createdAt DESC,id DESC`, 다음 페이지는 `(created_at,id) < (?,?)` 배타 경계다. nextCursor는 owner·목적·필터에 묶인 위치 힌트다. limit/facets 변경은 허용한다.

## 후보 이동 PUR-08

`POST /v1/purposes/{id}/candidate-moves`, UUID `Idempotency-Key` 필수.

```json
{"expectedVersion":1,"items":[{"id":"55555555-5555-4555-8555-555555555555","expectedVersion":3,"expectedPurposeId":null}]}
```

- 1~100개, 중복 ID 불가, expectedPurposeId 필수 nullable. version은 양의 정수다.
- 대상 목적 version과 전체 item version/기존 목적/상태를 한 transaction에서 검사한다. 새 요청의 이미 대상 소속은 409다.
- 하나라도 충돌하면 상품·목적·receipt 전체 rollback. 다른 owner/없는 자원은 404. owner item의 version·상태·기존 목적 충돌은 모두 details에 itemId·currentVersion을 반환한다. 다른 owner/없는 선택 item과 owner stale item이 섞이면 404를 우선한다. 정확한 검사 순서와 code는 아래 오류 표를 따른다.
- 사용자 연결은 USER 출처이며 B7 편집 review 규칙을 따른다. 각 item version +1, 이전/대상 membershipVersion은 상품별 +1. 추가 목적만 활동 시각이 바뀐다.
- 200: `items:[{id,version,purposeId}]`, `purposes:[{id,candidateCount,membershipVersion}]`.
- 같은 key·같은 payload(선택 순서 무관)는 저장 당시 성공 결과를 반환하고 `Idempotency-Replayed:true`. 이후 이동/삭제가 있어도 다시 이동하지 않는다. 현재 상태를 뜻하지 않으므로 필요하면 별도 조회한다. 같은 key·다른 payload는 409 IDEMPOTENCY_KEY_REUSED.

## 영향 조회 CAT-05/PUR-05

`GET /v1/custom-categories/{id}/deletion-impact`, `GET /v1/purposes/{id}/deletion-impact`.

query `limit` 기본 20·최대 100, `cursor`. 응답 `{version,impactToken,affectedCount,items,nextCursor}`. ACTIVE 영향 전체 count이며 이름 누락도 포함한다. ARCHIVED/DELETED는 제외한다. 페이지별 items/count/token은 동일 REPEATABLE READ snapshot이다. items 원소는 후보 조회와 동일한 WishlistItemDto이며 anchorCursor·deletionImpact가 없다.

목록 정렬은 `createdAt DESC,id DESC`, 다음 페이지는 `(created_at,id) < (?,?)` 배타 경계다. limit+1건으로 다음 페이지 유무를 판단하고 마지막 반환 상품의 `(createdAt,id)`로 nextCursor를 만든다. 마지막 페이지는 nextCursor=null이다. 같은 생성 시각에도 ID가 tie-break한다. fingerprint canonicalization의 ID 오름차순은 목록 정렬과 별개다. limit 변경은 허용한다.

impactToken은 owner·자원 종류/ID·version·목적 membershipVersion·전체 영향 item ID/version의 SHA-256 fingerprint다. 특정 page의 영향만 나타내지 않는다. 시간 만료는 없으며 현재 상태가 바뀌면 무효다. cursor는 같은 fingerprint에 묶고 다음 page 조회 중 영향이 달라지면 409 재확인을 요구한다.

## 삭제 CAT-06/PUR-06

`DELETE /v1/custom-categories/{id}`, `DELETE /v1/purposes/{id}`.

```json
{"expectedVersion":1,"impactToken":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
```

처리 순서는 **owner 잠금 → 성공 receipt 조회 → replay 판정 → 살아 있는 자원 조회 → 잠금/전체 영향 재검증 → 변경/receipt commit**이다.

- receipt는 owner/kind/resource ID로 조회한다. 저장 token과 expectedVersion이 모두 같으면 살아 있는 자원 조회 없이 204를 반환한다. 따라서 삭제 후 정상 재전송도 404가 되지 않는다.
- receipt가 있지만 token/version이 다르면 404. receipt가 없으면 살아 있는 owner 자원을 조회하며 없으면 404.
- 최초 삭제는 전체 영향·version을 재검증하고 일치할 때만 204. 변화는 409 CATEGORY_DELETION_IMPACT_CONFLICT 또는 PURPOSE_DELETION_IMPACT_CONFLICT, details는 빈 object다.
- 빈 자원도 삭제할 수 있다. 최초 삭제/replay 모두 응답 body가 없다.

category는 soft-delete하고 ACTIVE 참조 상품의 custom 참조/category_source를 비운다. CUSTOM_CATEGORY_DELETED 누락 사유와 CATEGORY override, item version +1을 기록한다. 상품의 purpose/review/metadata/analysis는 보존한다. READY 재지정은 B7 PATCH다.

목적은 DELETED로 전환하고 상품은 보존한다. purpose_id=null·purpose_source=USER·item version +1, membershipVersion은 제거 상품별 +1. review/category는 보존하며 AI가 삭제 목적이나 대체 목적을 자동 연결하지 못한다. 제거만으로 활동 시각을 바꾸지 않는다.

모든 신규 성공 receipt는 계정 수명 동안 보존한다. archived snapshot 통합 검증은 B10이다. 기존 ITEM-05의 version 없는 삭제 계약은 바뀌지 않는다.

## 삭제와 후속 분석의 구분

삭제 transaction 자체는 analysis/job 상태를 보존한다. GENERAL/BROWSER 양쪽에서 삭제 후 PENDING job을 처음 claim하면 변경된 item version으로 실행할 수 있다. 이 실행과 성공 반영에도 CATEGORY override·CUSTOM_CATEGORY_DELETED 또는 USER+null 목적 해제를 보존한다. 남은 category 조건에 따라 READY/PARTIAL이므로 PENDING 경로를 일괄 FAILED_RETRYABLE로 취급하지 않는다.

삭제 전에 이미 claim한 RUNNING job은 이전 version을 들고 있다. 이 claim의 finish는 job CANCELLED·상품 FAILED_RETRYABLE로 전이한다. Worker가 돌아오지 않으면 lease 만료 뒤 AnalysisJobReconciler가 같은 전이를 수행하며 stale job의 retry outbox는 만들지 않는다. 이후 명시적 재분석도 override/목적 사용자 해제를 유지한다. [단계별 검증 행렬](../../superpowers/specs/2026-10-24-b8-candidate-move-deletions-design.md#삭제-후-worker-검증-행렬)을 따른다.

## 오류 형식과 검사 순서

기존 공통 envelope를 사용한다. `X-Request-ID`와 body requestId가 일치하며 원문 사용자 입력은 반사하지 않는다. details가 없으면 `{}`다. 현재 version은 DB 양의 정수이며 itemId는 owner 확인을 마친 item의 canonical UUID만 제공한다.

| HTTP | code | 조건 | details |
| --- | --- | --- | --- |
| 401 | UNAUTHORIZED | 인증 없음, 모든 B8 route | `{}` |
| 400 | INVALID_PURPOSE_ID / INVALID_CATEGORY_ID | path UUID 형식 오류 | `{}` |
| 400 | INVALID_IDEMPOTENCY_KEY | 이동 key 누락·UUID 형식 오류 | `{}` |
| 400 | INVALID_CANDIDATE_QUERY | PUR-07 중복/알 수 없는 query, filter 동시 지정, 알려지지 않은 공개 ID/parent, limit/boolean 형식·범위 오류 | `{}` |
| 400 | INVALID_CANDIDATE_CURSOR | PUR-07 cursor 형식 또는 owner/대상/filter scope 불일치 | `{}` |
| 400 | INVALID_DELETION_QUERY | 영향 GET 중복/알 수 없는 query, limit 형식·범위 오류 | `{}` |
| 400 | INVALID_DELETION_CURSOR | 영향 cursor 형식 또는 owner/kind/resource scope 불일치 | `{}` |
| 422 | INVALID_CANDIDATE_INPUT | 이동 body 구조·타입·ID·version·선택 개수/중복 오류 | 구조 오류는 `{}`, 값 오류는 `{"fields":[...]}` |
| 422 | INVALID_DELETION_INPUT | DELETE body 구조·타입·값 오류 | 구조 오류는 `{}`, 값 오류는 `{"fields":[...]}` |
| 404 | PURPOSE_NOT_FOUND | 대상 목적 없음/다른 owner/비ACTIVE, 삭제 receipt 불일치 포함 | `{}` |
| 404 | CATEGORY_NOT_FOUND | custom filter 또는 삭제 category 없음/다른 owner/삭제됨, 삭제 receipt 불일치 포함 | `{}` |
| 404 | WISHLIST_ITEM_NOT_FOUND | 선택 item 없음/다른 owner/DELETED. 어떤 item인지 노출하지 않음 | `{}` |
| 409 | IDEMPOTENCY_KEY_REUSED | 같은 owner·이동 key에 다른 payload | `{}` |
| 409 | PURPOSE_VERSION_CONFLICT | 이동 대상 메타데이터 version 충돌 | `{"currentVersion":2}` |
| 409 | WISHLIST_ITEM_VERSION_CONFLICT | 선택 item version 충돌 | `{"itemId":"…","currentVersion":4}` |
| 409 | WISHLIST_ITEM_STATE_CONFLICT | 선택 item ARCHIVED/PROCESSING/이미 대상 목적 소속 | `{"itemId":"…","currentVersion":4}` |
| 409 | WISHLIST_ITEM_PURPOSE_CONFLICT | 선택 item의 현재 목적이 expectedPurposeId와 다름 | `{"itemId":"…","currentVersion":4}` |
| 409 | CATEGORY_DELETION_IMPACT_CONFLICT / PURPOSE_DELETION_IMPACT_CONFLICT | DELETE expectedVersion/token 또는 영향 GET cursor fingerprint가 현재 전체 영향과 다름 | `{}` |

값 오류의 fields는 중복 없는 문자열 오름차순이다. 이동 최상위 expectedVersion 오류는 `expectedVersion`, 선택 개수·중복·원소의 필드 오류는 `items`로 묶는다. DELETE 값 오류는 `expectedVersion`/`impactToken`이다. body가 object 아님·알 수 없는 key·필수 key 누락 같은 구조 오류는 details를 비운다. impactToken은 `[0-9a-f]{64}`다.

HTTP는 인증 → path → key(이동) → query/body/cursor 형식 검사를 먼저 수행한다. cursor scope는 형식 검사에서 확인한다. 서비스는 다음 순서를 고정한다.

- 이동: owner 잠금 → receipt(동일 payload replay 즉시 반환/불일치 409) → 대상 ACTIVE 확인 → **선택 전체의 owner/존재 검사** → 목적/상품 SQL ID순 잠금 후 owner/존재 재확인 → 대상 version → item SQL ID순으로 각 version → 상태 → 기존 목적 검사. 여러 item 충돌 시 SQL ID순 첫 충돌 하나를 반환한다. 존재 검사에서는 ARCHIVED를 숨기지 않고 이후 상태 409로 처리한다. DELETED는 404다.
- 후보 조회: 대상 목적 확인 → custom filter 사용 가능 확인 → pool/page/facets 조회. UUID 형식은 맞지만 다른 owner/삭제된 custom filter는 CATEGORY_NOT_FOUND다.
- 영향 조회: 살아 있는 자원 확인 → 전체 fingerprint 계산 → cursor fingerprint 확인 → page 조회. 자원이 없어졌으면 404, 살아 있지만 영향이 바뀌면 409다.
- 삭제: owner 잠금 → receipt 판정 → 살아 있는 자원 → 목적/상품 잠금 → version/fingerprint 재검증. receipt 일치 시 자원 조회·변경을 실행하지 않는다.

성공 이동 body의 items/purposes는 각각 SQL UUID ID 오름차순이다. replay는 동일한 저장 body를 반환한다. 204는 별도 replay header를 약속하지 않는다.

## 완전한 JSON 예시

UUID·시각·requestId는 예시다. cursor는 실제로는 opaque string이며 아래 null은 마지막 페이지를 뜻한다. 모든 items 배열은 같은 WishlistItemDto schema를 사용한다.

### PUR-07: 200 · includeCategoryFacets=true

```json
{
  "items": [
    {
      "id": "55555555-5555-4555-8555-555555555555",
      "clientSubmissionId": "66666666-6666-4666-8666-666666666666",
      "version": 3,
      "sourceUrl": "https://shop.example/item",
      "product": {
        "name": "헤드폰",
        "imageUrl": null,
        "price": null,
        "currency": null,
        "brand": null,
        "merchant": null,
        "metadataCheckedAt": null,
        "nameSource": "AI",
        "imageSource": null,
        "imageMediaId": null
      },
      "category": {
        "id": "C026",
        "source": "AI",
        "missingReason": null,
        "name": "헤드폰",
        "parentId": "G003",
        "kind": "PUBLIC"
      },
      "purpose": {
        "id": null,
        "name": null,
        "colorKey": null,
        "iconKey": null,
        "source": "UNASSIGNED"
      },
      "analysis": {
        "status": "READY",
        "failureCode": null
      },
      "reviewStatus": "PENDING",
      "lifecycleStatus": "ACTIVE",
      "requiredAction": "CLASSIFICATION_REVIEW",
      "createdAt": "2026-10-24T01:00:00Z",
      "updatedAt": "2026-10-24T01:00:00Z",
      "manualCompletionAt": null,
      "allowedActions": [
        "EDIT",
        "DELETE",
        "REVIEW"
      ],
      "clientCreatedAt": null
    }
  ],
  "totalCount": 1,
  "nextCursor": null,
  "categoryFacets": [
    {
      "categoryId": "C026",
      "parentId": "G003",
      "count": 1
    }
  ],
  "unassignedCategoryCount": 0
}
```

### PUR-08: 200(최초 성공과 replay의 body 동일)

```json
{
  "items": [
    {
      "id": "55555555-5555-4555-8555-555555555555",
      "version": 4,
      "purposeId": "44444444-4444-4444-8444-444444444444"
    }
  ],
  "purposes": [
    {
      "id": "44444444-4444-4444-8444-444444444444",
      "candidateCount": 1,
      "membershipVersion": 2
    }
  ]
}
```

### PUR-05: 200(활성 영향 1개)

```json
{
  "version": 1,
  "impactToken": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "affectedCount": 1,
  "items": [
    {
      "id": "55555555-5555-4555-8555-555555555555",
      "clientSubmissionId": "66666666-6666-4666-8666-666666666666",
      "version": 3,
      "sourceUrl": "https://shop.example/item",
      "product": {
        "name": "헤드폰",
        "imageUrl": null,
        "price": null,
        "currency": null,
        "brand": null,
        "merchant": null,
        "metadataCheckedAt": null,
        "nameSource": "AI",
        "imageSource": null,
        "imageMediaId": null
      },
      "category": {
        "id": "C026",
        "source": "AI",
        "missingReason": null,
        "name": "헤드폰",
        "parentId": "G003",
        "kind": "PUBLIC"
      },
      "purpose": {
        "id": "44444444-4444-4444-8444-444444444444",
        "name": "출퇴근용",
        "colorKey": "CORAL",
        "iconKey": "MUSIC",
        "source": "AI"
      },
      "analysis": {
        "status": "READY",
        "failureCode": null
      },
      "reviewStatus": "PENDING",
      "lifecycleStatus": "ACTIVE",
      "requiredAction": "CLASSIFICATION_REVIEW",
      "createdAt": "2026-10-24T01:00:00Z",
      "updatedAt": "2026-10-24T01:00:00Z",
      "manualCompletionAt": null,
      "allowedActions": [
        "EDIT",
        "DELETE",
        "REVIEW"
      ],
      "clientCreatedAt": null
    }
  ],
  "nextCursor": null
}
```

### CAT-05: 200(활성 영향 1개)

```json
{
  "version": 1,
  "impactToken": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "affectedCount": 1,
  "items": [
    {
      "id": "55555555-5555-4555-8555-555555555555",
      "clientSubmissionId": "66666666-6666-4666-8666-666666666666",
      "version": 3,
      "sourceUrl": "https://shop.example/item",
      "product": {
        "name": "헤드폰",
        "imageUrl": null,
        "price": null,
        "currency": null,
        "brand": null,
        "merchant": null,
        "metadataCheckedAt": null,
        "nameSource": "AI",
        "imageSource": null,
        "imageMediaId": null
      },
      "category": {
        "id": "77777777-7777-4777-8777-777777777777",
        "source": "AI",
        "missingReason": null,
        "name": "내 헤드폰",
        "parentId": "G003",
        "kind": "CUSTOM"
      },
      "purpose": {
        "id": null,
        "name": null,
        "colorKey": null,
        "iconKey": null,
        "source": "UNASSIGNED"
      },
      "analysis": {
        "status": "READY",
        "failureCode": null
      },
      "reviewStatus": "PENDING",
      "lifecycleStatus": "ACTIVE",
      "requiredAction": "CLASSIFICATION_REVIEW",
      "createdAt": "2026-10-24T01:00:00Z",
      "updatedAt": "2026-10-24T01:00:00Z",
      "manualCompletionAt": null,
      "allowedActions": [
        "EDIT",
        "DELETE",
        "REVIEW"
      ],
      "clientCreatedAt": null
    }
  ],
  "nextCursor": null
}
```

### PUR-08: 409 소속 충돌

```json
{
  "error": {
    "code": "WISHLIST_ITEM_PURPOSE_CONFLICT",
    "requestId": "33333333-3333-4333-8333-333333333333",
    "details": {
      "itemId": "55555555-5555-4555-8555-555555555555",
      "currentVersion": 4
    }
  }
}
```

### CAT-06: 409 영향 변경

```json
{
  "error": {
    "code": "CATEGORY_DELETION_IMPACT_CONFLICT",
    "requestId": "33333333-3333-4333-8333-333333333333",
    "details": {}
  }
}
```

### PUR-08: 422 선택 항목 값 오류

```json
{
  "error": {
    "code": "INVALID_CANDIDATE_INPUT",
    "requestId": "33333333-3333-4333-8333-333333333333",
    "details": {
      "fields": [
        "items"
      ]
    }
  }
}
```
