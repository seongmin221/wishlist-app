# B9 중복 비교와 선택 해소 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 상품 식별 값·Product cache·중복 쌍 저장을 만들고 DUP-01 조회와 DUP-02 결정(둘 다 두기/선택 삭제)을 원자적으로 구현한다.

**Architecture:** 기존 Ktor·JDBC 구조를 확장한다. 식별 값은 추출 때 계산해 job pending을 거쳐 item에 복사한다. cache는 metadata 저장 지점(두 lane 공통)에서 쓰고 general lane fetch 전에 읽는다. 쌍은 finish transaction에서 bases만 재계산하고, kind는 SQL 식으로 실제 상태에서 계산한다. DUP-02는 owner 잠금 아래 ITEM-05 삭제 단계를 재사용한다.

**Tech Stack:** Kotlin/JDK 17, Ktor, PostgreSQL 16(Testcontainers), Flyway SQL, kotlinx.serialization.

**Spec:** [B9 설계](../specs/2026-10-24-b9-duplicate-resolution-design.md)

## Global Constraints

- 기존 V1~V21 수정 금지. 새 migration은 V22(cache·식별), V23(중복 쌍·receipt).
- 모든 쓰기: owner 구조 잠금 → (candidate) → 목적 ID순 → 상품 ID순 → media → job.
- 같은 URL 새 key는 새 item. cache 적중도 category/목적은 owner별 분류. item은 cache와 동기화하지 않는다.
- cache: 새 fetch의 `Complete`(이름 있음)만 upsert, 적중 실행은 cache를 쓰지 않음, TTL은 DB 시각 기준 7일, checked_at은 cache `extracted_at`.
- GTIN: 8/12/13/14자리, mod-10 check, 14자리 0 채움, 전부 0 거절, 서로 다른 값 여럿이면 null. MPN: NFKC·대문자·공백/`-`/`_`/`.`/`/` 제거 후 3~64자, brand(NFKC·소문자·문자/숫자만) 필수, key `brand|mpn`. parser ambiguous면 둘 다 null.
- DUP-01 limit 기본 20·최대 50. DUP-02 receipt는 계정 수명 보존.
- 커밋 메시지: `feature(server): 한글 설명` / `docs: …` + 빈 줄 + 간단한 본문 + `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. issue 번호 없음.
- 테스트 명령(server/): `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home DOCKER_HOST=unix:///var/run/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 RUN_REAL_URL_PILOT=0 ./gradlew test --tests '<class>'` (이하 `$TEST`).

## Review Focus

- 재분석으로 existing이 PROCESSING이 된 뒤의 DELETE_EXISTING → kind가 AWAITING이므로 409 NOT_ALLOWED(Task 4 `decisionBlockedWhileExistingReanalyzes`).
- lease 만료 복구로 existing이 FAILED가 되면 finish 없이도 SAME_URL로 보여야 한다(Task 2 `kindFollowsReconcilerFailure`).
- 같은 key 동시 두 요청과 new 삭제 뒤 replay → 결과 하나, 둘째는 저장 body(Task 4 `sameKeyConcurrentReplay`).
- 다른 owner의 같은 canonical item은 쌍·count·cursor 어디에도 나타나지 않음(Task 2/3 `ownerIsolation`).
- cache 적중 item의 가격 확인 시각이 처리 시각으로 새로 보이지 않음(Task 1 `cacheHitKeepsExtractedCheckTime`).

---

## Task 1 — B9a 식별 값과 Product cache

**Files:**
- Create: `server/src/main/kotlin/app/extraction/ProductIdentifiers.kt`, `server/src/main/kotlin/app/product/ProductCacheRepository.kt`, `server/src/main/resources/db/migration/V22__product_cache_and_identifiers.sql`
- Modify: `extraction/ProductMetadataParser.kt`(ParsedProduct `gtin`,`mpnKey`), `extraction/HttpMetadataExtractor.kt`(Metadata `gtin: String? = null`, `mpnKey: String? = null`, 전달), `extraction/GeneralExtractionProcessor.kt`(fetch 전 cache), `analysis/AnalysisPendingResultRepository.kt`, `analysis/AnalysisMetadataMerge.kt`, `analysis/AnalysisResultRepository.kt`(checked_at·cache id 전달), `wishlist/WishlistItemRepository.kt`(insert에 normalized_source_url)
- Test: `server/src/test/kotlin/app/extraction/ProductIdentifiersTest.kt`, `ProductMetadataParserTest.kt`(추가), `server/src/test/kotlin/app/product/ProductCacheTest.kt`

