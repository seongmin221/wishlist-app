# B0 서버 상태·DB·Worker 기반 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 실행은 현재 세션에서 순차 진행하며 각 Task를 검증하고 다음으로 넘어간다.

**Goal:** B1 상품 API를 시작하기 전에 상태·owner 조회·DB 확장·Worker 실행 보호·IO 및 자원 종료 기반을 실제 PostgreSQL로 검증한다.

**Architecture:** 기존 Ktor/JDBC 코드에서 공통 상태 정책과 owner 조회를 분리한다. 일반/browser 처리의 모든 임시·최종 쓰기는 DB에서 발급한 `AnalysisClaim`을 전달받아 같은 보호 조건과 item→job 잠금 순서를 적용한다. 원격 호출 동안 DB transaction을 열어 두지 않는다.

**Tech Stack:** 기존 Kotlin 2.3.21/JDK 17, Ktor 3.6.0, PostgreSQL 16 local fixture, Flyway 11.20.0, Testcontainers 1.21.3. 연결 pool은 HikariCP 7.0.2를 추가한다.

**Spec:** [승인한 전체 구현 순서의 B0](../../architecture/server/mvp-api-implementation-order.md), [상품 상태·API 계약](../../architecture/wishlist-item-state-api.md), [제품 API·데이터 설계](../../architecture/server/mvp-product-api-design.md).

> 상태: **실행 전 계획** · 2026-10-04. 체크박스는 실제 구현·검증 이후에만 체크한다. 제품 API 추가와 B1의 생성 응답/상세 mapper 완성은 다음 묶음이다.

## Global Constraints

- 기준 서버 HEAD는 `9eacba51420e07de150b9427b8d2ed39be184939`. 다음 migration 번호는 현재 V8/V9이며 실행 시 충돌을 다시 확인한다. V1~V7 내용·checksum을 바꾸지 않는다.
- 승인한 제품 기준 `design/handoff@51c67e0` 이후 현재 `d23c857`까지 차이는 디자인 색·표시 규칙이며 B0 제품 상태 정책 변화는 없다. 문서 통합은 기능 관련 diff만 반영하고 디자인 작업 파일을 가져오지 않는다.
- Firebase owner UUID 생성 규칙, 기존 UUID·생성 key·sourceUrl·createdAt·기존 version을 migration에서 보존한다. 새 데이터·새 job 생성은 같은 transaction이다.
- `PROCESSING`에는 삭제만 변경 가능하다. 직접 보완은 ACTIVE의 PARTIAL/FAILED_RETRYABLE/FAILED_TERMINAL, 재분석은 ACTIVE의 FAILED_RETRYABLE이고 manualCompletionAt 없는 경우다. 완료 보완의 원래 analysisStatus는 유지한다.
- 삭제에는 expectedVersion이 없다. 편집·보완·검토는 expectedVersion을 요구한다. B0는 이 정책을 정의·검증하며 신규 변경 route는 B7에서 구현한다.
- 사용자 값·목적 해제·manual completion을 이전 분석이 덮어쓰지 않는다. currentGeneration, executionToken, lease, stage, owner, lifecycle, claimedItemVersion을 함께 확인한다.
- 내부 Worker payload는 기존 jobId/generation을 유지한다. executionToken은 DB claim에서 만들며 외부 요청이 지정하지 못한다.
- Worker timeout 90초·Task deadline 105초, 기존 복구 판단 120초를 기준으로 **claim lease 120초**를 사용한다. 임의 heartbeat/lease 연장은 추가하지 않는다. 전체 retry 3회·30분 합산 및 timeout 재구성은 B5이며 B0에서 조용히 정책을 바꾸지 않는다.
- local은 Docker PostgreSQL, 인증/queue/AI fake를 사용한다. 외부 AI opt-in pilot과 production 배포는 실행하지 않는다. runtime 시작 시 Flyway를 실행하지 않는다.
- 모든 Task는 실패 확인→최소 구현→대상/영향 테스트 통과→문서 갱신→해당 변경만 커밋 순서다. 기존 미커밋 문서 변경을 임의로 섞거나 되돌리지 않는다.

## Review Focus

1. 같은 generation에서 lease 만료 후 새 실행이 claim한 경우, 옛 실행의 성공뿐 아니라 pending metadata·candidate snapshot·실패 쓰기와 retry도 무효다. Task 4~7.
2. 원격 AI를 호출한 뒤 claim이 무효가 되어도 발생한 비용은 정산한다. 결과를 폐기한다는 이유로 비용 reservation을 취소하지 않는다. Task 5.
3. V7의 중단된 RUNNING job과 기존 item을 upgrade한 뒤 구 token 없는 실행은 쓸 수 없고 새 복구 실행은 진행 가능하다. Task 3·7.
4. item 변경과 Worker 완료·reconciler가 동시에 잠금을 잡아도 교착하거나 일부 데이터만 반영하지 않는다. Task 4·6·7.
5. DTO/error·IO/pool 교체가 생성 201/replay 200/key conflict 409·인증·health를 바꾸거나 취소를 삼키지 않는다. Task 2·8·9.

## 착수 전 확인과 현재 증거

- 조사 시 `docker info --format '{{.ServerVersion}}'`는 `/Users/user/.colima/default/docker.sock`의 Docker daemon 연결 실패였다. 계획 작성에서 엔진을 시작하거나 DB 테스트를 실행하지 않았다.
- Task 3 이전에 `docker info`와 JDK 17을 확인한다. daemon이 없으면 사용 중인 local Docker/Colima를 실행하고 동일 명령으로 준비를 확인한다. 실제 DB 실행 실패는 RED 단계의 기능 실패로 취급하지 않는다.
- 현재 `DatabaseMigrationTest`는 migration 성공 건수를 7로 고정한다. 현재 processor/classifier는 `(jobId)`만 받고 여러 곳이 token 없이 UPDATE한다. Main의 DataSource는 PGSimpleDataSource이고 생성/Worker 경로는 동기 IO다.
- 전체 suite의 `RealUrlPilotTest`는 opt-in이다. 테스트 명령에는 `RUN_REAL_URL_PILOT=0`을 명시한다. secret 환경변수 내용을 출력하지 않는다.

