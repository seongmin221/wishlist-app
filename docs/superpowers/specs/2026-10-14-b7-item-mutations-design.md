# B7 상품 사용자 변경 설계

> 2026-10-14 · **spec 초안 · 독립 리뷰·사용자 1차 리뷰 반영(2026-10-14)** · 제품·운영 정책 확정

## 목표와 범위

일반 편집(ITEM-04), 직접 보완(ITEM-07), 분류·목적 검토(ITEM-08), 삭제(ITEM-05), 재분석(ITEM-06)을 [구현 순서](../../architecture/server/mvp-api-implementation-order.md#b7--상품-사용자-변경)의 내부 순서대로 만든다. 공통 참조 검증·CAS·값 출처·membership 갱신을 먼저 만들고 다섯 명령이 공유한다. ITEM-03 상세에 목적 삭제 영향(deletionImpact)을 붙인다.

[B7 제품·운영 결정](../../history/product-planning/mvp/decisions/b7-item-mutation-policy-2026-10-14.md)은 2026-10-14 사용자 답변으로 확정했다.

**범위 밖:** 후보 일괄 이동·category/목적 삭제(B8), 중복 판단(B9), archive와 archived 상품의 종합 검증(B10), 실제 bucket·부하 시험(B11), 클라이언트 연결(C 묶음). `OwnerStructureLock` 잠금 방식 변경(결정 7). client/와 design/handoff/는 수정하지 않는다.

**통과 조건:** 하나의 저장 transaction, 필드 누락/optional null 구분, price/currency/sourceUrl 편집 거절, category 삭제 뒤 READY 재지정은 PATCH, PARTIAL/실패는 수동 완료, PROCESSING 편집 거절·삭제 허용, 수동 완료 뒤 재분석 거절, DEFERRED 미재노출, 사용자 목적 해제를 AI가 덮어쓰지 않음, 동시 edit/review의 stale version 409, 삭제 반복 204, 재분석 key replay에 job 하나, 이전 generation Worker의 성공·실패 무시. 후속 확인으로 ITEM-02·HOME-01/02·PUR-01/03·CAT-01 건수·순서가 변경 뒤 실제 데이터와 일치하고, B4 통과 조건의 anchor 복구를 실제 변경 흐름(ITEM-05로 anchor 삭제, ITEM-04 category 변경으로 anchor가 다른 범위로 이동)으로 반복한다. 구현 순서의 "비로그인/오프라인 local pending 전송을 인증 fake로 끝까지 검증"은 서버 쪽에서 다음으로 확인한다: fake 인증 owner가 과거 `clientCreatedAt`과 같은 key로 응답 유실 뒤 재전송(로그인 후 대기분 전송 모사) → 분석 → B7 변경 → 삭제. 기기 LocalSubmission 자체는 클라이언트 묶음의 범위다.

## 확정 결정 요약

| # | 결정 |
| --- | --- |
| 1 | 상품명 1~200, 브랜드 1~200 code point, `UserTextRules` 한 줄 규칙. 이름은 지울 수 없음, 브랜드는 null로만 지움. 내용 안전성 검사 없음 |
| 2 | 편집 취소 뒤에도 만든 category 유지 |
| 3 | ITEM-04가 category·목적을 바꾼 뒤 PENDING/DEFERRED 상품에 AI category·AI 목적이 남지 않으면 CONFIRMED |
| 4 | 사진 해제 시 외부 분석 이미지로 복귀(없으면 이미지 없음). 해제 media는 `unreferenced_since = now` |
| 5 | PROCESSING이 아닌 ACTIVE는 PATCH 가능. PATCH는 수동 완료를 기록하지 않음 |
| 6 | 신규 재분석 owner별 60초 10건, 초과 429·`Retry-After`. replay는 미집계, key는 계정 수명 보존 |
| 7 | `OwnerStructureLock`은 FOR UPDATE 유지 |

## 현재 코드와의 차이

| 영역 | 현재 | B7 변경 |
| --- | --- | --- |
| API | ITEM-01·02·03만 | PATCH·DELETE `/v1/wishlist-items/{id}`, PUT `…/manual-completion`, PUT `…/review-decision`, POST `…/analysis-attempts` |
| schema | `mutation_receipts`는 category·purpose 대상만 | V19: `wishlist_item_id` 대상 추가, `wishlist_items(owner_id,id)` unique |
| 정책 | `WishlistItemPolicy`가 allowedActions 계산 | 변경 없음. 각 명령의 허용 여부를 이 표에서 판정 |
| 상세 응답 | deletionImpact 없음 | 단일 상품 응답에 `deletionImpact` |
| media | 연결 경로 없음, `unreferenced_since`는 READY 때 기록 | 연결 때 null, 해제·교체·상품 삭제 때 now |

## 1. 데이터 모델 (V19)

마지막 migration은 V18이다. 운영 배포가 B11 이전이므로 B5·B6처럼 transaction 안에서 일반 index를 만든다.

```sql
alter table wishlist_items add constraint wishlist_items_owner_id_unique unique (owner_id, id);
alter table mutation_receipts add column wishlist_item_id uuid;
alter table mutation_receipts add constraint mutation_receipt_item_owner_fk
    foreign key (owner_id, wishlist_item_id) references wishlist_items(owner_id, id);
alter table mutation_receipts drop constraint mutation_receipt_single_target;
alter table mutation_receipts add constraint mutation_receipt_single_target
    check (num_nonnulls(category_id, purpose_id, wishlist_item_id) = 1);
```

- 상품 행은 hard delete하지 않으므로(DELETED tombstone) receipt FK가 깨지지 않는다.
- `ReceiptTarget`에 `WISHLIST_ITEM("wishlist_item_id")`를 더한다. 재분석 operation 이름은 `REANALYZE_WISHLIST_ITEM`이다.
- upgrade 테스트: V18 데이터(category·purpose receipt, 상품, media)를 둔 뒤 V19를 적용해 기존 receipt가 유지되고, 세 대상 중 정확히 하나만 허용되며, 다른 owner 상품을 가리키는 receipt가 FK로 거절되는지 확인한다.

## 2. 공통 명령 구조

### 잠금과 transaction

모든 변경은 `inTransaction` 하나에서 다음 순서로 진행한다. 외부 호출(서명·Cloud Tasks)은 commit 뒤에 한다.

1. `OwnerStructureLock.lock(owner)` — 기존 FOR UPDATE.
2. 상품을 잠금 없이 읽어(owner 잠금 아래라 다른 구조 변경과 Worker 반영이 끼어들 수 없다) 관련 목적 ID를 찾는다. 이전 목적과 새 목적을 lifecycle과 관계없이 **ID 순서**로 `for update`한다(`select id from purposes where owner_id=? and id = any(?) order by id for update`). 기존 `PurposeRepository.find(lock=true)`는 ACTIVE만 찾으므로 쓰지 않는다. 이 단계는 잠그기만 하고 판정하지 않는다.
3. 상품을 `for update`로 잠그고 다시 읽는다.
4. 이전 media와 새 media를 **ID 순서**로 `for update`한다(B6 인계, 필수). 정리 작업은 media 한 행만 잠그므로 deadlock이 없다.
5. 재분석·삭제는 그 뒤 해당 상품의 job을 ID 순서로 잠근다.
6. 잠근 행으로 아래 오류 순서(5~10)를 판정한다. 판정 실패는 rollback이므로 receipt·membership·media 변화가 남지 않는다.

owner → 목적 → 상품 → media → job 순서는 [상품 상태 저장 기반](../../architecture/server/wishlist-state-persistence.md#b3-목적-schema와-잠금)의 owner → purpose → item → job 규칙에 media를 item 뒤에 끼운 것이다. Worker 반영은 owner → item → job → purpose 순서지만 둘 다 owner 잠금을 먼저 잡으므로 순환 대기가 생기지 않는다. 사용자 category는 owner 잠금 아래 `deleted_at is null`을 읽기만 한다. category 삭제(B8)와 편집(CAT-04)도 owner 잠금을 잡기 때문이다.

### 오류 순서와 code

| 순서 | 조건 | 응답 |
| --- | --- | --- |
| 1 | 인증 없음 | 401 `UNAUTHORIZED` |
| 2 | 경로 ID 형식 | 400 `INVALID_WISHLIST_ITEM_ID` |
| 3 | ITEM-06 key 형식 | 400 `INVALID_IDEMPOTENCY_KEY` |
| 4 | body 구문·key·타입·null 허용 여부·ID 형식 | 422 `INVALID_WISHLIST_ITEM_INPUT`, `details.fields`(정렬) |
| 5 | 상품 없음·다른 owner·DELETED(삭제 제외) | 404 `WISHLIST_ITEM_NOT_FOUND` |
| 6 | `expectedVersion` 불일치 | 409 `WISHLIST_ITEM_VERSION_CONFLICT`, `details.currentVersion` |
| 7 | 현재 상태에서 허용되지 않는 명령 | 409 `WISHLIST_ITEM_STATE_CONFLICT`, `details.currentVersion` |
| 8 | 저장값과 다른 name·brand의 텍스트 규칙 위반 | 422 `INVALID_WISHLIST_ITEM_INPUT`, `details.fields` |
| 9 | 저장값과 다른 참조의 사용 불가. category → 목적 → media 순서로 첫 위반 하나 | 409 `CATEGORY_NOT_AVAILABLE` / `PURPOSE_NOT_AVAILABLE` / `MEDIA_NOT_AVAILABLE` |
| 10 | 재분석 한도 | 429 `WISHLIST_ITEM_REANALYSIS_RATE_LIMITED`, `Retry-After` |

- version과 상태 충돌은 code를 나눈다. B2·B3의 `*_VERSION_CONFLICT`와 맞추고, 상태 충돌은 [상태 API 오류 예시](../../architecture/wishlist-item-state-api.md#오류-계약)의 `WISHLIST_ITEM_STATE_CONFLICT`를 쓴다. 클라이언트는 두 경우 모두 상세를 다시 읽는다.
- B6 spec이 임시로 부른 `MEDIA_NOT_READY` 대신 `MEDIA_NOT_AVAILABLE` 하나로 없음·다른 owner·READY 아님(PENDING/FAILED/DELETING/DELETED)·다른 상품이 사용 중을 묶는다. category·목적과 같은 이름 규칙이며 클라이언트 행동(사진 다시 고르기)이 같다.
- 공개 category ID가 catalog에 없는 문자열이고 UUID도 아니면 422(`categoryId`)다. UUID인데 owner의 미삭제 사용자 category가 아니면 409 `CATEGORY_NOT_AVAILABLE`다.

### 입력 파서

기존 `parseJsonObject`로 JSON object만 받는다. 구문 오류, 알 수 없는 key, 잘못된 타입은 422다. 문자열은 `strictString`(JSON string만)으로 읽는다.

| 필드 | 규칙 |
| --- | --- |
| `expectedVersion` | 양의 정수, 필수(ITEM-04·07·08) |
| `name` | string. null 거절. 길이·문자 규칙은 아래 텍스트 규칙에서 |
| `brand` | null 또는 string |
| `categoryId` | string, `CategoryRef.parse` 성공. null 거절(category 해제는 B8 삭제에서만) |
| `purposeId` | null 또는 정규 UUID |
| `mediaId` | null 또는 정규 UUID |
| `decision` | `CONFIRM` 또는 `DEFER`(ITEM-08 필수) |

`price`·`currency`·`sourceUrl`·`merchant` 등은 알 수 없는 key로 422다. 저장 텍스트는 사용자가 보낸 그대로 둔다.

**텍스트 규칙(결정 1).** 파서는 타입과 null만 본다. `UserTextRules.valid(text, 200)`이고 `!isBlank`인지는 잠근 행과 비교해 **저장된 값과 다른** name·brand에만 적용한다(오류 순서 8). 추출 상품명에는 길이·문자 제한이 없으므로(`ProductMetadataParser`), 줄바꿈이 섞인 201자 이상의 추출명을 바꾸지 않고 그대로 보내도 ITEM-04는 no-op, ITEM-07은 그 값을 USER로 기록하며 거절하지 않는다.

**참조 규칙.** category·목적·media도 저장된 값과 같으면 사용 가능 여부를 검사하지 않는다(그 필드는 변경 없음). 예를 들어 화면이 받은 category ID를 그대로 돌려보낸 ITEM-07은 그 category가 이후 삭제됐어도 409가 아니다. 단, 삭제된 사용자 category는 B8 삭제 transaction이 참조를 비우므로 정상 데이터에서는 이런 저장값이 남지 않는다.

### 값 변경 규칙(`ItemChanges`)

필드마다 `FieldChange.Keep`/`Set`을 쓴다. 적용은 하나의 순수 함수(`ItemChangePlan`)가 현재 행과 변경으로 새 행을 계산하고, DB는 계산 결과를 한 UPDATE로 쓴다.

| 필드 | Set(값) | Set(null) |
| --- | --- | --- |
| name | 현재 값과 다르면 `product_name`, `name_source='USER'`, override `NAME` 추가 | (파서가 거절) |
| brand | 다르면 `product_brand`, override `BRAND` | 현재 brand가 null이 아니면 null + override `BRAND`(AI가 다시 채우지 않음). 이미 null이면 변경 없음이고 override도 더하지 않으므로 이후 재분석이 brand를 채울 수 있다 |
| category | 다르면 public/custom 컬럼 하나만 채우고 다른 하나는 반드시 null(`wishlist_category_exclusive_check`), `category_source='USER'`, `category_missing_reason=null` | (파서가 거절) |
| purpose | 다르면 `purpose_id`, `purpose_source='USER'` | `purpose_id=null`, `purpose_source='USER'`(명시적 해제). 이미 USER+null이면 변경 없음 |
| media | 다르면 연결(아래 media 절), `image_source='USER'`. override `IMAGE`는 더하지 않는다(USER 출처만으로 Worker가 이미지를 보호한다) | 연결된 media가 있으면 해제, `image_source = case when product_image_url is null then null else 'AI' end`, override `IMAGE`가 있으면 제거(legacy 방어) |

- 같은 값을 보내면 그 필드는 바뀌지 않고 출처도 그대로다(예: AI 이름과 같은 이름은 USER로 바뀌지 않는다). ITEM-07의 이름·category만 예외로 같은 값도 USER로 기록한다. 예외는 목적 null이다. UNASSIGNED/AI 상태에서 null을 보내면 "목적 미지정 확정"이라는 새 사용자 결정이므로 USER+null로 바뀐다.
- 계산 결과가 현재 행과 같으면 UPDATE를 하지 않고 version도 올리지 않는다(PUR-04·CAT-04와 같음). 응답은 현재 표현이다. ITEM-07·08은 상태가 반드시 바뀌므로 no-op이 없다.
- 실제로 바뀌면 `version = version + 1`, `updated_at = clock_timestamp()`를 한 번 기록한다.
- category가 USER로 바뀌어도 `predicted_category_id` 등 진단 값은 그대로 둔다.

### 목적 membership

목적이 바뀌면 같은 transaction에서 `PurposeMembership.recordTransition(from, to)`를 부른다. 이전 목적은 membershipVersion만, 새 목적은 membershipVersion과 activity(`CANDIDATE_ADDED`)를 갱신한다(B3 결정: 후보 제거는 순서를 바꾸지 않음). 목적 해제·상품 삭제는 `to = null`이다.

### media 연결·해제 (B6 인계)

- 새 media는 owner의 `status='READY'`이고 다른 상품이 참조하지 않아야 한다. 참조 확인은 media를 잠근 뒤 별도 문장으로 `wishlist_items where media_asset_id = ? and id <> ?`를 읽는다. 위반은 `MEDIA_NOT_AVAILABLE`다. 같은 media를 다시 보내면 변경 없음이다.
- 연결: `update media_assets set unreferenced_since = null, updated_at = clock_timestamp()`. `updated_at` 갱신은 B6 정리 쪽 조건 재평가를 위한 추가 방어다.
- 해제·교체·상품 삭제: 이전 media에 `unreferenced_since = clock_timestamp(), updated_at = clock_timestamp()`. 이전 media가 이미 READY가 아니면(정리됨) 상태를 바꾸지 않는다.
- partial unique index(`wishlist_items_media_asset_unique`)가 마지막 방어선이다. unique 위반이 나면 transaction을 되돌리고 `MEDIA_NOT_AVAILABLE`로 바꾼다. owner 잠금 아래에서는 정상 경로로 생기지 않는다.

### 검토 상태 규칙 (결정 3)

ITEM-04에서 category 또는 목적 필드가 실제로 바뀐 경우에만 적용한다.

```text
if review in (PENDING, DEFERRED)
   and not (category_source == 'AI' and (category_id != null or custom_category_id != null))
   and not (purpose_source == 'AI' and purpose_id != null)
then review = CONFIRMED
```

name·brand·media만 바뀐 PATCH는 검토 상태를 바꾸지 않는다. NOT_REQUIRED·CONFIRMED는 그대로다. 이 판정은 `ReviewAfterEdit` 순수 함수로 두고 표 테스트한다.

### 허용 판정

각 명령은 잠근 행으로 `WishlistItemPolicy.evaluate`를 다시 계산해 허용 행동을 확인한다. 정책 표는 바꾸지 않는다.

| 명령 | 필요한 allowedAction | 비고 |
| --- | --- | --- |
| ITEM-04 | `EDIT` | ACTIVE·비PROCESSING. ARCHIVED·PROCESSING은 409 STATE |
| ITEM-07 | `MANUAL_COMPLETE` | ACTIVE + PARTIAL/FAILED_* + 수동 미완료 |
| ITEM-08 | `REVIEW` | ACTIVE + READY + 이름·category + PENDING + 수동 미완료 |
| ITEM-06 | `REANALYZE` | ACTIVE + FAILED_RETRYABLE + 수동 미완료 |
| ITEM-05 | `DELETE` 또는 이미 DELETED | ARCHIVED는 409 STATE |

## 3. 명령별 계약

모든 성공 응답(204 제외)은 ITEM-03과 같은 단일 상품 표현(`WishlistItemDto` + `deletionImpact`)이다. 응답은 commit 전 같은 transaction에서 다시 읽은 행이며, media 서명은 commit 뒤 transaction 밖에서 한다.

### ITEM-04 `PATCH /v1/wishlist-items/{id}`

```json
{ "expectedVersion": 3, "name": "노이즈 캔슬링 헤드폰", "brand": null, "categoryId": "C026", "purposeId": null, "mediaId": "uuid" }
```

`expectedVersion`과 편집 필드 하나 이상이 필요하다(없으면 422). 200과 갱신된 상품. 수동 완료 시각을 기록하지 않는다(결정 5). PARTIAL/실패 상품에서 이름·category를 채우면 requiredAction이 정보 보완에서 빠질 수 있지만 MANUAL_COMPLETE/REANALYZE 허용은 분석 상태로 계속 계산된다. READY 상품의 `CUSTOM_CATEGORY_DELETED` 재지정은 이 명령이다.

### ITEM-07 `PUT /v1/wishlist-items/{id}/manual-completion`

```json
{ "expectedVersion": 3, "name": "제품명", "categoryId": "uuid-or-C-id", "brand": "브랜드", "purposeId": "uuid", "mediaId": "uuid" }
```

`name`·`categoryId`·`expectedVersion` 필수, 나머지는 PATCH와 같은 생략/ null 의미다. 이름과 category는 같은 값이어도 USER 출처로 기록한다(사용자가 직접 보완을 완료했다는 기록). `manual_completion_at = clock_timestamp()`, `review_status = 'CONFIRMED'`, `category_missing_reason = null`. `analysis_status`·`analysis_failure_code`·진단 값은 유지한다. 이후 REVIEW·MANUAL_COMPLETE·REANALYZE는 허용되지 않는다(B0 정책).

### ITEM-08 `PUT /v1/wishlist-items/{id}/review-decision`

```json
{ "expectedVersion": 3, "decision": "CONFIRM", "categoryId": "C026", "purposeId": null }
```

`decision`·`expectedVersion` 필수, `categoryId`(null 거절)·`purposeId`(null=해제)는 선택이다. 값 변경 규칙은 ITEM-04와 같다: 저장값과 다른 값은 USER 출처가 되고, 저장값과 같은 값을 보내면 그 필드는 변경 없음이라 기존 출처(AI 포함)를 유지한다(목적 null은 UNASSIGNED/AI에서 USER+null로 바뀌는 ITEM-04 예외를 그대로 따름). `review_status`는 CONFIRMED 또는 DEFERRED다. 바뀌지 않은 AI 값의 출처는 AI로 남고 검토 상태가 보호한다(Worker 반영은 CONFIRMED/DEFERRED의 category·목적을 바꾸지 않는다). 확정한 상품의 빈 목적을 AI가 채우지 않는 B3 규칙도 그대로다. DEFERRED 상품은 HOME 검토 그룹 predicate(PENDING)에 들지 않으므로 자동 재노출이 없다. 이 명령은 이름·브랜드·사진을 받지 않는다.

### ITEM-05 `DELETE /v1/wishlist-items/{id}`

version을 받지 않는다. 결과:

- 없음·다른 owner: 404. 이미 DELETED: 204, 변경 없음.
- ARCHIVED: 409 `WISHLIST_ITEM_STATE_CONFLICT`(B10이 archive 삭제를 담당).
- ACTIVE(PROCESSING 포함): `lifecycle_status='DELETED'`, `version+1`, `updated_at`, `media_asset_id=null`(이전 media `unreferenced_since=now`). 진행 중 job(`GENERAL_PENDING/RUNNING`, `BROWSER_PENDING/RUNNING`)만 `CANCELLED`로 바꾸고 실행 identity를 해제한다(`transitionAnalysisJob`). 이미 끝난 COMPLETE·PARTIAL·FAILED·CANCELLED job은 stage와 진단 값을 바꾸지 않는다(분석 이력 보존). 목적 membership `from → null`. category·목적 참조와 진단 값은 tombstone에 남긴다.
- 발행 전 outbox는 남아도 된다. claim이 CANCELLED/DELETED를 보고 Ignored로 끝낸다. 실행 중 Worker는 token이 해제돼 중간·최종 쓰기가 모두 stale이 된다. 이미 예약한 AI 예산 정산은 기존 경로가 그대로 처리한다.

### ITEM-06 `POST /v1/wishlist-items/{id}/analysis-attempts`

`Idempotency-Key` 필수. body는 비어 있거나 `{}`만 허용한다(그 외 422). 처리 순서(owner 잠금 아래):

1. receipt 조회(owner + `REANALYZE_WISHLIST_ITEM` + key). 있으면 대상 상품이 경로 ID와 같을 때 replay: 현재 상품이 DELETED면 404, 아니면 200 + `Idempotency-Replayed: true` + 현재 표현. 다른 상품이면 409 `IDEMPOTENCY_KEY_REUSED`. replay는 상태·한도를 검사하지 않는다.
2. 상품 404, `REANALYZE` 미허용 409 STATE.
3. 한도: `MutationReceipts.retryAfterSeconds(owner, operation, 10, 60)`. 초과면 429.
4. 이전 generation의 진행 중 job이 있으면 CANCELLED(방어; FAILED_RETRYABLE이면 보통 이미 FAILED).
5. 상품: `current_generation+1`, `analysis_status='PROCESSING'`, `analysis_failure_code=null`, `version+1`, `updated_at`. 이름·category·목적·review·metadata·출처·override는 유지한다.
6. 새 job(generation N+1, `GENERAL_PENDING`)과 outbox(`GENERAL_ANALYSIS`, task 이름 `analysis-{jobId}-{generation}`), receipt(fingerprint = `{"itemId": …}`의 SHA-256)를 같은 transaction에 저장한다.
7. commit 뒤 `dispatchAfterCommit(eventId)`. B1 생성과 같이 일반 예외는 eventId·예외 타입만 경고 로그로 남기고 성공 응답을 유지한다(durable outbox를 maintenance가 발행). `CancellationException`은 잡지 않고 다시 던져 요청 취소를 전파한다. 이때도 item·job·outbox·receipt는 이미 commit돼 있으므로, 클라이언트가 같은 key로 재전송하면 replay(200, job 추가 없음)로 결과를 받고 미발행 outbox는 maintenance가 발행한다.

응답은 200과 PROCESSING 상품. 새 generation은 B5의 합산 예산(3회·30분)을 새로 받는다. 이후 Worker 반영은 기존 보호(USER/override, CONFIRMED/DEFERRED, 목적 USER+null)를 그대로 쓴다. B5 가격 규칙(페이지를 읽은 finish는 가격 쌍 교체)도 재분석에 그대로 적용된다.

### ITEM-03 deletionImpact

단일 상품 응답(ITEM-01 생성·replay, ITEM-03, ITEM-04/06/07/08)에만 붙이고 목록 카드(ITEM-02, HOME)에는 넣지 않는다.

- DTO: `WishlistItemDto.deletionImpact: DeletionImpactDto?`에 `@EncodeDefault(EncodeDefault.Mode.NEVER)`를 붙인다. `ApiJson`은 `encodeDefaults = true`라 그냥 nullable 필드를 더하면 `ReadCardDto`가 품은 카드마다 `"deletionImpact": null`이 나가기 때문이다. 카드 mapper는 값을 넣지 않으므로 key 자체가 없다. 카드 JSON에 key가 없음을 테스트로 고정한다.
- ITEM-01: `CreateWishlistItemService`가 생성·replay 결과와 함께 impact를 같은 transaction에서 읽고, `wishlistRoutes`의 `create`/`getItem` 함수형 시그니처가 impact를 포함한 결과를 받도록 바꾼다.

```json
"deletionImpact": { "purpose": { "id": "uuid", "name": "출퇴근 헤드폰", "candidateCount": 3, "remainingCount": 2 } }
```

- 단일 응답의 ACTIVE 상품에는 항상 `deletionImpact`가 있다. ACTIVE 목적에 연결돼 있으면 `purpose`를 채운다. `candidateCount`는 PUR-01/03과 같은 "그 목적의 ACTIVE 상품 수", `remainingCount = candidateCount - 1`. 0이면 클라이언트가 "빈 목적은 유지된다"를 안내한다.
- 목적 미지정이면 `{ "purpose": null }`. ARCHIVED·DELETED(생성 replay tombstone) 표현에는 key가 없다.
- **일관성.** 상품 행과 목적 영향은 하나의 snapshot에서 읽는다. ITEM-03은 지금의 autocommit 조회(문장마다 새 snapshot)를 `inTransaction(readOnly = true)`(REPEATABLE READ)로 바꾸고 상품 조회와 impact 조회를 그 transaction 안에서 실행한다. 그래야 두 읽기 사이에 다른 transaction이 상품을 다른 목적으로 옮기거나 후보를 더해도, 응답의 `purpose.id`는 응답 상품의 목적과 같고 `candidateCount`는 그 상품을 포함한 같은 시점의 수(`remainingCount ≥ 0`)다. 변경 명령(ITEM-01·04·06·07·08)은 owner 잠금을 쥔 쓰기 transaction 안에서 갱신 뒤 다시 읽는다. 목적 소속을 바꾸는 모든 경로(B7 명령·Worker 반영·B8 이후)가 owner 잠금을 먼저 잡으므로 READ COMMITTED에서도 두 읽기 사이에 소속이 바뀌지 않는다.
- 확인창을 연 뒤 다른 기기 변경으로 달라질 수 있다는 점은 ITEM-05의 version 없는 기존 계약대로 둔다.

## 4. 경합과 늦은 Worker

| 경합 | 결과 | 보장 |
| --- | --- | --- |
| 같은 expectedVersion의 PATCH 두 개 / PATCH와 review | 먼저 commit한 쪽 200, 나머지 409 VERSION | owner 잠금 + 상품 잠금 뒤 version 비교 |
| 삭제 두 번 동시 | 둘 다 204, 변경 한 번 | owner 잠금 뒤 DELETED 재확인 |
| 같은 key 재분석 두 개 동시 | job·outbox·receipt 하나, 둘 다 200(하나는 replay) | owner 잠금 아래 receipt 조회 |
| 재분석 뒤 이전 generation Worker의 성공·실패·재시도·복구 | 상품·job·outbox 불변 | generation·token 검사(B0) |
| 삭제 뒤 Worker 성공·실패 | 상품 불변, job CANCELLED 유지 | lifecycle·token 검사 |
| 연결과 media 정리 | 둘 중 하나만 성공 | media `for update` + 정리의 별도 참조 확인 문장(B6) |
| PATCH 직후 같은 상품 재분석 | 순서대로 반영, 사용자 값 보호 | owner 잠금, USER/override |

## 5. 테스트 계획

| 층 | 대상 |
| --- | --- |
| 순수 | 입력 파서(필드별 422, unknown key, null 의미, 길이 200/201, 제어문자·공백), `ItemChangePlan`(출처·override·no-op·목적 null), `ReviewAfterEdit` 표, 허용 판정 |
| 실제 PostgreSQL | V19 upgrade, 명령별 상태 전이, 참조 검증(삭제된 custom·다른 owner category/purpose/media, archived purpose, PENDING/DELETING media, 다른 상품 media), media `unreferenced_since`, membership/activity, 동시 PATCH·review 409(latch), 삭제 반복·동시, 재분석 key 동시 job 하나, 한도 429·Retry-After, 이전 generation Worker 성공/실패/재시도 무시, 삭제 뒤 Worker 무시, 사용자 목적 해제 보존, media 정리 경합 |
| HTTP | 다섯 route의 status·code·details·header, 401/400/404, 422 fields 정렬, 단일 응답의 deletionImpact·서명 |
| 읽기 일관성 | 변경 뒤 ITEM-02 category/purpose/미지정 목록, HOME-01 count·preview, HOME-02 그룹, PUR-01/03 후보 수·membershipVersion·활동순, CAT-01 count, anchor 삭제·범위 이동 뒤 window(`anchorResolved=false`) |
| 오류 순서 | stale version + ARCHIVED 목적 → VERSION, PROCESSING + stale version → VERSION, 변경 없는 긴 추출명 재전송 허용, STATE 409·429 뒤 receipt 없음 |
| 전체 흐름 | fake 인증·fake Worker로 지연 생성(과거 clientCreatedAt·key 재전송) → 실패 → 재분석 → READY → 검토(DEFER/CONFIRM) → 편집(사진 연결·해제) → 삭제, local 저장소 사진 포함 |

## 6. 문서 갱신

- 계약: 새 `docs/architecture/server/wishlist-item-mutation-api.md`(다섯 명령·오류·deletionImpact), [상태 API](../../architecture/wishlist-item-state-api.md)의 B7 링크, [B1 조회 계약](../../architecture/server/wishlist-item-read-api.md)의 deletionImpact, [media 계약](../../architecture/server/media-upload-api.md)의 연결 절, [상태 저장 기반](../../architecture/server/wishlist-state-persistence.md)의 B7 잠금·V19.
- 목록·순서: API 목록 ITEM-03~08 구현 상태와 CAT-03 문구, 구현 순서 B7 상태·미결정 표, server/specs/plans/decisions INDEX.
- 제품: [상품 확인과 편집](../../product/inspect-and-edit-a-product.md)의 입력 제한·사진 해제.
- 이력: `docs/history/architecture/server/b7-item-mutations-implementation-2026-10-1x.md`(RED/GREEN·회귀·리뷰 반영·B8/B10 인계).
- 클라이언트: `docs/architecture/client/server-integration-status.md`의 ITEM-04~08 서버 상태.

## 후속 묶음 인계(예상)

- B8: 목적·category 삭제는 같은 잠금 순서와 `PurposeMembership`을 쓴다. category 삭제가 만드는 `CUSTOM_CATEGORY_DELETED`의 재지정은 이미 ITEM-04로 동작한다.
- B10: archived 상품의 B7 명령 거절(409 STATE)은 이미 정책 표로 막히며, B10이 실제 ARCHIVED 데이터로 종합 검증한다. archive snapshot의 media 참조는 B10이 `MediaReferences`에 추가한다.
- B11: owner 잠금 경합 관측 뒤 FOR NO KEY UPDATE 재검토(결정 7).