**Interfaces:**
- Produces: `ProductIdentifiers.gtin(raw: List<String>): String?`, `ProductIdentifiers.mpnKey(brand: String?, mpn: String?): String?`; `data class CachedProduct(val id: UUID, val metadata: Metadata, val extractedAt: Instant)`; `ProductCacheRepository.lookup(c: Connection, normalizedSourceUrl: String): CachedProduct?`, `upsert(c: Connection, normalizedSourceUrl: String, metadata: Metadata): UUID`; `AnalysisPendingResultRepository.cachedProduct(claim): CachedProduct?`, `saveMetadata(claim, metadata, cached: CachedProduct? = null): Boolean`.
- V22 컬럼: `products(id uuid pk, canonical_url text unique not null, product_name text not null, product_description, product_image_url, product_brand, product_price numeric(19,4), product_currency char(3), merchant_name, product_gtin char(14), product_mpn_key text, extracted_at timestamptz not null, updated_at)`, `product_url_aliases(normalized_source_url text pk, product_id uuid fk, updated_at)`, `wishlist_items + normalized_source_url text, product_gtin char(14), product_mpn_key text, product_cache_id uuid fk products`, `analysis_jobs + pending_gtin, pending_mpn_key, pending_checked_at timestamptz, pending_product_cache_id uuid`. index `(owner_id, canonical_url)`, `(owner_id, product_gtin)`, `(owner_id, product_mpn_key)`, `(owner_id, normalized_source_url)` 모두 `where lifecycle_status='ACTIVE'` 부분 index. 가격 컬럼 타입은 기존 `wishlist_items.product_price`와 같게 맞춘다.

- [ ] **Step 1: 실패 테스트 작성**
  - `ProductIdentifiersTest`: `gtin(listOf("4006381333931"))=="04006381333931"`; `gtin(listOf("400-638 1333931"))` 동일; check digit 틀림 `"4006381333932"`→null; `"00000000"`→null; 8자리 `"96385074"`→`"00000096385074"`; 서로 다른 두 유효값→null; 같은 값 두 번→값. `mpnKey("HOKA", "1123 190-BBLC")=="hoka|1123190BBLC"`; `mpnKey(null, "ABC123")`→null; `mpnKey("Hoka", "AB")`→null; 65자→null; `mpnKey("Ｈｏｋａ One", "ab.c/1")=="hokaone|ABC1"`.
  - parser: Product에 `"gtin13":"4006381333931","mpn":"X-1"` + brand → `gtin`·`mpnKey` 채움; 이름 다른 Product 두 개면 둘 다 null.
  - `ProductCacheTest`(실제 DB, fake extractor가 호출 수를 센다):
    - `sameUrlNewKeyCreatesNewItemFromCache`: owner A가 URL 저장·분석 → owner B와 A가 같은 URL 다른 key로 저장 → 두 번째부터 extract 0회, item id 서로 다름, 같은 key 재전송은 Replayed.
    - `ownerSpecificClassification`: 같은 cache로 A·B classify fake가 owner별 다른 category/목적 → item별로 다르게 저장.
    - `snapshotIndependence`: cache로 만든 item X → 다른 URL 새 fetch가 같은 canonical의 cache를 갱신 → X의 name/price 불변; X를 ITEM-04로 이름 수정 → products 행 불변.
    - `expiryAfterSevenDays`: `extracted_at`을 `now()-interval '7 days 1 second'`로 조정 → fetch 발생; `6 days 23 hours` → 적중.
    - `onlyCompleteFreshExtractionIsCached`: Partial·NeedsBrowser·BLOCKED → products 0행; 적중 실행 뒤 `extracted_at` 불변.
    - `cacheHitKeepsExtractedCheckTime`: 적중 item의 `metadata_checked_at == products.extracted_at`.
    - `aliasHitForRedirectedUrl`: 짧은 URL이 canonical 다른 페이지로 redirect → 같은 짧은 URL 재저장 시 적중; tracking query(`utm_source`)만 다른 URL도 적중.
    - `staleClaimDoesNotWriteCache`: claim 뒤 item version 변경 → saveMetadata false, products 0행.
    - `browserLaneWritesCache`: browser render metadata 저장 → products 1행.
    - `identifiersCopiedToItem`: finish 뒤 item의 `product_gtin`/`product_mpn_key`/`product_cache_id`(적중 시)/`normalized_source_url`.