## 파일 책임과 작업 의존성

아래 `app/`는 `server/src/main/kotlin/app/`, test의 `app/`는 `server/src/test/kotlin/app/` 기준이다. Files에 표시된 새 파일은 이번 계획의 생성 대상이며 현재 존재한다고 가정하지 않는다.

| Task | 담당 경계 | 산출물 | 선행 |
| --- | --- | --- | --- |
| 1 | 상태·기능 문서 | 상태표와 순수 정책 | 없음 |
| 2 | 공개 오류·DTO | 공통 serialization·오류 계약 | 1 |
| 3 | migration·owner 조회 | 상태/current generation/claim schema·upgrade | 1 |
| 4 | claim/잠금 | 원자 claim·쓰기 보호 repository | 3 |
| 5 | extraction/render/classification | 모든 중간 쓰기에 claim 전달 | 4 |
| 6 | 일반/browser Worker | 모든 결과·실패·fallback 반영 보호 | 5 |
| 7 | reconciler | lease 취소·복구와 outbox 원자성 | 6 |
| 8 | runtime IO·pool | bounded connection·재사용·종료 | 2, 6, 7 |
| 9 | 전체 회귀·문서 | B0 통과 증거와 B1 인계 | 1~8 |

실행은 Task 1→9 순서다. Task 4~7 동안 함수 시그니처 전환은 함께 영향 범위를 갱신하되 token 없는 호환 overload를 남기지 않는다.

### Task 1: 상태 정책과 홈 영역의 대응을 고정한다

**Files:**
- Create: `server/src/main/kotlin/app/wishlist/WishlistItemState.kt`, `WishlistItemPolicy.kt`
- Test: `server/src/test/kotlin/app/wishlist/WishlistItemPolicyTest.kt`
- Modify: `docs/architecture/wishlist-item-state-api.md`, `docs/architecture/server/mvp-product-api-design.md`
- Modify: `docs/product/inspect-and-edit-a-product.md`, `docs/product/references/item-states.md` — 최신 디자인 브랜치의 해당 기능 변경만 통합

**Interfaces:**
- `AnalysisStatus`, `ReviewStatus`, `LifecycleStatus`, `CategoryMissingReason` enum은 기존 계약 값을 그대로 갖는다.
- `ValueSource = AI | USER | UNASSIGNED`; category/name/image의 없는 출처는 null, 목적 미지정 기본 출처는 UNASSIGNED, 사용자가 목적을 해제한 출처는 USER다.
- `WishlistItemState(analysisStatus, reviewStatus, lifecycleStatus, productName: String?, categoryId: String?, categoryMissingReason: CategoryMissingReason?, manualCompletionAt: Instant?)`.
- `ItemAction = EDIT | DELETE | REVIEW | MANUAL_COMPLETE | REANALYZE`.
- `ItemPolicy(requiredAction: RequiredAction, homeActionGroup: HomeActionGroup?, allowedActions: Set<ItemAction>)`.
- `WishlistItemPolicy.evaluate(state: WishlistItemState): ItemPolicy`는 DB/HTTP 없이 계산한다.

**정책:** 기존 requiredAction의 6개 값을 유지하고 홈 표시 projection을 분리한다. INFORMATION_COMPLETION/CATEGORY_ASSIGNMENT/CATEGORY_REASSIGNMENT는 홈 INFORMATION_COMPLETION 영역, CLASSIFICATION_REVIEW는 동일 이름 영역, PROCESSING은 ANALYSIS_IN_PROGRESS 영역에 연결한다. archived/deleted는 requiredAction NONE·homeActionGroup null·allowedActions empty다. homeActionGroup은 이 단계에서 기존 공개 JSON에 추가하지 않는다.

ACTIVE에서는 PROCESSING → 누락 이름 → category 누락 원인 → review PENDING → NONE 우선순위를 유지한다. category 없는 PARTIAL/실패에서 reason이 null인 legacy 값은 EXTRACTION_UNRESOLVED로 해석한다. 분석 실패 자체는 failureCode로 표현하며 이미 name/category가 있는 경우에도 수동 완료 가능 여부는 analysisStatus로 별도 계산한다.

allowedActions: ACTIVE 비PROCESSING은 EDIT/DELETE, 수동 미완료 PARTIAL/실패는 MANUAL_COMPLETE, 수동 미완료 FAILED_RETRYABLE은 REANALYZE를 추가한다. REVIEW는 READY이고 name/category가 있고 review PENDING일 때만 허용한다. manualCompletionAt이 있으면 REVIEW/MANUAL_COMPLETE/REANALYZE를 제외한다. READY의 삭제 category 재지정은 EDIT로 수행한다.

- [x] **Step 1: 실패할 상태표 테스트 작성.** 아래 행을 parameterized 또는 table-driven 단위 테스트로 고정한다.

