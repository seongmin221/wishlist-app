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

사용자 답변(2026-10-07)과 근거 문서에서 확정한 값이다. 경위·근거·감수한 부작용은
[B3 제품 결정](../../history/product-planning/mvp/decisions/b3-purpose-api-policy-2026-10-07.md)에 둔다.

| 항목 | 결정 |
| --- | --- |
| 색 key | `CORAL, MUSTARD, PERIWINKLE, CYAN, MINT, PINK`; 기본 CORAL (디자인 6색·canvas 기본) |
| 아이콘 key | `HEART, HOME, PLANE, GIFT, TENT, MUSIC, STAR, BOOK`; 기본 HEART 미리 선택 (canvas·handoff 8개) |
| 필수 입력 | 이름·colorKey·iconKey 필수, 설명 선택. 기본값은 클라이언트의 초기 선택이며 서버는 생략된 key를 채우지 않는다 |
| 입력 제한 | 이름 1~40, 설명 0~200 Unicode code point. B2와 같은 문자 규칙(설명만 LF/CRLF). 이름 중복 허용 |
| 개수·속도 | ACTIVE 목적 owner당 30개. 신규 생성 성공 owner별 직전 60초 10건 |
| 생성 key | receipt를 계정 데이터 유지 동안 보존. 실패는 receipt 없음 |
| 빈 목적 | 생성 허용·자동 삭제 없음·archive 불가. 상품 편집 취소 뒤에도 목적 유지 |
| 활동 | activityAt = 생성 시각 또는 마지막 후보 유입(사용자 연결·AI 연결·이동 유입). 편집·유출·조회는 갱신하지 않음 |
| 정렬 | 목적 탭·선택 목록·홈(B4)·AI 후보 선정 모두 `activityAt DESC, id DESC`. 빈 목적 포함 |
| 후보 수 | 해당 목적이 연결된 ACTIVE 상품 전부(분석·검토·보완 상태 무관). 미리보기는 최근 저장순(서버 `created_at DESC, id DESC`, ITEM-02와 동일) 4개 |
| AI 근거 | 활동순 ACTIVE 목적 최대 10개의 이름·설명·최근 저장순 후보 상품명 최대 2개(각 20 code point). 목적 근거를 먼저 줄인다 |
| AI 비용 | 유료 입력 상한 2,000→**2,500**, 출력 80 유지. 일·월 예산을 같은 처리 건수가 되도록 비례 상향(일 $0.60→$0.721, 월 $6.00→$7.21) |
| 편집 후 재판단 | MVP에서는 없음. 진행 중 분석이 고른 목적의 이름·설명이 바뀌었으면 그 결과의 목적을 버리고 기존 연결을 유지한다 |
| 확정/보류 목적 미지정 | CONFIRMED/DEFERRED 상품의 빈 목적을 AI가 채우지 않는다(B0/B2 동작 변경) |
| AI 후보 제외 | 목적 텍스트에는 B2 custom 안전 제외를 적용하지 않는다. 근거는 결정 기록 참조 |
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
description 생략과 null은 같은 null로 정규화한다.

검사 순서: owner 잠금 → 같은 key receipt → 60초 10건 → ACTIVE 30개 → 생성·receipt 저장.
정상 replay는 한도 검사보다 먼저 처리한다. payload fingerprint는 원문 name·정규화한 description·
colorKey·iconKey의 canonical JSON SHA-256이다. receipt가 있으면 B2와 같은 순서로 판정한다.
fingerprint가 다르면 대상 상태와 관계없이 409 IDEMPOTENCY_KEY_REUSED, 같으면 대상이 ACTIVE가 아닐 때
(향후 삭제·archive) 409 PURPOSE_NOT_AVAILABLE, ACTIVE면 replay다.
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

`ARCHIVE`는 candidateCount ≥ 1일 때만 포함한다. allowedActions는 B1 상품의 REANALYZE처럼 상태가 허용하는
행동이며 API 구현 묶음과 별개다. B3만 배포된 동안 클라이언트(C6)는 DELETE·ADD_CANDIDATES·ARCHIVE를
fake 또는 비활성으로 처리하고 B8/B10 연결 때 실제 호출로 바꾼다.
없는 목적·ACTIVE가 아닌 목적·다른 owner는 모두 404 PURPOSE_NOT_FOUND, 형식 오류는 400 INVALID_PURPOSE_ID다.

