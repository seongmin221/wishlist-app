# B3 목적 기본 관리 설계

> 상태: 사용자 승인 설계 · 2026-10-07 · 범위 PUR-01~04와 AI 목적 후보

## 목표와 경계

목적 생성·상세·목록·편집 API와 owner별 AI 목적 후보 공급·stale 보호를 구현한다.
기준은 develop `1c6d949`(B2 병합)이다. 이 공간의 baseline 전체 `--rerun-tasks`는
tests=252, failures=0, errors=0, skipped=1(RealUrlPilot)이다.

구현하지 않는 범위: 목적 삭제 PUR-05/06, 후보 추가/이동 PUR-07/08(B8), 상품 편집·확정·해제(B7),
ITEM-02/HOME(B4), Scheduler·browser runtime(B5), archive 생명주기(B10).
ITEM-03 deletionImpact의 목적 정보는 상품 삭제 ITEM-05와 함께 B7에서 확장한다.
`client/`와 `design/handoff/`는 수정하지 않는다.

## 확정한 제품 정책

사용자 답변(2026-10-07)과 근거 문서에서 확정한 값이다. 경위는
[B3 제품 결정](../../history/product-planning/mvp/decisions/b3-purpose-api-policy-2026-10-07.md)에 둔다.

| 항목 | 결정 |
| --- | --- |
| 색 key | `CORAL, MUSTARD, PERIWINKLE, CYAN, MINT, PINK`; 기본 CORAL (디자인 6색·canvas 기본) |
| 아이콘 key | `HEART, HOME, PLANE, GIFT, TENT, MUSIC, STAR, BOOK`; 기본 HEART 미리 선택 (canvas·handoff 8개) |
| 필수 입력 | 이름·colorKey·iconKey 필수, 설명 선택. 서버는 기본값을 채우지 않는다 |
| 입력 제한 | 이름 1~40, 설명 0~200 Unicode code point. B2와 같은 문자 규칙(설명만 LF/CRLF). 이름 중복 허용 |
| 개수·속도 | ACTIVE 목적 owner당 30개. 신규 생성 성공 owner별 직전 60초 10건 |
| 생성 key | receipt를 계정 데이터 유지 동안 보존. 실패는 receipt 없음 |
| 빈 목적 | 생성 허용·자동 삭제 없음·archive 불가. 상품 편집 취소 뒤에도 목적 유지 |
| 활동 | activityAt = 생성 시각 또는 마지막 후보 유입(사용자 연결·AI 연결·이동 유입). 편집·유출·조회는 갱신하지 않음 |
| 정렬 | 목적 탭·선택 목록·AI 후보 선정 모두 `activityAt DESC, id DESC`. 빈 목적 포함 |
| 후보 수 | 해당 목적이 연결된 ACTIVE 상품 전부(분석·검토·보완 상태 무관). 미리보기는 최근 저장순 4개 |
| AI 근거 | 활동순 ACTIVE 목적 최대 10개의 이름·설명·최근 후보 상품명 최대 2개 |
| 편집 후 재판단 | 없음. 진행 중 분석이 고른 목적이 낡았으면 목적 연결만 버린다 |
| 확정/보류 목적 미지정 | CONFIRMED/DEFERRED 상품의 빈 목적을 AI가 채우지 않는다(B0/B2 동작 변경) |
| AI 후보 제외 | 목적 텍스트에는 B2 custom 안전 제외를 적용하지 않는다. JSON 데이터 분리로 보호 |
| archive 입구 | B3는 ARCHIVED 목적의 실제 수(현재 0)와 빈 제목 목록. B10에서 archive 기록으로 출처 전환 |

## 공개 계약

모든 route는 Firebase owner 범위다. 400은 path/header/query 형식, 422는 body 입력,
404는 owner 범위 미존재, 409는 한도·key·version 충돌, 429는 생성 속도 제한이다.
오류 envelope·requestId·canonical UUID parser는 B1/B2 공통 계약을 따른다.

### 표시 key 리소스

서버의 `PurposeStyle` 상수 `v1`이 허용 key와 기본값을 고정한다. 별도 HTTP API는 두지 않는다.
key는 모양 이름이며 화면 라벨(예: PLANE=여행)이 바뀌어도 바꾸지 않는다. 앱은 테마별 색상·SVG에 매핑한다.

