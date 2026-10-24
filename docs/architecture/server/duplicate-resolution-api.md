# B9 중복 비교와 선택 해소 API

> 2026-10-24 · 정책 확정 · DUP-01/DUP-02·Product cache·식별 값(V22)·중복 쌍/receipt(V23) 구현 · [구현 이력](../../history/architecture/server/b9-duplicate-resolution-implementation-2026-10-24.md) · [제품 정책](../../history/product-planning/mvp/decisions/b9-duplicate-resolution-policy-2026-10-24.md) · [설계](../../superpowers/specs/2026-10-24-b9-duplicate-resolution-design.md)

## 식별 기준과 쌍 종류

중복 쌍은 같은 owner의 ACTIVE 상품 두 개다. 늦게 만든 쪽(`createdAt`, 같으면 id)이 **new**, 먼저 만든 쪽이 **existing**이다. 근거(`bases`)는 추출 시점의 값으로만 정한다. 사용자의 이름·브랜드 편집은 영향이 없다.

| basis | 조건 |
| --- | --- |
| `CANONICAL_URL` | 두 상품의 canonical URL이 같다 |
| `GTIN` | JSON-LD GTIN(8/12/13/14자리, check digit 검증, 14자리 정규화)이 같다 |
| `BRAND_MPN` | 정규화한 브랜드와 모델 번호(MPN)가 함께 같다 |
| `SOURCE_URL` | 한쪽의 정규화 공유 URL 또는 canonical이 다른 쪽의 정규화 공유 URL 또는 canonical과 같다. canonical이 같으면 함께 붙는다 |

이름 유사도와 AI 판정은 쓰지 않는다. 판매처가 GTIN이나 브랜드+MPN을 구조화 데이터로 노출하지 않으면 판매처가 다른 동일 상품은 탐지하지 않는다.

쌍 종류(`kind`)는 저장하지 않는다. 응답 시점에 두 상품의 실제 분석 상태로 계산한다. new가 READY/PARTIAL이고 canonical이 있을 때만 쌍이 보인다.

| existing 상태 | kind | 조건 | allowedDecisions |
| --- | --- | --- | --- |
| READY / PARTIAL | `MATCH` | CANONICAL_URL·GTIN·BRAND_MPN 중 하나 | `KEEP_BOTH`, `DELETE_NEW`, `DELETE_EXISTING` |
| FAILED_RETRYABLE / FAILED_TERMINAL, 또는 페이지를 읽지 못한 READY/PARTIAL(canonical 없음) | `SAME_URL` | SOURCE_URL 또는 CANONICAL_URL | 위와 같음 |
| PROCESSING | `AWAITING_ANALYSIS` | 근거가 하나라도 있음 | `[]` |
| canonical이 있는 READY / PARTIAL | (보이지 않음) | SOURCE_URL만 있음. 추출 뒤 다른 상품으로 확인됐다 | — |

`bases`는 분석 finish(재시도 소진 포함)에서만 다시 계산한다. 판단한 쌍(DECIDED)은 재분석 뒤에도 바뀌지 않으며 다시 묻지 않는다. 근거가 사라진 OPEN 쌍은 WITHDRAWN이 되고, 다시 생기면 OPEN으로 돌아간다. 둘 중 한 상품이 ACTIVE가 아니면 행은 남지만 보이지 않는다.

## 상품 표현의 후보 수

`WishlistItemDto`에 `duplicateCandidates: {matchCount, sameUrlCount, awaitingAnalysisCount}`를 추가했다. 그 상품이 new인, 보이는 OPEN 쌍을 kind별로 센 값이다. 셋 다 0이면 key를 생략한다. 상세·목록·홈·후보·영향·생성 replay 응답에 똑같이 붙는다. 분류·목적 확인 카드의 "같은 상품으로 보이는 항목이 있어요" 안내 근거다. 안내 위치는 C6에서 정한다.

## 중복 후보 조회 DUP-01

`GET /v1/wishlist-items/{id}/duplicate-candidates?limit=&cursor=`

