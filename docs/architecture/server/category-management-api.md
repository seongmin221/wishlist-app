# B2 카테고리 조회·생성·편집 계약과 설계

> 2026-10-07 · 확정 계약 · B2 구현·전체 검증 완료

## 기준과 범위

develop `005772261fb6f4d5f32e5adb9489b841209dd9b3`의 B1 계약을 유지한다.
CAT-01~04와 owner별 AI custom 후보 공급·최종 반영 재검증을 구현한다.
CAT-05/06 삭제는 B8, Scheduler/browser runtime은 B5, 사용자 재분석 API는 B7이다.
client와 design/handoff는 변경하지 않는다.

제품 근거는 [구매 후보 정리](../../product/organize-candidates.md),
[입력·AI 안전성 결정](../../history/product-planning/mvp/decisions/custom-category-safety.md),
[category lifecycle](../../history/product-planning/mvp/decisions/custom-category-lifecycle.md),
[API 목록](mvp-api-inventory.md), [구현 순서](mvp-api-implementation-order.md)다.

## 사용자 확인으로 확정한 정책

2026-10-07 사용자가 세 항목 모두 제안대로 진행하도록 확정했다.

1. 생성 receipt 보존: 계정 데이터가 유지되는 동안 보존한다. 시간에 따른 자동 만료는 없다.
2. 진행 중 후보 stale: 사용자 미확정 실행만 취소하고 최신 후보로 새 generation/job/outbox를
   원자 예약한다.
   이미 완료한 상품 전체를 재분석하지 않으며 CONFIRMED/DEFERRED·USER 연결은 보존한다.
3. 생성 rate limit: owner별 직전 60초에 신규 생성 성공 5건, 정상 replay 제외다.
   DB 시각과 owner 잠금으로 여러 API instance에서도 같은 한도를 적용한다.

## 별도 제품 확인 대상

category 생성 후 상품 초안을 취소하는 경우는 API inventory가 생성 category 유지로 기술하지만
제품 확인 대상으로도 기록한다. B2 생성은 상품 연결과 독립적이며, 취소·자동 삭제 API는 추가하지 않는다.

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
- BROWSE: 활성 상품이 있는 공용 leaf와 모든 미삭제 custom(빈 custom 포함)을 반환한다.
  그 결과 leaf가 있는 상위만 포함한다. 빈 DB는 빈 groups다.
- itemCount: 해당 owner의 `lifecycle_status=ACTIVE`이고 현재 category 참조가 일치하는
  item 수다. 예측 진단·다른 owner·ARCHIVED·DELETED는 세지 않는다.
  group count는 반환 leaf에 속하는 활성 item 합계다.
- customUsedCount는 parent filter와 관계없는 owner 전체 미삭제 custom 수다.
- 공용 group/leaf 순서는 v1 resource 순서다. custom은 공용 leaf 뒤에
  생성 당시 parent의 모든 기존 custom(삭제된 것 포함)의 최대 displayOrder + 1을 저장한다.
  첫 값은 공용 leaf 수다. 생성 순서로 반환하며 필터·향후 B8 삭제로 번호를 다시 매기지 않는다.
- 여러 count와 custom projection은 한 repeatable-read transaction snapshot에서 읽는다.

v1 resource에는 group ID가 없다. 서버 registry로 `G001`~`G011`을 현재 순서와
명시적으로 매핑한다. 이후 taxonomy 순서가 바뀌어도 ID를 재계산하지 않는다.
`C001`~`C087`은 그대로 유지한다. migration seed와 resource·registry의 일치를 테스트한다.

## CAT-03 생성과 replay

`POST /v1/custom-categories`, 필수 정규 UUID `Idempotency-Key`.
body는 필수 `parentId`, `name`, 선택 `description`, `examples`다.
description 생략/null은 null, examples 생략/null은 빈 배열이다.
parent는 공용 상위 registry ID만 허용한다. 공용 leaf/custom UUID는 parent가 될 수 없다.