| fixture | requiredAction·home 영역 | 핵심 assertion |
| --- | --- | --- |
| ACTIVE PROCESSING | ANALYSIS_IN_PROGRESS·같은 영역 | actions == {DELETE} |
| READY, name 있음, category 삭제 | CATEGORY_REASSIGNMENT·INFORMATION_COMPLETION | EDIT 가능·MANUAL_COMPLETE 불가 |
| PARTIAL, AI_ABSTAINED | CATEGORY_ASSIGNMENT·INFORMATION_COMPLETION | MANUAL_COMPLETE 가능·REANALYZE 불가 |
| FAILED_RETRYABLE, name/category 없음 | INFORMATION_COMPLETION·같은 영역 | MANUAL_COMPLETE·REANALYZE 가능 |
| FAILED_TERMINAL | 누락 정보 기준 | MANUAL_COMPLETE 가능·REANALYZE 불가 |
| PARTIAL 수동 완료, name/category 있음, CONFIRMED | NONE·null | EDIT/DELETE만 가능, 원래 PARTIAL 유지 |
| READY PENDING, name/category 있음 | CLASSIFICATION_REVIEW·같은 영역 | REVIEW 가능 |
| READY DEFERRED, name/category 있음 | NONE·null | REVIEW 불가 |
| ARCHIVED/DELETED 각각 모든 analysisStatus | NONE·null | allowedActions.isEmpty() |

- [x] **Step 2: RED 확인.** `./gradlew test --tests app.wishlist.WishlistItemPolicyTest` — 정책 타입 미구현으로 compile 실패여야 한다.
- [x] **Step 3: 위 순수 타입/정책 구현 및 최신 제품 문서 차이 통합.** 상태 JSON key를 제거/rename하지 않고 홈 그룹·manual 상태의 차이를 계약에 명시한다. 목적 활동순·이미지 제한 등 다른 묶음 결정을 섞지 않는다.
- [x] **Step 4: GREEN 확인.** 같은 명령이 PASS이고 상태별 assertion이 모두 실행돼야 한다.
- [x] **Step 5: 해당 파일만 stage하고 커밋.** `feature(server): 상품 상태와 허용 행동 기반 정의` / 본문 `홈 조치 영역과 상태별 사용자 행동을 공통 정책으로 정리한다.`

### Task 2: 공통 DTO와 안전한 공개 오류를 준비한다

**Files:**
- Create: `server/src/main/kotlin/app/http/ApiError.kt`, `WishlistItemDtos.kt`, `ApiHttpSupport.kt`, `DecimalJsonSerializer.kt`
- Modify: `server/src/main/kotlin/app/http/WishlistRoutes.kt`, `server/src/main/kotlin/app/Main.kt`, `server/build.gradle.kts`
- Test: `server/src/test/kotlin/app/http/ApiErrorTest.kt`, 기존 `WishlistRoutesTest.kt`, `FirebaseOwnerResolverTest.kt`

**Interfaces:**
- `@Serializable ApiErrorEnvelope(error: ApiError)`와 `ApiError(code: String, requestId: String, details: Map<String,JsonElement> = emptyMap())`.
- `suspend fun ApplicationCall.respondApiError(status: HttpStatusCode, code: String, details: Map<String,JsonElement> = emptyMap())`.
- `fun Application.installApiHttpSupport()`는 request UUID 발급·X-Request-ID 응답과 미처리 오류 500 INTERNAL_ERROR 변환을 등록한다. 임의 입력 request ID/exception message를 공개 응답에 복사하지 않는다.
- `WishlistItemDto`와 `ProductDto/CategoryDto/PurposeDto/AnalysisDto`의 기존 key, nullable 의미·상태 enum을 선언하고 encodeDefaults로 필수 null/default 필드가 사라지지 않게 한다. UUID와 Instant는 wire DTO에서 String이다. 실제 entity→DTO mapper와 상세 route 연결은 B1이다. 가격은 nullable BigDecimal과 숫자 serializer로 정밀도를 보존한다. 오류 details의 JSON 숫자도 문자열로 바꾸지 않는다.
- 취소 검증은 test engine의 기본 진단 응답에 예외가 전달되고 API `INTERNAL_ERROR` envelope로 변환되지 않는지 확인한다. Ktor test engine이 취소를 HTTP 500으로 표현하므로 client 예외 assertion으로 판단하지 않는다.

- [x] **Step 1: 실패할 오류·직렬화 테스트 작성.** missing auth→401 UNAUTHORIZED, malformed key→400 INVALID_IDEMPOTENCY_KEY, 잘못된/누락 URL→기존 422 INVALID_URL, key 재사용→409 IDEMPOTENCY_KEY_REUSED를 유지하며 모든 오류의 requestId가 UUID이고 header와 일치해야 한다. RuntimeException의 비밀 문자열은 500 body에 없어야 하고 CancellationException은 500으로 삼키지 않아야 한다. DTO의 explicit null·enum 값과 기존 key를 roundtrip한다.
- [x] **Step 2: RED 확인.** `./gradlew test --tests app.http.ApiErrorTest --tests app.http.WishlistRoutesTest --tests app.http.FirebaseOwnerResolverTest` — 새 오류 envelope/타입 부재로 실패. DB 준비 실패와 구별한다.
- [x] **Step 3: helper·DTO 구현.** Ktor StatusPages dependency는 기존 ktorVersion을 사용한다. 기존 생성 성공 JSON을 여기서 가짜 mapper로 치환하지 않는다. 오류를 공통 helper로 변경하고 testApplication도 support를 설치하게 수정한다. FirebaseOwnerResolver의 owner 생성 규칙은 유지한다.
- [x] **Step 4: GREEN 확인.** 같은 명령 PASS, 기존 생성/replay 성공·Location/header assertions 유지.
- [x] **Step 5: 커밋.** `feature(server): 공통 API 오류와 응답 타입 준비` / `기존 오류 코드를 유지하며 요청 식별자와 안전한 응답 기반을 추가한다.`

### Task 3: 상태·generation·claim schema와 owner 조회를 확장한다