### PUR-02 `POST /v1/purposes`

필수 정규 UUID `Idempotency-Key`. body는 `name`, `colorKey`, `iconKey`, 선택 `description`.
알 수 없는 필드·잘못된 타입·key 밖의 값은 422 INVALID_PURPOSE_INPUT과 `details.fields`다.
description 생략/null은 null이다.

검사 순서: owner 잠금 → 같은 key receipt → 60초 10건 → ACTIVE 30개 → 생성·receipt 저장.
정상 replay는 한도 검사보다 먼저 처리한다. payload fingerprint는 원문 name·description(기본값 적용)·
colorKey·iconKey의 canonical JSON SHA-256이다. 다른 payload는 409 IDEMPOTENCY_KEY_REUSED다.
replay 대상이 ACTIVE가 아니면(향후 삭제·archive) 409 PURPOSE_NOT_AVAILABLE이다.
rate limit은 직전 60초의 CREATE_PURPOSE 성공 receipt를 대상 목적 상태와 무관하게 센다.
Retry-After는 B2와 같은 계산이다.

최초 201 + `Location: /v1/purposes/{id}`, replay 200 + `Idempotency-Replayed: true`.
응답은 `{purpose, activeCount, purposeLimit: 30}`이며 purpose는 PUR-03 표현이다.
최초 응답은 commit한 snapshot, replay는 현재 상태다. 새 목적 생성은 기존 상품·분석 job·snapshot을 바꾸지 않는다.

### PUR-03 `GET /v1/purposes/{id}`

```json
{
  "id": "44444444-4444-4444-8444-444444444444",
  "name": "출퇴근 헤드폰", "description": "지하철에서 쓸 노이즈 캔슬링",
  "colorKey": "CORAL", "iconKey": "MUSIC",
  "candidateCount": 0, "membershipVersion": 1, "version": 1,
  "activity": {"at": "2026-10-07T03:00:00Z", "kind": "CREATED"},
  "createdAt": "2026-10-07T03:00:00Z", "updatedAt": "2026-10-07T03:00:00Z",
  "allowedActions": ["EDIT", "DELETE", "ADD_CANDIDATES"]
}
```

`ARCHIVE`는 candidateCount ≥ 1일 때만 포함한다. allowedActions는 상태가 허용하는 행동이며
해당 API의 구현 묶음(B8/B10)과 별개다. 없는 목적·ACTIVE가 아닌 목적·다른 owner는 모두
404 PURPOSE_NOT_FOUND, 형식 오류는 400 INVALID_PURPOSE_ID다.

### PUR-01 `GET /v1/purposes`

query: `projection=SUMMARY|SELECT`(필수), 선택 `limit`(1~30, 기본 30), 선택 `cursor`.
중복·잘못된 query는 400 INVALID_PURPOSE_QUERY, 잘못된 cursor 또는 다른 projection·owner의 cursor는
400 INVALID_PURPOSE_CURSOR다. 응답은 repeatable-read 한 snapshot으로 읽는다.

- SELECT 항목: `id, name, colorKey, iconKey, version`. 상품·검토의 목적 선택 시트용이다.
- SUMMARY 항목: SELECT 필드 + `description, candidateCount, activity, previews`.
  previews는 최근 저장순 최대 4개의 `{itemId, imageUrl}`이며 imageUrl은 nullable이다.
- 공통 최상위: `projection, purposes, nextCursor, activeCount, purposeLimit: 30, archiveSummary`.
- `archiveSummary`: `{count, recentTitles}`. B3는 archive 생성 경로가 없으므로 count는 ARCHIVED 목적의
  실제 수(항상 0), recentTitles는 `[]`이다. B10에서 archive 기록의 수·최근 종료 제목으로 출처를 바꾼다.

cursor는 마지막 항목의 `(activityAt, id)`와 projection·owner 식별 hash를 담은 opaque 문자열이다.
목록 사이에 활동이 바뀐 목적은 다음 페이지에서 빠지거나 반복될 수 있다. 갱신 정책(진입·foreground·새로고침)상
처음부터 다시 읽는다. ACTIVE 30개 상한이므로 기본 limit에서는 한 페이지로 끝난다.

