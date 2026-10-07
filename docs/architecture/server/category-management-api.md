# 카테고리 조회·생성·편집 API 계약

> 확정 계약 · CAT-01~04 · B4 itemCount 변경은 제품 결정 확정, 구현 예정

## 적용 범위

Firebase 인증 owner별 CAT-01~04 계약이다. 삭제 API CAT-05/06은 별도 B8 계약에 속한다.
제품 규칙은 [구매 후보 정리](../../product/organize-candidates.md),
[입력 안전성](../../history/product-planning/mvp/decisions/custom-category-safety.md),
[category lifecycle](../../history/product-planning/mvp/decisions/custom-category-lifecycle.md)을 따른다.
결정 경위와 초안 취소 후 category 수명 확인 대상은 [제품 결정 기록](../../history/product-planning/mvp/decisions/b2-category-api-policy-2026-10-07.md)에 있다.

## CAT-01 공개 조회

`GET /v1/categories?scope=SELECT|BROWSE&parentId={groupId}`. scope는 필수다.
parentId 생략은 모든 상위 group을 반환하고, 지정하면 해당 group 하나로 제한한다.
잘못된 scope·중복 query·없는 parent는 400이며 다른 owner의 custom은 조회되지 않는다.
상위 group은 선택 가능한 leaf category가 아니다.

응답은 `scope`, `taxonomyVersion`, `groups`, `customUsedCount`, `customLimit: 20`이다.
각 group은 `id`, `name`, `displayOrder`, `itemCount`, `categories`를 가진다.
각 category는 `id`, `name`, `parentId`, `kind: PUBLIC|CUSTOM`, `displayOrder`, `itemCount`다.
custom의 편집 필드·AI 내부 상태는 목록 DTO에 넣지 않는다.

- SELECT: 모든 상위·87개 공용 leaf·owner의 모든 미삭제 custom을 반환한다. 상품 0개도 포함한다.
- BROWSE: 목록 표시 대상 상품이 있는 공용 leaf와 모든 미삭제 custom(빈 custom 포함)을 반환한다.
  그 결과 leaf가 있는 상위만 포함한다. 빈 DB는 빈 groups다.
- itemCount: 해당 owner의 `lifecycle_status=ACTIVE`이고 현재 category 참조가 일치하는
  상품 중 비공백 제품명이 있는 item 수다. 이름 누락은 WishlistItemPolicy의
  `isNullOrBlank()` 기준(null·빈 문자열·탭·줄바꿈 등 공백만 있는 문자열)이다.
  분석 상태·review는 제한하지 않는다. 예측 진단·다른 owner·ARCHIVED·DELETED는 세지 않는다.
  group count는 반환 leaf의 itemCount 합계다. ITEM-02와 동일한 표시 집합을 사용한다.
  이 변경은 [B4 제품 결정](../../history/product-planning/mvp/decisions/b4-read-api-policy-2026-10-07.md)에 따른다.
  현재 B2 코드는 ACTIVE 전체를 집계하며 B4 구현에서 count와 BROWSE 노출을 함께 변경한다.
- customUsedCount는 parent filter와 관계없는 owner 전체 미삭제 custom 수다.
- 공용 group/leaf 순서는 v1 resource 순서다. custom은 공용 leaf 뒤에
  생성 당시 parent의 모든 기존 custom(삭제된 것 포함)의 최대 displayOrder + 1을 저장한다.
  첫 값은 공용 leaf 수다. 생성 순서로 반환하며 필터·향후 B8 삭제로 번호를 다시 매기지 않는다.
- 한 응답의 목록과 count는 같은 시점의 상태를 표현한다.

공용 상위 ID는 `G001`~`G011`, 공용 leaf는 `C001`~`C087`이다.
표시 순서가 바뀌어도 ID는 재계산하지 않는다.

## CAT-03 생성과 replay

`POST /v1/custom-categories`, 필수 정규 UUID `Idempotency-Key`.
body는 필수 `parentId`, `name`, 선택 `description`, `examples`다. 알 수 없는 필드는 422다.
description 생략/null은 null, examples 생략/null은 빈 배열이다.
parent는 공용 상위 registry ID만 허용한다. 공용 leaf/custom UUID는 parent가 될 수 없다.