**Files:**
- Create: `server/src/main/resources/db/migration/V8__wishlist_item_state_foundation.sql`, `V9__analysis_execution_claim.sql`
- Create: `server/src/main/kotlin/app/wishlist/WishlistItemStateRepository.kt`
- Modify: `server/src/main/kotlin/app/wishlist/CreateWishlistItemService.kt`
- Test: `server/src/test/kotlin/app/DatabaseMigrationTest.kt`, `app/wishlist/WishlistItemStateRepositoryTest.kt`, `CreateWishlistItemServiceTest.kt`

**Interfaces:**
- `StoredWishlistItemState(id: UUID, ownerId: UUID, version: Int, currentGeneration: Int, state: WishlistItemState, categorySource: ValueSource?, purposeId: String?, purposeSource: ValueSource, nameSource: ValueSource?, imageSource: ValueSource?, userOverrideFields: Set<String>)`.
- `WishlistItemStateRepository.findOwned(ownerId: UUID, itemId: UUID): StoredWishlistItemState?`의 SQL에는 owner_id와 id 조건이 함께 있어야 한다. archived/deleted 표현을 내부에서 읽을 수 있으나 공개 GET 정책은 B1이다.

**V8 item 필드:** `review_status varchar(16) not null default 'NOT_REQUIRED'`, `manual_completion_at timestamptz`, `category_id varchar(128)`, `category_source varchar(16)`, `category_missing_reason varchar(32)`, `purpose_id varchar(128)`, `purpose_source varchar(16) not null default 'UNASSIGNED'`, `name_source/image_source varchar(16)`, `user_override_fields text[] not null default '{}'`, `current_generation integer not null default 1`.

검증: enum allow-list, version/current_generation >0, category가 있으면 missingReason null·source AI/USER, purpose source AI이면 purpose ID 필요. 목적 USER+null은 명시적 해제다. override 필드는 NAME/BRAND/IMAGE/CATEGORY/PURPOSE만 허용한다. category/purpose/media 테이블·owner FK는 B2/B3/B6에서 추가하며 없는 테이블을 미리 만들지 않는다.

**backfill:** currentGeneration은 기존 job generation의 max 또는 job 없으면 1. READY의 predicted category는 current category+AI로 옮기고 name/category가 있으면 review PENDING, 나머지는 NOT_REQUIRED. name/image가 있으면 출처 AI. 기존 predicted purpose는 진단 보관하고 실제 목적 자원이 없으므로 current purpose로 자동 승격하지 않는다. category 없는 경우 AI_ABSTAINED→같은 reason, AI_UNUSABLE_RESPONSE/AI_INVALID_CANDIDATE/AI_USAGE_OUT_OF_RANGE→AI_RESPONSE_UNUSABLE, 기타→EXTRACTION_UNRESOLVED. 기존 analysisStatus·lifecycle·version·timestamps·진단 값을 그대로 유지한다.

**V9 job 필드:** `execution_token uuid`, `lease_until timestamptz`, `claimed_item_version integer`. running stage의 legacy row는 token/claimed version null, lease는 migration 시각으로 만료 처리한다. 이를 기존 실행으로 이어받지 않고 Task 7에서 복구한다. `(stage, lease_until, id)` 복구 index를 추가하고 기존 unique(item,generation)를 유지한다.

- [x] **Step 1: 실패할 empty/upgrade·owner 테스트 작성.** Flyway target 7로 생성한 fixture에 READY/PARTIAL/실패·여러 generation·RUNNING·job 없는 item·DELETED를 넣은 뒤 최신 migrate한다. 필드 backfill 값과 원본 UUID/owner/key/version/timestamps·metadata·예산 rows 보존을 assert한다. owner A 조회 성공, owner B는 null, invalid enum/금지 override SQL은 constraint 실패여야 한다.
- [x] **Step 2: RED 확인.** `./gradlew test --tests app.DatabaseMigrationTest --tests app.wishlist.WishlistItemStateRepositoryTest --tests app.wishlist.CreateWishlistItemServiceTest` — 신규 column/repository 부재로 실패.
- [x] **Step 3: migration·repository 구현.** 기존 7건 고정 검사를 V1~V9의 실제 version과 필요한 constraint/backfill assertions로 변경한다. 생성은 currentGeneration=1과 job generation=1을 원자 저장한다.
- [x] **Step 4: GREEN 확인.** 같은 명령 PASS, Flyway validate 재실행도 PASS. schema 적용 실패를 테스트 skip으로 숨기지 않는다.
- [x] **Step 5: 커밋.** `feature(server): 상품 상태와 분석 실행 스키마 확장` / `기존 데이터를 보존하며 상태와 generation 및 실행 claim 필드를 추가한다.`

### Task 4: 원자 claim과 공통 쓰기 보호를 만든다

**Files:**
- Create: `server/src/main/kotlin/app/analysis/AnalysisClaim.kt`, `AnalysisClaimRepository.kt`, `AnalysisWriteGuard.kt`
- Test: `server/src/test/kotlin/app/analysis/AnalysisClaimRepositoryTest.kt`

**Interfaces:**
- `AnalysisLane = GENERAL | BROWSER`.
- `AnalysisClaim(jobId: UUID, itemId: UUID, ownerId: UUID, generation: Int, executionToken: UUID, lane: AnalysisLane, expectedItemVersion: Int, leaseUntil: Instant)`.
- `ClaimResult = Claimed(claim: AnalysisClaim) | Ignored | Exhausted`.
- `AnalysisClaimRepository.claim(jobId: UUID, generation: Int, lane: AnalysisLane): ClaimResult`.
- `AnalysisWriteGuard.lockCurrent(connection: Connection, claim: AnalysisClaim): Boolean`는 호출자가 연 transaction에서 item→job를 잠그고 검증한다. connection commit/rollback은 호출자 책임이다.

