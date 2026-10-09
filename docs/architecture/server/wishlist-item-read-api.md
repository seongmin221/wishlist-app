# 상품 생성·상세와 B4 목록·홈 조회

## 조회와 응답 경계

`GET /v1/wishlist-items/{id}`와 생성·재전송은 `WishlistItemRepository`의 동일 owner-scoped projection을 읽고 `app.http.WishlistItemViewMapper`로 직렬화한다. 한 SQL에서 상태·version·metadata·출처·시각을 함께 읽어 서로 다른 version의 필드가 섞이지 않게 한다. `requiredAction`과 `allowedActions`는 기존 `WishlistItemPolicy`를 사용한다. mapper는 HTTP presenter이므로 도메인이 HTTP DTO를 참조하지 않는다.

`Main`에서 생성·조회 서비스를 각각 조립해 route에 필수 주입한다. 생성 서비스 내부 repository를 조회 서비스 조립에 사용하지 않고, 조회 callback 기본값도 두지 않는다. repository는 생성의 item/job/outbox SQL과 owner/key 조회를 담당한다. key 충돌의 기존 항목은 key 기준 projection 한 번으로 읽으며 상태만 반환하는 미사용 조회 API는 제거했다. 생성 서비스는 transaction과 결과·발행 순서를 조정한다.

인증이 없으면 `401 UNAUTHORIZED`, UUID 형식이 잘못되면 `400 INVALID_WISHLIST_ITEM_ID`다. 존재하지 않음·다른 owner·DELETED는 모두 `404 WISHLIST_ITEM_NOT_FOUND`다. ARCHIVED는 조회할 수 있지만 조치는 없다. 생성 key 재전송에는 기존 삭제 tombstone을 반환한다. GET과 생성·발행 JDBC 작업은 `Dispatchers.IO`에서 실행하고 취소를 전파한다.

공개 projection은 이름·이미지·각 값 출처, category/purpose의 현재 ID·출처·누락 사유, analysis/review/lifecycle, version, 수동 완료 시각, 원본 URL과 저장/변경 시각을 반환한다. DB에 없는 가격·통화·brand·merchant·metadataCheckedAt은 기존 DTO의 nullable 필드로 유지한다. `classified_at`은 AI 분류 시각이므로 metadata 확인 시각으로 대신 사용하지 않는다. 목적명·후보 수를 사용하는 deletionImpact는 상품 삭제 ITEM-05와 함께 B7에서 확장한다.

## 공유 시각과 생성 key

HTTP·HTTPS scheme은 대소문자 무관하게 검사하지만 `sourceUrl` 원문은 변경하지 않는다. 예를 들어 `HTTPS://A.EXAMPLE/Path`도 생성할 수 있고 같은 원문 재전송은 replay다. 같은 key로 scheme만 소문자화해서 재전송하면 다른 원문 URL이므로 409다.

`sourceUrl`은 최대 2048자(UTF-16 길이)이며 UTF-8로 인코딩할 수 없는 문자열(짝 없는 surrogate)도 거절한다. 둘 다 새 오류 code 없이 기존 `422 INVALID_URL`이며 DB에 쓰기 전에 검사한다. JDBC가 짝 없는 surrogate를 `?`로 바꿔 저장하면 같은 key의 재전송이 저장 URL과 달라져 409가 되기 때문이다. 정상 surrogate 쌍(예: 이모지)은 허용하며 재전송이 일치한다. 요청 body 전체 상한은 여전히 운영 설정 범위의 후속 항목이다. 생성 시점에는 대소문자와 IPv6 괄호를 정규화해 `localhost`/`*.localhost`, IPv4 `127.0.0.0/8`·`0.0.0.0`, IPv6 loopback·unspecified literal을 DNS 조회 없이 거절한다. 사설·link-local 대역과 DNS 결과 판정은 extraction의 `UrlSafetyPolicy`가 계속 담당한다.