- [ ] **Step 2: 실패 확인** — `$TEST` 각 class. 기대: 컴파일 실패 또는 assertion 실패.
- [ ] **Step 3: 구현.** parser는 Product node의 `gtin`,`gtin8`,`gtin12`,`gtin13`,`gtin14`(문자열 또는 숫자) 모두를 모아 `gtin()`에 넘긴다. `saveMetadata`는 guarded 블록 안에서 pending 컬럼을 쓰고, `cached==null && !metadata.title.isNullOrBlank()`이면 `upsert(c, normalize(sourceUrl), metadata)` 한다. upsert는 `on conflict (canonical_url) do update` 뒤 alias를 `on conflict do update`한다. `cached!=null`이면 `pending_checked_at=cached.extractedAt`, `pending_product_cache_id=cached.id`를 쓴다. lookup은 alias join 또는 `canonical_url=?` 중 `extracted_at > clock_timestamp()-interval '7 days'`인 행을 고른다. 둘 다 있으면 `extracted_at`이 최신인 행이다. 병합은 page read이면 gtin/mpn을 교체한다. `updateAnalyzedItem`의 checked_at은 `coalesce(pending_checked_at, now)`이며, product_cache_id는 page read일 때 pending 값으로 교체한다.
- [ ] **Step 4: 통과 확인** — `$TEST` 위 class와 `app.extraction.*`, `app.analysis.*`, `app.AnalysisRuntimeFlowTest`, `app.wishlist.CreateWishlistItemServiceTest`. 기대: 모두 PASS.
- [ ] **Step 5: 커밋** — `feature(server): 상품 식별 값과 Product cache 재사용`

## Task 2 — B9b 중복 쌍 탐지와 상품 표현

**Files:**
- Create: `server/src/main/resources/db/migration/V23__duplicate_candidates.sql`, `server/src/main/kotlin/app/duplicates/DuplicateKinds.kt`, `server/src/main/kotlin/app/duplicates/DuplicateDetector.kt`
- Modify: `analysis/AnalysisResultRepository.kt`(finishLocked에서 `updateAnalyzedItem` 뒤 `DuplicateDetector.refresh`), `wishlist/WishlistItemRowMapper.kt`, `wishlist/WishlistItem.kt`(`duplicateCounts: DuplicateCounts? = null`), `http/WishlistItemDtos.kt`, `http/WishlistItemViewMapper.kt`
- Test: `server/src/test/kotlin/app/duplicates/DuplicateDetectionTest.kt`, `server/src/test/kotlin/app/testutil/DuplicateTestSupport.kt`