claim transaction: job의 item ID를 먼저 찾은 뒤 item→job `FOR UPDATE`로 재검증한다. owner는 DB에서 얻는다. currentGeneration==요청 generation, ACTIVE, PROCESSING, manualCompletionAt null, 해당 lane PENDING 상태만 claim한다. token은 새 UUID, lease는 DB `clock_timestamp()+interval '120 seconds'`, claimed_item_version은 현재 item version이다. lane별 기존 attempt/time 한도 판단을 유지하고 소진 시 같은 잠금 아래 현 generation item만 FAILED_RETRYABLE로 반영한다.

쓰기 guard는 owner·item ID·generation·token·lane RUNNING·lease>DB clock_timestamp()·ACTIVE·PROCESSING·manual null·현재 version==claimed version을 모두 검증한다. 유효하지 않은 claim은 false이며 임의로 현재 실행 token을 취소하지 않는다. 외부 호출 중 lock/connection을 잡지 않는다.

claim 회전 시 이전 assignment/failure 임시 필드는 지운다. general 재claim은 임시 metadata도 지우고 browser 진입은 general에서 얻은 metadata를 보존한다. generation의 candidate snapshot은 유지하되 token 없는 실행이 수정하지 못하게 한다.

- [x] **Step 1: 실패할 claim 경합 테스트 작성.** 두 connection·CountDownLatch로 동시에 claim해 Claimed 정확히 1건, token/lease/claimed version 저장, 다른 generation/DELETED/ARCHIVED/manual 완료는 Ignored를 assert한다. lease를 SQL로 만료시켜 옛 claim guard=false, 현재 item version 증가 시 false를 확인한다. 서로 다른 job 두 개를 같은 item에 만들고 currentGeneration과 맞는 하나만 claim되는지 확인한다. 테스트의 시간 대기는 sleep 대신 SQL lease 변경·latch를 사용한다.
- [x] **Step 2: RED 확인.** `./gradlew test --tests app.analysis.AnalysisClaimRepositoryTest` — 새 repository 부재로 실패.
- [x] **Step 3: 위 인터페이스 구현.** DB 시각을 lease 판단의 기준으로 사용한다. 모든 잠금 순서는 item→job이며 job만 잠근 뒤 item을 잠그는 helper를 만들지 않는다.
- [x] **Step 4: GREEN 확인.** 같은 명령 PASS, 중복 claim은 attempt_count를 두 번 올리지 않는다.
- [x] **Step 5: 커밋.** `feature(server): 분석 작업 claim과 쓰기 보호 추가` / `generation과 실행 token 및 lease를 transaction에서 검증한다.`

### Task 5: processor·AI의 모든 중간 쓰기에 claim을 전달한다

**Files:**
- Create: `server/src/main/kotlin/app/analysis/AnalysisPendingResultRepository.kt`
- Modify: `app/extraction/GeneralExtractionProcessor.kt`, `app/browser/BrowserRenderProcessor.kt`, `app/ai/AiClassificationService.kt`, `app/budget/LlmBudgetService.kt`, `app/analysis/GeneralWorkerService.kt`, `app/browser/BrowserWorkerService.kt`, `app/Main.kt`
- Test/modify callers: `app/extraction/GeneralExtractionProcessorTest.kt`, `app/browser/BrowserRenderProcessorTest.kt`, `app/ai/AiClassificationServiceTest.kt`, `app/budget/LlmBudgetServiceTest.kt`, `app/http/LocalClassificationPathTest.kt`, `app/http/RealUrlPilotTest.kt` 및 worker 테스트

**Interfaces:**
- `GeneralExtractionProcessor.process(claim: AnalysisClaim): ProcessingOutcome`.
- `BrowserRenderProcessor.render(claim: AnalysisClaim): Metadata?`.
- `AiClassificationService.classify(claim: AnalysisClaim, metadata: Metadata): ProcessingOutcome`.
- `ProcessingOutcome.Stale`를 추가하고 일반/browser의 when과 호출자를 함께 갱신한다.
- `AnalysisPendingResultRepository.saveMetadata(claim: AnalysisClaim, metadata: Metadata): Boolean`, `saveAssignment(claim: AnalysisClaim, result: ClassificationResult.Assigned): Boolean`, `saveFailure(claim: AnalysisClaim, code: String): Boolean`은 guard 통과 뒤만 UPDATE하며 false면 Stale이다.
- `AnalysisPendingResultRepository.candidateSnapshot(claim: AnalysisClaim, supply: (UUID) -> CandidateSnapshot): CandidateSnapshot?`은 guard transaction 안에서 기존 snapshot 읽기/최초 저장을 원자 처리한다. supply는 DB/local 후보 조회만 하고 원격 호출하지 않는다.
- `LlmBudgetService.reserveBeforeCall(claim: AnalysisClaim, requestId: UUID): ReserveResult`와 `ReserveResult.Stale` 추가. 실제 runtime 경로에는 기존 token 없는 overload를 남기지 않는다. 예산 테스트도 유효 claim을 사용한다.

