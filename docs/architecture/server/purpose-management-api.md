# 목적 조회·생성·편집 API 계약

> 확정 계약 · PUR-01~04

## 적용 범위

Firebase 인증 owner별 PUR-01~04 계약이다. 목적 삭제 PUR-05/06과 후보 추가·이동 PUR-07/08은 B8,
archive 생명주기는 B10 계약에 속한다. 제품 규칙은 [구매 후보 정리](../../product/organize-candidates.md),
결정 경위는 [B3 제품 결정](../../history/product-planning/mvp/decisions/b3-purpose-api-policy-2026-10-07.md)에 있다.
AI 목적 후보와 반영 보호는 [AI 목적 후보](purpose-ai-candidates.md), DB 경계는
[상태 영속성](wishlist-state-persistence.md#b3-목적-schema와-잠금)을 따른다.

## 표시 key 리소스

서버 상수 `PurposeStyle` `v1`이 허용 key와 기본값을 고정한다. 별도 HTTP API는 없다.

| 종류 | stable key | 기본 선택 |
| --- | --- | --- |
| colorKey | `CORAL, MUSTARD, PERIWINKLE, CYAN, MINT, PINK` | CORAL |
| iconKey | `HEART, HOME, PLANE, GIFT, TENT, MUSIC, STAR, BOOK` | HEART |

key는 모양·색 이름이며 화면 라벨(예: PLANE=여행)이 바뀌어도 바꾸지 않는다.
기본값은 클라이언트의 초기 선택이며, 서버는 생략된 key를 채우지 않는다. 대소문자가 다른 값과 목록 밖 값은 422다.

## PUR-02 생성과 replay

`POST /v1/purposes`, 필수 정규 UUID `Idempotency-Key`.
body는 필수 `name`, `colorKey`, `iconKey`, 선택 `description`이다. 알 수 없는 필드는 422다.
description 생략과 null은 같은 null로 정규화한다.

**입력 제한**
- 이름은 비공백 1~40, 설명은 0~200 Unicode code point다. 이름 중복은 허용한다.
- 문자 규칙은 사용자 category와 같다. 설명에만 LF/CRLF를 허용하고, 제어문자·잘못된 surrogate·U+200B/U+FEFF·bidi 제어는 거절한다.
- 원문은 보존한다.

**검사 순서**
1. owner 잠금을 잡는다.
2. 같은 key의 receipt가 있으면 replay로 판정한다. payload가 다르면 대상 상태와 관계없이 409 `IDEMPOTENCY_KEY_REUSED`다. 같으면 대상이 ACTIVE가 아닐 때 409 `PURPOSE_NOT_AVAILABLE`, ACTIVE면 replay다.
3. 직전 60초 성공 10건이면 429 `PURPOSE_CREATE_RATE_LIMITED`다.
4. ACTIVE 30개면 409 `PURPOSE_LIMIT_REACHED`다.
5. 생성하고 receipt를 저장한다.

**receipt와 rate limit**
- payload fingerprint는 원문 name·정규화한 description·colorKey·iconKey의 canonical JSON SHA-256이다.
- receipt는 계정 데이터 유지 동안 보존한다. 실패는 receipt를 남기지 않으므로 같은 key로 처음부터 재시도할 수 있다.
- rate limit은 대상 목적의 현재 상태와 관계없이 직전 60초 성공 receipt를 센다. `Retry-After = max(1, ceil(가장 오래된 성공 + 60초 - 판정 시각))`이다.
- key namespace는 operation별이다. ITEM-01·CAT-03 key와 독립적이다.

**응답**
- 최초 201 + `Location`, replay 200 + `Idempotency-Replayed: true`.
- body는 `{purpose, activeCount, purposeLimit: 30}`이다. 최초 응답은 commit한 snapshot, replay는 현재 상태다.
- 새 목적은 후보 0개의 ACTIVE 목적이다. 기존 상품·분석 job·AI snapshot을 바꾸지 않는다. 상품 편집을 취소해도 목적은 남는다.

## PUR-03 상세

`GET /v1/purposes/{id}`. 응답 필드는 다음과 같다.

- `id`, `name`, `description`, `colorKey`, `iconKey`
- `candidateCount`, `membershipVersion`, `version`
- `activity{at, kind}`: kind는 CREATED 또는 CANDIDATE_ADDED
- `createdAt`, `updatedAt`, `allowedActions`

**allowedActions**
- `EDIT, DELETE, ADD_CANDIDATES`이고, candidateCount ≥ 1일 때만 `ARCHIVE`가 붙는다.
- 이는 상태가 허용하는 행동이다(B1 상품의 REANALYZE와 같다). DELETE·ADD_CANDIDATES·ARCHIVE API는 B8/B10에서 연결되며, 그 전까지 클라이언트는 fake 또는 비활성으로 처리한다.

**candidateCount**는 같은 owner·ACTIVE·현재 purpose 일치 상품 전부를 센다. 분석·검토·보완 상태는 따지지 않는다. B4 ITEM-02의 `purposeId` filter도 같은 집합을 반환해야 한다.

없는 목적, ACTIVE가 아닌 목적, 다른 owner의 목적은 모두 404 `PURPOSE_NOT_FOUND`다. 형식 오류는 400 `INVALID_PURPOSE_ID`다.

## PUR-01 목록

`GET /v1/purposes?projection=SUMMARY|SELECT&limit=1..30&cursor=…`. projection은 필수, limit 기본은 30이다.

- **SELECT**(상품·검토의 목적 선택 시트): 각 항목 `id, name, colorKey, iconKey, version`.
- **SUMMARY**(목적 탭): SELECT 필드 + `description, candidateCount, activity, previews`. previews는 최근 저장순(서버 `created_at DESC, id DESC`) ACTIVE 후보 최대 4개의 `{itemId, imageUrl}`이다.
- **공통 최상위**: `projection, purposes, nextCursor, activeCount, purposeLimit, archiveSummary`.

정렬은 `activityAt DESC, id DESC`이고 빈 목적도 포함한다.
활동은 생성 또는 후보 유입(사용자 연결·AI 연결·다른 목적에서 이동)이다. 목적 정보 편집·후보 제거·조회는 순서를 바꾸지 않는다.
한 응답은 repeatable-read 한 snapshot으로 읽는다.

**cursor**
- 마지막 항목의 `(activityAt, id)`와 projection·owner 식별 hash를 담은 opaque 값이다.
- 다른 projection·owner의 cursor와 해석할 수 없는 값은 400 `INVALID_PURPOSE_CURSOR`다.
- 페이지 사이에 활동이 바뀐 목적은 빠지거나 반복될 수 있다. 진입·foreground·새로고침 때 처음부터 다시 읽는다.

**archiveSummary `{count, recentTitles}`**: B3에는 archive를 만드는 경로가 없다. 그래서 count는 ARCHIVED 목적의 실제 수(현재 0)이고 recentTitles는 `[]`다. B10에서 archive 기록의 수·최근 종료 제목으로 출처를 바꾼다.

## PUR-04 편집

`PATCH /v1/purposes/{id}`. 필수 양의 정수 `expectedVersion`과 변경 가능한 `name, description, colorKey, iconKey`만 받는다.

- 필드를 생략하면 유지한다. description null은 해제다. name·colorKey·iconKey의 null은 422다.
- 변경 필드가 없거나 알 수 없는 필드가 있으면 422다.
- version이 다르면 409 `PURPOSE_VERSION_CONFLICT`와 `details.currentVersion`이다.
- 원문 값이 하나라도 바뀌면 version +1, 같은 내용이면 version을 유지한 200이다. activity와 membershipVersion은 바꾸지 않는다.
- 응답은 PUR-03 표현이다.

목적 이름·설명 편집은 이미 연결된 미확정 AI 목적을 다시 판단하지 않는다(MVP). 진행 중 분석에 미치는 영향은 [AI 목적 후보](purpose-ai-candidates.md)를 따른다.

## 상품 응답 영향

B1 공통 mapper의 `purpose`에 nullable `name, colorKey, iconKey`가 추가된다. 값은 현재 owner 목적 row에서 읽는다.

```json
{"purpose":{"id":"44444444-4444-4444-8444-444444444444","name":"출퇴근 헤드폰","colorKey":"CORAL","iconKey":"MUSIC","source":"AI"}}
{"purpose":{"id":null,"name":null,"colorKey":null,"iconKey":null,"source":"UNASSIGNED"}}
{"purpose":{"id":null,"name":null,"colorKey":null,"iconKey":null,"source":"USER"}}
```

- UNASSIGNED는 아직 연결이 없다는 뜻이고, USER+null은 사용자가 확정한 목적 미지정이다(저장 표현은 하나).
- 목적 편집은 상품 version·출처·review를 바꾸지 않는다. 상품 version이 같아도 목적 표시값은 바뀔 수 있으므로, 클라이언트는 상품 version만으로 목적 표시 캐시를 유지하지 않는다.

## 공통 HTTP 경계와 오류 코드

Firebase owner, canonical UUID, requestId/error envelope는 B1 공통 계약을 따른다. body parser는 타입을 검사하고 사용자 원문을 오류에 반사하지 않는다.

| 상태 | code | 조건 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 인증 owner 없음 |
| 400 | INVALID_PURPOSE_ID | path UUID 형식 오류 |
| 400 | INVALID_IDEMPOTENCY_KEY | 생성 key 누락·형식 오류 |
| 400 | INVALID_PURPOSE_QUERY | projection 누락·오류, limit 범위·형식, 중복·알 수 없는 query |
| 400 | INVALID_PURPOSE_CURSOR | 해석 불가·다른 projection/owner cursor |
| 422 | INVALID_PURPOSE_INPUT | JSON·타입·길이·문자·key·편집 필드 오류, `details.fields` |
| 404 | PURPOSE_NOT_FOUND | 없음·비ACTIVE·다른 owner |
| 409 | PURPOSE_LIMIT_REACHED | ACTIVE 30개 |
| 409 | IDEMPOTENCY_KEY_REUSED | 같은 key 다른 payload |
| 409 | PURPOSE_NOT_AVAILABLE | 비ACTIVE 목적의 생성 replay |
| 409 | PURPOSE_VERSION_CONFLICT | expectedVersion 불일치, `details.currentVersion` |
| 429 | PURPOSE_CREATE_RATE_LIMITED | 60초 성공 10건, `Retry-After` |

## 요청·응답 예시

UUID·시각·requestId는 예시 값이다.

### PUR-02 생성

```http
POST /v1/purposes
Idempotency-Key: 22222222-2222-4222-8222-222222222222
Content-Type: application/json
```

```json
{"name":"출퇴근 헤드폰","description":"지하철에서 쓸 노이즈 캔슬링","colorKey":"CORAL","iconKey":"MUSIC"}
```

```http
HTTP/1.1 201 Created
Location: /v1/purposes/44444444-4444-4444-8444-444444444444
```

```json
{
  "purpose": {
    "id": "44444444-4444-4444-8444-444444444444",
    "name": "출퇴근 헤드폰", "description": "지하철에서 쓸 노이즈 캔슬링",
    "colorKey": "CORAL", "iconKey": "MUSIC",
    "candidateCount": 0, "membershipVersion": 1, "version": 1,
    "activity": {"at": "2026-10-07T03:00:00Z", "kind": "CREATED"},
    "createdAt": "2026-10-07T03:00:00Z", "updatedAt": "2026-10-07T03:00:00Z",
    "allowedActions": ["EDIT", "DELETE", "ADD_CANDIDATES"]
  },
  "activeCount": 1, "purposeLimit": 30
}
```

같은 key·payload replay는 `HTTP/1.1 200 OK`와 `Idempotency-Replayed: true`이며 purpose는 현재 상태다.
PUR-03의 200 응답은 위 `purpose` object 자체다.

### PUR-01 목록

```http
GET /v1/purposes?projection=SUMMARY&limit=2
```

```json
{
  "projection": "SUMMARY",
  "purposes": [{
    "id": "44444444-4444-4444-8444-444444444444", "name": "출퇴근 헤드폰",
    "colorKey": "CORAL", "iconKey": "MUSIC", "version": 1,
    "description": "지하철에서 쓸 노이즈 캔슬링", "candidateCount": 5,
    "activity": {"at": "2026-10-06T09:00:00Z", "kind": "CANDIDATE_ADDED"},
    "previews": [{"itemId": "55555555-5555-4555-8555-555555555555", "imageUrl": "https://shop.example/a.jpg"},
                 {"itemId": "66666666-6666-4666-8666-666666666666", "imageUrl": null}]
  }],
  "nextCursor": "djF8U1VNTUFSWXwuLi4",
  "activeCount": 7, "purposeLimit": 30,
  "archiveSummary": {"count": 0, "recentTitles": []}
}
```

SELECT 항목은 `{"id":"…","name":"출퇴근 헤드폰","colorKey":"CORAL","iconKey":"MUSIC","version":1}`이며 같은 최상위 필드를 갖는다.

### PUR-04 편집과 오류

```http
PATCH /v1/purposes/44444444-4444-4444-8444-444444444444
Content-Type: application/json
```

```json
{"expectedVersion":1,"description":null,"iconKey":"BOOK"}
```

200 응답은 `description: null`, `iconKey: "BOOK"`, `version: 2`인 PUR-03 표현이다.

```json
{"error":{"code":"INVALID_PURPOSE_INPUT","requestId":"33333333-3333-4333-8333-333333333333","details":{"fields":["colorKey"]}}}
```

```json
{"error":{"code":"PURPOSE_VERSION_CONFLICT","requestId":"33333333-3333-4333-8333-333333333333","details":{"currentVersion":2}}}
```

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 37
X-Request-ID: 33333333-3333-4333-8333-333333333333
```

```json
{"error":{"code":"PURPOSE_CREATE_RATE_LIMITED","requestId":"33333333-3333-4333-8333-333333333333","details":{}}}
```

## 검증 결과

실행 명령·회귀·리뷰 결과는 [B3 구현 이력](../../history/architecture/server/b3-purpose-implementation-2026-10-07.md)에 있다.