**Interfaces:**
- V23: `duplicate_candidates(id uuid pk, owner_id uuid, new_item_id uuid, existing_item_id uuid, bases text[] not null, status varchar(16) check in (OPEN,DECIDED,WITHDRAWN), decision varchar(16) null check in (KEEP_BOTH,DELETE_NEW,DELETE_EXISTING), decided_at, version int >0, created_at, updated_at, unique(new_item_id, existing_item_id), check(new_item_id<>existing_item_id), check((status='DECIDED')=(decision is not null)), check(bases <@ array['CANONICAL_URL','GTIN','BRAND_MPN','SOURCE_URL'])`, FK `(owner_id,new_item_id)`/`(owner_id,existing_item_id)` → `wishlist_items(owner_id,id)`(없으면 unique 추가), 부분 index `(new_item_id) where status='OPEN'`, `(existing_item_id) where status='OPEN'`. `duplicate_decision_receipts(owner_id, idempotency_key, request_fingerprint char(64), candidate_id uuid fk, result_json jsonb, created_at, pk(owner_id,idempotency_key))`.
- Produces: `enum class DuplicateBasis { CANONICAL_URL, GTIN, BRAND_MPN, SOURCE_URL }`, `enum class DuplicateKind { MATCH, SAME_URL, AWAITING_ANALYSIS }`; `DuplicateKinds.kindSql(newAlias: String, existingAlias: String, candidateAlias: String): String`(kind 문자열 또는 null인 SQL 식), `DuplicateKinds.visibleSql(...)`(두 item ACTIVE·status OPEN·kind not null); `DuplicateDetector.refresh(c: Connection, owner: UUID, itemId: UUID)`; `data class DuplicateCounts(val match: Int, val sameUrl: Int, val awaitingAnalysis: Int)`; DTO `duplicateCandidates: DuplicateCountsDto? = null`(`matchCount`,`sameUrlCount`,`awaitingAnalysisCount`, 모두 0이면 생략, `@EncodeDefault(NEVER)`).

- [ ] **Step 1: 실패 테스트 작성** (`DuplicateDetectionTest`, 실제 finish 경로 사용: `newFinishJob` 대신 owner 지정 fixture. support에 `finishWith(source, owner, url, metadata: Metadata, outcome)` 둔다)
  - `stateMatrix`: existing READY·PARTIAL × CANONICAL/GTIN/BRAND_MPN → MATCH, bases 정확; existing FAILED_TERMINAL·FAILED_RETRYABLE + 같은 정규화 URL → SAME_URL; existing PROCESSING + 같은 URL → AWAITING_ANALYSIS; existing READY + SOURCE_URL만 → 숨김(count 0).
  - `olderItemFinishingLaterCreatesPair`: B(늦게 생성) 먼저 READY, A(먼저 생성) 나중 READY → 쌍 new=B, existing=A 하나.
  - `awaitingTurnsIntoMatch`·`kindFollowsReconcilerFailure`: existing PROCESSING(AWAITING) → finish READY 같은 canonical → MATCH; 다른 경우 lease 만료 `AnalysisJobReconciler.reconcileExpired` → FAILED_RETRYABLE → SAME_URL(행 변경 없이).
  - `reanalysisMakesKindAwaiting`: MATCH 쌍의 existing에 ITEM-06 → AWAITING_ANALYSIS; new에 ITEM-06 → 숨김.
  - `withdrawAndReopen`: new 재분석 결과 canonical 변경 → WITHDRAWN(version+1); 다시 같아지면 OPEN(version+1).
  - `decidedPairSurvivesReanalysis`: status DECIDED/KEEP_BOTH 행 → 양쪽 재분석 finish 뒤 행 불변.
  - `multipleCandidatesAndChain`: A<B<C 같은 canonical → (B,A),(C,A),(C,B) 세 쌍, C의 count match=2.
  - `ownerIsolation`: 다른 owner 같은 canonical → 쌍 0.
  - `archivedAndDeletedExcluded`: existing ARCHIVED/DELETED → 새 쌍 없음, 기존 쌍은 count에서 제외.
  - `userEditsDoNotAffectIdentity`: existing 이름·브랜드 ITEM-04 수정 뒤에도 BRAND_MPN MATCH 유지.
  - `legacyNullNormalizedUrlFallback`: existing의 `normalized_source_url`을 null로 만든 뒤 FAILED 상태 → SAME_URL.
  - `failedNewHasNoVisiblePair`: new FAILED → count 없음.
  - `dtoCounts`: ITEM-03 상세와 ITEM-02 card JSON에 `duplicateCandidates.matchCount`; 쌍 없으면 key 없음.
