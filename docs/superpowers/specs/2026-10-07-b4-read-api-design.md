# B4 상품 목록·홈·연속 처리 조회 설계

> 2026-10-07 · **설계 제안 · 피드백 반영 후 재검토 전** · 제품 정책 확정 · 구현 미착수

## 목표와 범위

사용자는 category/목적별 후보를 최근 저장순으로 보고, 홈의 조치 영역에서 같은 대상들을 연속 처리하며, 진입·foreground·새로고침 때 현재 카드 주변을 복구한다. ITEM-02 → HOME-02 → HOME-01 순서로 구현한다. owner 격리, 같은 시점의 count/preview, 위치 복구, B1~B3·Worker 회귀가 통과 조건이다.

[제품 결정](../../history/product-planning/mvp/decisions/b4-read-api-policy-2026-10-07.md)은 확정됐다. 이 문서의 parameter·페이지 크기·cursor·구조·index는 승인받을 설계다. mutation, restart 명령, polling/push, 검색, archive, Scheduler/browser runtime은 추가하지 않는다. client/와 design/handoff/는 수정하지 않는다.

## 접근 방식

1. **권장: JDBC 공통 조회 predicate와 짧은 repeatable-read snapshot.** 현재 schema·mapper·transaction helper를 활용하고 SQL에서 필터/정렬/페이지를 제한한다. count와 목록의 판정을 함께 유지하고 SQL과 순수 WishlistItemPolicy의 parity를 테스트한다.
2. 전체 owner 상품을 읽어 Kotlin에서 필터/페이지를 계산하면 정책 재사용은 쉽지만 목록이 커질수록 메모리·전송·읽기 비용이 증가한다.
3. requiredAction을 저장하는 별도 read model은 조회가 단순하지만 Worker와 후속 mutation마다 동기화를 강제하고 stale count 위험을 만든다. B4에는 도입하지 않는다.

## 기존 계약과 코드 대조