`clientCreatedAt`은 선택 문자열이며 생략과 JSON null은 동일하게 null로 저장한다. 순수 `parseCreateRequest` 함수가 JSON wire 타입과 시각을 검증하고 URL의 허용 여부는 생성 서비스가 검사한다. 시간대가 있는 ISO 8601 날짜/시각을 Instant로 변환하며, 현지 시각과 UTC Instant 양쪽 연도가 1~9999여야 한다. 날짜만·offset 없는 시각·잘못된 날짜·문자열 이외의 값은 `422 INVALID_CLIENT_CREATED_AT`다. 기기 시계 오차를 이유로 미래 시각을 거절하지 않는다.

V10은 `client_created_at timestamptz`만 추가하고 기존 행은 null로 유지한다. 입력은 microsecond 아래 자릿수를 절삭해 PostgreSQL 반올림으로 UTC 연도 10000에 넘어가지 않게 한다. JDBC는 `setObject(OffsetDateTime)`/`getObject(OffsetDateTime::class.java)`로 공유 시각을 기록·조회해 1582년 이전 날짜도 Gregorian 기준 Instant를 유지한다. 응답은 DB에 보관한 값을 UTC로 반환한다. 서버 `createdAt`과 `(created_at,id)` 정렬은 유지한다. 같은 owner/key/URL의 유효 재전송은 최초 공유 시각을 덮어쓰지 않는다. 같은 key와 다른 URL은 기존대로 409이고, 같은 URL과 다른 key는 새 상품이다. GET ID와 Idempotency-Key는 같은 UUID parser로 정규 36자 형식을 확인하며 대소문자는 허용한다.

## 공개 실패 코드

도메인 `AnalysisFailureCode` enum이 공개 failure code 어휘와 category 누락 사유를 정의한다. enum의 모든 값은 공개 계약이며, 내부 전용 진단은 enum 밖의 저장 문자열로만 남는다. Worker의 새 실패 저장은 enum을 받고, 최종 반영의 category 사유와 HTTP 응답도 같은 enum을 사용한다. V8 SQL은 당시 migration 기록이므로 수정하지 않는다. PROCESSING/READY의 failureCode는 null이다. PARTIAL·실패에는 아래 허용 code를 반환한다.

- `BLOCKED_ADDRESS`, `UNSUPPORTED_CONTENT`, `ACCESS_DENIED`
- `AI_ABSTAINED`, `AI_UNUSABLE_RESPONSE`, `AI_INVALID_CANDIDATE`, `AI_USAGE_OUT_OF_RANGE`
- `AI_BUDGET_EXCEEDED`, `AI_CONFIGURATION_ERROR`

실패인데 저장 code가 없거나 알려지지 않았으면 FAILED_RETRYABLE은 `ANALYSIS_RETRYABLE_FAILURE`, FAILED_TERMINAL은 `ANALYSIS_FAILED`다. PARTIAL의 알 수 없는 code도 `ANALYSIS_FAILED`로 가리고, code가 없으면 null을 유지한다. 원본 내부 진단을 공개하지 않으며 DB 진단값을 바꾸지는 않는다. B5에서 추출·실행 실패 분류를 더 구체화한다.

`BLOCKED_ADDRESS`/`UNSUPPORTED_CONTENT`/`ACCESS_DENIED`는 기존 공개 계약의 범주를 유지하는 것이며 현재 extraction이 이 code를 저장한다는 의미는 아니다. 구체적인 producer 연결은 B5에서 구현한다.

## commit 후 지정 발행

생성 transaction은 item·job·outbox event를 함께 commit한다. 생성 연결을 반환한 뒤 event ID를 callback에 전달하고 `OutboxDispatcher.dispatchEvent(id)`가 해당 event만 claim한다. 오래된 retry backlog가 새 생성의 즉시 발행을 대신 소비하지 않는다. 지정 발행과 batch 발행은 DB 시각 기반 120초 lease·SKIP LOCKED·결정적 task 이름을 공유한다. 없는 event·발행 완료·살아 있는 lease는 발행하지 않는다.

