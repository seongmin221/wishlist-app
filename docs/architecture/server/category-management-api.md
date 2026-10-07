# B2 카테고리 조회·생성·편집 계약과 설계

> 2026-10-07 · 계약 준비 중 · 구현 완료 문서가 아님

## 기준과 범위

develop `005772261fb6f4d5f32e5adb9489b841209dd9b3`의 B1 계약을 유지한다.
CAT-01~04와 owner별 AI custom 후보 공급·최종 반영 재검증을 구현한다.
CAT-05/06 삭제는 B8, Scheduler/browser runtime은 B5, 사용자 재분석 API는 B7이다.
client와 design/handoff는 변경하지 않는다.

제품 근거는 [구매 후보 정리](../../product/organize-candidates.md),
[입력·AI 안전성 결정](../../history/product-planning/mvp/decisions/custom-category-safety.md),
[category lifecycle](../../history/product-planning/mvp/decisions/custom-category-lifecycle.md),
[API 목록](mvp-api-inventory.md), [구현 순서](mvp-api-implementation-order.md)다.

## 구현 전에 사용자 답변이 필요한 정책

아래 값은 제품 문서가 확정하지 않았으므로 제안으로만 남긴다. 의존 구현은 답변 이후 진행한다.

1. 생성 receipt 보존: 계정 데이터가 유지되는 동안 보존을 제안했다. 대안은 30일이다.
2. 진행 중 후보 stale: 사용자 미확정 실행만 취소하고 최신 후보로 새 generation/job/outbox를
   원자 예약하는 방식을 제안했다. 대안은 사용자 재분석 대상으로 종료하는 것이다.
   이미 완료한 상품 전체를 재분석하지 않으며 CONFIRMED/DEFERRED·USER 연결은 보존한다.
3. 생성 rate limit: owner별 1분에 신규 생성 성공 5건, 정상 replay 제외를 제안했다.
   대안은 10건이다. DB 시각과 owner 잠금으로 여러 API instance에서도 같은 한도를 적용한다.

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
  `(created_at,id)` 오름차순으로 둔다. 반환 범위를 줄여도 displayOrder를 다시 매기지 않는다.
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
예시는 최대 5개·각 최대 60 code point다. 제어문자·잘못된 surrogate는 422다.
비표시 U+200B/U+FEFF와 bidi 제어 U+202A~202E/U+2066~2069도 거절한다.
FORMAT 범주 전체를 거절하지 않는다. 정상 ZWJ/ZWNJ 결합 문자·emoji tag sequence는 보존한다.
원문은 보존한다. 중복 비교만 Unicode 공백 trim/collapse와 Locale.ROOT 소문자화를 사용한다.
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
향후 B8 삭제 후에도 receipt를 다른 category 생성에 재사용하지 않는 계약을 유지해야 한다.

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
성공은 200과 최신 상세 표현이며 version은 1 증가한다.
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

## 영속성·잠금

V1~V10은 변경하지 않는다. 후속 migration에 app_users 잠금 기준,
public_category_groups/public_categories registry, custom_categories와 생성 receipt를 추가한다.
app_users는 기존 wishlist owner를 backfill하고 Firebase 정보는 nullable이다.
인증 시 사용할 owner UUID 알고리즘을 바꾸지 않는다.

custom에는 owner, 고정 parent FK, 원문/normalized 이름, description/text[] examples,
version, AI 허용/내부 이유, createdAt/updatedAt/deletedAt을 저장한다.
DB 길이·배열 개수·version 제약과 미삭제 normalized partial UNIQUE를 둔다.
20개 제한과 rate limit은 app_users FOR UPDATE 아래 transaction에서 검사한다.

wishlist_items에 public `category_id`와 별도 `custom_category_id` UUID를 두고
둘 중 최대 하나 CHECK, `(owner_id,custom_category_id)` 복합 FK를 추가한다.
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
category 편집은 후보 입력이 바뀌므로 관련 version을 갱신한다.
prompt injection·URL/코드 중심·유해 텍스트는 저장 거절 대신 내부 AI 제외로 처리한다.
텍스트는 명령이 아닌 구조화된 데이터로 전달한다. 외부 AI 없이 동작하는 local 정책 adapter를
두고 그 한계·판정 규칙을 기록한다. 의미 기반 유해성 판정의 범위는 구현 검토에서 따로 명시한다.

최종 적용 transaction에서 snapshot owner·공급했던 custom들의 owner/활성/AI 자격/version과
선택 ID의 후보 포함 여부를 재검증한다. 선택하지 않은 후보의 입력 변경도 당시 비교 근거를
낡게 만들 수 있으므로 검증한다. snapshot 이후 신규 생성만으로 기존 후보 snapshot을
무효화하거나 완료한 상품을 다시 분석하지 않는다. 유효하지 않은 ID를 item FK에 먼저 써 보지 않는다.
불일치 처리·새 generation 예약은 위 정책 답변에 의존한다.
staging 이후 edit·finish 경합도 재검증하며 실제 AI 비용 정산은 결과 폐기와 독립적이다.
CONFIRMED/DEFERRED·USER category/목적 해제·generation/token/lease/lifecycle fence를 유지한다.
general/browser 공통 repository로 보호하고 browser runtime 조립은 추가하지 않는다.

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