- [x] **Step 1: 실패할 stale 중간 쓰기 테스트 작성.** extract/gateway 진행 중 latch로 멈추고 claim token 회전 또는 item generation/version/manual 상태를 변경한다. resume 뒤 pending metadata/assignment/failure/candidate snapshot 불변, outcome Stale를 assert한다. 만료 claim의 새 예산 reserve도 Stale이고 LLM gateway 호출 0건이어야 한다.
- [x] **Step 2: RED 확인.** `./gradlew test --tests app.extraction.GeneralExtractionProcessorTest --tests app.browser.BrowserRenderProcessorTest --tests app.ai.AiClassificationServiceTest --tests app.budget.LlmBudgetServiceTest` — token 보호 없이 stale UPDATE 또는 reserve가 수행돼 실패.
- [x] **Step 3: 인터페이스와 모든 호출자 변경.** guard로 source URL 읽기/중간 쓰기를 보호한다. budget reserve의 잠금 순서는 item→job→예산 window(기존 고정 순서)이고 정산은 item/job를 뒤늦게 잠그지 않는다. gateway 호출 전 claim을 다시 확인하며 검사 후 무효화와의 좁은 race는 결과 쓰기에서 차단한다.
- [x] **Step 4: 비용 정산 검증.** gateway가 유효 usage를 반환한 뒤 claim이 stale여도 reservation SETTLED·실제 비용 유지, 상품/임시 결과는 불변이어야 한다. usage 누락 시 기존 maximum settlement/lease reconciliation 정책을 유지한다.
- [x] **Step 5: GREEN 및 caller 검증.** 위 명령과 `./gradlew compileKotlin compileTestKotlin` PASS. `rg 'process\(|render\(|classify\(|reserveBeforeCall\(' src/main/kotlin src/test/kotlin`로 jobId-only runtime call이 남지 않음을 확인한다.
- [x] **Step 6: 커밋.** `feature(server): 분석 중간 결과에도 실행 claim 적용` / `추출과 AI 및 예산 경로의 오래된 실행 쓰기를 차단한다.`

### Task 6: 일반·browser의 최종 결과를 같은 보호 조건으로 반영한다

**Files:**
- Create: `server/src/main/kotlin/app/analysis/AnalysisResultRepository.kt`
- Modify: `app/analysis/GeneralWorkerService.kt`, `app/browser/BrowserWorkerService.kt`, `app/http/WorkerRoutes.kt`, `app/Main.kt`
- Test: 기존 `app/analysis/GeneralWorkerServiceTest.kt`, `app/browser/BrowserWorkerServiceTest.kt`, `app/http/WorkerRoutesTest.kt`

**Interfaces:**
- `AnalysisResultRepository.finish(claim: AnalysisClaim, outcome: ProcessingOutcome): WorkerDisposition`.
- worker HTTP용 `runGeneral(jobId: UUID, generation: Int)`/`runBrowser(jobId: UUID, generation: Int)`는 유지한다. 내부 callback은 claim을 전달하며 fake도 동일 타입을 사용한다.

finish는 guard와 job/item 변경을 같은 transaction에서 실행한다. stale는 ACK, 현재 실행의 retry만 RETRY이며 token을 지우고 PENDING으로 바꾼다. 성공/부분/terminal/한도 실패/NeedsBrowser에 각각 token을 무효화한다. 정상 최종 반영은 item version을 **한 번만** 증가시키고 current category·값 출처·review·missingReason과 predicted 진단을 일관되게 저장한다. READY에는 usable category가 필요하다. AI 반영의 모든 결과에서 USER 출처 또는 userOverrideFields로 보호한 NAME/IMAGE/CATEGORY/PURPOSE 값을 유지하고, 진단용 predicted 값과 current 사용자 값을 구분한다. 목적 USER+null도 보호한다. 현재 category가 있으면 missingReason은 null이다. CONFIRMED/DEFERRED를 자동 PENDING으로 되돌리지 않으며, 실제 미확정 AI 연결에만 PENDING을 부여한다. 실패·부분은 기존 metadata를 보존한다.

NeedsBrowser는 stage/flag/token 해제+browser outbox가 한 transaction이다. 이미 취소된 실행은 fallback outbox를 만들지 않는다. browser가 받은 metadata는 Task 5의 pending repository에 저장하고 최종 item 반영은 finish 하나로 모은다. infrastructure exception과 CancellationException 처리는 기존 retry 의미를 보존하고 취소를 삼키지 않는다.

- [x] **Step 1: 실패할 결과별 race 테스트 작성.** 일반/browser 각각 Complete/Partial/Terminal/Retryable 및 일반 NeedsBrowser를 parameterize한다. 처리 중 generation 증가·ARCHIVED·DELETED·manual completion·item version 변경·lease 만료·token 회전 뒤 item과 현 job/outbox가 불변이며 ACK인지 assert한다. 정상 처리 각 결과의 item version은 +1, retry/fallback에는 item version 유지다. claim 전 이미 NAME/CATEGORY/PURPOSE를 USER로 지정하고 CONFIRMED/DEFERRED인 fixture에서도 USER 값·명시적 목적 해제·검토 상태가 유지돼야 한다.
- [x] **Step 2: RED 확인.** `./gradlew test --tests app.analysis.GeneralWorkerServiceTest --tests app.browser.BrowserWorkerServiceTest --tests app.http.WorkerRoutesTest` — 기존 unguarded final/retry 쓰기가 stale 상태를 변경해 실패.
- [x] **Step 3: finish와 worker 조립 구현.** 만료 한도 처리·예외→retry도 같은 guard를 거친다. current 실행의 fault만 RETRY/503, stale와 완료는 ACK/204로 처리한다. claim 결과 Ignored/Exhausted는 추가 processor 호출 없이 ACK다.
- [x] **Step 4: GREEN 확인.** 같은 명령 PASS. item 수정과 finish를 두 connection/latch로 경합시켜 deadlock 없이 먼저 commit된 변경 기준으로 한쪽만 유효함을 확인한다.
- [x] **Step 5: 커밋.** `feature(server): Worker 최종 반영과 실패 경로 보호` / `일반 및 browser 결과와 fallback을 동일한 실행 검증으로 반영한다.`

### Task 7: reconciler가 옛 실행을 확실히 폐기하고 복구한다

**Files:**
- Modify: `server/src/main/kotlin/app/analysis/AnalysisJobReconciler.kt`
- Create/test: `server/src/test/kotlin/app/analysis/AnalysisJobReconcilerTest.kt`
- Modify tests: `app/browser/BrowserWorkerServiceTest.kt`, `app/analysis/GeneralWorkerServiceTest.kt` — updated_at 대신 lease 만료 fixture