이름은 비공백이며 최대 40 Unicode code point, 설명은 최대 200,
예시는 최대 5개·각 최대 60 code point다. 배열 내 빈/공백뿐인 예시는 422이며 []는 허용한다.
설명에만 LF와 CRLF를 허용하고 원문을 보존한다. 단독 CR과 이름·예시의 LF/CRLF는 거절한다.
그 밖의 제어문자·잘못된 surrogate는 422다.
비표시 U+200B/U+FEFF와 bidi 제어 U+202A~202E/U+2066~2069도 거절한다.
FORMAT 범주 전체를 거절하지 않는다. 정상 ZWJ/ZWNJ 결합 문자·emoji tag sequence는 보존한다.
원문은 보존한다. 중복 비교만 NFC→Unicode 공백 trim/collapse→Locale.ROOT 소문자화를 사용한다.
대소문자 무시는 이번 사용자 원지시의 동명 회귀 요구를 근거로 한다. 길이는 저장 원문의
Unicode code point 수다(CRLF는 2개). NFC 비교는 NFD 한글의 중복 우회를 막는다.
클라이언트의 grapheme/UTF-16 counter와 다르므로 후속 KMP 입력 검증·counter도 이 단위에
맞춰야 한다. 이번 작업에서 client 코드는 변경하지 않는다.
같은 owner·parent의 normalized 이름은 미삭제 category에 대해 unique다.
다른 parent에서는 같은 이름을 허용한다. normalization은 공용 이름과의 중복 제한을 추가하지 않는다.

owner 잠금 아래 receipt 조회→replay/충돌→신규 요청 제한→20개 한도·이름 검사→
category/receipt 저장→응답 snapshot 읽기→commit한다. 최초 201/Location,
replay 200/Idempotency-Replayed:true다. 결과 DTO는 `category`, `customUsedCount`, `customLimit`이다.
category는 CAT-02와 같은 표현이다. 최초 응답은 commit한 snapshot이고 replay는 현재 category다.

fingerprint는 원문 name·parent와 기본값을 적용한 description/examples의 정규 JSON에
SHA-256을 적용한다. JSON key 순서·생략/null 기본값은 같고, 원문 name의 공백/대소문자나
예시 배열 순서 변경은 다른 payload다. 같은 owner+operation+key의 다른 payload는
409 IDEMPOTENCY_KEY_REUSED다. ITEM-01 key namespace와 독립적이다.
실패 409/429 등은 receipt를 남기지 않으므로 같은 key 재시도는 처음부터 검사한다.
향후 B8 삭제 뒤 정상 payload replay는 409 CATEGORY_NOT_AVAILABLE이며 새 category를 만들지 않는다.
rate limit은 삭제 여부와 관계없이 성공 receipt를 센다. owner 잠금 뒤 얻은 단일
clock_timestamp()를 기준으로 createdAt > asOf - 60초를 센다.
Retry-After = max(1, ceil(가장 오래된 해당 성공 시각 + 60초 - asOf)) 초다.
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

활성 item 표시명은 owner 조건으로 현재 custom 이름을 join한다. category 편집만으로
item version·출처·reviewStatus·연결을 초기화하지 않는다. B1 공통 mapper를 유지하고
category summary에 name·parentId·kind를 추가한다. archive snapshot은 B10 소유다.

## 공통 HTTP·서비스 경계

Firebase owner, canonical UUID, requestId/error envelope, 동기 JDBC의 Dispatchers.IO와
취소 전파는 B1을 따른다. route에서 SQL·상태 전이를 하지 않는다.
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

## 영속성·잠금