- `{id}`는 new 상품이다. 없음·다른 owner·DELETED는 404 `WISHLIST_ITEM_NOT_FOUND`다. ARCHIVED는 보이는 쌍이 없으므로 빈 목록이다.
- `limit` 기본 20, 최대 50. 알 수 없는/중복/빈 query와 잘못된 limit은 400 `INVALID_DUPLICATE_QUERY`다.
- 응답 `{item, candidates, totalCount, nextCursor}`.
  - `item`과 `candidates[].existingItem`은 `WishlistItemDto`다. 둘 다 `deletionImpact`(목적 이름·후보 수)를 포함한다.
  - `candidates[]`: `{id, kind, bases, version, allowedDecisions, existingItem}`.
- 정렬은 existing의 `createdAt DESC, id DESC`이고, 다음 페이지는 배타 경계다. `nextCursor`는 owner와 new 상품에 묶인 위치 힌트다. 다른 상품·owner의 cursor는 400 `INVALID_DUPLICATE_CURSOR`다. 모든 값은 하나의 REPEATABLE READ snapshot에서 읽는다.

## 중복 판단 DUP-02

`PUT /v1/wishlist-items/{id}/duplicate-decisions`, UUID `Idempotency-Key` 필수.

```json
{"candidateId":"…","decision":"DELETE_EXISTING","expectedCandidateVersion":1,"expectedItemVersion":4}
```

- `expectedItemVersion`은 지울 상품의 version이다(DELETE_NEW면 new, DELETE_EXISTING면 existing). DELETE_*에는 필수이고, KEEP_BOTH에는 넣으면 422다. 남는 상품의 변경으로는 거절하지 않는다.
- KEEP_BOTH: 쌍만 DECIDED로 기록한다. 두 상품의 version은 바뀌지 않는다.
- DELETE_NEW / DELETE_EXISTING: 쌍 기록과 함께 그 상품을 ITEM-05와 같은 방식으로 지운다. 진행 중 job 취소, 목적 membership 갱신, media 해제가 포함된다. new를 지우면 그 상품의 다른 쌍도 보이지 않는다.
- 판단 기록, 삭제, receipt는 하나의 transaction이다. review 상태(ITEM-08)와는 서로 바꾸지 않으며, 확정·보류한 상품도 판단할 수 있다.
- 200 응답 `{candidate:{id,decision,decidedAt,version}, deletedItemId, remainingItemIds, purposes:[{id,candidateCount,membershipVersion}]}`. `purposes`는 지운 상품의 목적이고, 없으면 `[]`다.
- 같은 key·같은 payload는 저장된 결과를 반환한다(`Idempotency-Replayed: true`). new를 지운 뒤에도 그렇다. 같은 key·다른 payload는 409다. receipt는 계정 수명 동안 보존한다. 판단은 쌍마다 한 번이다.

### 검사 순서와 오류

인증 → path → key → body → (transaction) owner 잠금 → receipt → new 상품 존재 → 쌍 조회·잠금(이 new의 쌍, WITHDRAWN 아님, 두 상품 ACTIVE) → 쌍 version → OPEN이고 실제 상태로 계산한 kind가 결정을 허용하는지 → 지울 상품의 목적 → 두 상품 ID순 → media → job → 지울 상품 version → 삭제 허용 상태 → 쓰기.

| HTTP | code | 조건 | details |
| --- | --- | --- | --- |
| 401 | UNAUTHORIZED | 인증 없음 | `{}` |
| 400 | INVALID_WISHLIST_ITEM_ID | path UUID 오류 | `{}` |
| 400 | INVALID_IDEMPOTENCY_KEY | key 누락·형식 오류 | `{}` |
| 400 | INVALID_DUPLICATE_QUERY / INVALID_DUPLICATE_CURSOR | DUP-01 query·cursor 오류 | `{}` |
| 422 | INVALID_DUPLICATE_DECISION_INPUT | object 아님·알 수 없는/누락 key는 `{}`. 값 오류는 `{"fields":[…]}`(`candidateId`, `decision`, `expectedCandidateVersion`, `expectedItemVersion` 오름차순) | 왼쪽 |
| 404 | WISHLIST_ITEM_NOT_FOUND | new 상품 없음·다른 owner·DELETED | `{}` |
| 404 | DUPLICATE_CANDIDATE_NOT_FOUND | 이 new의 쌍이 아님·WITHDRAWN·한쪽이 ACTIVE 아님 | `{}` |
| 409 | IDEMPOTENCY_KEY_REUSED | 같은 key 다른 payload | `{}` |
| 409 | DUPLICATE_CANDIDATE_VERSION_CONFLICT | 쌍 version 불일치 | `{"currentVersion":2}` |
| 409 | DUPLICATE_DECISION_NOT_ALLOWED | 이미 판단함, 또는 계산한 kind가 판단을 허용하지 않음(AWAITING_ANALYSIS·숨김) | `{"currentVersion":2}` |
| 409 | WISHLIST_ITEM_VERSION_CONFLICT / WISHLIST_ITEM_STATE_CONFLICT | 지울 상품의 version·상태 | `{"itemId":"…","currentVersion":4}` |