- [ ] **Step 2: 실패 확인** — `$TEST` `app.duplicates.DuplicateDetectionTest`.
- [ ] **Step 3: 구현.** `refresh`는 X의 식별 값을 읽는다. 후보 Y는 같은 owner의 ACTIVE 중 `canonical_url = X.canonical`, `product_gtin = X.gtin`, `product_mpn_key = X.mpn`, URL 관계 중 하나로 고른다. URL 관계는 `normalized_source_url = any(X의 URL key 집합)`, `canonical_url = any(X의 URL key)`, null 행의 Kotlin 정규화다. 각 Y의 bases를 계산해 orientation별 upsert/withdraw한다. 그다음 X가 참여한 OPEN 쌍 중 이번 후보에 없는 것을 WITHDRAWN으로 바꾼다. DECIDED는 건드리지 않는다. kind SQL은 설계 3절의 표를 그대로 옮긴다(new 상태 READY/PARTIAL+canonical not null 조건 포함).
- [ ] **Step 4: 통과 확인** — `$TEST` 위 class, `app.analysis.*`, `app.http.WishlistDetailRoutesTest`, `app.http.WishlistReadRoutesTest`, `app.http.HomeActionRoutesTest`. 기대: PASS.
- [ ] **Step 5: 커밋** — `feature(server): 중복 후보 쌍 탐지와 후보 수 표시`

## Task 3 — B9c DUP-01 조회

**Files:**
- Create: `server/src/main/kotlin/app/duplicates/DuplicateReadService.kt`, `server/src/main/kotlin/app/http/DuplicateRoutes.kt`, `server/src/main/kotlin/app/http/DuplicateDtos.kt`, `server/src/main/kotlin/app/http/DuplicateRequestParser.kt`
- Modify: `Main.kt`(apiRoutes에 `duplicateRoutes`), `http/CandidateCursorCodec.kt`(`encodeDuplicates/decodeDuplicates`)
- Test: `server/src/test/kotlin/app/duplicates/DuplicateReadTest.kt`, `server/src/test/kotlin/app/http/DuplicateRoutesTest.kt`, `app/http/ApiRouteExposureTest.kt`(두 route 추가)

**Interfaces:**
- Produces: `data class DuplicateCandidateView(val id: UUID, val kind: DuplicateKind, val bases: List<DuplicateBasis>, val version: Int, val allowedDecisions: List<DuplicateDecision>, val existingItem: WishlistItem)`; `data class DuplicatePage(val item: WishlistItem, val candidates: List<DuplicateCandidateView>, val totalCount: Long, val next: ReadPosition?)`; `DuplicateReadService.list(owner: UUID, itemId: UUID, limit: Int, after: ReadPosition?): DuplicatePage`(item 없으면 `DuplicateException("WISHLIST_ITEM_NOT_FOUND")`); `enum class DuplicateDecision { KEEP_BOTH, DELETE_NEW, DELETE_EXISTING }`; `class DuplicateException(code, currentVersion: Int? = null, itemId: UUID? = null, fields: Set<String> = emptySet())`.
- 응답 DTO: `DuplicatePageDto(item: WishlistItemDto, candidates: List<DuplicateCandidateDto>, totalCount: Long, nextCursor: String?)`, `DuplicateCandidateDto(id, kind, bases, version, allowedDecisions, existingItem: WishlistItemDto)`. item·existingItem은 `DeletionImpacts.attach`를 거친다.

- [ ] **Step 1: 실패 테스트 작성**
  - `DuplicateReadTest`: `kindsAndDecisions`(MATCH/SAME_URL 셋, AWAITING 빈 배열), `deletionImpactIncluded`(existing 목적 이름·candidateCount), `keysetSameCreatedAt`(같은 created_at existing 5개, limit 2 → 2,2,1, 누락·중복 없음, 끝 cursor null), `hiddenWhenCounterpartDeleted`(ITEM-05 뒤 totalCount 감소), `archivedNewItemEmpty`, `deletedNewItem404`, `ownerIsolation`(다른 owner item id → 404), `countsMatchDto`(totalCount == item DTO 세 count 합).
  - `DuplicateRoutesTest`: 401, `INVALID_WISHLIST_ITEM_ID`, 알 수 없는/중복 query·limit 0/51 → 400 `INVALID_DUPLICATE_QUERY`, 다른 item의 cursor → 400 `INVALID_DUPLICATE_CURSOR`, 200 JSON 형태(서명 이미지 URL 포함).