### PUR-04 `PATCH /v1/purposes/{id}`

필수 양의 정수 `expectedVersion`, 변경 가능한 `name, description, colorKey, iconKey`만 받는다.
생략은 유지, description null은 해제, name·colorKey·iconKey null은 422다. 변경 필드가 없거나 알 수 없는
필드는 422다. version 불일치는 409 PURPOSE_VERSION_CONFLICT와 `details.currentVersion`이다.
원문 값이 하나라도 바뀌면 version +1, 동일 내용은 version을 유지한 200이다. activity와
membershipVersion은 바꾸지 않는다. 응답은 PUR-03 표현이다.

### 상품 응답 확장

B1 공통 mapper의 `purpose`에 nullable `name, colorKey, iconKey`를 추가한다. 현재 owner 목적 row에서 읽어
편집 결과가 활성 상품 GET에 바로 반영된다. 목적 편집은 상품 version·출처·review를 바꾸지 않는다.

```json
{"purpose":{"id":"44444444-4444-4444-8444-444444444444","name":"출퇴근 헤드폰","colorKey":"CORAL","iconKey":"MUSIC","source":"AI"}}
```

### 오류 코드

| 상태 | code | 조건 |
| --- | --- | --- |
| 401 | UNAUTHORIZED | 인증 owner 없음 |
| 400 | INVALID_PURPOSE_ID / INVALID_IDEMPOTENCY_KEY | path·key 형식 |
| 400 | INVALID_PURPOSE_QUERY / INVALID_PURPOSE_CURSOR | projection·limit·cursor |
| 422 | INVALID_PURPOSE_INPUT | JSON·타입·길이·문자·key·편집 필드 |
| 404 | PURPOSE_NOT_FOUND | 없음·비ACTIVE·다른 owner |
| 409 | PURPOSE_LIMIT_REACHED | ACTIVE 30개 |
| 409 | IDEMPOTENCY_KEY_REUSED | 같은 key 다른 payload |
| 409 | PURPOSE_NOT_AVAILABLE | 비ACTIVE 목적의 생성 replay |
| 409 | PURPOSE_VERSION_CONFLICT | expectedVersion 불일치 |
| 429 | PURPOSE_CREATE_RATE_LIMITED | 60초 성공 10건, Retry-After |

## 데이터와 migration

V13만 추가한다. V1~V12는 수정하지 않는다.

**purposes**: `id uuid`, `owner_id → app_users`, name(1~40)·description(≤200) CHECK, colorKey/iconKey CHECK,
`lifecycle_status`(ACTIVE/ARCHIVED/DELETED, B3는 ACTIVE만 생성), `version`, `membership_version`,
`activity_at`, `activity_kind`(CREATED/CANDIDATE_ADDED), created/updated 시각, `unique(owner_id,id)`,
`(owner_id, activity_at desc, id desc) where ACTIVE` 인덱스.

**legacy 목적 참조 전환**: 목적 테이블이 없었으므로 기존 `wishlist_items.purpose_id` 문자열은 실제 목적을
가리킬 수 없다. 원문을 새 `legacy_purpose_id`에 보존한다. AI 출처는 UNASSIGNED, USER 출처는 USER+null(보호)로
바꾸고 review는 유지한다. 그 뒤 `purpose_id`를 uuid로 바꾸고 `(owner_id,purpose_id)` 복합 FK를 VALID로 추가한다.
`predicted_purpose_id`·`pending_purpose_id`는 진단·임시 문자열로 유지하고 finish에서 재검증한다.
활성 목적별 count용 `(owner_id,purpose_id) where ACTIVE` 인덱스를 둔다.

**mutation_receipts**: category_id를 nullable로 바꾸고 `purpose_id` + `(owner_id,purpose_id)` FK를 추가한다.
`num_nonnulls(category_id,purpose_id)=1` CHECK. operation은 CREATE_PURPOSE다.

**잠금과 version**: 사용자 구조 변경은 owner → purpose → item → job 순서다. Worker finish는 기존
owner → item → job 뒤 목적 row를 갱신한다. 모든 목적 쓰기가 owner 잠금을 먼저 잡으므로 순환 대기가 없다.
외부 HTTP/AI 동안 DB 잠금을 유지하지 않는다. `version`은 목적 정보 CAS, `membershipVersion`은 후보 유입·유출마다
+1(B8 이동·B10 archive 확인용)이다. AI 재검증은 version 대신 snapshot의 이름·설명 원문을 비교해
색·아이콘 편집이 AI 결과를 무효화하지 않게 한다.