## Product cache와 snapshot

- 새로 fetch한 추출이 `Complete`(이름 있음)일 때만 canonical URL 기준 `products`에 저장한다. 정규화 공유 URL은 alias로 묶는다. general·browser lane 모두 metadata 저장과 같은 guarded transaction에서 쓴다. stale claim은 쓰지 않는다.
- 신규 저장(generation 1)의 general 실행만 fetch 전에 cache를 찾는다. 추출 시각부터 7일이 지나지 않은 행만 쓴다. 적중하면 fetch를 생략하지만 category/목적은 그 owner로 분류한다. 적중 실행은 cache를 다시 쓰지 않는다. 재분석(ITEM-06)은 항상 페이지를 다시 읽는다.
- item은 cache를 한 번 복사한 독립 snapshot이다. `metadata_checked_at`은 cache 추출 시각이고, `product_cache_id`는 출처 기록일 뿐 동기화에 쓰지 않는다. cache가 갱신되어도, item을 편집해도 서로 바뀌지 않는다.

## 완전한 JSON 예시

### DUP-01: 200

```json
{"item":{"id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","clientSubmissionId":"…","version":3,"sourceUrl":"https://shop-a.example/products/1",
  "product":{"name":"마샬 MAJOR V","imageUrl":"https://…","price":229000,"currency":"KRW","brand":"Marshall","merchant":"shop-a.example",
    "metadataCheckedAt":"2026-10-24T01:00:00Z","nameSource":"AI","imageSource":"AI"},
  "category":{"id":"C026","source":"AI","name":"헤드폰","parentId":"…","kind":"PUBLIC"},
  "purpose":{"id":"…","name":"출퇴근 헤드폰","colorKey":"CORAL","iconKey":"MUSIC","source":"AI"},
  "analysis":{"status":"READY"},"reviewStatus":"PENDING","lifecycleStatus":"ACTIVE","requiredAction":"CLASSIFICATION_REVIEW",
  "createdAt":"2026-10-24T01:00:00Z","updatedAt":"2026-10-24T01:00:05Z","allowedActions":["EDIT","REVIEW","DELETE"],
  "deletionImpact":{"purpose":{"id":"…","name":"출퇴근 헤드폰","candidateCount":2,"remainingCount":1}},
  "duplicateCandidates":{"matchCount":1,"sameUrlCount":0,"awaitingAnalysisCount":0}},
 "candidates":[{"id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","kind":"MATCH","bases":["GTIN","BRAND_MPN"],"version":1,
   "allowedDecisions":["KEEP_BOTH","DELETE_NEW","DELETE_EXISTING"],
   "existingItem":{"id":"cccccccc-cccc-4ccc-8ccc-cccccccccccc","…":"WishlistItemDto(deletionImpact 포함)"}}],
 "totalCount":1,"nextCursor":null}
```

`item`의 나머지 필드는 기존 `WishlistItemDto` 직렬화 규칙(optional null·생략)을 따른다.

### DUP-02: 200(최초와 replay의 body 동일)

```json
{"candidate":{"id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb","decision":"DELETE_EXISTING","decidedAt":"2026-10-24T01:02:03.123456Z","version":2},
 "deletedItemId":"cccccccc-cccc-4ccc-8ccc-cccccccccccc","remainingItemIds":["aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"],
 "purposes":[{"id":"dddddddd-dddd-4ddd-8ddd-dddddddddddd","candidateCount":1,"membershipVersion":5}]}
```

### DUP-02: 409 판단 불가(기존 상품 재분석 중)

```json
{"error":{"code":"DUPLICATE_DECISION_NOT_ALLOWED","requestId":"…","details":{"currentVersion":1}}}
```

### DUP-02: 422 값 오류

```json
{"error":{"code":"INVALID_DUPLICATE_DECISION_INPUT","requestId":"…","details":{"fields":["expectedItemVersion"]}}}
```