**Interfaces:** 기존 `AnalysisJobReconciler.reconcileExpired(): Int` 유지. 결과는 실제 복구/취소/한도 실패로 전이한 job 수다.

- [x] **Step 1: 실패할 recovery 테스트 작성.** 만료 lease의 RUNNING claim을 복구 후 다시 claim하면 새 token이어야 한다. 옛 token의 Task 5/6 쓰기는 모두 무효, 새 실행은 정상 반영해야 한다. ACTIVE 아닌 item·currentGeneration/version/manual이 다른 job은 CANCELLED·token 해제, item 실패 반영/outbox 생성 없음. V7 legacy RUNNING의 null token도 재처리 가능해야 한다.
- [x] **Step 2: RED 확인.** `./gradlew test --tests app.analysis.AnalysisJobReconcilerTest --tests app.browser.BrowserWorkerServiceTest` — updated_at 기반·item generation 무검증 복구 때문에 실패.
- [x] **Step 3: recovery 구현.** lease<=DB clock_timestamp()인 RUNNING 후보를 읽고 item→job 순서로 lock/recheck한다. SKIP LOCKED는 이 순서를 깨지 않게 사용한다. 만료 token을 비운 뒤 lane PENDING+새 recovery outbox 또는 한도 소진 FAILED를 같은 transaction으로 저장한다. 현재 실행·lease가 바뀌었으면 건너뛴다. job 먼저 잠그고 item으로 진행하는 기존 query는 제거한다.
- [x] **Step 4: GREEN 확인.** 같은 명령 PASS. reconciler 두 개의 동시 실행과 정상 finish 경합에서도 recovery outbox 1건·고유 task_name, 중복 item version 증가 없음, 기존 예산 reservation 정산 불변을 검증한다. 복구된 job의 attempt_count는 새 claim에서만 증가한다.
- [x] **Step 5: 커밋.** `feature(server): lease 기반 분석 작업 복구 정리` / `만료 실행을 폐기하고 현재 generation만 원자적으로 재예약한다.`

### Task 8: IO 경계와 제한된 pool·자원 종료를 연결한다

**Files:**
- Create: `server/src/main/kotlin/app/DatabaseFactory.kt`, `RuntimeResources.kt`
- Modify: `app/Main.kt`, `app/RuntimeConfig.kt`, `app/http/WishlistRoutes.kt`, `app/http/WorkerRoutes.kt`, `app/extraction/SafeHttpTransport.kt`, `server/build.gradle.kts`, `server/.env.example`
- Test: `server/src/test/kotlin/app/DatabaseFactoryTest.kt`, `RuntimeResourcesTest.kt`, 기존 `RuntimeConfigTest.kt`, `HealthRouteTest.kt`, `WishlistRoutesTest.kt`, `WorkerRoutesTest.kt`

**Interfaces:**
- 기존 `DatabaseFactory.dataSource(url, username, password): DataSource`와 `migrate(...)` 유지. 현재 test/단발 도구는 기존 비pool DataSource를 사용할 수 있다.
- `DatabasePoolConfig(maximumPoolSize: Int, connectionTimeoutMs: Long = 5000)`와 `DatabaseFactory.pooledDataSource(url, username, password, config): HikariDataSource` 추가.
- `RuntimeResources : AutoCloseable`, `fun <T: AutoCloseable> own(resource: T): T`로 등록한 자원을 역순·한 번 닫는다. close 하나가 실패해도 나머지를 닫으며 secret을 로그하지 않는다.
- 공개/Worker route의 blocking service 호출만 `withContext(Dispatchers.IO)`에서 실행한다. service는 동기 JDBC API를 유지한다. 내부 fire-and-forget 작업을 만들지 않는다.

pool 기본값 API 5·일반 Worker 2, minimumIdle=0, connectionTimeout=5000ms다. `DB_POOL_MAX_SIZE`가 있으면 양수만 허용하며 운영 DB 허용 연결은 B11에서 instance 수를 곱해 검증한다. LOCAL_HEALTH는 DB/Firebase/Tasks 초기화를 요구하지 않는다.

- [x] **Step 1: 실패할 pool/lifecycle 테스트 작성.** max=1에서 한 connection을 잡은 동안 두 번째 획득은 정해진 timeout으로 실패, 첫 connection 반환 뒤 재획득 가능, pool close 뒤 획득 불가. RuntimeResources는 역순·중복 close 안전·한 자원 close 실패에도 다음 자원 close를 assert한다. fake service latch로 blocking call이 route 실행 스레드에서 벗어나는지 확인하고 request cancellation은 전파돼야 한다.
- [x] **Step 2: RED 확인.** `./gradlew test --tests app.DatabaseFactoryTest --tests app.RuntimeResourcesTest --tests app.RuntimeConfigTest --tests app.HealthRouteTest` — pool·종료 기반 부재로 실패.
- [x] **Step 3: 구현.** HikariCP 7.0.2를 명시적으로 추가하고 DatabaseFactory를 Main에서 추출한다. Main은 역할별 pool을 한 번 만들고 ApplicationStopped에서 닫는다. CloudTasksClient는 요청마다 생성하지 않고 역할 resource로 재사용한다. 실제 HTTP transport의 pinned DNS per request는 유지하면서 공유 OkHttp dispatcher/connectionPool을 관리·종료한다. JDK 17 HttpClient는 이미 gateway에서 재사용하며 존재하지 않는 close API를 호출하지 않는다.
- [x] **Step 4: GREEN 확인.** 위 명령과 `./gradlew test --tests app.http.WishlistRoutesTest --tests app.http.WorkerRoutesTest --tests app.extraction.SafeHttpTransportTest --tests app.http.FirebaseOwnerResolverTest` PASS. health는 env credential 없이 200 ok, malformed production 설정의 기존 fail-fast 유지. 종료 중 새로운 task 등록을 예약하지 않는다.
- [x] **Step 5: 커밋.** `feature(server): 서버 IO와 연결 자원 수명 관리` / `역할별 연결 pool과 외부 client 재사용 및 종료 처리를 적용한다.`