발행 실패는 가능한 경우 lease를 해제하고 item과 미발행 event를 보존한다. gateway 실패는 dispatcher가, lease 해제·claim 실패처럼 생성 서비스까지 올라온 예외는 생성 서비스가 event ID와 예외 타입만 warn 로그로 남긴다. Scheduler가 연결되기 전에는 이 로그가 PROCESSING에 남은 상품을 추적하는 유일한 신호다. 취소도 lease 해제 후 전파한다. key 재전송은 저장된 API 결과를 복구하며 발행을 다시 시도하지 않는다. 미발행 event의 queue 전달 복구는 B5 Scheduler에서 연결한다. 생성 응답은 transaction 안에서 읽고 성공적으로 commit한 snapshot을 반환하며 발행 후 재조회하지 않는다. post-commit 재조회 장애로 201/Location을 잃는 경로를 없앤다. 성공적인 생성·즉시 발행의 pool 대여는 생성 transaction, claim, published update의 세 번이다. Worker가 그 사이 완료하면 이후 GET/재전송에서 최신 결과를 읽는다. runtime 종료 gate와 작은 connection pool을 유지한다.

B0의 production createTask RPC 5초 상한과 batch 발행 메서드를 유지한다. B5의 Scheduler·장기 PENDING 복구·batch의 후보별 시간/실패 격리·generation 전체 retry 예산은 후속 범위다.

Scheduler가 있는 상태의 비동기 발행 전환과 API/Worker body 크기 제한은 운영 정책·설정 범위의 후속 검토 항목이다. 이번 리뷰 보완은 fire-and-forget 작업이나 임의 body 상한을 추가하지 않는다.

## B2 category 표시 확장

생성 snapshot·owner GET·replay는 같은 mapper를 유지한다. category에 nullable name/parentId/kind를 추가하며 공용 C-ID와 custom UUID를 하나의 id로 표시한다. custom 이름은 owner 조건의 현재 row에서 읽으므로 편집 뒤 활성 item GET에 반영되며 item version·연결·review를 초기화하지 않는다. category 자원 관리 계약은 [B2 계약](category-management-api.md)을 따른다.

## B3 purpose 표시 확장

생성 snapshot·owner GET·replay의 같은 mapper가 `purpose`에 nullable `name`, `colorKey`, `iconKey`를 더한다.
값은 owner 조건의 현재 목적 row에서 읽는다. 목적이 없으면 네 값이 모두 null이고, `source`로 미연결(UNASSIGNED)과 사용자 확정 미지정(USER)을 구분한다.
목적 편집은 상품 version·출처·review를 바꾸지 않는다. 상품 version이 같아도 목적 표시값은 바뀔 수 있다.
목적 자원 계약은 [목적 API](purpose-management-api.md)를 따른다.

## B4 공통 조회 계약

ITEM-02·HOME-02·HOME-01은 API runtime에 등록한다. Worker와 LOCAL_HEALTH에는 노출하지 않는다. Firebase Bearer 인증 owner로만 조회하고 JDBC는 Dispatchers.IO의 read-only repeatable-read transaction에서 실행한다. 조회에서 owner/purpose/item/job 잠금을 잡지 않는다.

### ITEM-02 범위와 표시

```http
GET /v1/wishlist-items?categoryId=C026&limit=40
Authorization: Bearer {firebaseIdToken}
GET /v1/wishlist-items?categoryId={customUuid}&cursor={nextCursor}&limit=40
GET /v1/wishlist-items?purposeId={purposeUuid}&limit=40
GET /v1/wishlist-items?purposeUnassigned=true&limit=40
```

categoryId·purposeId·purposeUnassigned 중 **정확히 하나**를 받는다. categoryId는 공용 leaf C-ID 또는 custom UUID이며 parentId 조회는 없다. category 목록은 같은 owner·ACTIVE·현재 category 일치·Kotlin isNullOrBlank 기준 이름 존재 집합이다. PROCESSING도 이름이 남아 있으면 표시한다. CAT-01/02 표시 count와 같다.

특정 purposeId는 현재 owner의 ACTIVE 목적에 연결된 ACTIVE 상품 전부이며 분석/review·이름/category 존재를 제한하지 않는다. PUR candidateCount와 같은 집합이다. purposeUnassigned=true는 purpose_id IS NULL인 ACTIVE 전체이며 UNASSIGNED와 USER+null을 함께 포함한다. source는 두 의미를 구분한다. 목적별 이름 없는 카드의 placeholder는 클라이언트 책임이다. 다른 owner·없는/삭제된 custom은404 CATEGORY_NOT_FOUND, 다른 owner·없는/ARCHIVED 목적은404 PURPOSE_NOT_FOUND다.