candidateCount는 B4 ITEM-02 `purposeId` filter 결과와 같은 집합이다(같은 owner·ACTIVE·현재 purpose 일치).
B4는 일반 category 목록의 표시 조건(이름·category 존재)을 목적 filter에 추가하지 않는다.
B7 이후 변경 흐름에서도 PUR-01/PUR-03 count·미리보기와 ITEM-02 결과의 일치를 다시 확인한다.

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
편집 결과가 활성 상품 GET에 바로 반영된다. 목적이 없으면 네 값이 모두 null이고 source로 의미를 구분한다.

```json
{"purpose":{"id":"44444444-4444-4444-8444-444444444444","name":"출퇴근 헤드폰","colorKey":"CORAL","iconKey":"MUSIC","source":"AI"}}
{"purpose":{"id":null,"name":null,"colorKey":null,"iconKey":null,"source":"UNASSIGNED"}}
{"purpose":{"id":null,"name":null,"colorKey":null,"iconKey":null,"source":"USER"}}
```

UNASSIGNED는 아직 연결이 없음, USER+null은 사용자가 확정한 목적 미지정이다.
목적 편집은 상품 version·출처·review를 바꾸지 않는다. B2 category 이름 변경과 같이 상품 version이 같아도
목적 표시값은 바뀔 수 있으므로 클라이언트는 상품 version만으로 목적 표시 캐시를 유지하지 않고 재조회 값을 쓴다.

### 확정된 목적 미지정의 표현

저장 표현은 하나로 확정한다. 사용자가 목적 없음을 확정한 상태는 V8부터 정의된 `purpose_source=USER, purpose_id=null`이다.
legacy 전환과 B8 목적 삭제는 이 표현을 기록한다. B7은 목적 없이 저장하는 확정·편집 명령 중 어느 것이 이 표현을 기록하는지만 계약에서 정한다.
CONFIRMED/DEFERRED + UNASSIGNED는 별도 의미가 아니라 "검토를 마친 상품"에 대한 AI 보호 조건이다.
AI는 USER 출처·PURPOSE override·CONFIRMED/DEFERRED 중 하나라도 해당하면 목적을 쓰지 않는다.

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

V13(목적 구조)과 V14(예산 window ceiling)를 추가한다. V1~V12는 수정하지 않는다.

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

**잠금 순서**: 사용자 구조 변경은 owner → purpose(ID순) → item(ID순) → job 순서다. Worker finish는 기존
owner → item → job 뒤 목적 row를 갱신한다. 모든 목적 쓰기가 owner 잠금을 먼저 잡으므로 순환 대기가 없다.
이 전제는 B3에 한정되지 않는다. B7 ITEM-04/07/08, B8 PUR-05~08, B10 archive/restore도 owner 잠금 없이
목적 row를 잠그거나 갱신하는 경로를 만들지 않는다. 외부 HTTP/AI 동안 DB 잠금을 유지하지 않는다.

**목적 row 보존**: 생성 receipt를 계정 수명 동안 보존하고 `(owner_id,purpose_id)` FK를 걸었으므로 목적 row는 hard delete하지 않는다.
B8 삭제와 B10 archive/restore도 lifecycle 전환(DELETED/ARCHIVED)만 사용한다.

**version 역할**: `version`은 목적 정보(이름·설명·색·아이콘) CAS다. `membershipVersion`은 후보 유입·유출마다
+1이며 B8 이동·B10 archive 확인용이다. AI 재검증은 version 대신 snapshot의 이름·설명 원문을 비교해
색·아이콘 편집이 AI 결과를 무효화하지 않게 한다. 후보 구성 변경은 목적 정보 변경으로 보지 않는다.

## AI 목적 후보

상세는 구현과 함께 작성하는 `docs/architecture/server/purpose-ai-candidates.md`에 둔다.

### 공급과 snapshot

B2 `CategoryCandidateSupply`가 같은 connection으로 ACTIVE 목적 상위 10개(`activity_at DESC, id DESC`, 빈 목적 포함)와
각 목적의 ACTIVE 후보 상품명 최대 2개를 읽는다. 상품명은 서버 `created_at DESC, id DESC` 순서이며
분석 대상 상품과 빈 이름은 제외하고 각 20 code point로 자른다.

기존 v2 codec에는 이미 `purposes` ID 배열·`purpose_labels`와 `purposes.size <= 10` 검사가 있고 gateway가
compact 문자열로 보낸다. 그러나 이 값은 목적 테이블이 생기기 전의 문자열 ID라 신뢰하지 않는다.
v1/v2 snapshot의 목적 결과는 판단 없음으로 처리한다.