Hikari 버전은 [공식 7.0.2 tag POM](https://github.com/brettwooldridge/HikariCP/blob/HikariCP-7.0.2/pom.xml)에서 존재를 확인했다. 최신 버전이라는 주장은 하지 않으며 이번 계획은 이 버전을 사용한다.

### Task 9: 전체 회귀·B0 증거와 B1 인계를 마무리한다

**Files:**
- Modify: `docs/architecture/server/overview.md`, `mvp-product-api-design.md`, `mvp-api-inventory.md`, `mvp-api-implementation-order.md`, `INDEX.md`
- Modify: 이번 계획 체크 상태·`docs/superpowers/plans/INDEX.md`
- Create: `docs/history/architecture/server/b0-foundation-implementation.md` 및 해당 INDEX — 실제 완료 시 생성

**Interfaces:** B1은 Task 1의 정책·Task 2 DTO/error·Task 3 owner 조회·Task 8 pool/IO를 사용한다. B5는 Task 4~7 claim/guard/result/recovery를 일반/browser runtime에 재사용한다.

- [ ] **Step 1: 테스트 환경 확인.** `docker info --format '{{.ServerVersion}}'`와 `./gradlew --version` 성공·JDK 17 확인. 가용하지 않으면 DB 회귀 미실행 사유를 기록하며 B0 완료로 넘기지 않는다.
- [ ] **Step 2: 전체 회귀.** `RUN_REAL_URL_PILOT=0 ./gradlew test` PASS. 테스트 보고서에서 신규 DB/경합 사례가 실행됐고 환경 이유로 skip되지 않았음을 확인한다. opt-in RealUrlPilot만 skip 가능하다. compile·unit 통과만으로 완료하지 않는다.
- [ ] **Step 3: 쓰기 경로 감사.** `rg -n 'update wishlist_items|update analysis_jobs|candidate_snapshot_json|pending_' src/main/kotlin/app`로 모든 쓰기 위치를 다시 읽는다. 처리/실패/복구 SQL에는 공통 guard 또는 같은 잠금의 current 검증이 있어야 하며 budget 정산/outbox publish는 상품 결과 쓰기와 구분한다. token 없는 runtime overload·옛 updated_at 회복 조건이 남지 않음을 확인한다.
- [ ] **Step 4: legacy rollout 기록.** production 적용 시 구 Worker drain/중지→V8/V9 적용→보호 코드 배포→queue 재개 순서가 필요함을 기록한다. 구 코드와 새 코드 혼재 시 구 Worker의 unguarded SQL을 migration만으로 차단할 수 없기 때문이다. 이번 Task에서 production 작업을 수행하지 않는다.
- [ ] **Step 5: 문서·리뷰 확인.** migration/type/상태표 실제 결과와 미실행 범위를 기록하고 INDEX를 갱신한다. `git diff --check` PASS, 계획 밖 파일 변경 없음, B1 GET/mapper 등 새 route를 구현하지 않았음 확인. 변경을 검토한다.
- [ ] **Step 6: 완료 기록 커밋.** `docs: B0 서버 기반 구현과 검증 기록` / `상태 및 Worker 실행 보호 검증 결과와 B1 인계 사항을 정리한다.`

## B0 완료 체크리스트

- [ ] 최신 제품 의미와 기존 requiredAction을 함께 보존하며 재지정/수동 완료가 구분된다.
- [ ] DTO/error/owner 조회는 계약에 맞고 인증·생성/replay·health 기존 동작이 유지된다.
- [ ] 빈 DB와 V7 데이터 upgrade, 원본 식별·key·metadata·예산 보존을 실제 PostgreSQL로 확인했다.
- [ ] generation/token/lease/stage/owner/lifecycle/manual/version 보호가 모든 중간·최종 결과와 복구에 적용된다.
- [ ] 재claim 후 옛 실행의 성공·실패·retry·fallback·candidate 쓰기가 모두 무효다.
- [ ] 실제 발생 AI 비용은 stale와 관계없이 정산되고 예산 reservation 정책을 보존한다.
- [ ] 잠금 순서·복구/outbox 원자성·동시 실행 결과를 latch 기반 DB 테스트로 확인했다.
- [ ] JDBC/외부 호출 IO 경계·bounded pool·client 재사용·종료 처리와 전체 회귀가 통과했다.
- [ ] 문서와 변경 리뷰 및 결과 기록이 완료됐으며 B1 미구현 범위를 정확히 인계했다.

## 계획 자체 검토 결과

B0의 다섯 범위(문서·상태·DTO/owner·claim/결과 보호·DB 실행 기반)를 Task 1~9에 배정했다. 기존 SQL 경로를 기준으로 processor/classifier/budget/reconciler의 빠진 보호를 포함했다. 타입/파일/선행 관계·테스트 명령을 대조하며 B5의 전체 retry budget/runtime 배포와 B1의 GET/mapper 구현은 후속 범위로 유지했다.

계획 작성에서는 신규 코드·migration·테스트를 생성하지 않았다. 현재 Docker 연결 실패는 실행 단계의 준비 항목이며 B0 검증 통과를 의미하지 않는다. 실행 전 이 계획을 검토하고, 실행 시 세부 체크 결과를 기록한다.