정렬은 `created_at DESC, id DESC`이고 UUID 순서도 PostgreSQL에 맡긴다. clientCreatedAt은 정렬에 쓰지 않는다. 일반 페이지 limit은 ITEM40/HOME20 기본, 1~100이다. cursor는 응답 previousCursor 또는 nextCursor를 전달하며 같은 owner·endpoint·scope·용도에만 사용할 수 있다.

### 공용 카드와 page/window

카드는 `item`과 `anchorCursor` wrapper다. item은 B1~B3 상세 mapper 표현과 동일하며 purpose 색/아이콘/source, reviewStatus, requiredAction, allowedActions, version을 포함한다. 가격/통화·brand·merchant·metadataCheckedAt은 B5에서 저장을 연결하므로 B4 응답은 null이다. classified_at을 확인 시각으로 대신 쓰지 않는다.

다음은 ITEM-02 첫 페이지 예시다. `opaque-anchor`는 설명용 값이며 실 요청에는 응답받은 cursor를 사용한다.

```json
{
  "items": [
    {
      "item": {
        "id": "00000000-0000-0000-0000-000000000001",
        "clientSubmissionId": "00000000-0000-0000-0000-000000000002",
        "version": 1,
        "sourceUrl": "https://example.com/product",
        "product": {
          "name": "헤드폰",
          "imageUrl": null,
          "price": null,
          "currency": null,
          "brand": null,
          "merchant": null,
          "metadataCheckedAt": null,
          "nameSource": "USER",
          "imageSource": null
        },
        "category": {
          "id": "C026",
          "source": "USER",
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
          "source": "USER"
        },
        "analysis": {
          "status": "READY",
          "failureCode": null
        },
        "reviewStatus": "CONFIRMED",
        "lifecycleStatus": "ACTIVE",
        "requiredAction": "NONE",
        "createdAt": "2026-10-07T10:00:00Z",
        "updatedAt": "2026-10-07T10:00:00Z",
        "manualCompletionAt": null,
        "allowedActions": [
          "EDIT",
          "DELETE"
        ],
        "clientCreatedAt": null
      },
      "anchorCursor": "opaque-anchor"
    }
  ],
  "totalCount": 1,
  "previousCursor": null,
  "nextCursor": null,
  "requestedAnchorItemId": null,
  "resolvedAnchorItemId": null,
  "anchorResolved": null
}
```

```http
GET /v1/wishlist-items?categoryId=C026&anchor={anchorCursor}&before=20&after=20
GET /v1/home/action-items?group=CLASSIFICATION_REVIEW&anchor={anchorCursor}&before=20&after=20
```

anchor query 하나에 위치와 requested ID가 들어 있다. anchorItemId/anchorCursor query는 받지 않는다. anchor는 cursor/limit과 함께 쓰지 못하며 before/after는 anchor에서만 각각0~20(기본20)이다. 응답은 anchor 자신1개를 포함해 최대41개이고 대체 anchor를 중심으로 양쪽을 채운다. 어느 한쪽이 짧다고 반대쪽 제한을 늘리지 않는다.

anchor가 여전히 scope 안이면 requestedAnchorItemId=resolvedAnchorItemId, anchorResolved=true다. 삭제·이동·처리로 빠지면 요청 정렬 위치의 **다음(더 오래된) 항목**, 없으면 바로 앞(더 새로운) 항목으로 복구하고 false를 반환한다. 맨 끝 삭제도 같은 규칙이다. scope가 비면 requested ID만 남고 resolved ID=null, false, items=[]다. 연속 처리에서 false는 정상 복구이며 오류가 아니다. page 모드의 requested/resolved/anchorResolved는 모두null이다.