V1~V10은 변경하지 않는다. 후속 migration에 app_users 잠금 기준,
public_category_groups/public_categories registry, custom_categories와 생성 receipt를 추가한다.
app_users는 기존 wishlist owner를 backfill하고 Firebase 정보는 nullable이다.
신규 Firebase owner는 공통 잠금 helper가 INSERT ON CONFLICT DO NOTHING 후 FOR UPDATE한다.
CAT-03뿐 아니라 B1 ITEM-01와 Worker 잠금 경로도 이 helper를 사용한다.
인증 시 사용할 owner UUID 알고리즘을 바꾸지 않는다.

custom에는 owner, 고정 parent FK, 원문/normalized 이름, description/text[] examples,
version, AI 허용/내부 이유, createdAt/updatedAt/deletedAt을 저장한다.
DB 길이·배열 개수·version 제약과 미삭제 normalized partial UNIQUE를 둔다.
20개 제한과 rate limit은 app_users FOR UPDATE 아래 transaction에서 검사한다.

wishlist_items에 public `category_id`와 별도 `custom_category_id` UUID를 두고
둘 중 최대 하나 CHECK, `(owner_id,custom_category_id)` 복합 FK를 추가한다.
V8 assignment CHECK를 교체해 두 종류 모두 source AI/USER·missingReason null을 요구한다.
공개 category.id는 공용 C-ID 또는 custom UUID 하나로 합친다.
legacy 공용 참조와 V10 item/job/outbox 의미를 upgrade 테스트로 보존한다.

모든 B2 구조 변경과 Worker 최종 적용은 owner→category→item(ID순)→job 순서다.
owner discovery는 잠금 없는 조회 후 잠금 안에서 관계를 재검증한다.
기존 claim/staging/finish/reconciler 경로도 같은 선행 owner 잠금에 맞춘다.
DB 잠금을 잡은 채 외부 HTTP/AI를 호출하지 않는다. snapshot 공급은 같은 connection으로
읽어 bounded pool에서 잠금 transaction이 두 번째 connection을 기다리지 않게 한다.

## AI 연결

job→item→owner로 공용 taxonomy와 owner의 미삭제·AI 허용 custom만 공급한다.
snapshot에 owner, custom ID→관련 version, 이름/설명/예시의 구조화된 입력을 보관한다.
snapshot schema v2를 추가한다. owner/version이 없는 legacy snapshot은 현재 claim의 owner·job
관계 보호 아래 기존 공용 후보만 재사용한다. legacy에 custom 또는 알 수 없는 ID가 있으면
신뢰하지 않고 현재 후보로 대체 예약한다. migration이 기존 JSON을 무조건 파싱하거나 다시 쓰지는 않는다.
category 편집은 후보 입력이 바뀌므로 관련 version을 갱신한다.
prompt injection·URL/코드 중심·유해 텍스트는 저장 거절 대신 내부 AI 제외로 처리한다.
developer 메시지는 고정 지시문만 담고 user 메시지는 JSON 데이터 문자열이다.
custom_categories는 id/parent_id/name/description/examples 객체 배열이며 JSON encoder로 escape한다.
category_labels는 기존 ID→표시명 JSON object를 유지하고 v2 custom_categories object를 추가한다.
공용 registry만 compact 문자열로 표현하며 사용자 이름을 그 구문에 끼워 넣지 않는다. 외부 AI 없이 동작하는 local 정책 adapter를
두고 그 한계·판정 규칙을 기록한다. 현재 adapter는 명시적 지시 무시·system prompt, URL/HTML/실행 코드, 일부 직접적 위해 지시
패턴을 보수적으로 제외한다. 문맥을 이해하는 의미 기반 moderation은 제공하지 않는다.