- [상품 상태 API의 HOME-02 예시](../../architecture/wishlist-item-state-api.md#홈-조치-영역)는 action·cursor·limit만 있다. anchor·before/after와 응답 requestedAnchorItemId/resolvedAnchorItemId/anchorResolved가 없다. B4에서 아래 group/window 계약으로 보완한다.
- WishlistItemPolicy는 INFORMATION_COMPLETION/CATEGORY_ASSIGNMENT/CATEGORY_REASSIGNMENT를 하나의 HomeActionGroup.INFORMATION_COMPLETION으로 묶는다. 제품 문서도 별도 카테고리 미지정 영역을 두지 않는다. 개별 action 전용 화면은 B4 범위에 없으므로 group query만 둔다.
- `DataSource.inTransaction(readOnly=true)`는 repeatable-read다. 목적 count와 최근 후보 4개는 PurposeRepository의 connection 기반 조회를 재사용할 수 있다. 이미지 null인 후보도 4개 한도에 들어간다.
- 기존 상품 상세 조회는 현재 category/purpose를 join하고 WishlistItemViewMapper가 B1~B3 표현을 제공한다. 카드도 이 mapper를 재사용한다.
- [B5 계획](../../architecture/server/mvp-api-implementation-order.md#b5--비동기-분석과-운영-복구)에 metadata 추출/저장이 이미 배정됐다. B4의 brand·price·currency·merchant·metadataCheckedAt은 기존 nullable 응답을 유지한다. 분류 시각을 확인 시각으로 꾸미지 않는다.
- V13 ACTIVE 목적 index는 `(owner_id,purpose_id,created_at DESC,id DESC)`다. V11의 공용 `(owner_id,category_id)`와 custom `(owner_id,custom_category_id)` index에는 정렬 키가 없다. categoryId 종류에 따라 공용/custom SQL을 나눈다.
- 기존 CAT-01/02 코드는 ACTIVE 전체를 센다. 확정된 B4 결정에 따라 아래 category visibility로 변경한다. 기존 B2 테스트 기대값 변경의 근거는 이 제품 결정이다.
- SQL `nullif(btrim(product_name),'')`는 탭·줄바꿈 등의 blank 판정을 Kotlin isNullOrBlank와 같게 만들지 못한다. B4는 같은 Unicode whitespace 집합을 명시해 SQL 판정을 구현한다. 기존 V8 migration과 B3 AI 후보 reader의 범위를 확장하지 않는다.

## 공통 표시와 조치 판정

모든 조회는 인증된 owner와 ACTIVE를 기본 조건으로 한다. `(created_at DESC,id DESC)`만 정렬에 쓰며 clientCreatedAt·updatedAt은 사용하지 않는다.

| 조회 | 추가 조건 |
|---|---|
| category 목록·CAT-01/02 itemCount | 현재 category 일치 + productName이 isNullOrBlank가 아님 |
| 특정 목적 목록·PUR candidateCount | 현재 purpose 일치. 이름·category·analysis/review 조건 없음 |
| 목적 미지정 목록 | purpose_id IS NULL. UNASSIGNED와 USER+null 모두 포함 |
| 홈 그룹·연속 처리 | WishlistItemPolicy와 동일한 requiredAction/group 판정 |

inactive는 requiredAction=NONE/group=null/allowedActions=[]로 판정한다. ACTIVE의 조치 우선순위는 PROCESSING → 이름 누락/EXTRACTION_UNRESOLVED → AI_ABSTAINED/AI_RESPONSE_UNUSABLE → CUSTOM_CATEGORY_DELETED → category 누락 → review PENDING → NONE이다. 각각 기존 policy의 ANALYSIS_IN_PROGRESS, INFORMATION_COMPLETION, CATEGORY_ASSIGNMENT, CATEGORY_REASSIGNMENT, INFORMATION_COMPLETION, CLASSIFICATION_REVIEW, NONE에 대응한다. 앞 조건이 뒤 조건보다 우선한다. 이름·category blank 판정도 policy와 동일하다.

CONFIRMED/DEFERRED를 재진입 때문에 분류 검토로 돌리지 않는다. 다만 별도의 이름/category 누락이 생긴 상품은 기존 policy에 따라 정보 보완 대상일 수 있다. allowedActions는 상태별 기존 policy를 그대로 계산한다(PROCESSING은 DELETE만). 목록에 포함되는 것과 행동 가능 여부를 혼동하지 않는다.

SQL predicate는 조회 전용 공통 객체에서 만들고 category count·상품 목록·홈·action 목록이 공유한다. requiredAction을 계산하는 CASE와 그 결과에서 group을 도출하는 표현은 각각 이 객체 한 곳에만 정의한다. 각 repository가 별도 CASE를 복사하지 않는다. 허용 행동은 기존 Kotlin policy로 계산한다.

### Unicode blank 판정

JDK 17에서 Kotlin/JVM Char.isWhitespace는 Character.isWhitespace || Character.isSpaceChar 기준이다. Kotlin 상수 `POLICY_WHITESPACE` 하나에 아래 28개 문자를 고정하고 SQL에 parameter로 전달한다.

`U+0009~000D, U+001C~001F, U+0020, U+00A0, U+1680, U+2000~200A, U+2028, U+2029, U+202F, U+205F, U+3000`

blank 식은 `value IS NULL OR btrim(value, :whitespace) = ''`다. 비공백 이름 visibility는 이 식의 부정이다. NBSP(U+00A0)·U+2007·U+202F·U+2028/2029는 blank, U+0085·U+200B는 nonblank다. Kotlin 테스트가 0..0xFFFF 모든 Char의 isWhitespace 결과와 상수 포함 여부를 대조해 집합을 고정한다. 저장 원문은 변경하지 않는다.

category 누락은 `coalesce(category_id, custom_category_id::text)`에 같은 blank 식을 적용한다. 공용 텍스트만 보거나 custom UUID 연결을 누락하지 않는다. DB에 허용된 공백 category_id도 policy와 대조한다.

### 표시용 count와 삭제 영향의 경계

비공백 제품명 visibility는 ITEM-02 category 목록과 **CAT-01/02 표시용 itemCount**에 적용한다. B8 CAT-05 deletion-impact와 CAT-06 참조 해제는 같은 owner·ACTIVE·category 일치 **전체**를 대상으로 한다. 이름 없는 상품도 영향을 받는다. 공통 CustomCategory.itemCount를 삭제 영향 수로 재사용하지 않고 별도 ACTIVE 전체 집계 경로를 둔다. B4에서는 그 삭제 API를 구현하지 않고 이 경계를 후속 계약에 남긴다.

## 입력 계약

모든 endpoint는 Firebase 인증을 요구한다. 알 수 없는 query, 중복 query, 빈 값, 정수가 아닌 수, 범위 밖 값은 400이다. GET body는 사용하지 않는다.

### ITEM-02 `GET /v1/wishlist-items`

아래 scope 중 **정확히 하나**를 받는다. 무필터 전체 목록이나 category와 목적의 교집합은 B4 화면에 필요하지 않아 추가하지 않는다.

- `categoryId=C001` 등 공용 leaf 또는 정규 custom UUID. parentId는 받지 않는다.
- `purposeId={UUID}`: 인증 owner의 ACTIVE 목적.
- `purposeUnassigned=true`: 목적 미지정. false/null 문자열은 허용하지 않는다.

존재하지 않는 공용 leaf·잘못된 ID는 400이다. 없는/삭제된/다른 owner의 custom은 동일 404 CATEGORY_NOT_FOUND다. 없는/비활성/다른 owner 목적은 동일 404 PURPOSE_NOT_FOUND다. 빈 유효 category/목적은 200 빈 목록이다.

### HOME-02 `GET /v1/home/action-items`

필수 `group=ANALYSIS_IN_PROGRESS|INFORMATION_COMPLETION|CLASSIFICATION_REVIEW`와 아래 공통 페이지 입력을 받는다. action parameter는 받지 않는다. INFORMATION_COMPLETION은 세 requiredAction을 함께 반환하며 각 카드의 requiredAction/allowedActions를 유지한다.

### 공통 페이지와 anchor 입력

| 모드 | 입력 | 크기 |
|---|---|---|
| 첫/다음/이전 페이지 | 선택 cursor, 선택 limit | ITEM 기본 40, HOME 기본 20, 두 endpoint 모두 1~100 |
| 현재 카드 주변 | 필수 anchor={cursor}, 선택 before/after | 각각 기본 20, 허용 0~20. 기준 카드 포함 최대 41개 |

cursor/limit와 anchor 모드는 함께 사용하지 않는다. before/after는 anchor 모드에서만 사용한다. anchorItemId/anchorCursor query는 받지 않는다. anchor에 담긴 id가 요청 anchor의 유일한 식별자다. anchor window 크기는 `before + 기준 카드 1 + after`이며 limit와 독립적이다. before는 더 최신 항목, after는 더 오래된 항목이다. 한쪽이 부족하다고 다른 쪽을 요청 개수보다 더 채우지 않는다.

HOME-01은 query를 받지 않는다.

## 정렬·cursor·anchor 복구

다음 페이지는 마지막 카드보다 오래된 항목을, 이전 페이지는 첫 카드보다 최신인 가장 가까운 항목을 ASC로 요청 개수+1만큼 조회하고, 응답 항목을 DESC로 되돌린다. 다음 페이지도 DESC로 응답한다. 동일 created_at에서는 UUID id가 순서를 완전히 정한다. 페이지 경계의 항목은 중복 포함하지 않는다. 페이지의 진행 방향은 요청 개수+1 조회로 판단한다. 응답 첫 카드보다 최신/마지막 카드보다 오래된 항목에 대한 반대 방향 존재 여부는 같은 owner/scope snapshot에서 EXISTS 쿼리로 확인한다. anchor 모드는 before/after 각각 +1 조회로 양쪽 경계를 판단한다. 다른 HTTP 요청 간 snapshot은 유지하지 않는다.

카드마다 해당 endpoint/scope의 anchorCursor를 제공한다. HOME-01 preview의 cursor는 대응 HOME-02 group에 바인딩된다. nextCursor/previousCursor는 방향을 포함하므로 같은 cursor query에 전달하며, anchorCursor와 상호 교환하지 않는다. limit·before/after 크기를 바꾸는 것은 cursor 유효성에 영향을 주지 않는다.

anchorCursor는 원래 상품의 immutable created_at/id를 보존하며 query의 anchor로 전달한다. requestedAnchorItemId는 이 cursor에서 읽는다. 같은 owner/scope에서 원래 상품이 아직 표시 대상이면 그 상품을 기준으로 anchorResolved=true다. 삭제·이동·처리로 목록에서 빠졌으면 **원래 정렬 위치의 다음(더 오래된) 항목으로 복구하고, 없으면 바로 앞(더 최신) 항목으로 복구**한다. 대체 anchor를 중심으로 요청한 앞뒤를 채우며 anchorResolved=false다. 다음 항목 우선은 제거된 자리를 채우는 쪽으로 진행하기 위한 규칙이고 맨 끝 삭제도 앞 항목 fallback으로 포함한다. 목록이 비면 resolvedAnchorItemId=null, items=[]다.

연속 처리 후 anchorResolved=false는 정상적이고 자주 발생하는 결과다. 클라이언트는 오류로 처리하지 않고 대체 anchor와 내부 offset으로 복원한다. 프로세스 신규 실행과 ‘다시 보기’는 cursor 없는 첫 페이지로 시작한다.

### Cursor 구조와 검증 범위

B3 PurposeCursorCodec의 Base64URL·owner digest·정렬 키 범위 검증 방식을 일반화한 B4 codec을 사용한다. payload는 version, endpoint, owner digest, canonical scope(kind/id 또는 group), 용도(ANCHOR/NEXT/PREVIOUS), created_at epoch microseconds, canonical UUID id의 고정 필드다. B3처럼 구분자로 직렬화하고 Base64URL no-padding으로 인코딩하며 B3의 기존 token 형식은 변경하지 않는다. owner digest는 B3의 SHA-256 앞 8바이트 hex 방식이다. 같은 owner라도 다른 filter/group/endpoint/용도의 cursor는 400이다. purposeUnassigned는 독립 scope다.

길이는 최대 2048 ASCII 문자, timestamp는 epoch 0부터 9999-12-31T23:59:59.999999Z까지다. 잘못된 Base64/UTF-8·필드 수·version·owner digest·scope·용도·UUID·정수 overflow·timestamp 범위는 400으로 처리하며 파싱/DB 변환 오류를 500으로 누출하지 않는다. 원래 상품이 삭제된 유효 cursor는 정상 anchor 복구 대상이다.

owner digest는 token 소유자/조회 범위 검사이며 암호학적 진위 증명이 아니다. 구조와 범위가 유효하게 재작성된 위치값 자체의 변조를 탐지한다고 주장하지 않는다. cursor를 권한으로 사용하지 않고 모든 SQL이 인증된 owner와 검증한 scope를 별도로 강제하므로 조작 가능한 위치는 자기 조회 범위에 한정된다. 원래 인계의 ‘위조 cursor는 500이 아니라 400’은 손상·범위 밖 등 잘못된 입력의 안전한 거절로 검증한다. HMAC·추가 secret·RuntimeConfig 변경·시간 만료는 도입하지 않는다.

### 기존 조회 계약과 비교

[상품 상태 API](../../architecture/wishlist-item-state-api.md#카테고리-목록-window)의 ITEM-02와 HOME-02를 함께 갱신한다. 아래는 승인받을 변경이며 현재 실행 API가 구현됐다는 뜻은 아니다.

| 항목 | 기존 상태 API 문서 | B4 수정안 |
|---|---|---|
| ITEM-02 anchor 입력 | anchor={cursor} 하나 | 그대로 유지. 이전 spec의 anchorItemId+anchorCursor 제안 폐기 |
| ITEM-02 anchor 응답 | requestedAnchorItemId/resolvedAnchorItemId/anchorResolved | 모두 유지. 요청 id는 cursor에서 읽음 |
| anchor 대체 | 가장 가까운 현재 항목 | 정렬상 다음(더 오래된) 항목 우선, 없으면 바로 앞. 대체 항목 중심 window |
| window 크기 | anchor 앞뒤 20, 페이지 limit=40 예시 | anchor 포함 최대 41; 페이지와 별도 모드. 페이지 limit 상한 100 |
| HOME-02 filter | action={requiredAction} | group={HomeActionGroup}; 정보 보완 세 action 포함 |
| HOME-02 위치 복구 | cursor/limit만 명시 | ITEM-02와 같은 anchor/before/after 및 요청/대체 id·anchorResolved 추가 |
| 카드/목록 | items와 앞뒤 cursor | items의 item은 B1~B3 DTO 재사용, 카드별 anchorCursor·totalCount 제공 |

spec 승인 후 상태 API의 요청 예시·응답·‘가장 가까운 항목’ 본문을 이 규칙으로 함께 갱신한다. 클라이언트 메모리의 anchorItemId/anchorCursor는 유지할 수 있지만 서버 query에는 anchor cursor 하나만 보낸다.

## 응답 계약과 카드 projection

B1~B3 WishlistItemDto를 수정 없이 카드의 `item`에 재사용한다. product metadata·category·purpose `{id,name,colorKey,iconKey,source}`·reviewStatus·requiredAction·allowedActions·version·시각을 유지한다. USER+null은 확정 미지정, UNASSIGNED는 미연결이다. 이름 없는 목적 후보의 product.name은 null/원문을 유지하고 클라이언트가 대체 제목을 표시한다.

목록 envelope는 두 endpoint에서 동일하다. `totalCount`는 해당 필터 전체 집합의 Long이다. page 모드의 requestedAnchorItemId/resolvedAnchorItemId/anchorResolved는 null이다. anchor 모드의 requestedAnchorItemId는 cursor id이며 anchorResolved는 boolean이다. 목록이 비어도 requestedAnchorItemId는 유지하고 resolvedAnchorItemId는 null이다. previousCursor/nextCursor는 응답 window 바깥의 항목이 있을 때만 제공한다. 빈 window에서는 둘 다 null이다.

```http
GET /v1/wishlist-items?categoryId=C001&limit=40
Authorization: Bearer <firebase-id-token>
X-Request-ID: b4-read-example
```

```json
{
  "items": [{
    "item": {
      "id": "11111111-1111-4111-8111-111111111111",
      "clientSubmissionId": "22222222-2222-4222-8222-222222222222",
      "version": 1,
      "sourceUrl": "https://example.com/item",
      "product": {"name":"후보 상품","imageUrl":null,"price":null,"currency":null,"brand":null,"merchant":null,"metadataCheckedAt":null,"nameSource":"AI","imageSource":null},
      "category": {"id":"C001","source":"AI","missingReason":null,"name":"아우터","parentId":"G001","kind":"PUBLIC"},
      "purpose": {"id":null,"name":null,"colorKey":null,"iconKey":null,"source":"UNASSIGNED"},
      "analysis": {"status":"READY","failureCode":null},
      "reviewStatus":"PENDING","lifecycleStatus":"ACTIVE","requiredAction":"CLASSIFICATION_REVIEW",
      "createdAt":"2026-10-07T10:00:00Z","updatedAt":"2026-10-07T10:00:00Z",
      "manualCompletionAt":null,"allowedActions":["EDIT","DELETE","REVIEW"],"clientCreatedAt":null
    },
    "anchorCursor": "<opaque-anchor-cursor>"
  }],
  "totalCount": 1,
  "previousCursor": null,
  "nextCursor": null,
  "requestedAnchorItemId": null,
  "anchorResolved": null,
  "resolvedAnchorItemId": null
}
```

HOME-02도 같은 envelope/card 구조를 사용한다.

## HOME-01 집계와 최근 목적

응답은 `actionGroups`와 `recentPurposes`다. actionGroups는 ANALYSIS_IN_PROGRESS → INFORMATION_COMPLETION → CLASSIFICATION_REVIEW 고정 순서로 항상 3개를 반환한다. 대상이 없으면 count=0/previews=[]이고 클라이언트가 count로 영역 표시 여부를 판단한다.

각 group은 `group`, `count`, `previews`다. previews는 최근 저장순 최대 4개의 `{item,anchorCursor}`다. count와 HOME-02 totalCount는 같은 판정에 따른다. HOME-01의 count/preview는 같은 snapshot이지만 이후 HOME-02 요청은 최신 snapshot이므로 요청 사이에 변경되면 건수가 달라질 수 있다. 분석 중은 ACTIVE PROCESSING 전체를 세고 local pending은 세지 않는다. 별도 서버 할 일 합계 필드는 반환하지 않는다.

recentPurposes는 B3 PurposeSummaryItemDto 구조로 최대 3개이며 activity_at DESC/id DESC의 ACTIVE 목적을 빈 목적까지 포함한다. candidateCount는 모든 ACTIVE 후보이며 previews는 created_at DESC/id DESC의 최대 4개 후보로 imageUrl null도 포함한다.

```json
{
  "actionGroups": [
    {"group":"ANALYSIS_IN_PROGRESS","count":0,"previews":[]},
    {"group":"INFORMATION_COMPLETION","count":0,"previews":[]},
    {"group":"CLASSIFICATION_REVIEW","count":0,"previews":[]}
  ],
  "recentPurposes": [{
    "id":"33333333-3333-4333-8333-333333333333","name":"이사 준비",
    "colorKey":"CYAN","iconKey":"HOME","version":1,"description":null,
    "candidateCount":0,"activity":{"at":"2026-10-07T10:00:00Z","kind":"CREATED"},"previews":[]
  }]
}
```

## 구현 구조와 snapshot

- HTTP routes는 query/auth/error를 담당하고 IO dispatcher에서 read service를 호출한다. Wishlist list·Home action list·Home summary를 API runtime에만 조립한다.
- Read service는 owner scope 검증과 page/window 구성을 담당하며, 요청당 한 번의 readOnly repeatable-read transaction 안에서 repository를 호출한다.
- Read repository는 공통 predicate·keyset·count·group preview SQL을 실행한다. WishlistItemRepository의 row projection/mapper를 필요한 범위에서 공통화하고 카드별 재GET이나 N+1 조회를 피한다.
- HOME-01은 같은 connection으로 그룹 집계, 그룹별 상위 4개, 최근 목적 3개와 candidateCount/preview를 읽는 짧은 snapshot을 사용한다. 별도 connection으로 병렬 읽지 않는다.
- CAT-01/02는 category visibility predicate를 공유한다. 기존 owner→purpose→item→job mutation 잠금 순서는 바꾸지 않는다. B4 GET은 FOR UPDATE를 사용하지 않는다.

## 홈 SQL 형태와 스캔 비용

HOME-02는 owner·ACTIVE 정렬 범위에 공통 group CASE를 **필터**로 적용한다. group 전용 index를 가정하지 않는다. NONE 상품이 많으면 페이지를 채우기 위해 많은 행을 검사하며 totalCount는 owner의 ACTIVE 집합을 집계해야 한다. 이 분포를 EXPLAIN 표본에 포함하고 비용을 기록한다.

HOME-01은 아래 형태로 group count와 preview id를 한 SQL에서 계산한다. 공통 표현 객체가 requiredAction CASE와 group mapping을 생성한다. 예시 SQL은 그 표현을 펼쳐 보여 주며 구현에서 다른 CASE를 별도로 쓰지 않는다. `:whitespace`는 고정 Kotlin 상수, `:owner`는 인증된 owner다.

```sql
WITH classified AS MATERIALIZED (
  SELECT id, created_at,
    CASE required_action
      WHEN 'ANALYSIS_IN_PROGRESS' THEN 'ANALYSIS_IN_PROGRESS'
      WHEN 'INFORMATION_COMPLETION' THEN 'INFORMATION_COMPLETION'
      WHEN 'CATEGORY_ASSIGNMENT' THEN 'INFORMATION_COMPLETION'
      WHEN 'CATEGORY_REASSIGNMENT' THEN 'INFORMATION_COMPLETION'
      WHEN 'CLASSIFICATION_REVIEW' THEN 'CLASSIFICATION_REVIEW'
      ELSE NULL
    END AS grp
  FROM (
    SELECT id, created_at,
      CASE
        WHEN lifecycle_status <> 'ACTIVE' THEN 'NONE'
        WHEN analysis_status = 'PROCESSING' THEN 'ANALYSIS_IN_PROGRESS'
        WHEN product_name IS NULL OR btrim(product_name, :whitespace) = ''
          THEN 'INFORMATION_COMPLETION'
        WHEN category_missing_reason = 'EXTRACTION_UNRESOLVED'
          THEN 'INFORMATION_COMPLETION'
        WHEN category_missing_reason IN ('AI_ABSTAINED', 'AI_RESPONSE_UNUSABLE')
          THEN 'CATEGORY_ASSIGNMENT'
        WHEN category_missing_reason = 'CUSTOM_CATEGORY_DELETED'
          THEN 'CATEGORY_REASSIGNMENT'
        WHEN coalesce(category_id, custom_category_id::text) IS NULL
          OR btrim(coalesce(category_id, custom_category_id::text), :whitespace) = ''
          THEN 'INFORMATION_COMPLETION'
        WHEN review_status = 'PENDING' THEN 'CLASSIFICATION_REVIEW'
        ELSE 'NONE'
      END AS required_action
    FROM wishlist_items
    WHERE owner_id = :owner AND lifecycle_status = 'ACTIVE'
  ) actions
), counts AS (
  SELECT
    count(*) FILTER (WHERE grp = 'ANALYSIS_IN_PROGRESS') AS analysis_count,
    count(*) FILTER (WHERE grp = 'INFORMATION_COMPLETION') AS completion_count,
    count(*) FILTER (WHERE grp = 'CLASSIFICATION_REVIEW') AS review_count
  FROM classified
), ranked AS (
  SELECT id, created_at, grp,
    row_number() OVER (PARTITION BY grp ORDER BY created_at DESC, id DESC) AS rn
  FROM classified WHERE grp IS NOT NULL
)
SELECT g.grp, g.total_count, r.id, r.created_at
FROM counts c
CROSS JOIN LATERAL (VALUES
  (0, 'ANALYSIS_IN_PROGRESS', c.analysis_count),
  (1, 'INFORMATION_COMPLETION', c.completion_count),
  (2, 'CLASSIFICATION_REVIEW', c.review_count)
) AS g(ordinal, grp, total_count)
LEFT JOIN ranked r ON r.grp = g.grp AND r.rn <= 4
ORDER BY g.ordinal, r.rn;
```

owner ACTIVE의 기반 테이블 분류 스캔은 한 번이며 materialized 결과를 counts/ranked가 각각 읽는다. group=null인 NONE은 ranking 전에 제외해 불필요한 정렬을 줄인다. 그룹 0건도 left join row로 반환한다. 최대 12개 preview id의 상세 projection은 같은 snapshot에서 한 번의 batch 조회로 읽는다. 목적 요약도 같은 connection에서 읽는다. 이 형태와 MATERIALIZED의 메모리/임시 파일 비용은 실제 EXPLAIN으로 확인하며 ‘한 번 스캔’은 모든 연산이 한 pass라는 뜻이 아니다.

## DB와 EXPLAIN

V1~V14는 변경하지 않는다. V15 이후 migration에 필요한 index만 추가한다. 후보는 ACTIVE의 `(owner_id,category_id,created_at DESC,id DESC)`와 `(owner_id,custom_category_id,created_at DESC,id DESC)`, 홈 전용 `(owner_id,created_at DESC,id DESC)`다. 목적 미지정도 B-tree가 NULL을 포함하므로 V13 `(owner_id,purpose_id,created_at DESC,id DESC)`의 purpose_id IS NULL 범위와 정렬을 재사용한다. requiredAction 저장 컬럼은 추가하지 않는다.

새 공용/custom index는 V11 index의 prefix를 포함한다. 실제 EXPLAIN과 기존 CAT/B3/Worker 접근 경로에 영향이 없으면 V15에서 wishlist_active_public_category/wishlist_active_custom_category의 중복 index drop을 검토한다. V1~V14 파일 수정 금지는 후속 migration의 index drop 금지가 아니다. 필요한 짧은 index를 유지한다면 실제 plan 근거를 기록한다.

대표 데이터(owner 혼재·동일 시각·빈 목적·이름 누락·각 group)로 실제 SQL에 EXPLAIN(ANALYZE,BUFFERS)을 실행한다. 공용/custom/목적/미지정/anchor/홈 집계의 스캔 수·sort·선택 index를 확인하고 불필요한 후보 index는 migration에 남기지 않는다. 빈 DB의 plan만 최적화 근거로 삼지 않는다. 데이터 규모·분포·측정 결과를 구현 이력에 남긴다.

## 오류와 헤더

기존 오류 envelope와 X-Request-ID를 사용하고 성공은 200 application/json이다. 인증 없음/실패는 401 UNAUTHORIZED다. 입력은 400 INVALID_WISHLIST_QUERY 또는 INVALID_HOME_QUERY, cursor는 400 INVALID_WISHLIST_CURSOR 또는 INVALID_HOME_CURSOR다. 자원 없음은 앞서 정한 404다. 오류 details에 다른 owner 정보·cursor 내용을 노출하지 않는다.

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json
X-Request-ID: b4-read-example
```

```json
{"error":{"code":"INVALID_HOME_CURSOR","requestId":"b4-read-example","details":{}}}
```

## parity 테스트 조합

DB 제약을 통과하는 모든 조합을 batch insert하고 행마다 공통 SQL의 requiredAction/group/visibility와 WishlistItemPolicy.evaluate를 대조한다. row projection의 allowedActions도 같은 상태로 검증한다. DB 제약을 피하려고 테스트에서 constraint를 해제하지 않는다.

- analysis: 5개 enum 전체. review: 4개 enum 전체. missingReason: null과 4개 enum(총 5).
- 이름: null, 빈 문자열, 일반 공백, 탭+줄바꿈, NBSP, U+2007, U+202F, U+2028+2029, U+0085, U+200B, 정상 제품명, 공백으로 둘러싸인 정상 제품명(12개). 모든 whitespace 개별 문자와 혼합 문자열은 별도 blank 테스트로 보강한다.
- category: 미지정(두 컬럼 null), 공용 leaf, 같은 owner의 custom UUID(3개). manualCompletion: null/저장 시각(2개).
- 조합은 analysis 5 × review 4 × missingReason 5 × 이름 12 × category 3 × manualCompletion 2 = 7,200개 후보이다. 공용/custom 연결이 있으면 missingReason=null, categorySource=AI 또는 USER여야 하는 category_assignment_check 때문에 유효 조합은 3,360개다. 미지정은 모든 missingReason을 허용한다. category source는 연결 시 AI, 미지정 시 UNASSIGNED로 고정해 이 축에서 중복시키지 않는다.
- lifecycle ACTIVE/ARCHIVED/DELETED를 확장하면 10,080개 valid row다. inactive SQL 결과는 requiredAction=NONE/group=null/visibility=false로 대조하며, 다른 owner는 별도 조회 격리 테스트로 확인한다.
- 별도 경계 row는 공백만 있는 공용 category_id(배타/assignment 제약 충족), custom UUID가 있을 때 category 누락 아님, 이름의 U+0085 nonblank를 확인한다. FK가 있는 custom은 실제 같은 owner category fixture를 생성한다.

## 검증과 문서

TDD로 아래 실패를 확인한 뒤 구현한다. 리뷰 지적도 재현 테스트의 실패를 먼저 확인한다.

- owner 격리, 다른 owner/filter/endpoint/용도 cursor 거절, 구조·owner/scope/용도·범위에 맞지 않는 필드 조작과 손상/과대/overflow cursor 400, limit 경계, 중복/알 수 없는 query.
- 같은 created_at의 UUID 순서, 앞뒤 페이지 중복 없음, anchor 유지/삭제/이동/처리 직후/맨 앞/맨 끝/빈 목록, before/after 개수와 최대 41개.
- purposeId 결과와 PUR candidateCount 일치, 미지정의 두 source, 이름 없는 목적 후보, 공용/custom category count와 목록/상위 합계/BROWSE 일치.
- SQL과 WishlistItemPolicy parity(Unicode whitespace·analysis·missingReason·review·lifecycle·manualCompletion), 홈 count/preview/action totalCount 일치, DEFERRED/CONFIRMED 검토 재노출 없음, 로컬 pending 미합산.
- 같은 snapshot 읽기의 일관성, N+1 없음, nullable metadata와 B1~B3 DTO 호환, 신규 API의 Worker/health 비노출, B1~B3/Worker 회귀.

B10에서 목적 archive가 생기면 ARCHIVED 목적에 연결된 ACTIVE 상품은 purposeUnassigned(null만)에도 특정 목적 목록(비활성 목적 404)에도 나타나지 않을 수 있다. 목적/상품 lifecycle 전이와 조회 predicate를 B10에서 함께 검증·갱신한다. B4가 목적 archive 동작을 추가하지 않는다.

spec 승인 후 작업별 plan을 작성해 구현·독립 리뷰·보완·전체 테스트를 진행한다. architecture의 read API/state API/category 계약, inventory, implementation order, 각 INDEX와 의미 있는 구현 이력을 최종 구현에 맞춰 갱신한다. 전체 테스트는 `--rerun-tasks`의 완료 결과만 기록한다. 현재 baseline은 295 통과·RealUrlPilot 1 skip이며 B4 구현 완료를 뜻하지 않는다.