window 바깥에 항목이 있을 때만 이전/다음 cursor를 준다. ITEM-02와 HOME-02는 같은 WishlistWindowReader가 한 SQL에서 count·window·양방향 존재·anchor fallback을 구성한다. 경계 없는 최신 첫 페이지의 previous는 false로 고정한다. 일반 scope의 eligible은 NOT MATERIALIZED로 커서 경계·정렬을 인덱스에 밀어 넣을 수 있게 하고, 홈 group은 공통 materialized action/group 분류 결과를 재사용한다. 이전 페이지는 가까운 ASC key를 제한해 선택한 후 최종 SQL에서 DESC로 반환한다. 카드 anchor cursor와 페이지 cursor는 서로 대체하지 않는다. totalCount는 현재 snapshot의 전체 scope count이며 상세 카드는 같은 connection에서 batch 조회한다.

다음/이전 page 요청 사이에 진행 방향의 항목이 모두 삭제·이동·처리되면 items=[]여도 반대쪽에 남은 가장 가까운 항목을 가리키는 복귀 cursor를 반환한다. 이 cursor를 따라가면 복귀 기준 항목 자신도 포함한다. 양방향 모두 비었을 때만 두 cursor가 null이다. opaque token 내부의 NEXT_INCLUSIVE/PREVIOUS_INCLUSIVE 용도로 이를 구분하며 query·JSON 필드는 추가하지 않는다. 기존 NEXT/PREVIOUS cursor는 계속 배타적 경계다. page의 anchor 관련 세 필드는 계속 null이다.

cursor는 B3처럼 Base64URL 구조·owner digest·scope·endpoint·용도·UUID·시각 범위·입력 길이를 검증한다. HMAC/신규 secret은 없다. 응답 mapper는 owner digest를 한 번 만들어 모든 카드·페이지 cursor에 공유하며, 페이지 token은 방향을 포함해 한 번만 decode한다. 형식 오류나 범위 불일치는400이며 암호학적 위조 방지를 보장하지 않는다. 자기 범위 안의 유효 위치를 바꾼 token을 권한으로 신뢰하지 않고 SQL이 인증 owner/scope를 계속 제한한다.

### HOME-02 그룹 조회

```http
GET /v1/home/action-items?group=INFORMATION_COMPLETION&limit=20
GET /v1/home/action-items?group=INFORMATION_COMPLETION&cursor={nextCursor}&limit=20
```

필수 group은 ANALYSIS_IN_PROGRESS·INFORMATION_COMPLETION·CLASSIFICATION_REVIEW 중 하나다. action query는 없다. INFORMATION_COMPLETION 그룹은 requiredAction의 INFORMATION_COMPLETION·CATEGORY_ASSIGNMENT·CATEGORY_REASSIGNMENT를 포함한다. 카드별 action과 allowedActions는 그대로 둔다. PROCESSING이 먼저, 이름/category 보완이 다음, PENDING review가 마지막이라는 WishlistItemPolicy 우선순위를 SQL과 공유한다.