snapshot 재사용 시에도 유효성·JSON 구조를 확인한다. malformed/stale snapshot은
유료 호출·예산 예약 없이 최종 반영으로 넘겨 같은 stale 정책을 적용한다.
최종 적용 transaction에서 snapshot owner·공급했던 custom들의 owner/활성/AI 자격/version과
선택 ID의 후보 포함 여부를 재검증한다. 선택하지 않은 후보의 입력 변경도 당시 비교 근거를
낡게 만들 수 있으므로 검증한다. snapshot 이후 신규 생성만으로 기존 후보 snapshot을
무효화하거나 완료한 상품을 다시 분석하지 않는다. 유효하지 않은 ID를 item FK에 먼저 써 보지 않는다.
불일치 시 위 확정 정책에 따라 해당 미확정 실행만 최신 후보로 새 generation을 예약한다.
replacement job은 이전 general/browser attempt 수와 최초 실행 시각을 승계한다.
새 GENERAL 실행을 시작할 기존 3회·30분 예산이 없으면 FAILED_RETRYABLE로 종료한다.
category 편집으로 재시도 예산을 초기화하지 않는다. B5 전체 lane 예산 재설계는 포함하지 않는다.
staging 이후 edit·finish 경합도 재검증하며 실제 AI 비용 정산은 결과 폐기와 독립적이다.
CONFIRMED/DEFERRED의 기존 category/목적 연결·USER category/목적 해제·generation/token/lease/lifecycle fence를 유지한다.
기존 B0 계약대로 목적이 null·UNASSIGNED인 빈 슬롯의 신규 AI 연결은 허용하고 review 값을 유지한다.
general/browser 공통 repository로 보호하고 browser runtime 조립은 추가하지 않는다.

AI 요청은 저장 원문을 바꾸지 않고 2,000-token 비용 상한을 유지한다. custom 정보는
구조화된 데이터로 보내고 토큰 preflight가 초과하면 설명/예시를 제외한 이름 tier,
그다음 이름 12 code point·상품 metadata 160 code point의 최소 tier를 순서대로 검사한다.
전체 tier의 상품 metadata는 2,400, 이름 tier는 800, 최소 tier는 160자다.
custom 예산은 고정된 별도 추가분이 아니라 공용 후보·상품·출력 schema를 포함한
실측 입력 2,000 토큰에서 남은 공간이며 가격표 2,000/80은 변경하지 않는다.
세 tier는 같은 후보 ID 집합을 유지하고 남은 Worker 처리 시간을 공유한다.
최소 tier도 초과하면 그 호출에서 custom을 모두 제외하고 metadata 160자의 공용 taxonomy로 분류한다.
저장된 custom·수동 지정·AI 적합성은 바꾸지 않는다. 모델 응답 ID는 실제 전송한 후보로 검증한다.
공용 최소 입력조차 초과하면 유료 호출 없이 기존 AI_UNUSABLE_RESPONSE/PARTIAL로 종료한다.
외부 token-count 실패는 retry 규칙을 유지한다. 최대 20개 유효 입력과 tier 전환을 fake HTTP로 검증한다.

## 검증 계획

- 전체 테스트 baseline과 변경 후 실행을 구분하고 실제 명령·집계를 이력에 기록한다.
- CAT-01 SELECT 11/87·빈 BROWSE·활성 count·빈 custom·parent filter·owner 격리·안정 순서.
- 19개 상태에서 동시 2건 생성 중 1건 성공, 동시 normalized 중복은 1건 성공.
- 같은 parent 공백/대소문자 중복, 다른 parent 동일 이름 허용, parent 변경 거절.
- 40/200/5×60 경계, Unicode 길이·잘못된 surrogate·제어문자·null/생략/type 오류.
- 상세·편집 owner 404, DB 다른 owner 참조 FK 거절, optimistic version 경합.
- 같은 key 동시 replay·payload 충돌·편집 뒤 replay·owner/operation namespace 분리·한도 이후 replay.
- AI owner별 후보·내부 제외 비노출·snapshot 뒤 edit·staging 뒤 edit·무효 owner/활성/version,
  양 lane의 최신 generation·CONFIRMED/DEFERRED·USER 보호와 외부 호출 중 편집 성공.
- 빈 DB 및 V10 upgrade, migration checksum, B1/Worker 전체 회귀.