이름은 비공백이며 최대 40 Unicode code point, 설명은 최대 200,
예시는 최대 5개·각 최대 60 code point다. 배열 내 빈/공백뿐인 예시는 422이며 []는 허용한다.
설명에만 LF와 CRLF를 허용하고 원문을 보존한다. 단독 CR과 이름·예시의 LF/CRLF는 거절한다.
그 밖의 제어문자·잘못된 surrogate는 422다.
비표시 U+200B/U+FEFF와 bidi 제어 U+202A~202E/U+2066~2069도 거절한다.
U+2028/2029는 이름·예시에서도 허용하며 비교 key에서는 Unicode 공백으로 처리한다.
U+3164(Hangul filler), U+2800(Braille blank), LRM/RLM/ALM(U+200E/200F/061C)도 허용한다.
이 문자는 비공백 검사에서 화면상의 가시성까지 판정하는 대상이 아니다.
FORMAT 범주 전체를 거절하지 않는다. 정상 ZWJ/ZWNJ 결합 문자·emoji tag sequence는 보존한다.
원문은 보존한다. 중복 비교만 NFC→Unicode 공백 trim/collapse→Locale.ROOT 소문자화를 사용한다.
길이는 저장 원문의
Unicode code point 수다(CRLF는 2개). NFC 비교는 NFD 한글의 중복 우회를 막는다.
클라이언트의 grapheme/UTF-16 counter와 다르므로 후속 KMP 입력 검증·counter도 이 단위에
맞춰야 한다.
같은 owner·parent의 normalized 이름은 미삭제 category에 대해 unique다.
다른 parent에서는 같은 이름을 허용한다. normalization은 공용 이름과의 중복 제한을 추가하지 않는다.

생성 receipt는 계정 데이터가 유지되는 동안 보존하며 시간 만료하지 않는다.
정상 replay는 생성 개수·rate limit 검사보다 먼저 처리한다. 신규 생성 성공은 owner별 직전
60초 최대 5건이며 custom 한도는 미삭제 20개다. 최초 201/Location,
replay 200/Idempotency-Replayed:true다. 결과 DTO는 `category`, `customUsedCount`, `customLimit`이다.
category는 CAT-02와 같은 표현이다. 최초 응답은 commit한 snapshot이고 replay는 현재 category다.

payload 동일 여부는 원문 name·parent와 기본값을 적용한 description/examples로 판정한다.
JSON key 순서·생략/null 기본값은 같고, 원문 name의 공백/대소문자나
예시 배열 순서 변경은 다른 payload다. 같은 owner+operation+key의 다른 payload는
409 IDEMPOTENCY_KEY_REUSED다. ITEM-01 key namespace와 독립적이다.
실패 409/429 등은 receipt를 남기지 않으므로 같은 key 재시도는 처음부터 검사한다.
향후 B8 삭제 뒤 정상 payload replay는 409 CATEGORY_NOT_AVAILABLE이며 새 category를 만들지 않는다.
rate limit은 삭제 여부와 관계없이 직전60초의 성공 receipt를 센다.
Retry-After = max(1, ceil(가장 오래된 해당 성공 시각 + 60초 - 판정 시각)) 초다.
생성·편집 상세에는 kind/displayOrder가 없으므로 클라이언트는 CAT-01을 재조회해 목록 캐시를 갱신한다.

## CAT-02 상세와 CAT-04 편집

상세: `GET /v1/custom-categories/{id}`. 정규 UUID만 받는다.
응답은 `id`, `name`, `parentId`, `description`, `examples`, `itemCount`, `version`이다.
없는/미삭제가 아닌/다른 owner 자원은 동일 404 CATEGORY_NOT_FOUND다.
빈 custom도 itemCount 0으로 조회한다. AI 허용·제외 사유는 공개하지 않는다.

편집: `PATCH /v1/custom-categories/{id}`. 필수 양의 정수 `expectedVersion`,
변경 가능한 `name`, `description`, `examples`만 받는다.
필드 생략은 유지, name null은 422, description null은 해제, examples null은 빈 배열이다.
parentId는 같은 값이어도 422 CATEGORY_PARENT_IMMUTABLE로 거절한다.
알 수 없는 필드·변경 필드 없는 요청은 422다. expectedVersion이 다르면
409 CATEGORY_VERSION_CONFLICT와 currentVersion을 반환한다.
성공은 200과 최신 상세 표현이다. 원문 name/description/examples 중 실제 변경이 있으면
version을 1 증가시키고 AI 적합성을 다시 검사한다. 동일 내용 PATCH는 version·AI 후보 version을 유지한다.
입력·normalized unique·AI 후보 적합성은 생성과 같은 경계를 사용한다.

## 상품 응답 영향

ITEM-01 생성, owner GET 및 replay는 동일한 B1 mapper를 사용한다.
상품 응답의 `category.id`는 공용 C-ID 또는 custom UUID다. nullable `name`, `parentId`,
`kind: PUBLIC|CUSTOM`을 함께 제공하며 미지정일 때 이 값들은 null이다.
custom 이름은 owner의 현재 row를 읽으므로 활성 상품 GET에 편집된 표시명이 반영된다.
category 편집만으로 item version, 출처, reviewStatus 및 기존 연결을 초기화하지 않는다.
archive snapshot은 B10 계약에 속한다. [상품 읽기 API](wishlist-item-read-api.md)를 따른다.

```json
{"category":{"id":"11111111-1111-4111-8111-111111111111","name":"책상 장비","parentId":"G003","kind":"CUSTOM","source":"AI","missingReason":null}}
```

## 공통 HTTP·서비스 경계

Firebase owner, canonical UUID, requestId/error envelope는 B1 공통 계약을 따른다.
400은 path/header/query 형식, 422는 body/필드 입력, 404는 owner-scoped 미존재,
409는 이름·한도·key·version 충돌, 429는 신규 생성 rate limit(Retry-After)이다.
body parser는 문자열/숫자/배열 타입을 검사하고 사용자 원문을 오류에 반사하지 않는다.