- [ ] **Step 2: 실패 확인** — `$TEST` 두 class.
- [ ] **Step 3: 구현.** REPEATABLE READ read-only transaction 하나에서 item·count·page를 읽는다(B8 `CandidateReadService` 방식). cursor 문자열은 `v1|DUPLICATES|ownerTag|itemId|micros|existingId`. 정렬은 existing `created_at desc, id desc`.
- [ ] **Step 4: 통과 확인** — `$TEST` 두 class와 `app.http.ApiRouteExposureTest`. 기대: PASS.
- [ ] **Step 5: 커밋** — `feature(server): 중복 후보 비교 조회 DUP-01 구현`

## Task 4 — B9d DUP-02 결정과 선택 삭제

**Files:**
- Create: `server/src/main/kotlin/app/duplicates/DuplicateDecisionService.kt`, `server/src/main/kotlin/app/duplicates/DuplicateReceipts.kt`
- Modify: `wishlist/ItemMutationService.kt`(`internal fun deleteLocked(c, owner, item: MutableItem, jobs)` 분리, `delete`가 호출), `http/DuplicateRoutes.kt`, `http/DuplicateRequestParser.kt`, `http/DuplicateDtos.kt`
- Test: `server/src/test/kotlin/app/duplicates/DuplicateDecisionTest.kt`, `server/src/test/kotlin/app/duplicates/DuplicateDecisionConcurrencyTest.kt`, `DuplicateRoutesTest.kt`(추가)

**Interfaces:**
- Consumes: Task 2 `DuplicateKinds`, Task 3 `DuplicateDecision`/`DuplicateException`, B7 `ItemMutationRepository.lockAll/lockPurposes/lockMedia/lockJobs`, `PurposeMembership`.
- Produces: `data class DuplicateDecisionRequest(val candidateId: UUID, val decision: DuplicateDecision, val expectedCandidateVersion: Int, val expectedItemVersion: Int?)`; `data class DuplicateDecisionResult(val candidateId: UUID, val decision: DuplicateDecision, val decidedAt: Instant, val candidateVersion: Int, val deletedItemId: UUID?, val remainingItemIds: List<UUID>, val purposes: List<MovedPurpose>, val replayed: Boolean = false)`; `DuplicateDecisionService(dataSource, afterLocks: () -> Unit = {}).decide(owner: UUID, itemId: UUID, key: UUID, request): DuplicateDecisionResult`.
- fingerprint canonical: `v1|itemId|candidateId|decision|expectedCandidateVersion|expectedItemVersion(-)`의 SHA-256.

- [ ] **Step 1: 실패 테스트 작성**
  - `DuplicateDecisionTest`: `keepBoth`(DECIDED/KEEP_BOTH, version+1, 두 item version 불변, DUP-01에서 사라짐, 재분석 finish 뒤에도 불변), `deleteNew`(new DELETED, 목적 membership+1·count 응답, 다른 쌍 숨김, new의 진행 job 없음 확인), `deleteExisting`(existing DELETED·media 해제, new review·연결·version 불변), `atomicRollback`(`afterLocks`에서 쌍 행을 막는 실패 주입: 삭제 직후 예외 → item ACTIVE·쌍 OPEN·receipt 없음), `reviewSeparation`(DUP-02 뒤 review PENDING 그대로 → ITEM-08 CONFIRM 뒤 쌍 상태 불변; CONFIRMED item에도 DUP-02 허용), `multiCandidatePairwise`(existing 3개: 하나 DELETE_EXISTING, 하나 KEEP_BOTH, 남은 하나 OPEN), `errors`(다른 owner item 404, 다른 item의 candidate 404, version 불일치 409 currentVersion, 이미 DECIDED 409 NOT_ALLOWED, AWAITING 409 NOT_ALLOWED, KEEP_BOTH에 expectedItemVersion → 서비스에 오기 전 422, 지울 item version 불일치 409 itemId·currentVersion), `decisionBlockedWhileExistingReanalyzes`(existing ITEM-06 → DELETE_EXISTING 409 NOT_ALLOWED), `replay`(같은 key·같은 body → 같은 결과 replayed=true, DELETE_NEW 뒤에도; 다른 body → 409 IDEMPOTENCY_KEY_REUSED; 다른 key 재요청 → 409 NOT_ALLOWED).
  - `DuplicateDecisionConcurrencyTest`(실제 thread + latch): `twoKeysSamePair`(하나 200·하나 409, 삭제 한 번), `sameKeyConcurrentReplay`(둘 다 200, 하나만 replayed, receipt 1행), `deleteExistingVsItemDelete`(ITEM-05 먼저 → DUP-02 404; DUP-02가 잠금 보유 중 ITEM-05 대기 → ALREADY_DELETED), `decisionVsExistingFinish`(existing 재분석 finish와 DELETE_EXISTING 경합 → 어느 순서든 deadlock 없음, 최종 상태 일관), `twoPairsDeleteSameItem`(C의 쌍(C,A)·B의 쌍(B,A)에서 동시에 A 삭제 → 하나 성공·하나 404).
  - `DuplicateRoutesTest`: PUT 401/400 key/422 fields(`candidateId`,`decision`,`expectedCandidateVersion`,`expectedItemVersion`)/404/409 details, replay header `Idempotency-Replayed: true`.