신규 **schema v3**는 `purpose_labels`를 없애고 `purposes`를 **활동 순위 순서 그대로의 배열**
`[{"id", "name", "description", "item_names"}]`로 저장한다. 기존 v2처럼 정렬하지 않는다.
codec은 순서를 보존해 decode하고 ID 중복·정규 UUID·최대 10개를 검사한다. 재시도도 저장 순서를 그대로 쓴다.
`P01`~`P10` alias와 "활동순 5개" 단계는 이 저장 순서에서만 만든다. 그래서 첫 시도와 재시도의 alias가 같은 목적을 가리킨다.

### 요청 형식과 안전성

목적은 compact 문자열 대신 JSON object 배열로 보낸다. 사용자 텍스트의 구두점이 구조를 깨지 않는다.
key는 짧게 `{"id":"P01","n":이름,"d":설명,"i":[상품명]}`로 쓰고 고정 developer 지시문이 key 의미를 설명한다.
출력 80 token 안에 custom UUID와 목적 UUID가 함께 들어가지 않도록 목적 ID는 alias로 보내고
응답을 실제 ID로 되돌린다. 응답은 실제 전송한 alias 집합으로만 검증하며 그 밖의 값은 Unusable이다.

목적 이름·설명은 사용자 텍스트이고 후보 상품명은 외부 쇼핑몰 추출 텍스트(향후 사용자 편집 포함)다.
모두 JSON 데이터 메시지에만 넣는다. 목적에 custom 안전 제외를 적용하지 않는 근거는 결정 기록에 있다.

### 토큰 단계와 최악 크기

유료 입력 상한을 2,000에서 2,500으로 올리고 출력 80을 유지한다. `PriceTable` 최대 예약액은 496→596 micro USD다.
일·월 ceiling은 같은 최대 처리 건수(일 약 1,209·월 약 12,096건)가 되도록 일 721,000·월 7,210,000 micro USD로 올린다.
V14는 이미 생성된 `llm_budget_windows`의 ceiling도 새 값으로 갱신한다. 예약은 저장 ceiling이 현재 release 값과 다르면
사용량을 유지한 채 현재 값으로 맞춘 뒤 판정하므로, 다른 release가 만든 window 때문에 하루 예약 전체가 막히지 않는다.
원화 hard cap(1 USD=1,600원)은 일 약 1,160원·월 약 11,600원으로 AI·운영 문서에 반영한다.

단계마다 token-count endpoint로 같은 요청을 검사하고 통과한 단계 하나만 유료 호출한다.
목적 근거를 먼저 줄여 B2의 custom 근거가 B2보다 일찍 빠지지 않게 한다.

| 단계 | 상품 | custom | 목적 | 최악 | 보통 10개 | 보통 5개 |
| --- | --- | --- | --- | ---: | ---: | ---: |
| T0 | 2,400 | 전체 | 10개 이름+설명+상품명 | 18,211 | 2,750 | 2,229 ✓ |
| T1 | 2,400 | 전체 | 10개 이름+설명 | 17,751 | 2,290 ✓ | — |
| T2 | 2,400 | 전체 | 10개 이름만 | 15,715 | — | — |
| T3 | 800 | 이름만 | 10개 이름만 | 3,691 | — | — |
| T4 | 160 | 이름 12 | 10개 이름만 | 2,491 ✓ | — | — |
| T5 | 160 | 제외 | 10개 이름만 | 1,603 | — | — |
| T6 | 160 | 제외 | 활동순 5개 이름만 | 1,355 | — | — |
| T7 | 160 | 제외 | 제외(공용 최소) | 1,107 | — | — |

입력 형태별 단계 목록(같은 본문인 단계는 하나로 합친다). 첫 단계를 검사하고 넘으면 나머지를 이분 탐색해 들어가는 가장 앞 단계를 고르므로 token-count는 최대 4회다:

- custom·목적 모두 없음: T0 → T7. B2 공용-only의 tier 0 → 3과 같다.
- custom만 있음: T0 → T3 → T4 → T7. B2 tier 0~3(전체 → 이름 → 최소 → 공용 fallback)과 1:1이다.
- 목적만 있음: T0 → T1 → T2 → T5 → T6 → T7. 상품 800·custom 단계(T3/T4)는 custom이 있을 때만 쓴다.
- 둘 다 있음: T0~T7 전체. token-count는 Worker의 남은 처리 시간을 공유한다.