정확한 최신 totalCount는 매 요청 유지한다. HOME-01과 공유하는 action/group CTE에서 owner ACTIVE CASE를 한 번 계산하고 materialized key 결과를 count·window·복구·존재 판정에 재사용한 뒤 카드 projection을 같은 snapshot에서 batch 조회한다. 별도 count 쿼리·반복 CASE를 제거하지만 ACTIVE 전체 판정 비용 자체는 남는다. 새 쿼리의 실제 EXPLAIN·메모리/임시 파일 비용은 [리뷰 보완 이력](../../history/architecture/server/b4-read-api-implementation-2026-10-07.md#2026-10-09-리뷰-보완)에서 검증 상태를 확인한다.

완성된 CONFIRMED/DEFERRED 상품은 검토 그룹에서 제외한다. ‘처음부터 다시 보기’는 기기 skip만 초기화하고 cursor 없이 현재 미완료 첫 페이지를 조회한다. review 상태를 되돌리거나 별도 restart API를 만들지 않는다. local pending은 서버 item으로 합치지 않는다.

### HOME-01 요약

```http
GET /v1/home
Authorization: Bearer {firebaseIdToken}
```

query를 받지 않는다. 고정순서의 세 actionGroups를 반환하며 각 count와 최대4개 최신 previews가 함께 나온다. 빈 그룹도 count=0/previews=[]로 유지한다. preview는 HOME-02 anchorCursor를 포함한다. 별도 todo 합계 필드는 없고 기기 pending은 클라이언트에서 합성한다.

recentPurposes는 B3 summary DTO이며 ACTIVE activity_at DESC/id DESC 최대3개, 빈 목적도 포함한다. candidateCount는 ACTIVE 전체, previews는 최근 저장 후보 최대4개다. 이미지 없는 후보도 한 자리를 차지하고 imageUrl=null이면 클라이언트 placeholder를 쓴다. 아래는 빈 그룹과 빈 목적 예시다.

```json
{
  "actionGroups": [
    {
      "group": "ANALYSIS_IN_PROGRESS",
      "count": 0,
      "previews": []
    },
    {
      "group": "INFORMATION_COMPLETION",
      "count": 0,
      "previews": []
    },
    {
      "group": "CLASSIFICATION_REVIEW",
      "count": 0,
      "previews": []
    }
  ],
  "recentPurposes": [
    {
      "id": "00000000-0000-0000-0000-000000000003",
      "name": "여행",
      "colorKey": "CORAL",
      "iconKey": "HEART",
      "version": 1,
      "description": null,
      "candidateCount": 0,
      "activity": {
        "at": "2026-10-07T10:00:00Z",
        "kind": "CREATED"
      },
      "previews": []
    }
  ]
}
```

같은 우선순위 조건 생성기와 policy의 homeGroupFor에서 group CASE를 직접 만들고 MATERIALIZED classified에 한 번 저장한다. FILTER count와 group별 row_number key를 한 SQL로 읽으며 NONE을 ranking 전에 제외한다. 최대12개 카드 projection과 PurposeRepository.page/previews는 같은 connection snapshot에서 읽는다. preview key에 대응하는 상세 row가 없으면 명시적으로 실패하여 count를 유지한 채 카드를 생략하지 않는다. N+1 상세 호출은 없다. group은 계산 filter이므로 count는 owner ACTIVE 전체를 읽는 비용이 남는다. [실측 기록](../../history/architecture/server/b4-read-api-implementation-2026-10-07.md#task-8--v15-인덱스와-실제-sql-측정)은 정렬 index 효과와 남은 scan 비용을 구분한다.

### 조회 오류와 헤더

| 상황 | HTTP·code |
| --- | --- |
| 인증 없음/실패 | 401 UNAUTHORIZED |
| ITEM query 혼합·중복·누락·알 수 없는 이름·범위 초과 | 400 INVALID_WISHLIST_QUERY |
| ITEM cursor 형식/owner/scope/endpoint/용도/범위 오류 | 400 INVALID_WISHLIST_CURSOR |
| HOME query 오류(group·bounds·HOME-01 query 등) | 400 INVALID_HOME_QUERY |
| HOME cursor 오류 | 400 INVALID_HOME_CURSOR |
| custom/purpose 없음 또는 다른 owner | 404 CATEGORY_NOT_FOUND / PURPOSE_NOT_FOUND |

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json; charset=UTF-8
X-Request-ID: 00000000-0000-0000-0000-000000000004
```

```json
{"error":{"code":"INVALID_WISHLIST_CURSOR","requestId":"00000000-0000-0000-0000-000000000004","details":{}}}
```

인증과 입력 검증은 DB 접근 전에 한다. 알 수 없는 query·중복 값·빈 값은400으로 거절한다. GET에는 Idempotency-Key나 mutation version precondition이 없다. 성공/실패 모두 X-Request-ID를 제공하며 실패 body와 같은 값이다.

### 후속 경계

B8 category 삭제 영향은 표시 count를 재사용하지 않고 이름 누락을 포함한 ACTIVE 전체를 센다. B10 purpose archive 이후 ARCHIVED 목적에 연결된 ACTIVE 상품은 현재 purposeId404/미지정NULL 목록 양쪽에서 빠질 수 있으므로 archive 상태 전환과 조회 predicate를 함께 갱신한다. B4는 edit/review/delete/retry/archive mutation, polling·push·search·전역sync를 추가하지 않는다.
