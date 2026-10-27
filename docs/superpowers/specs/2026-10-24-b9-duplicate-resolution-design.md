# B9 중복 비교와 선택 해소 설계

> 2026-10-24 · 정책 Q1~Q3 사용자 확정 · builder check 반영(kind 계산·legacy 식별 값) · 구현 완료([구현 이력](../../history/architecture/server/b9-duplicate-resolution-implementation-2026-10-24.md))

## 의도와 범위

B8(PR #24) 병합 지점 `ef2077d`에서 B9를 구현한다. 순서는 상품 식별 기준 → Product cache와 item snapshot 복사 → 중복 candidate/decision 저장 → DUP-01 → DUP-02다. 사용자가 같은 상품을 두 번 저장해도 저장은 막지 않는다. 대신 추출이 끝난 뒤 기존 항목과 비교해 둘 다 두기, 새 항목 지우기, 기존 항목 지우기를 고르게 한다. 고른 판단은 영속 기록한다.

범위 밖: 클라이언트 C10(비교 시트 연결·안내 위치), B10 archive lifecycle, B11 배포, 이름 유사도·AI 기반 동일 상품 판정.

근거: [구현 순서 B9](../../architecture/server/mvp-api-implementation-order.md#b9--중복-비교와-판단), [inventory DUP-01/02](../../architecture/server/mvp-api-inventory.md), [중복 결정](../../history/product-planning/mvp/decisions/duplicate-items.md), [cache 결정](../../history/product-planning/mvp/decisions/product-cache-snapshot.md), [구매 후보 정리](../../product/organize-candidates.md#중복-후보-비교와-정리), [디자인 결정 2026-10-02](../../design/decisions.md), 디자인 보드 `FDuplicateCompare*`·`FDuplicateConfirm*`, [B8 인계](../../history/architecture/server/b8-candidate-move-deletions-implementation-2026-10-24.md).

## 이번 세션 확정 정책

| # | 질문 | 확정 |
| --- | --- | --- |
| Q1 | 다른 판매처 동일 상품의 판단 근거 | **식별 코드만 사용한다.** 페이지 구조화 데이터의 GTIN이 일치하거나, 정규화한 브랜드+모델 번호(MPN)가 일치할 때만 같은 상품으로 본다. 이름 유사도와 AI 판정은 쓰지 않는다. 코드를 노출하지 않는 판매처 쌍은 탐지하지 못한다 |
| Q2 | 후보가 여럿일 때 판단 단위 | **후보 쌍별로 판단한다.** 새 항목과 각 기존 항목을 1:1로 판단한다. 새 항목 지우기는 그 새 항목의 남은 쌍을 모두 정리한다 |
| Q3 | 검토에 들어가지 않는 새 항목(PARTIAL, 연결을 사용자가 모두 정한 항목)의 후보 | **후보만 저장한다.** 서버는 후보를 기록하고 DUP-01/02를 언제든 허용한다. 화면 안내 위치는 C10에서 정한다 |

## 보존할 규칙

- 같은 URL을 새 Idempotency-Key로 저장하면 항상 새 item을 만든다. 같은 key의 재전송은 기존 item을 반환한다(ITEM-01 불변).
- 중복 탐지는 저장을 차단하거나 병합하지 않는다. 사용자 canonical 유일성 제약을 두지 않는다.
- `WishlistItem`은 독립 snapshot이다. cache에서 한 번 복사한 뒤 어느 방향으로도 동기화하지 않는다. 화면은 cache를 읽지 않는다.
- cache 적중 때도 category와 목적은 owner별로 새로 판단한다. 따라서 분석이 끝날 때까지 item은 PROCESSING이다.
- 모든 쓰기는 기존 owner 구조 잠금을 먼저 잡는다. 순서는 owner → (candidate) → 목적 ID순 → 상품 ID순 → media → job이다. 다른 owner의 자원·존재·version을 노출하지 않는다.
- review 결정(ITEM-08)과 중복 결정(DUP-02)은 서로의 상태를 바꾸지 않는다.
- ARCHIVED/DELETED item은 후보 대상이 아니다. archive에만 있는 동일 상품은 새 활성 item으로 남는다.
- 기존 V1~V21 migration은 수정하지 않는다.

## 1. 상품 식별 기준

세 가지 식별 근거(`bases`)를 쓴다. 모두 추출 시점의 값이며 사용자 편집(이름·브랜드)은 식별에 영향이 없다.

| basis | 값 | 비교 |
| --- | --- | --- |
| `CANONICAL_URL` | 기존 `UrlCanonicalizer.choose` 결과(`wishlist_items.canonical_url`) | 문자열 완전 일치. variant query는 보존되므로 다른 옵션 URL은 다른 상품이다 |
| `GTIN` | JSON-LD Product의 `gtin`/`gtin8`/`gtin12`/`gtin13`/`gtin14` | 숫자 외 공백·하이픈 제거 → 8/12/13/14자리 → mod-10 check digit 검증 → 0을 채워 14자리. 전부 0이면 버린다. 한 Product에서 서로 다른 GTIN이 나오면 null |
| `BRAND_MPN` | 추출 brand + JSON-LD Product `mpn` | brand: NFKC·소문자·문자/숫자 외 제거. mpn: NFKC·대문자·공백/`-`/`_`/`.`/`/` 제거, 3~64자. 둘 다 있을 때만 `brand|mpn` key |

- 기존 parser의 `ambiguous`(이름이 다른 Product 여러 개)이면 GTIN·MPN을 모두 null로 둔다. 같은 이름의 Product가 여럿(옵션별 node)이면 이름 없는 node를 포함한 모든 node가 유효한 값을 가지고 정규화 결과가 같을 때만 쓴다. 값이 없거나 하나라도 유효하지 않은 node가 있으면 null이다(최종 리뷰·PR 리뷰 반영). `sku`는 판매처 내부 코드이므로 쓰지 않는다.
- URL 관계(`SOURCE_URL`)는 동일성 근거가 아니라 안내용이다. 새 항목의 정규화 공유 URL·canonical 중 하나가 기존 항목의 정규화 공유 URL·canonical 중 하나와 같으면 URL 관계가 있다. 정규화는 `UrlCanonicalizer.normalize`다.

## 2. Product cache와 item snapshot

### 저장

- `products`: canonical URL(unique), 추출 metadata(이름·설명·이미지 URL·brand·가격/통화·판매처·GTIN·MPN key), `extracted_at`.
- `product_url_aliases`: 정규화 공유 URL(unique) → product. 짧은 링크·redirect URL에서도 cache를 찾기 위해서다. 원본 공유 URL(추적 query 포함)은 저장하지 않는다.
- 새로 fetch한 추출이 `Complete`일 때만 upsert한다(general·browser 공통). 같은 canonical이면 metadata와 `extracted_at`을 새 값으로 바꾼다. Partial·NeedsBrowser·실패·차단 주소는 쓰지 않는다. cache 적중으로 처리한 실행은 cache를 다시 쓰지 않는다. 그래야 `extracted_at`이 늘어나지 않는다.
- cache 쓰기는 metadata 저장과 같은 guarded transaction 안에서 한다. claim이 stale이면 쓰지 않는다. *내 판단:* 결과는 공개 페이지의 사실이지만, stale 실행의 쓰기를 막는 기존 규칙과 같게 둔다.

### 조회와 복사

- general Worker는 claim 뒤 fetch 전에 cache를 찾는다. 정규화 공유 URL로 alias를 찾거나, canonical이 정규화 공유 URL과 같은 product를 찾는다. 그중 `extracted_at > DB now - 7일`인 행만 적중이다.
- 적중하면 fetch하지 않고 cache metadata를 job pending에 저장한다. 그 뒤 분류는 지금과 같다(owner의 category 후보·목적 후보). finish의 기존 병합 규칙으로 item에 복사한다.
- 가격 확인 시각: cache 적중 item의 `metadata_checked_at`은 처리 시각이 아니라 product의 `extracted_at`이다. job에 `pending_checked_at`을 두고, fresh 추출이면 null(= finish 시각)로 둔다.
- item에 `product_cache_id`(출처 추적용, 동기화 아님)를 기록한다. cache 행이 나중에 바뀌어도 item은 바뀌지 않는다. item 편집도 cache를 바꾸지 않는다.
- item에 `normalized_source_url`을 생성 시 기록한다. 기존 행은 null이다(아래 탐지 fallback 참고). item에 `product_gtin`, `product_mpn_key`를 추가한다. pending에도 같은 값을 둔다. 병합은 가격처럼 page read가 있으면 null 포함 교체하고, 없으면 유지한다.

## 3. 중복 후보 탐지

### 저장하는 것과 계산하는 것

쌍에는 **식별 관계(`bases`)만 저장**한다. 쌍 종류(`kind`)와 허용 결정은 읽기·결정 시점에 두 item의 **실제 분석 상태**로 계산한다. 분석 상태는 finish 밖(재시도 소진·lease 만료 복구·pending 복구·재분석 시작)에서도 바뀐다. 반면 식별 값(canonical·GTIN·MPN key)은 finish의 metadata 병합에서만 바뀐다. 따라서 bases 재계산은 finish 한 곳에서 충분하고, 상태 변화는 별도 hook 없이 바로 반영된다. *내 판단(builder check 반영).*

### bases 재계산 시점과 방향

- 분석 finish transaction(`finishLocked`, owner·item 잠금 보유)이 item을 갱신한 직후 그 item X의 쌍 bases를 다시 계산한다. 결과 상태(READY/PARTIAL/FAILED)와 무관하다. stale·재시도·browser 전환 경로에서는 식별 값이 바뀌지 않으므로 하지 않는다.
- 쌍의 방향은 `(created_at, id)`로 정한다. 늦게 만든 쪽이 `new`, 먼저 만든 쪽이 `existing`이다. X는 어느 쪽이든 될 수 있다. 상대 Y는 같은 owner의 ACTIVE item만이다.
- bases: `CANONICAL_URL`·`GTIN`·`BRAND_MPN`은 두 item에 저장된 값이 같을 때다(이전 generation 값 포함). `SOURCE_URL`은 1절의 URL 관계다. 정렬은 이 순서다. bases가 하나도 없으면 쌍이 없다.

### 쌍 종류(kind) — 계산

new의 실제 상태가 READY/PARTIAL이고 canonical이 있을 때만 쌍이 보인다. new가 PROCESSING(재분석 중 포함)이거나 FAILED_*이면 보이지 않는다(*내 판단:* 비교할 최신 상품 정보가 없다).

| existing 실제 상태 | kind | 조건 | 허용 결정 |
| --- | --- | --- | --- |
| READY / PARTIAL | `MATCH` | bases에 CANONICAL_URL·GTIN·BRAND_MPN 중 하나 이상 | 셋 다 |
| canonical이 있는 READY / PARTIAL | (숨김) | SOURCE_URL만 있음. 추출 뒤 다른 상품으로 확인됐다 | — |
| FAILED_RETRYABLE / FAILED_TERMINAL, 또는 페이지를 읽지 못한 READY/PARTIAL(canonical 없음, 최종 리뷰 반영) | `SAME_URL` | bases에 SOURCE_URL 또는 CANONICAL_URL. 실패 항목을 같은 상품으로 단정하지 않는다 | 셋 다 (*내 판단:* 사용자가 실패 항목을 정리할 수 있어야 한다) |
| PROCESSING | `AWAITING_ANALYSIS` | bases가 하나라도 있음 | 없음. 기존 항목의 분석이 끝나면 kind가 바뀐다 |

- 수동 완료 여부와 review 상태는 kind에 영향이 없다.
- 계산은 SQL 식 하나(`DuplicateKinds`)로 두고 DUP-01·상품 DTO 후보 수·DUP-02가 같이 쓴다.

### 저장과 재계산

`duplicate_candidates`: id, owner, new_item_id, existing_item_id, bases, status(`OPEN`/`DECIDED`/`WITHDRAWN`), decision, decided_at, version, created_at, updated_at. `(new_item_id, existing_item_id)`당 한 행이다.

- 새 쌍: OPEN, version 1.
- OPEN 쌍의 bases가 바뀌면 갱신하고 version +1. 같으면 쓰지 않는다.
- OPEN 쌍의 bases가 비면 WITHDRAWN, version +1. 나중에 다시 생기면 OPEN, version +1로 되살린다.
- **DECIDED 쌍은 재계산에서 바꾸지 않는다.** KEEP_BOTH는 재분석 뒤에도 유지되며 다시 묻지 않는다.
- 상대 item의 삭제·archive는 행을 바꾸지 않는다. 읽기에서 두 item 모두 ACTIVE인 쌍만 보인다. 그래서 ITEM-05·B8·B10 경로를 수정하지 않는다.
- 기존 행의 `normalized_source_url`이 null이면 Kotlin에서 `source_url`을 정규화해 비교한다. 대상은 null 행만이다. 식별 basis는 인덱스가 있는 컬럼(canonical·GTIN·MPN key) 동등 조회로 찾는다.

## 4. 상품 표현에 후보 수 추가

`WishlistItemDto`에 `duplicateCandidates: {matchCount, sameUrlCount, awaitingAnalysisCount}`를 추가한다. item이 `new`인 OPEN 쌍 중 두 item이 모두 ACTIVE이고 계산한 kind가 있는 것만 kind별로 센다. 세 값이 모두 0이면 key를 생략한다. 기존 card/detail JSON과 같은 생략 방식이다. 공용 projection(`WishlistItemRowMapper`)에 넣으므로 상세·목록·홈·replay에 같이 나온다. review 카드의 "같은 상품으로 보이는 항목이 있어요" 안내 근거다. 안내 위치는 C10에서 정한다(Q3).

## 5. DUP-01 계약

`GET /v1/wishlist-items/{id}/duplicate-candidates?cursor=&limit=` (기본 20, 최대 50)

- `{id}`는 new item이다. owner item이 없거나 DELETED이면 404 `WISHLIST_ITEM_NOT_FOUND`. ARCHIVED이면 보이는 쌍이 없으므로 빈 목록이다.
- 응답: `{item, candidates, totalCount, nextCursor}`
  - `item`: new item의 `WishlistItemDto`. 새 항목 지우기 확인에 쓰도록 `deletionImpact`를 포함한다.
  - `candidates[]`: `{id, kind, bases, version, allowedDecisions, existingItem}`. `existingItem`은 `WishlistItemDto`이며 기존 항목 지우기 확인에 쓰도록 `deletionImpact`를 포함한다(목적 이름·후보 수).
  - 보이는 OPEN 쌍만 반환한다. DECIDED·WITHDRAWN, 상대가 ACTIVE가 아닌 쌍, 계산한 kind가 없는 쌍(숨김)은 제외한다. `totalCount`도 같은 기준이다. `kind`·`allowedDecisions`는 응답 시점의 실제 상태로 계산한 값이다.
- 정렬은 existing의 `createdAt DESC, id DESC` keyset이다. cursor는 owner와 new item ID에 묶는다. 모든 값은 하나의 REPEATABLE READ snapshot에서 읽는다.

## 6. DUP-02 계약

`PUT /v1/wishlist-items/{id}/duplicate-decisions`, UUID `Idempotency-Key` 필수.

```json
{"candidateId":"…","decision":"DELETE_EXISTING","expectedCandidateVersion":1,"expectedItemVersion":4}
```

- `decision`: `KEEP_BOTH` | `DELETE_NEW` | `DELETE_EXISTING`.
- `expectedItemVersion`: 지울 item(DELETE_NEW면 new, DELETE_EXISTING면 existing)의 version이다. DELETE_*에는 필수이고 KEEP_BOTH에는 넣으면 안 된다(422). 남는 item의 변화로는 거절하지 않는다. 확인창이 보여준 것은 지울 항목과 쌍뿐이기 때문이다.
- 결과:
  - KEEP_BOTH: 쌍을 DECIDED/KEEP_BOTH로 기록한다. 두 item 모두 바뀌지 않는다(version 불변).
  - DELETE_NEW: 쌍을 DECIDED/DELETE_NEW로 기록하고 new item을 ITEM-05와 같은 방식으로 삭제한다(job 취소·목적 membership·media 해제). new의 다른 쌍은 읽기에서 사라진다.
  - DELETE_EXISTING: 쌍을 DECIDED/DELETE_EXISTING으로 기록하고 existing을 같은 방식으로 삭제한다. new의 review·연결은 그대로다.
  - 판단 기록, 삭제, receipt는 하나의 transaction이다. 하나라도 실패하면 모두 rollback한다.
- 200 응답: `{candidate:{id,decision,decidedAt,version}, deletedItemId, remainingItemIds, purposes:[{id,candidateCount,membershipVersion}]}`. `purposes`는 삭제된 item의 목적이며, 없으면 빈 배열이다. `remainingItemIds`는 쌍에서 남는 item의 ID 오름차순이다.
- 같은 key·같은 payload는 저장된 결과를 반환한다(`Idempotency-Replayed: true`). new item이 이미 삭제된 뒤에도 그렇다. 같은 key·다른 payload는 409 `IDEMPOTENCY_KEY_REUSED`. receipt는 계정 수명 동안 보존한다(B8과 같음).
- 판단은 쌍마다 한 번이다. 끝난 판단을 다시 여는 API는 없다.
- review 상태와 무관하게 허용한다(Q3). ITEM-08은 쌍을 바꾸지 않는다.

### 검사 순서

인증 → path UUID → key → body 형식 → (transaction) owner 잠금 → receipt(일치 시 replay, 불일치 409) → new item 존재(owner, not DELETED; 아니면 404) → 쌍 조회·잠금(이 new에 속하고 WITHDRAWN이 아니며 두 item 모두 ACTIVE여야 한다. 아니면 404 `DUPLICATE_CANDIDATE_NOT_FOUND`) → 쌍 version(409 `DUPLICATE_CANDIDATE_VERSION_CONFLICT {currentVersion}`) → OPEN이고, owner 잠금 아래 두 item의 실제 상태로 다시 계산한 kind가 MATCH/SAME_URL인지(409 `DUPLICATE_DECISION_NOT_ALLOWED {currentVersion}`; 숨김 kind도 여기에 해당) → 지울 item의 목적 잠금 → 두 item ID순 잠금 → media → job → 지울 item version(409 `WISHLIST_ITEM_VERSION_CONFLICT {itemId,currentVersion}`) → 삭제 허용 상태(409 `WISHLIST_ITEM_STATE_CONFLICT`) → 쓰기.

### 오류

| HTTP | code | 조건 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 인증 없음 |
| 400 | INVALID_WISHLIST_ITEM_ID | path UUID 오류(기존 code 재사용) |
| 400 | INVALID_IDEMPOTENCY_KEY | key 누락·형식 오류 |
| 400 | INVALID_DUPLICATE_QUERY / INVALID_DUPLICATE_CURSOR | DUP-01 query·cursor 오류, 다른 item/owner cursor |
| 422 | INVALID_DUPLICATE_DECISION_INPUT | body 구조 오류는 `{}`, 값 오류는 `{"fields":[…]}` |
| 404 | WISHLIST_ITEM_NOT_FOUND / DUPLICATE_CANDIDATE_NOT_FOUND | 위 검사 순서 |
| 409 | IDEMPOTENCY_KEY_REUSED, DUPLICATE_CANDIDATE_VERSION_CONFLICT, DUPLICATE_DECISION_NOT_ALLOWED, WISHLIST_ITEM_VERSION_CONFLICT, WISHLIST_ITEM_STATE_CONFLICT | 위 검사 순서 |

## 구현 경계

- `app/extraction`: parser의 GTIN/MPN 추출·정규화(`ProductIdentifiers`), `Metadata`·pending 확장.
- `app/product`(신규): `ProductCacheRepository`(lookup/upsert/alias). general processor가 fetch 전 lookup을 하고, 두 lane의 metadata 저장 시 upsert한다.
- `app/duplicates`(신규): `DuplicateDetector`(finish 안에서 호출), `DuplicateReadService`(DUP-01), `DuplicateDecisionService`(DUP-02), receipt.
- `ItemMutationService.delete`의 잠금 뒤 삭제 단계를 내부 함수로 분리해 DUP-02가 재사용한다. ITEM-05의 외부 동작은 바꾸지 않는다.
- HTTP: B8과 같은 parser·오류 envelope·IO dispatcher·CancellationException 전파 방식을 쓴다.
- migration V22(cache·식별 컬럼), V23(중복 쌍·receipt)을 하위 묶음 커밋에 맞춰 추가한다.

## 검증과 완료 기준

실제 PostgreSQL/Testcontainers로 검증한다. 하위 묶음마다 커밋한다. mock만으로 완료를 판정하지 않는다.

- **식별:** GTIN 각 길이·check digit 실패·전부 0·여러 GTIN, MPN 정규화·brand 없음, ambiguous Product.
- **cache:** 같은 URL 새 key → 새 item(cache 적중으로 fetch 0회). 다른 owner 두 명이 같은 cache로 각자 다른 category/목적 판단을 받는다. cache 갱신 뒤에도 기존 item snapshot이 그대로다. item 편집이 cache를 바꾸지 않는다. 7일 경계에서 만료 뒤 fetch한다. PARTIAL·실패·NeedsBrowser는 저장하지 않는다. cache 적중 실행은 extracted_at을 바꾸지 않는다. checked_at은 cache 추출 시각이다. alias로 redirect URL이 적중한다. stale claim은 cache를 쓰지 않는다.
- **탐지:** 상태 행렬(READY/PARTIAL/FAILED/PROCESSING × CANONICAL/GTIN/BRAND_MPN/URL). finish 밖 상태 변화(lease 만료 복구로 FAILED, 재분석 시작으로 PROCESSING)에서 kind가 바로 바뀐다. 오래된 항목이 나중에 끝날 때 방향을 확인한다. AWAITING→MATCH/SAME_URL 전환, WITHDRAWN과 재개방, 재분석 뒤 DECIDED KEEP_BOTH 유지, 다중 후보(new 하나와 existing 셋, 연쇄 A<B<C), owner 격리, ARCHIVED/DELETED 제외, 사용자 이름·브랜드 편집의 비영향, 기존 null normalized URL fallback.
- **DUP-01:** 종류·근거·allowedDecisions·deletionImpact, 같은 시각 keyset 순회, 다른 item cursor 400, 다른 owner 404, 상대 삭제 시 숨김, DTO 후보 수 일치.
- **DUP-02:** 세 결정의 결과와 version. 판단과 삭제의 원자성(쓰기 중 실패 주입 시 전부 rollback). review 분리(ITEM-08 전후 서로 불변). KEEP_BOTH 뒤 재조회·재분석. 다중 후보에서 쌍별 판단, DELETE_NEW 뒤 나머지 숨김. replay(삭제 뒤 포함)와 다른 payload 409. 동시 요청: 같은 쌍 다른 key 두 요청, 같은 key 동시 replay, DUP-02 DELETE_EXISTING과 ITEM-05 경합, DUP-02와 existing 재분석 finish 경합, 서로 다른 쌍이 같은 item을 지우는 경합. 잠금 순서로 deadlock이 없어야 한다. owner 격리.
- **HTTP:** 두 route 공개 조립, 401/400/422/404/409, replay header, 서명 이미지, 취소 전파.
- **회귀:** 최종 전체 `./gradlew test`. docs의 계약·inventory·순서·이력·INDEX를 갱신하고 C10/B10/B11 인계를 기록한다.

## 남는 한계(정직한 범위)

- 다른 판매처 탐지는 페이지가 GTIN 또는 brand+MPN을 구조화 데이터로 노출할 때만 된다. 디자인 예시(musinsa vs hoka.com)도 두 페이지가 코드를 노출해야 잡힌다. 출시 평가 때 실제 판매처의 노출률을 측정해야 한다(B11 인계).
- B9 이전에 저장한 상품은 GTIN·MPN key가 없다. 재분석 전까지 판매처 간 탐지 대상이 아니다. 출시 전이라 backfill하지 않는다(*내 판단*). canonical 기준 탐지는 기존 값으로 된다. 단, 기존 상품끼리의 쌍은 둘 중 하나가 B9 이후 분석을 끝낼 때 생긴다.
- 화면에서 후보를 어디에 안내할지는 C10 범위다.