추정 기준: 실제 `ai/taxonomy/v1.json` 공용 compact와 지시문·schema를 고정분 ≈947 token으로 두고
한글 1자=1 token, ASCII 2.5자=1 token으로 계산했다. 최악은 custom 20개·목적 10개·모든 입력 최대 길이,
보통은 상품 metadata 300자·custom 3개(이름 10·설명 40·예시 5×10)·목적 이름 15·설명 30이다.
실제 값은 runtime token-count가 결정하며 이 표는 단계 설계의 근거이지 측정 결과가 아니다.

해석: 보통 입력에서 상품명 근거는 목적이 5개 안팎일 때 전달되고, 목적이 10개면 T1로 내려가 빠진다.
그래도 목적 설명과 custom 전체 근거는 남는다. 최악에서도 T4에서 목적 10개 이름과 custom 이름이 남고
T5~T7은 추정이 틀렸을 때의 보험이다. T6은 6~10번째, T7은 전체 목적을 그 호출에서만 제외한다.

관측: Worker는 유료 호출을 보낸 뒤 결과(성공·재시도·실패)와 관계없이 선택 단계·전송 custom 수·전송 목적 수를 jobId와 함께 INFO 로그로 남긴다.
사용자 텍스트는 기록하지 않는다. T5~T7 빈도는 이 로그로 확인하며 cloud metric 연결은 B11에서 다룬다.

### 반영과 보호

목적 결과는 **판단**과 **판단 없음**으로 나눈다.

- 판단: 유효 v3 snapshot에서 AI가 고른 목적이 같은 owner·ACTIVE이고 이름·설명이 snapshot과 같다.
  또는 snapshot의 목적 후보 전부를 전송한 단계(T0~T5)에서 AI가 목적 미지정을 반환했고, 상품의 현재 연결이 없거나 그 목적이 snapshot에 원문 그대로 있다.
- 판단 없음: 고른 목적이 비활성·다른 owner·이름/설명 변경, 모델이 보지 못했거나 이름/설명이 바뀐 현재 연결에 대한 목적 미지정, v1/v2 snapshot, 일부 또는 전부를 뺀 단계(T6/T7)의
  목적 미지정 결과다. 판단 없음은 기존 연결을 그대로 유지하며 membershipVersion도 바꾸지 않는다.

판단은 USER 출처·PURPOSE override·CONFIRMED/DEFERRED가 아닌 상품에만 반영한다. 연결이 바뀌면 같은 transaction에서
새 목적의 membershipVersion +1·activity(CANDIDATE_ADDED), 이전 목적의 membershipVersion +1을 기록한다.

`CategoryCandidateGuard.valid`는 목적 변경을 보지 않는다. 목적 변경은 snapshot 재사용·최종 반영 어느 쪽에서도
replacement(새 generation/job)를 만들지 않는다. category stale만 B2 규칙(예산 승계 재예약)을 따른다.
목적 생성·편집은 job을 만들지 않으며 기존 generation의 최대 3회·30분 예산은 그대로다.

## 검증

실제 PostgreSQL/Testcontainers로 다음을 확인한다.

- owner 격리·다른 owner 목적 참조의 DB 차단, 다른 owner 404
- key replay·payload 충돌·replay 판정 순서·실패 재시도·operation namespace, 29개에서 동시 생성 두 건 중 하나만 성공, 60초 10건과 Retry-After
- 입력 경계(code point·문자 규칙·key 밖 값), 빈 목적 생성·상세·목록 유지
- version 충돌·no-op·optional null, 색 편집 후 AI 결과 유지, 이름 편집 후 목적 결과 폐기·기존 연결 유지
- 동일 activityAt tie-break·cursor·projection·limit, 목적 0~1개 모델, 빈 목적의 상품 응답 null 표현
- stale snapshot의 owner·비활성·이름 변경, v1/v2 snapshot 목적 결과 폐기, CONFIRMED/DEFERRED·USER/override 보호, 신규 목적 생성 시 job 0
- T0~T7 입력 형태별 단계 순서·짧은 key·재시도 시 저장 순서의 alias·전송하지 않은 목적 거절·T6/T7 목적 미지정의 판단 없음 처리·단계 로그
- 2,500/80 예약·정산, 기존 budget window ceiling 갱신, legacy V12→V13 upgrade
- B1/B2/Worker 기존 회귀 전체

## 문서와 커밋

- `docs/architecture/server/purpose-management-api.md`: 공개 계약·JSON·오류·헤더 예시
- `docs/architecture/server/purpose-ai-candidates.md`: 후보 공급·snapshot·토큰·stale
- persistence(V13·잠금 규칙)·read API·inventory·implementation order·INDEX 갱신, 구현 이력 기록
- 의미별 한글 로컬 커밋. push·PR·병합은 하지 않는다.