## 오류 코드

| 상태 | code | 조건 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 인증 owner 없음 |
| 400 | INVALID_CATEGORY_QUERY | scope 누락/오류·중복 query |
| 400 | INVALID_CATEGORY_ID | path UUID 형식 오류 |
| 400 | INVALID_IDEMPOTENCY_KEY | 생성 key 누락/UUID 형식 오류 |
| 400 / 422 | INVALID_CATEGORY_PARENT | CAT-01 없는 parent / POST 없는 parent |
| 422 | INVALID_CATEGORY_INPUT | JSON·타입·길이·금지 문자·빈 예시·편집 필드 오류 |
| 422 | CATEGORY_PARENT_IMMUTABLE | PATCH parentId 전달 |
| 404 | CATEGORY_NOT_FOUND | 없는/삭제/다른 owner 상세·편집 |
| 409 | CATEGORY_NAME_DUPLICATE | 동일 parent normalized 이름 중복 |
| 409 | CATEGORY_LIMIT_REACHED | 미삭제 custom 20개 |
| 409 | IDEMPOTENCY_KEY_REUSED | 같은 key 다른 payload |
| 409 | CATEGORY_NOT_AVAILABLE | 삭제된 category의 생성 replay |
| 409 | CATEGORY_VERSION_CONFLICT | expectedVersion 불일치·details.currentVersion |
| 429 | CATEGORY_CREATE_RATE_LIMITED | 60초 생성 성공 5건·Retry-After |

## 요청·응답 예시

예시는 표시된 scope의 실제 결과 형태다. UUID와 requestId는 예시 값이다.

### CAT-01: 상품이 없는 custom 조회

```http
GET /v1/categories?scope=BROWSE&parentId=G003
```

```json
{
  "scope": "BROWSE",
  "taxonomyVersion": "v1",
  "groups": [{
    "id": "G003", "name": "디지털·IT", "displayOrder": 2, "itemCount": 0,
    "categories": [{
      "id": "11111111-1111-4111-8111-111111111111", "name": "책상 장비",
      "parentId": "G003", "kind": "CUSTOM", "displayOrder": 15, "itemCount": 0
    }]
  }],
  "customUsedCount": 1,
  "customLimit": 20
}
```

### CAT-03 생성과 CAT-02 상세

```http
POST /v1/custom-categories
Idempotency-Key: 22222222-2222-4222-8222-222222222222
Content-Type: application/json
```

```json
{"parentId":"G003","name":"책상 장비","description":"업무 공간\n주변 장비","examples":["키보드","헤드폰"]}
```

```http
HTTP/1.1 201 Created
Location: /v1/custom-categories/11111111-1111-4111-8111-111111111111
```

```json
{
  "category": {
    "id": "11111111-1111-4111-8111-111111111111", "name": "책상 장비", "parentId": "G003",
    "description": "업무 공간\n주변 장비", "examples": ["키보드", "헤드폰"], "itemCount": 0, "version": 1
  },
  "customUsedCount": 1, "customLimit": 20
}
```

CAT-02의 200 응답은 위 `category` object 자체다. 같은 생성 key/payload replay는 200과
`Idempotency-Replayed: true`를 반환하며 category는 현재 상태다.

### CAT-04: 설명·예시 해제와 오류

```http
PATCH /v1/custom-categories/11111111-1111-4111-8111-111111111111
Content-Type: application/json
```

```json
{"expectedVersion":1,"description":null,"examples":null}
```

```json
{"id":"11111111-1111-4111-8111-111111111111","name":"책상 장비","parentId":"G003","description":null,"examples":[],"itemCount":0,"version":2}
```

필드 오류(422)와 version 충돌(409)의 envelope:

```json
{"error":{"code":"INVALID_CATEGORY_INPUT","requestId":"33333333-3333-4333-8333-333333333333","details":{"fields":["examples"]}}}
```

```json
{"error":{"code":"CATEGORY_VERSION_CONFLICT","requestId":"33333333-3333-4333-8333-333333333333","details":{"currentVersion":2}}}
```

생성 rate limit 응답:

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 37
X-Request-ID: 33333333-3333-4333-8333-333333333333
```

```json
{"error":{"code":"CATEGORY_CREATE_RATE_LIMITED","requestId":"33333333-3333-4333-8333-333333333333","details":{}}}
```

## 내부 설계와 검증 결과

[AI 후보 공급·stale·토큰 정책](category-ai-candidates.md)과
[스키마·owner 잠금](wishlist-state-persistence.md#category-스키마와-구조-잠금)을 별도 문서에서 관리한다.
실행 명령·회귀·리뷰 결과는 [B2 구현 이력](../../history/architecture/server/b2-category-implementation-2026-10-07.md)과
[후속 리뷰 보완](../../history/architecture/server/b2-review-followup-2026-10-07.md)에 있다.