## AI 목적 후보

[AI 내부 설계](../../architecture/server/purpose-ai-candidates.md)에 상세를 둔다.

- **공급**: B2 `CategoryCandidateSupply`가 같은 connection으로 ACTIVE 목적 상위 10개(활동순, 빈 목적 포함)와
  각 목적의 최근 저장순 ACTIVE 후보 상품명 최대 2개(분석 대상 상품 제외, 각 40 code point)를 읽는다.
- **snapshot v3**: codec이 `purpose_candidates` `{id: {name, description, item_names}}`를 저장한다.
  v1/v2 snapshot에는 목적 후보가 없으므로 그 결과의 목적은 버린다.
- **요청**: 목적은 compact 문자열이 아니라 JSON object 배열로 보낸다. 출력 80 token 안에 UUID 두 개가 들어가지 않도록
  요청에서는 목적에 `P01`~`P10` alias를 쓰고 응답을 실제 ID로 되돌린다.
- **토큰 단계**(2,000/80 유지, 단계별 token-count 검사, 실제 전송 후보로만 응답 검증):

| 단계 | 상품 | custom | 목적 |
| --- | --- | --- | --- |
| T0 | 2,400 | 전체 | 이름+설명+상품명 |
| T1 | 800 | 이름만 | 이름+설명 |
| T2 | 160 | 이름 12 | 이름만 |
| T3 | 160 | 제외 | 이름만 |
| T4 | 160 | 제외 | 제외(B2 공용 최소) |

  목적·custom이 모두 없으면 B2와 같은 T0→T4다. 직전 단계와 같은 본문은 다시 검사하지 않는다.
  목적을 제외한 호출은 그 호출의 목적 미지정이며 stale·재시도로 다루지 않는다.
- **반영**: AI가 고른 목적이 같은 owner·ACTIVE이고 이름·설명이 snapshot과 같을 때만 연결한다. 아니면 목적만 버리고
  재예약하지 않는다. category stale은 B2 규칙(예산 승계 재예약)을 그대로 따른다. CONFIRMED/DEFERRED,
  USER 출처·PURPOSE override는 연결하지 않는다. 연결이 바뀌면 같은 transaction에서 새 목적의 membershipVersion +1·
  activity(CANDIDATE_ADDED), 이전 목적의 membershipVersion +1을 기록한다.
- **비용 보호**: 목적 생성·편집은 job을 만들지 않는다. 기존 generation의 최대 3회·30분 예산과 B2 replacement는 그대로다.

## 검증

실제 PostgreSQL/Testcontainers로 다음을 확인한다.

- owner 격리·다른 owner 목적 참조의 DB 차단, 다른 owner 404
- key replay·payload 충돌·실패 재시도·operation namespace, 29개에서 동시 생성 두 건 중 하나만 성공, 60초 10건과 Retry-After
- 입력 경계(code point·문자 규칙·key 밖 값), 빈 목적 생성·상세·목록 유지
- version 충돌·no-op·optional null, 색 편집 후 AI 결과 유지, 이름 편집 후 목적만 폐기
- 동일 activityAt tie-break·cursor·projection·limit, 목적 0~1개 모델
- stale snapshot의 owner·비활성·이름 변경, CONFIRMED/DEFERRED·USER/override 보호, 신규 목적 생성 시 job 0
- T0~T4 축약·목적 alias 역매핑·전송하지 않은 목적 거절, legacy V12→V13 upgrade
- B1/B2/Worker 기존 회귀 전체

## 문서와 커밋

- `docs/architecture/server/purpose-management-api.md`: 공개 계약·JSON·오류·헤더 예시
- `docs/architecture/server/purpose-ai-candidates.md`: 후보 공급·snapshot·토큰·stale
- persistence·read API·inventory·implementation order·INDEX 갱신, 제품 결정과 구현 이력 기록
- 의미별 한글 로컬 커밋. push·PR·병합은 하지 않는다.