- [ ] **Step 2: 실패 확인** — `$TEST` 세 class.
- [ ] **Step 3: 구현.** 설계 6절의 검사 순서를 따른다. 잠금 순서는 owner → receipt → new item peek → candidate `for update` → 두 item peek → 지울 item의 목적 → `lockAll(두 item)` → 지울 item의 media → jobs다. kind는 잠긴 item의 실제 상태로 `DuplicateKinds`의 Kotlin 판정(`kindOf(new: status+canonical, existing: status, bases)`)으로 계산한다. 이 판정은 SQL 식과 같은 표이며 parity 테스트를 하나 둔다. 쌍 갱신은 `status='DECIDED', decision, decided_at=clock_timestamp(), version+1`이다. receipt에는 결과 JSON을 schema 1로 저장한다.
- [ ] **Step 4: 통과 확인** — `$TEST` 세 class와 `app.wishlist.ItemDeleteTest`, `app.wishlist.ItemMutationConcurrencyTest`. 기대: PASS.
- [ ] **Step 5: 커밋** — `feature(server): 중복 판단 기록과 선택 삭제 DUP-02 구현`

## Task 5 — 전체 회귀·문서·인계

**Files:**
- Create: `docs/architecture/server/duplicate-resolution-api.md`(계약·JSON 예시), `docs/history/product-planning/mvp/decisions/b9-duplicate-resolution-policy-2026-10-24.md`, `docs/history/architecture/server/b9-duplicate-resolution-implementation-2026-10-24.md`
- Modify: `docs/architecture/server/mvp-api-inventory.md`(DUP-01/02 구현, 남은 결정 행 해결), `mvp-api-implementation-order.md`(B9 구현 상태), `extraction-pipeline.md`(cache·식별), `mvp-product-api-design.md`(다른 판매처 행), `docs/product/organize-candidates.md`·`save-a-product.md`(확정 정책 반영), 각 `INDEX.md`(architecture/server, history/architecture/server, product-planning decisions, superpowers specs/plans), spec 상태줄.

- [ ] **Step 1:** 전체 `./gradlew test --rerun-tasks`(위 환경 변수). 기대: 실패/오류 0, RealUrlPilot 등 opt-in skip만 있다. 수치를 기록한다.
- [ ] **Step 2:** 문서를 갱신한다. 인계는 C6(안내 위치·비교 시트·결과 한 줄), B10(archive item 쌍 숨김 검증), B11(판매처 식별 코드 노출률 측정·cache 증가량·receipt 증가)이다.
- [ ] **Step 3:** 브랜치 전체 리뷰(fresh reviewer)를 받는다. 지적을 처리하고 영향받은 테스트를 다시 실행한다.
- [ ] **Step 4: 커밋** — `docs: B9 중복 해소 계약·회귀와 인계 기록`
