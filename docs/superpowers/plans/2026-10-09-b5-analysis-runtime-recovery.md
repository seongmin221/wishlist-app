# B5 비동기 분석과 운영 복구 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** WORK-01 → WORK-02 → OPS-01 순서로 일반 Worker → browser fallback → 결과 반영 → 중단 복구를 실제 runtime 역할로 조립하고, local runtime + fake queue로 전체 흐름을 통과시킨다.

**Architecture:** 기존 Ktor·JDBC modular monolith에 `browser-worker`·`maintenance` 역할을 추가한다. metadata 병합과 예산 소진 처리를 순수 함수와 공통 transaction helper로 모아 finish·claim·복구가 같은 규칙을 쓰게 한다. browser 연결은 프로세스 내 pinning proxy를 거치고, maintenance는 outbox 발행 → RUNNING 복구 → PENDING 복구 → budget 정리를 단계별 격리로 실행한다.

**Tech Stack:** Kotlin 2.3.21/JDK 17, Ktor 3.6.0, PostgreSQL 16, Flyway 11.20.0, OkHttp 5.3.0, jsoup 1.23.2, Playwright 1.63.0, google-cloud-tasks 2.95.0, Testcontainers 1.21.3. dependency 추가·버전 변경 없음.

**Spec:** [승인된 B5 설계](../specs/2026-10-09-b5-analysis-runtime-recovery-design.md). [제품·운영 결정](../../history/product-planning/mvp/decisions/b5-analysis-runtime-policy-2026-10-09.md), [착수 대조](../../history/architecture/server/b5-runtime-recovery-preparation-2026-10-09.md).

> 상태: Native Task1~12 구현 완료 · 독립 리뷰 Important 3+재등급 1 반영 · 전체 435 tests 433 통과/2 opt-in skip. [구현 이력](../../history/architecture/server/b5-analysis-runtime-implementation-2026-10-09.md).

## Global Constraints

- 서버·docs만 변경한다. client/·design/handoff/·다른 worktree는 수정하지 않는다. V1~V15 SQL과 `V16__recoverable_read_indexes.kt`·`ReadIndexRollout.kt`(V16 checksum 원본)는 수정하지 않는다. 새 migration은 V17이다.
- 처리 80초 < Worker 90초 < Cloud Tasks 105초 < lease 120초(`AnalysisTiming`)를 유지한다.
- 재시도 예산: `attempt_count + browser_attempt_count < 3` 그리고 두 lane 중 가장 이른 첫 시도부터 30분. 재예약은 attempt를 늘리지 않는다. PENDING 재예약은 `recovery_seq` job 누적 상한 3.
- metadata: 출처 JSON-LD `Product` → OpenGraph `product:*`·`og:*`. 이름이 다른 Product가 둘 이상이면 brand·price·currency null. 가격 범위·offer 상이 null. merchant는 `offers.seller.name` → `og:site_name`. brand·merchant 200자 초과 null. price `numeric(19,4)`·0 이상·scale≤4·정수부≤15, 통화는 `java.util.Currency` 코드 대문자. 금액·통화는 쌍으로만 저장.
- 병합: 성공은 `pending ?: existing`, 실패·PARTIAL은 `existing ?: pending`, `BRAND` override 보호. `metadata_checked_at`은 pending 페이지 metadata(`pending_canonical_url is not null`)를 반영하는 finish의 DB 시각이며, 그 finish에서는 price·currency를 이번 읽기 결과 쌍으로 교체(null 포함). 아니면 둘 다 유지. `classified_at`으로 대체하지 않는다.
- canonical: `link[rel=canonical]` → `og:url`, 최종 URL과 OkHttp `topPrivateDomain()`이 같고 http/https·userinfo 없음·IP literal 아님·기본 port일 때만 채택. 정규화는 scheme/host 소문자, fragment 제거, tracking query(`utm_*`, `fbclid`, `gclid`, `msclkid`, `igshid`, `mc_cid`, `mc_eid`)만 제거.
- 실패 의미: 일반 lane DNS 실패(`DnsLookupFailed`, resolver 시간 초과·포화 포함)는 Retryable. `UnsafeUrlException`(차단 주소·scheme·port·userinfo·local host·invalid URL·pinned Dns 불변식 위반)은 Terminal + `BLOCKED_ADDRESS`. browser lane의 대상 실패는 PARTIAL, Worker 인프라 장애는 Retryable. 공개 failure code 목록 불변.
- Worker 응답: retry outbox를 저장한 Retryable은 `ACKNOWLEDGE`(204). `RETRY`(503)는 durable 기록이 없는 claim 전 마감·executor 포화·90초 timeout만. retry outbox `not_before = DB 시각 + min(10초 × 2^(합산 attempts − 1), 600초)`.
- PENDING 복구: 발행된 최신 outbox가 있거나 outbox 없음 + `updated_at` 5분 경과 + `recovery_check_at` 도래만 발견. ALIVE → 5분 뒤, 조회 실패 → 1분 뒤, MISSING → 잠금 재검증 후 재예약/소진/취소. 동시 실행은 task_name UNIQUE로 1건.
- 잠금 순서 owner→item→job을 지킨다. DB 시각은 `clock_timestamp()`. 원격 호출 중 DB 잠금·connection을 쥐지 않는다.
- 로그는 jobId·eventId·예외 타입·단계 이름만. 예외 메시지·URL·SQL·credential을 남기지 않는다.
- 클라우드 IAM·OIDC·Scheduler 실호출·배포 egress firewall은 B11. local은 실제 PostgreSQL과 fake queue/AI로 검증한다. 실제 DB 테스트를 실행하지 못하면 해당 Task를 통과로 보지 않는다.
- 커밋은 `feature(server): 한글 설명`/`bugfix: …`/`docs: …` + 빈 줄 + 간결한 한글 본문, 이슈 번호 없음. author/committer는 seongmin221. PR은 마지막 Task에서 develop 대상으로 연다.

## 파일 구조와 책임

경로의 `app/`는 `server/src/main/kotlin/app/`, 테스트는 `server/src/test/kotlin/app/` 기준이다(새 파일은 +).

| 파일 | 책임 |
| --- | --- |
| + `server/src/main/resources/db/migration/V17__analysis_metadata_and_recovery.sql` | metadata·pending·recovery 컬럼, CHECK, index |
| + `app/extraction/UrlCanonicalizer.kt` | URL 정규화와 선언 canonical 채택 |
| + `app/extraction/ProductMetadataParser.kt` | JSON-LD/OG에서 title·brand·price·merchant·canonical 추출 |
| `app/extraction/HttpMetadataExtractor.kt` | `Metadata` 확장, 파서 위임, redirect 루프 유지 |
| + `app/analysis/AnalysisMetadataMerge.kt` | 기존·pending metadata 읽기, 순수 병합 함수, item 컬럼 값 |
| `app/analysis/AnalysisPendingResultRepository.kt` | 새 pending 컬럼 저장 |
| `app/analysis/AnalysisClaimRepository.kt` | 합산 예산, GENERAL 재claim의 새 pending 삭제, `recovery_check_at` null, 소진 시 `failExhausted` |
| `app/analysis/AnalysisJobTransitions.kt` | `hasRetryBudget(now)`, `failExhausted`, `retryBackoff`, `transitionAnalysisJob`의 `recovery_check_at` 초기화 |
| `app/analysis/AnalysisResultRepository.kt` | 병합 helper 사용, NeedsBrowser 예산, Retryable ACK·`not_before` |
| `app/analysis/StaleCategoryReplacement.kt` | 합산 예산, 소진 시 `failExhausted` |
| `app/analysis/AnalysisJobReconciler.kt` | 합산 예산, 순환 발견, 소진 시 `failExhausted` |
| + `app/analysis/PendingJobRecovery.kt` | 오래된 PENDING 발견·queue 확인·재예약 |
| + `app/analysis/RecoveryCheckDeferral.kt` | 건너뛴 후보의 `recovery_check_at` 미루기 공통 함수 |
| + `app/extraction/BoundedResolver.kt` | 시간 제한·포화 거부가 있는 DNS 해석 |
| `app/extraction/UrlSafetyPolicy.kt` | `DnsLookupFailed` 분리 |
| `app/extraction/GeneralExtractionProcessor.kt` | DNS→Retryable, 차단→`BLOCKED_ADDRESS` 저장 후 Terminal |
| + `app/browser/EgressProxy.kt` | loopback pinning forward proxy |
| `app/browser/PlaywrightGateway.kt` | proxy 경유 Chromium 설정 |
| `app/browser/BrowserWorkerService.kt` | `DnsLookupFailed`도 PARTIAL |
| `app/tasks/TaskGateway.kt` | `AnalysisTask.scheduleAt`, `TaskStatus`, `status` 기본 구현 |
| `app/tasks/CloudTasksGateway.kt` | scheduleTime, `getTask` 조회 |
| `app/tasks/OutboxDispatcher.kt` | 실패 격리·시간 상한·`DispatchReport`·`not_before` 전달 |
| + `app/maintenance/MaintenanceService.kt` | 4단계 실행·격리·`MaintenanceReport` |
| + `app/http/MaintenanceRoutes.kt` | `POST /internal/maintenance/run` |
| `app/budget/BudgetMaintenanceService.kt` | `MaintenanceReport` → `BudgetMaintenanceReport` |
| `app/http/WorkerRoutes.kt` | general/browser route 함수 분리 |
| `app/RuntimeConfig.kt`, `app/Main.kt` | 역할 4개, 역할별 route·자원 조립, `apiRoutes` 추출 |
| + `testutil/InMemoryTaskQueue.kt` | fake queue: 생성·유실·조회 장애·전달 |
| + `testutil/MetadataFixtures.kt` | HTML fixture와 fixture fetch |

## 테스트 실행과 RED/GREEN 기록

모든 Gradle 명령은 `server/`에서 [로컬 테스트 환경](../../architecture/server/local-test-environment.md#전체-테스트-실행)의 Podman/JDK 설정으로 실행한다.

```sh
b5_gradle() {
  env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    DOCKER_HOST=unix:///var/run/docker.sock \
    TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
    TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 RUN_REAL_URL_PILOT=0 ./gradlew "$@"
}
```

각 RED는 실패한 테스트명과 원인(assertion 실패 / 신규 symbol compile 실패)을 구분해 B5 구현 이력에 남긴다. GREEN은 실제 완료 결과만 기록한다. 컨테이너 기동 실패 같은 환경 원인은 로그로 확인해 별도로 기록하고 통과로 덮어쓰지 않는다. DB fixture는 기존 `withAnalysisDatabase`, `newAnalysisClaim`, `newFinishJob`(AnalysisFinishTestSupport)을 쓴다. 실제 Playwright 테스트는 `RUN_BROWSER_TESTS=1`일 때만 실행하고, 미실행이면 이력에 미실행으로 남긴다.

## Review Focus

- 같은 job에 Worker의 늦은 Retryable finish와 maintenance의 PENDING 복구가 겹치면 outbox가 두 개 생기면 안 된다. 잠금 재검증 기준(`updated_at`·최신 outbox ID)이 늦은 finish 뒤 바뀌어 복구가 무변경이 되는지 latch로 겹쳐 확인한다(Task 9).
- JSON-LD가 `@graph` 안의 Product·배열 `@type`(`["Product","Thing"]`)·문자열 숫자 `"price": "12,900"` 같은 실제 사이트 변형을 담으면 예외 없이 규칙대로 null 또는 값이 나와야 한다. 파싱 예외가 Worker Retryable로 번지지 않게 한다(Task 2).
- 사용자가 BRAND를 직접 고친 상품이 재분석 성공을 받아도 brand는 유지되고 price·확인 시각만 갱신돼야 한다. B7 재분석 전에도 USER override fixture로 고정한다(Task 3).
- proxy가 CONNECT 대상에 IPv6 literal·`localhost.`(끝 점)·대문자 host·`:443` 외 port를 받으면 모두 거부해야 한다. Chromium이 proxy를 우회해 직접 연결하는 경로(WebRTC·QUIC)는 설정 인자 assertion으로 고정한다(Task 6/7).
- Cloud Tasks가 같은 이름의 task를 이미 가진 상태(이전 dispatcher가 생성 후 기록 전에 중단)에서 `scheduleAt`을 다시 보내도 `AlreadyExists`로 성공 처리되고 outbox가 발행됨으로 기록돼야 한다(Task 8).

---

### Task 1: V17 migration과 upgrade

**Files:** Create `server/src/main/resources/db/migration/V17__analysis_metadata_and_recovery.sql`; modify `server/src/test/kotlin/app/DatabaseMigrationTest.kt`.

**Interfaces:**
- Produces: 컬럼 `wishlist_items.{product_brand text, product_price numeric(19,4), product_currency char(3), merchant_name text, metadata_checked_at timestamptz}`, `analysis_jobs.{pending_brand, pending_price, pending_currency, pending_merchant, recovery_seq int not null default 0, recovery_check_at timestamptz}`, `outbox_events.not_before timestamptz`. index `analysis_jobs_pending_recovery_idx`, `analysis_jobs_running_recovery_idx`, `outbox_events_job_created_idx`.

- [x] RED: `all Flyway migrations apply to an empty postgres database`의 기대 버전 목록 끝에 `"17"`을 추가한다.
- [x] RED: `V17 upgrade keeps V16 rows and adds nullable metadata and recovery defaults` 추가. `migrationConfiguration(...).target("16").load().migrate()` 후 item·job·outbox 1건씩 insert, 전체 migrate, 기존 컬럼 snapshot 불변(`snapshot(...)` helper)과 새 컬럼 null·`recovery_seq=0` 확인.
- [x] RED: `V17 constraints reject half price pairs and invalid currency` 추가. 다음 UPDATE가 모두 `PSQLException`으로 실패해야 한다.
  ```kotlin
  for (sql in listOf(
      "update wishlist_items set product_price=1000 where id='$item'",
      "update wishlist_items set product_currency='KRW' where id='$item'",
      "update wishlist_items set product_price=-1,product_currency='KRW' where id='$item'",
      "update wishlist_items set product_price=1,product_currency='krw' where id='$item'",
      "update analysis_jobs set pending_price=1 where id='$job'",
  )) assertFailsWith<org.postgresql.util.PSQLException> { c.createStatement().use { it.executeUpdate(sql) } }
  ```
  `product_price=12900.5,product_currency='KRW'`은 성공한다.
- [x] RED 실행: `b5_gradle test --tests 'app.DatabaseMigrationTest'`. 버전 목록 불일치·컬럼 없음으로 실패해야 한다.
- [x] 구현: 아래 SQL을 작성한다. 운영 배포는 B11 이전이고 대상이 작업 단위 테이블이므로 V16의 concurrently rollout 대신 transaction 안의 일반 `create index`를 쓴다. 이 근거를 B5 구현 이력에 기록한다.
  ```sql
  alter table wishlist_items
      add column product_brand text,
      add column product_price numeric(19,4),
      add column product_currency char(3),
      add column merchant_name text,
      add column metadata_checked_at timestamptz,
      add constraint wishlist_items_price_currency_pair_check check ((product_price is null) = (product_currency is null)),
      add constraint wishlist_items_price_nonnegative_check check (product_price is null or product_price >= 0),
      add constraint wishlist_items_currency_format_check check (product_currency is null or product_currency ~ '^[A-Z]{3}$');

  alter table analysis_jobs
      add column pending_brand text,
      add column pending_price numeric(19,4),
      add column pending_currency char(3),
      add column pending_merchant text,
      add column recovery_seq integer not null default 0,
      add column recovery_check_at timestamptz,
      add constraint analysis_jobs_pending_price_currency_pair_check check ((pending_price is null) = (pending_currency is null)),
      add constraint analysis_jobs_recovery_seq_check check (recovery_seq >= 0);

  create index analysis_jobs_pending_recovery_idx on analysis_jobs (recovery_check_at nulls first, updated_at, id)
      where stage in ('GENERAL_PENDING', 'BROWSER_PENDING');
  create index analysis_jobs_running_recovery_idx on analysis_jobs (recovery_check_at nulls first, lease_until, id)
      where stage in ('GENERAL_RUNNING', 'BROWSER_RUNNING');

  alter table outbox_events add column not_before timestamptz;
  create index outbox_events_job_created_idx on outbox_events (analysis_job_id, created_at desc, id desc);
  ```
- [x] GREEN: 같은 명령 + `--tests 'app.wishlist.ReadIndexRecoveryTest' --tests 'app.DatabaseMigrationRetryTest'`(V16 경로 회귀).
- [x] 커밋: `feature(server): B5 metadata·복구 컬럼 migration 추가`.

### Task 2: metadata·canonical 추출 (WORK-01)

**Files:** Create `app/extraction/{UrlCanonicalizer,ProductMetadataParser}.kt`, `testutil/MetadataFixtures.kt`; modify `app/extraction/HttpMetadataExtractor.kt`, `app/browser/PlaywrightGateway.kt`(생성자 호출만); test `extraction/{UrlCanonicalizerTest,ProductMetadataParserTest}.kt`, `extraction/HttpMetadataExtractorTest.kt`.

**Interfaces:**
- Produces:
  ```kotlin
  data class Metadata(
      val title: String?, val description: String?, val imageUrl: String?, val canonicalUrl: String,
      val brand: String? = null, val price: BigDecimal? = null, val currency: String? = null, val merchant: String? = null,
  )
  object UrlCanonicalizer {
      val TRACKING_PARAMETERS: Set<String>          // fbclid,gclid,msclkid,igshid,mc_cid,mc_eid (+ utm_ 접두사 규칙)
      fun normalize(url: String): String             // 실패 시 입력 그대로
      fun choose(declared: String?, finalUrl: String): String
  }
  data class ParsedProduct(val title: String?, val description: String?, val imageUrl: String?, val brand: String?,
      val price: BigDecimal?, val currency: String?, val merchant: String?, val declaredCanonical: String?)
  object ProductMetadataParser { fun parse(document: org.jsoup.nodes.Document, truncated: Boolean): ParsedProduct }
  ```
- `HttpMetadataExtractor.extract`는 `Complete(Metadata(..., canonicalUrl = UrlCanonicalizer.choose(parsed.declaredCanonical, current)))`를 만든다. title이 없으면 기존처럼 `NeedsBrowser`(데이터 없음).

- [x] RED `UrlCanonicalizerTest`:
  - `normalize`: `HTTPS://Shop.EXAMPLE.com/p/1?utm_source=a&color=red&fbclid=x#reviews` → `https://shop.example.com/p/1?color=red`. query 순서·값 보존, 모든 tracking 제거 시 `?` 없음.
  - `choose`: `https://m.example.com/p/1`(final) + `https://www.example.com/p/1` → 선언 채택. 다른 eTLD+1(`https://other.com/p/1`), `http://user@example.com/`, `https://93.184.216.34/p`, `https://www.example.com:8443/p`, `ftp://example.com/p`, null, `"not a url"` → final 정규화 값. 상대 경로 `/p/1?ref=x`는 final 기준 resolve 후 판정. `example.co.uk` 계열은 `topPrivateDomain()` 결과로 판정.
- [x] RED `ProductMetadataParserTest`(HTML fixture는 `MetadataFixtures`에 둔다):
  | 사례 | 기대 |
  | --- | --- |
  | 단일 Offer `{"price":"12900","priceCurrency":"krw"}` | 12900·KRW |
  | offers 배열 두 개 같은 값 | 값 |
  | offers 배열 값 상이 | price·currency null |
  | `AggregateOffer` lowPrice/highPrice | null |
  | price `"12,900"`, `"1e3"`, `"-1"`, `"1.23456"`, 정수부 16자리 | null |
  | priceCurrency `"XYZ"` 또는 누락 | 둘 다 null |
  | JSON-LD 없음 + `product:price:amount`/`product:price:currency` | OG 값 |
  | JSON-LD와 OG 가격 충돌 | JSON-LD |
  | brand `"Mizuno"` / `{"@type":"Brand","name":"Mizuno"}` / `product:brand` | Mizuno |
  | 이름이 다른 Product 두 개 | brand·price·currency null, title은 기존 규칙 |
  | `@graph` 안 Product, `@type` 배열 `["Product","Thing"]` | 정상 추출 |
  | 깨진 JSON-LD 스크립트 + 정상 OG | 예외 없이 OG 값 |
  | seller.name 있음 / 없고 og:site_name / 둘 다 없음 | 각각 seller / site_name / null |
  | brand 201자 | null |
  | `link[rel=canonical]`와 `og:url` 둘 다 | canonical 링크 우선 |
- [x] RED `HttpMetadataExtractorTest`: redirect 뒤 최종 URL 기준 canonical 채택, truncated 문서의 일반 `<title>`만 있으면 `NeedsBrowser` 유지, `Complete`에 brand·price·merchant 포함.
- [x] RED 실행: `b5_gradle test --tests 'app.extraction.*'`. 신규 symbol compile 실패를 확인한다.
- [x] 구현: 파서는 JSON-LD를 `runCatching`으로 개별 파싱하고 Product 후보를 모두 모은다. 가격은 문자열을 `^\d{1,15}(\.\d{1,4})?$`로 검사한 뒤 `BigDecimal(text)`로 만든다(JSON number는 `content` 문자열을 같은 검사에 통과시킨다). 통화는 `uppercase()` 후 `Currency.getAvailableCurrencies()` 코드 집합에 있을 때만 채택한다. 금액과 통화 중 하나라도 무효면 둘 다 null.
- [x] GREEN: 같은 명령 + `--tests 'app.browser.*' --tests 'app.http.LocalClassificationPathTest'`.
- [x] 커밋: `feature(server): 상품 brand·가격·판매처·canonical 추출`.

### Task 3: metadata 임시 저장·최종 병합·조회

**Files:** Create `app/analysis/AnalysisMetadataMerge.kt`; modify `app/analysis/{AnalysisPendingResultRepository,AnalysisResultRepository,AnalysisClaimRepository}.kt`, `app/wishlist/{WishlistItem,WishlistItemRowMapper}.kt`, `app/http/WishlistItemViewMapper.kt`; test `analysis/AnalysisMetadataMergeTest.kt`(순수), `analysis/MetadataPersistenceTest.kt`(DB), `http/WishlistItemViewMapperTest.kt`, `testutil/AnalysisTestSupport.kt`(`pendingSnapshot`에 새 컬럼).

**Interfaces:**
- Produces:
  ```kotlin
  internal data class StoredMetadata(val name: String?, val description: String?, val image: String?, val canonical: String?,
      val brand: String?, val merchant: String?, val price: BigDecimal?, val currency: String?,
      val nameSource: String?, val imageSource: String?, val overrides: Set<String>)
  internal data class PendingMetadata(val name: String?, val description: String?, val image: String?, val canonical: String?,
      val brand: String?, val merchant: String?, val price: BigDecimal?, val currency: String?) {
      val pageRead: Boolean get() = canonical != null
  }
  internal data class MergedMetadata(val name: String?, val description: String?, val image: String?, val canonical: String?,
      val brand: String?, val merchant: String?, val price: BigDecimal?, val currency: String?,
      val nameSource: String?, val imageSource: String?, val recordCheckedAt: Boolean)
  internal fun mergeMetadata(existing: StoredMetadata, pending: PendingMetadata, complete: Boolean): MergedMetadata
  internal fun Connection.readStoredMetadata(itemId: UUID): StoredMetadata
  internal fun Connection.readPendingMetadata(jobId: UUID): PendingMetadata
  /** SET 절 조각과 bind 값. 호출 측 UPDATE가 version·status를 함께 바꾼다. */
  internal fun MergedMetadata.assignments(): List<Pair<String, Any?>>   // product_name … metadata_checked_at
  ```
  `NAME`·`IMAGE` 보호는 기존 `source == "USER" || field in overrides`, `BRAND`는 `"BRAND" in overrides`. `recordCheckedAt == pending.pageRead`. 가격 쌍은 `recordCheckedAt`이면 pending 쌍, 아니면 existing 쌍. `assignments()`의 `metadata_checked_at` 값은 `recordCheckedAt`이면 SQL 식 `clock_timestamp()`, 아니면 기존 유지(`metadata_checked_at`)로 만든다.
- `WishlistItem`에 `brand`, `price: BigDecimal?`, `currency`, `merchant`, `metadataCheckedAt: Instant?` 추가. RowMapper `columns`에 `i.product_brand, i.product_price, i.product_currency, i.merchant_name, i.metadata_checked_at`.

- [x] RED `AnalysisMetadataMergeTest`(DB 없음):
  | 사례 | 기대 |
  | --- | --- |
  | 성공·pending brand null·existing "A" | "A" 유지 |
  | 성공·pending brand "B"·existing "A"·BRAND override | "A" |
  | 실패·existing null·pending "B" | "B" |
  | 성공·pageRead·pending price null·existing 1000/KRW | price·currency null, recordCheckedAt=true |
  | 실패·pageRead·pending 2000/USD | 2000/USD, recordCheckedAt=true |
  | pageRead=false(pending canonical null) | 가격 쌍·확인 시각 유지 |
  | NAME USER source | name 유지, nameSource 유지 |
- [x] RED `MetadataPersistenceTest`(실제 PostgreSQL):
  - `saveMetadata`가 pending brand·price·currency·merchant를 저장하고, GENERAL 재claim은 새 pending 컬럼까지 지우며 BROWSER 재claim은 유지한다.
  - 성공 finish 뒤 item의 다섯 컬럼과 `metadata_checked_at`이 finish transaction의 DB 시각(`metadata_checked_at = updated_at`)이다.
  - general `NeedsBrowser` → browser render null(PARTIAL) → `metadata_checked_at` null 유지.
  - PARTIAL·실패의 병합, BRAND override 보호, version은 finish당 1 증가.
- [x] RED `WishlistItemViewMapperTest`/상세·목록 응답: `product.brand/price/currency/merchant/metadataCheckedAt`이 저장값으로 나오고 가격이 JSON number(`12900.5`)다. `classified_at`만 있는 상품은 `metadataCheckedAt` null.
- [x] RED 실행: `b5_gradle test --tests 'app.analysis.AnalysisMetadataMergeTest' --tests 'app.analysis.MetadataPersistenceTest' --tests 'app.http.WishlistItemViewMapperTest'`.
- [x] 구현: `finishLocked`의 name/image/description/canonical 계산과 `mergedMetadata`/`mergedSource`를 `mergeMetadata` 호출로 바꾸고 `values`에 `assignments()`를 합친다. bind는 기존처럼 문자열 외에 `BigDecimal`·SQL 식을 다룰 수 있게 `setObject`로 바꾼다. `readItem`/`readPending`의 metadata 부분은 새 helper로 옮겨 중복을 없앤다. `AnalysisClaimRepository`의 GENERAL `clearMetadata`에 `pending_brand=null,pending_price=null,pending_currency=null,pending_merchant=null`을 추가한다.
- [x] GREEN: 같은 명령 + `--tests 'app.analysis.*' --tests 'app.browser.*' --tests 'app.http.Wishlist*' --tests 'app.wishlist.*'`.
- [x] 커밋: `feature(server): 추출 metadata 저장과 확인 시각 반영`.

### Task 4: generation 합산 예산·소진 처리·Worker 응답

**Files:** Modify `app/analysis/{AnalysisJobTransitions,AnalysisClaimRepository,AnalysisResultRepository,StaleCategoryReplacement,AnalysisJobReconciler}.kt`; test `analysis/GenerationBudgetTest.kt`(신규), 기존 `analysis/{GeneralWorkerServiceTest,AnalysisJobReconcilerTest}.kt`, `browser/BrowserWorkerServiceTest.kt`, `http/WorkerRoutesTest.kt`, `category/CategoryAiIntegrationTest.kt`.

**Interfaces:**
- Consumes: Task 3의 `mergeMetadata`, `readStoredMetadata`, `readPendingMetadata`, `assignments()`.
- Produces:
  ```kotlin
  internal const val MAX_GENERATION_ATTEMPTS = 3
  internal const val GENERATION_BUDGET_SECONDS = 1800L
  internal fun LockedAnalysisJob.hasRetryBudget(now: Instant): Boolean
  internal fun retryBackoffSeconds(attemptsSoFar: Int): Long     // 1→10, 2→20, … 최대 600
  /** Caller holds owner→item→job locks on a current processing item. */
  internal fun Connection.failExhausted(jobId: UUID, itemId: UUID)
  internal fun Connection.transitionAnalysisJob(jobId: UUID, stage: String, fallback: Boolean = false) // + recovery_check_at=null
  ```
  ```kotlin
  internal fun LockedAnalysisJob.hasRetryBudget(now: Instant): Boolean {
      val first = listOfNotNull(firstAttemptAt, firstBrowserAttemptAt).minOrNull()
      return attempts + browserAttempts < MAX_GENERATION_ATTEMPTS &&
          (first == null || now.isBefore(first.plusSeconds(GENERATION_BUDGET_SECONDS)))
  }
  ```
  `failExhausted`: `transitionAnalysisJob(jobId, "FAILED")` → `mergeMetadata(readStoredMetadata(itemId), readPendingMetadata(jobId), complete = false)` → 단일 UPDATE로 `analysis_status='FAILED_RETRYABLE'`, metadata assignments, `version=version+1`, `updated_at=clock_timestamp()`. `failRetryableItem`은 version 불일치 취소 경로 전용으로 남긴다.

- [x] RED `GenerationBudgetTest`(실제 PostgreSQL, spec §2 표 전체):
  - general 1회 후 NeedsBrowser → BROWSER_PENDING, browser claim 2회까지 허용, 3번째 browser claim은 Exhausted.
  - general attempt 2(SQL로 설정) 상태의 3번째 general 실행이 NeedsBrowser → browser outbox 없음, job FAILED, item FAILED_RETRYABLE, version +1.
  - `first_attempt_at`를 31분 전으로 둔 browser claim → Exhausted.
  - general Complete로 pending metadata 저장 → AI Retryable → `first_attempt_at` 31분 전으로 이동 → 다음 claim Exhausted가 item에 pending title·price와 `metadata_checked_at`을 반영.
  - stale replacement: 예산 남음 → 새 generation, 합산값·첫 시각 승계, 원래 pending metadata 미반영. 예산 소진 → `failExhausted` 반영.
  - RUNNING 복구와 재claim은 attempt를 바꾸지 않는다(복구는 0, 재claim만 +1).
  - 수동 편집으로 version이 바뀐 finish는 metadata를 반영하지 않는다(`failRetryableItem` 경로).
- [x] RED Worker 응답:
  - `GeneralWorkerServiceTest`의 `retry persists new outbox even when duplicate delivery already acknowledged running job`을 기대 `ACKNOWLEDGE`로 바꾸고, 새 outbox의 `not_before - created_at`이 첫 실패 10초(±1초), 두 번째 20초임을 확인한다.
  - `WorkerRoutesTest`의 현재 실행 fault는 204, claim 전 마감(`WorkerExecution(processingMillis=1, timeoutMillis=2)`)·executor 포화·90초 timeout은 503.
  - `BrowserWorkerServiceTest`의 `infrastructure failure retries current browser execution without fallback duplication`도 204 + retry outbox 1건.
- [x] RED 실행: `b5_gradle test --tests 'app.analysis.*' --tests 'app.browser.*' --tests 'app.http.WorkerRoutesTest' --tests 'app.category.CategoryAiIntegrationTest'`.
- [x] 구현:
  - 모든 `hasRetryBudget(lane, now)` 호출을 `hasRetryBudget(now)`로 바꾼다. claim 소진·reconciler 소진·replacement 소진은 `failRetryableItem` 대신 `failExhausted`.
  - `finishLocked` NeedsBrowser 분기: `!job.browserAttempted && job.hasRetryBudget(now)`일 때만 BROWSER_PENDING. 소진이면 `failExhausted`로 끝내고 ACKNOWLEDGE.
  - Retryable 분기: 예산 남음 → PENDING + outbox(`not_before = clock_timestamp() + make_interval(secs => ?)`, 값은 `retryBackoffSeconds(job.attempts + job.browserAttempts)`) → `ACKNOWLEDGE`. 소진 → `failExhausted` → ACKNOWLEDGE.
  - `transitionAnalysisJob`의 UPDATE에 `recovery_check_at=null`, claim lease UPDATE에도 `recovery_check_at=null`.
- [x] GREEN: 같은 명령 전체. 기존 lane별 기대값을 쓰던 테스트(`expired limits fail once using lane attempts and database deadline`, `third retryable attempt becomes failed retryable`, `expired thirty minute deadline prevents another attempt`)는 합산 규칙으로 기대값을 고치고, 테스트명도 의미에 맞게 바꾼다.
- [x] 커밋: `feature(server): generation 합산 재시도 예산과 Retryable ACK 적용`.

### Task 5: DNS 실패 분리와 차단 code (WORK-01 마무리)

**Files:** Create `app/extraction/BoundedResolver.kt`; modify `app/extraction/{UrlSafetyPolicy,GeneralExtractionProcessor}.kt`, `app/browser/BrowserWorkerService.kt`, `app/Main.kt`(resolver 소유); test `extraction/{UrlSafetyPolicyTest,BoundedResolverTest,GeneralExtractionProcessorTest}.kt`, `browser/BrowserWorkerServiceTest.kt`.

**Interfaces:**
- Produces:
  ```kotlin
  class DnsLookupFailed : RuntimeException("DNS lookup failed")
  class BoundedResolver(
      threads: Int = 4, queueCapacity: Int = 16,
      private val lookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
  ) : (String) -> List<InetAddress>, AutoCloseable   // WorkerExecution.remaining(최대 5초)만 기다림. 시간 초과·거부·UnknownHost·빈 결과 → DnsLookupFailed
  class UrlSafetyPolicy(private val resolve: (String) -> List<InetAddress> = BoundedResolver.default)
  ```
  `BoundedResolver.default`는 테스트·편의용 공유 인스턴스(daemon thread)이고, Worker 역할은 자기 인스턴스를 `resources.own`으로 소유한다.

- [x] RED `UrlSafetyPolicyTest`: resolver가 `UnknownHostException`·빈 목록을 내면 `DnsLookupFailed`, loopback/사설/메타데이터 주소는 `UnsafeUrlException`, scheme·port·userinfo·invalid URL도 `UnsafeUrlException`.
- [x] RED `BoundedResolverTest`: latch로 막힌 lookup에 대해 `remaining`이 다하면 `DnsLookupFailed`, threads+queue를 모두 채운 뒤 다음 호출은 즉시 `DnsLookupFailed`(포화 거부), close 뒤 호출 거부.
- [x] RED `GeneralExtractionProcessorTest`(DB): extract가 `DnsLookupFailed` → `Retryable`이며 pending failure 없음. `UnsafeUrlException` → `Terminal`이고 finish 뒤 item `analysis_failure_code='BLOCKED_ADDRESS'`·FAILED_TERMINAL. SafeHttpTransport의 다른 host DNS 요청(`unexpected DNS lookup`)도 Terminal.
- [x] RED `BrowserWorkerServiceTest`: render가 `DnsLookupFailed` → PARTIAL, 재시도 outbox 없음.
- [x] RED 실행: `b5_gradle test --tests 'app.extraction.*' --tests 'app.browser.BrowserWorkerServiceTest'`.
- [x] 구현: `validate`의 `runCatching { resolve(host) }`를 `DnsLookupFailed` 전파와 그 외 예외의 `DnsLookupFailed` 변환으로 바꾸고 빈 결과도 `DnsLookupFailed`. processor는 `catch (_: DnsLookupFailed) { return Retryable }`, `catch (_: UnsafeUrlException) { pending.saveFailure(claim, BLOCKED_ADDRESS); return Terminal }`(저장 실패 시 Stale).
- [x] GREEN: 같은 명령 + `--tests 'app.http.RealUrlPilotTest'`(skip 확인).
- [x] 커밋: `feature(server): DNS 실패를 재시도로 분리하고 차단 code 기록`.

### Task 6: EgressProxy (WORK-02)

**Files:** Create `app/browser/EgressProxy.kt`; test `browser/EgressProxyTest.kt`.

**Interfaces:**
- Produces:
  ```kotlin
  class EgressProxy(
      private val safety: UrlSafetyPolicy,
      private val connect: (InetAddress, Int, Duration) -> Socket = { address, port, timeout ->
          Socket().apply { connect(InetSocketAddress(address, port), timeout.toMillis().toInt()) } },
      maxConnections: Int = 32,
  ) : AutoCloseable {
      val port: Int                 // 127.0.0.1에 bind된 실제 port
      fun closeActiveConnections()  // render 종료 시 호출
  }
  ```
- 요청 처리: 첫 줄이 `CONNECT host:port HTTP/1.1`이면 port ∈ {443, 80}, `safety.validate("https://host/")`(80이면 `http://host/`)의 주소 중 첫 번째에 `connect` → `HTTP/1.1 200 Connection Established` → 양방향 복사. 첫 줄이 절대 URI(`GET http://host/path HTTP/1.1`)면 같은 검증 후 origin-form으로 바꿔 전달. 그 외 메서드/형식·차단·DNS 실패는 `HTTP/1.1 403 Forbidden` 후 종료. 헤더는 8KiB 상한. 연결 timeout과 socket read timeout은 30초 상한.

- [x] RED `EgressProxyTest`(Playwright 없음, 로컬 socket):
  - fake resolver가 `shop.test` → `93.184.216.34`를 내고 `connect`는 그 주소 요청을 로컬 echo 서버로 돌린다. CONNECT 성공·데이터 왕복과 `connect`가 받은 주소가 검증 주소와 같음을 확인한다. resolver 호출은 정확히 1회(재해석 없음).
  - rebinding: resolver가 첫 호출 공인 IP, 이후 `127.0.0.1`을 내도 proxy는 첫 검증 주소로만 연결한다.
  - resolver가 `127.0.0.1`·`10.0.0.1`을 내면 403, `connect` 미호출.
  - `CONNECT shop.test:8443`, `CONNECT [::1]:443`, `CONNECT localhost.:443`, `CONNECT SHOP.TEST:443`(대문자는 정상 처리), 절대 URI가 아닌 `GET /path`는 각각 기대대로 403/정상.
  - `closeActiveConnections()`·`close()` 뒤 열린 터널이 닫히고 새 연결이 거부된다.
- [x] RED 실행: `b5_gradle test --tests 'app.browser.EgressProxyTest'`.
- [x] 구현: `ServerSocket(0, 50, InetAddress.getLoopbackAddress())`, 고정 크기 daemon executor(`maxConnections`, 초과 시 즉시 close). host는 `lowercase().trimEnd('.')` 후 검증한다. IP literal host는 `UrlSafetyPolicy`의 literal 검사에 맡긴다.
- [x] GREEN: 같은 명령.
- [x] 커밋: `feature(server): browser egress pinning proxy 추가`.

### Task 7: browser runtime 역할

**Files:** Modify `app/browser/PlaywrightGateway.kt`, `app/http/WorkerRoutes.kt`, `app/RuntimeConfig.kt`, `app/Main.kt`; test `browser/PlaywrightGatewayTest.kt`, `RuntimeConfigTest.kt`, `HealthRouteTest.kt`, `http/WorkerRoutesTest.kt`, `browser/PlaywrightRealBrowserTest.kt`(신규, opt-in).

**Interfaces:**
- Consumes: Task 6 `EgressProxy`.
- Produces:
  ```kotlin
  enum class RuntimeRole(val defaultPoolSize: Int) { LOCAL_HEALTH(5), API(5), GENERAL_WORKER(2), BROWSER_WORKER(2), MAINTENANCE(2) }
  class PlaywrightGateway(private val safety: UrlSafetyPolicy, private val proxy: EgressProxy) {
      companion object { fun launchArguments(proxyPort: Int): List<String> }
      fun render(url: String): Metadata?
  }
  fun Route.generalWorkerRoute(run: (UUID, Int) -> WorkerDisposition)
  fun Route.browserWorkerRoute(run: (UUID, Int) -> WorkerDisposition)
  ```
  `launchArguments`: `--proxy-server=http://127.0.0.1:$port`, `--proxy-bypass-list=<-loopback>`, `--disable-quic`, `--force-webrtc-ip-handling-policy=disable_non_proxied_udp`. context는 `setServiceWorkers(ServiceWorkerPolicy.BLOCK)`, `setAcceptDownloads(false)`. 기존 route 단위 `canRequest` 검사와 최종 URL `validate`는 유지하고, render 종료 시 `proxy.closeActiveConnections()`.
  기존 `workerRoutes(general, browser)`는 두 함수에 위임하는 테스트 호환용으로 남긴다.

- [x] RED `PlaywrightGatewayTest`: `launchArguments(4567)`이 위 네 인자를 정확히 포함한다.
- [x] RED `RuntimeConfigTest`: `browser-worker`는 general-worker와 같은 필수 env를 요구하고 pool 2. `maintenance`는 DB 3개 필수, production은 Tasks 4개 필수, local은 Tasks 없이 허용, pool 2. 알 수 없는 role 거부.
- [x] RED `HealthRouteTest`: local `browser-worker` module이 `/internal/worker/browser`에 204(없는 job → Ignored), `/internal/worker/general`에 404. local `general-worker`는 `/internal/worker/browser`에 404.
- [x] RED 실행: `b5_gradle test --tests 'app.browser.PlaywrightGatewayTest' --tests 'app.RuntimeConfigTest' --tests 'app.HealthRouteTest' --tests 'app.http.WorkerRoutesTest'`.
- [x] 구현: Main의 GENERAL_WORKER/BROWSER_WORKER 조립을 공통 함수(`workerClassifier(env, source)`)로 묶어 OpenAI·budget·candidate 구성을 공유한다. BROWSER_WORKER는 `BoundedResolver`·`EgressProxy`·`WorkerExecution`을 `resources.own`하고 `BrowserWorkerService(source, BrowserRenderProcessor(source, gateway::render)::render, classifier::classify, execution)`를 `browserWorkerRoute`에 연결한다.
- [x] opt-in `PlaywrightRealBrowserTest`(`RUN_BROWSER_TESTS=1`): 로컬 HTTP 서버를 fake resolver의 공인 주소로 매핑한 proxy를 거쳐 실제 Chromium이 페이지를 렌더하고, `127.0.0.1` subresource 요청은 차단된다.
- [x] GREEN: 같은 명령. Chromium이 있으면 `RUN_BROWSER_TESTS=1 b5_gradle test --tests 'app.browser.PlaywrightRealBrowserTest'`도 실행하고, 없으면 미실행으로 기록한다.
- [x] 커밋: `feature(server): browser Worker 실행 역할 조립`.

### Task 8: TaskGateway 조회·예약 시각과 outbox 실패 격리 (OPS-01)

**Files:** Modify `app/tasks/{TaskGateway,CloudTasksGateway,OutboxDispatcher}.kt`; create `testutil/InMemoryTaskQueue.kt`; test `tasks/{OutboxDispatcherTest,CloudTasksGatewayTest}.kt`.

**Interfaces:**
- Produces:
  ```kotlin
  data class AnalysisTask(val name: String, val jobId: UUID, val generation: Int, val type: String, val scheduleAt: Instant? = null)
  enum class TaskStatus { ALIVE, MISSING }
  fun interface TaskGateway {
      fun create(task: AnalysisTask)
      /** Lookup failure throws; the default treats every lookup as failed so nothing is rescheduled. */
      fun status(task: AnalysisTask): TaskStatus = throw UnsupportedOperationException("Task lookup unavailable")
  }
  data class DispatchReport(val published: Int, val failed: Int)
  fun OutboxDispatcher.dispatchPending(limit: Int, deadlineNanos: Long = Long.MAX_VALUE): DispatchReport
  ```
  `fun interface`와 기본 `status`를 유지해 기존 lambda 사용처(`TaskGateway { … }`)를 바꾸지 않는다. spec §9의 "일반 interface로 변경" 문장은 이 방식으로 갱신한다(조회 불가 = 조회 실패라 재예약이 일어나지 않는 안전한 기본값).
  `InMemoryTaskQueue : TaskGateway`: `created: List<AnalysisTask>`, 같은 이름 재생성은 무시, `drop(name)`, `failLookups: Boolean`, `failCreates: Set<String>`, `deliver(name, worker: (UUID, Int) -> WorkerDisposition)`(ACK면 제거, RETRY면 유지).
- `CloudTasksGateway`: `scheduleAt`이 있으면 `Task.setScheduleTime`. `status`는 `client.getTask(TaskName)` 성공 ALIVE, `NotFoundException` MISSING. getTask 설정도 재시도 없이 총 5초(`clientSettings()`에 추가).

- [x] RED `OutboxDispatcherTest`:
  - `first failed event does not block later events in the same run`: 이벤트 3건 중 첫 번째만 `failCreates` → report(published=2, failed=1), 첫 번째는 미발행·lease 해제.
  - deadline 이미 지남 → 0건 시도.
  - `not_before`가 미래인 outbox → `scheduleAt` 전달, 과거면 null.
  - 이미 같은 이름 task가 있음(`AlreadyExists` 경로를 흉내 내는 gateway) → 발행됨 기록.
  - 기존 지정 발행·동시 lease 테스트는 변경 없이 통과.
- [x] RED `CloudTasksGatewayTest`: `buildTask`가 scheduleTime을 설정/미설정하고, `clientSettings()`의 getTask 총 timeout 5초·재시도 code 없음.
- [x] RED 실행: `b5_gradle test --tests 'app.tasks.*'`.
- [x] 구현: claim SQL에 `and not (e.id = any(?))`(이번 실행에서 실패한 ID 배열)를 추가하고 `e.not_before`를 함께 읽는다. `repeat` 대신 `while (published + failed < limit && System.nanoTime() < deadlineNanos)` 루프. 기존 `dispatchPending(limit): Int` 호출처는 `.published`로 바꾼다.
- [x] GREEN: 같은 명령 + `--tests 'app.wishlist.CreateWishlistItemServiceTest' --tests 'app.http.WishlistRoutesTest'`.
- [x] 커밋: `feature(server): outbox 발행 실패 격리와 task 조회 추가`.

### Task 9: RUNNING 순환과 PENDING 복구

**Files:** Create `app/analysis/{PendingJobRecovery,RecoveryCheckDeferral}.kt`; modify `app/analysis/AnalysisJobReconciler.kt`; test `analysis/PendingJobRecoveryTest.kt`(신규), `analysis/AnalysisJobReconcilerTest.kt`.

**Interfaces:**
- Consumes: Task 4 `failExhausted`·`hasRetryBudget(now)`, Task 8 `TaskGateway.status`·`AnalysisTask`.
- Produces:
  ```kotlin
  internal fun DataSource.deferRecoveryCheck(jobId: UUID, seconds: Long)  // 별도 tx, job만 for update skip locked, 잠겨 있으면 생략
  data class PendingRecoveryReport(val rescheduled: Int, val failed: Int, val rescheduleExhausted: Int,
      val cancelled: Int, val alive: Int, val lookupFailed: Int)
  class PendingJobRecovery(
      private val dataSource: DataSource, private val tasks: TaskGateway,
      private val batchSize: Int = 50, private val staleSeconds: Long = 300,
      private val aliveRecheckSeconds: Long = 300, private val lookupRetrySeconds: Long = 60,
      private val maxReschedules: Int = 3,
  ) { fun recover(deadlineNanos: Long = Long.MAX_VALUE): PendingRecoveryReport }
  ```
- 발견 SQL:
  ```sql
  select j.id, j.wishlist_item_id, j.generation, j.stage, j.updated_at, j.recovery_seq,
         o.id outbox_id, o.task_name, o.event_type
  from analysis_jobs j
  left join lateral (select id, task_name, event_type, published_at from outbox_events
                     where analysis_job_id = j.id order by created_at desc, id desc limit 1) o on true
  where j.stage in ('GENERAL_PENDING','BROWSER_PENDING')
    and j.updated_at <= clock_timestamp() - make_interval(secs => ?)
    and (j.recovery_check_at is null or j.recovery_check_at <= clock_timestamp())
    and (o.id is null or o.published_at is not null)
  order by j.recovery_check_at nulls first, j.updated_at, j.id
  limit ?
  ```
- 재예약 SQL(잠금·재검증 뒤): `update analysis_jobs set recovery_seq=recovery_seq+1, recovery_check_at=null, updated_at=clock_timestamp() where id=? returning recovery_seq` + outbox insert, task 이름 `${if (browser) "browser" else "analysis"}-$jobId-$generation-pending-$seq`, `not_before` null.
- RUNNING 발견은 기존 조건에 `(recovery_check_at is null or recovery_check_at <= clock_timestamp())`를 더하고 `order by recovery_check_at nulls first, lease_until, id`. skip-locked로 false가 된 후보와 예외 후보는 `deferRecoveryCheck(id, 60)`.

- [x] RED `PendingJobRecoveryTest`(실제 PostgreSQL + `InMemoryTaskQueue`):
  - 5분 미만 PENDING은 발견하지 않는다(`updated_at`을 SQL로 4분 59초 전/5분 1초 전).
  - 미발행 outbox가 있는 PENDING 60건 + 발행된 유실 PENDING 1건(가장 늦은 `updated_at`) → batch 50이어도 유실 1건이 재예약된다.
  - ALIVE → 무변경, `recovery_check_at ≈ now + 5분`. 조회 실패 → 무변경, `≈ now + 1분`, 상품 PROCESSING 유지.
  - MISSING → `recovery_seq` 1, 새 outbox 1건(이름 `…-pending-1`), stage·attempt·item version 불변.
  - outbox가 없는 PENDING → MISSING 취급 재예약.
  - `recovery_seq=3`에서 MISSING → `failExhausted`, report `rescheduleExhausted=1`. claim이 사이에 있었던 경우도 누적(`recovery_seq`를 claim이 바꾸지 않음).
  - 합산 예산 소진 상태 MISSING → `failExhausted`. 삭제·보관·수동 완료·generation 변경 → job CANCELLED, outbox 없음, item 불변.
  - 발견 후 잠금 전에 claim이 일어남(`pausedAnalysisCall`로 status 조회 중 claim) → 무변경.
  - 늦은 Worker Retryable finish가 status 조회 중 commit → `updated_at`·최신 outbox가 바뀌어 무변경, outbox는 finish의 1건만.
  - 두 `PendingJobRecovery`가 같은 후보를 동시에 처리(latch) → 재예약 1건, UNIQUE 위반 후보는 무변경으로 집계.
  - 105초 마감 뒤 중복 ACK → 원래 실행 Retryable outbox가 남아 있으면 발견하지 않는다(미발행) → 발행 뒤 정상 흐름.
- [x] RED `AnalysisJobReconcilerTest`: `locked first batch does not starve later candidates` — batch 2, 앞 2건의 item을 다른 connection으로 잠근 채 1차 실행 → 둘 다 `recovery_check_at` 미래, 2차 실행이 뒤 후보를 복구. 기존 `recovery bounds discovery and resumes remaining candidates on later scans` 유지.
- [x] RED 실행: `b5_gradle test --tests 'app.analysis.PendingJobRecoveryTest' --tests 'app.analysis.AnalysisJobReconcilerTest'`.
- [x] 구현: 후보별로 (1) status 조회를 transaction 밖에서 수행 (2) MISSING만 `lockAnalysisOwner`→`lockAnalysisItem`→`lockAnalysisJob`(skipLocked=true) 후 재검증 (3) 결과 처리. 후보 단위 try/catch로 실패를 격리하고 `CancellationException`/`InterruptedException`은 다시 던진다. UNIQUE 위반(`SQLState 23505`)은 rollback 후 무변경.
- [x] GREEN: 같은 명령 + `--tests 'app.analysis.*'`.
- [x] 커밋: `feature(server): 오래된 PENDING 복구와 순환 검사 추가`.

### Task 10: maintenance 실행 역할

**Files:** Create `app/maintenance/MaintenanceService.kt`, `app/http/MaintenanceRoutes.kt`; modify `app/budget/BudgetMaintenanceService.kt`, `app/RuntimeConfig.kt`, `app/Main.kt`; test `maintenance/MaintenanceServiceTest.kt`, `http/MaintenanceRoutesTest.kt`, `HealthRouteTest.kt`, `budget/BudgetMaintenanceServiceTest.kt`, `http/ApiRouteExposureTest.kt`(신규).

**Interfaces:**
- Consumes: Task 8 `OutboxDispatcher.dispatchPending(limit, deadlineNanos)`, Task 9 `PendingJobRecovery`, `AnalysisJobReconciler`, `BudgetMaintenanceService`.
- Produces:
  ```kotlin
  data class BudgetMaintenanceReport(val expiredReservations: Int, val deliveredAlerts: Int)   // 기존 MaintenanceReport 이름 변경
  @Serializable data class MaintenanceReport(
      val publishedEvents: Int, val failedEvents: Int, val recoveredRunning: Int,
      val pendingRescheduled: Int, val pendingFailed: Int, val pendingRescheduleExhausted: Int,
      val pendingCancelled: Int, val pendingAlive: Int, val lookupFailed: Int,
      val budget: BudgetMaintenanceReport?, val failedSteps: List<String>,
  )
  class MaintenanceService(
      private val dispatch: (Int, Long) -> DispatchReport, private val reconcile: (Long) -> Int,
      private val recoverPending: (Long) -> PendingRecoveryReport, private val budget: () -> BudgetMaintenanceReport,
      private val totalMillis: Long = 50_000, private val nanoTime: () -> Long = System::nanoTime,
  ) { fun runOnce(): MaintenanceReport }
  fun Route.maintenanceRoutes(service: MaintenanceService)   // POST /internal/maintenance/run → 200/500 + JSON report
  fun Route.apiRoutes(...)                                    // Main의 API 공개 route 묶음 추출
  ```
  `Application.module(env, resources, taskGateway: TaskGateway? = null)`: MAINTENANCE는 주입 gateway → 설정이 있으면 `CloudTasksGateway` → 없으면(local) 모든 호출이 실패하는 gateway(발행 실패·조회 실패로 무변경) 순서로 고른다. route 실행은 `resources.runIfOpen` 안에서만.

- [x] RED `MaintenanceServiceTest`(단위): 단계 순서 dispatch→reconcile→pending→budget, 두 번째 단계 예외에도 3·4단계 실행·`failedSteps=["reconcile"]`·`budget` 값 존재, budget 예외면 `budget=null`. 남은 시간이 각 단계에 감소하며 전달되고, 50초가 지나면 남은 단계는 실행하지 않고 `failedSteps`에 `timeout:<step>`을 남긴다.
- [x] RED `MaintenanceRoutesTest`: 정상 200, 실패 단계 있으면 500, 둘 다 JSON에 `publishedEvents` 등 포함. 로그에 예외 메시지가 없음(`ListAppender` 대신 고정 문장 assertion은 로그 함수 주입으로 확인).
- [x] RED `HealthRouteTest`: local `maintenance` module(+`InMemoryTaskQueue` 주입)이 `/internal/maintenance/run` 200, `/internal/worker/general` 404.
- [x] RED `ApiRouteExposureTest`: `routing { apiRoutes(...) }`에 `/internal/worker/general`·`/internal/worker/browser`·`/internal/maintenance/run` POST가 404.
- [x] RED `BudgetMaintenanceServiceTest`: 이름 변경 후 기존 검증 유지.
- [x] RED 실행: `b5_gradle test --tests 'app.maintenance.*' --tests 'app.http.MaintenanceRoutesTest' --tests 'app.http.ApiRouteExposureTest' --tests 'app.HealthRouteTest' --tests 'app.budget.*'`.
- [x] 구현: Main의 API routing 블록을 `apiRoutes`로 옮기고, MAINTENANCE 분기에서 pool·Tasks client를 소유해 `MaintenanceService`를 조립한다. 단계 예외 로그는 `"Maintenance step failed step={} exceptionType={}"`.
- [x] GREEN: 같은 명령.
- [x] 커밋: `feature(server): maintenance 실행 역할과 단계별 복구 연결`.

### Task 11: local 전체 흐름

**Files:** Create `server/src/test/kotlin/app/AnalysisRuntimeFlowTest.kt`.

**Interfaces:**
- Consumes: 앞 Task 전부. AI는 기존 `LocalClassificationPathTest`처럼 `AiClassificationService(source, LlmBudgetService(...), candidateProvider, fakeClassify)`, fetch는 `MetadataFixtures`의 URL→HTML map, browser render는 fixture HTML을 `HttpMetadataExtractor`로 파싱하는 fake(Playwright 없음).

- [x] RED `create to ready through general and browser with fake queue`: API `POST /v1/wishlist-items`(testApplication) → `InMemoryTaskQueue`에 general task → `deliver`로 general Worker 실행 → fixture가 제목 없는 HTML이라 NeedsBrowser → `MaintenanceService.runOnce()`가 browser outbox 발행 → `deliver`로 browser Worker → READY. `GET /v1/wishlist-items/{id}` 응답의 brand·price·currency·merchant·metadataCheckedAt 값 확인.
- [x] RED `lost general task is recovered by maintenance`: 생성 후 queue에서 `drop` → `updated_at`을 5분 전으로 이동 → `runOnce()`가 `…-pending-1` 재예약 → 발행·전달 → READY. attempt 합계 1.
- [x] RED `retryable fault acknowledges and backs off`: 첫 general 실행이 인프라 예외 → 204 → retry outbox `not_before` 10초 → `runOnce()` 발행 task의 `scheduleAt` 존재 → 전달 → READY.
- [x] RED `exhausted generation becomes failed retryable with read metadata`: Complete 저장 뒤 AI Retryable 3회 → FAILED_RETRYABLE, 응답 product에 읽은 title·가격과 `metadataCheckedAt`.
- [x] RED 실행: `b5_gradle test --tests 'app.AnalysisRuntimeFlowTest'`. 앞 Task가 완료돼 있으면 RED가 통과할 수 있다. 이 경우 각 사례의 핵심 assertion을 일시적으로 반대로 바꿔 실패를 한 번 확인한 뒤 되돌리고, 그 사실을 이력에 기록한다.
- [x] GREEN: 같은 명령.
- [x] 커밋: `feature(server): B5 분석 runtime 전체 흐름 테스트 추가`.

### Task 12: 문서·독립 리뷰·전체 회귀·PR

**Files:** Modify `docs/architecture/wishlist-item-state-api.md`(가격 단위·통화 확정), `docs/architecture/server/{wishlist-item-read-api,extraction-pipeline,analysis-pending-recovery,runtime-resources,overview,wishlist-state-persistence,mvp-api-inventory,mvp-api-implementation-order,INDEX}.md`, `docs/superpowers/specs/2026-10-09-b5-analysis-runtime-recovery-design.md`(§9 TaskGateway 기본 구현 반영·상태), 이 계획의 상태; create `docs/history/architecture/server/b5-analysis-runtime-implementation-2026-10-09.md`; `docs/learning/server/q-and-a/`에 pinning proxy·generation 예산 Q&A와 INDEX.

- [x] 문서: 상태 계약에 price 단위(원래 통화의 decimal, scale≤4)·ISO 4217 대문자·쌍 규칙과 `metadataCheckedAt` 의미를 확정한다. read API의 "B5까지 null" 문구를 실제 값으로 바꾼다. `analysis-pending-recovery.md`의 503 유지 문단을 ACK·`not_before`·재예약 상한으로 교체한다. `wishlist-state-persistence.md:74`의 "RETRY/HTTP 503"과 `mvp-api-inventory.md:146`의 "인프라 retry 503"을 갱신하고 inventory의 WORK-01/WORK-02/OPS-01 상태를 구현으로 바꾼다. 구현 순서 문서의 B5 상태와 "retry 예산·deadline·즉시 발행 제한" 미결정 행을 해결로 표시한다. learning Q&A 두 건(DNS rebinding과 pinning proxy, generation 합산 예산과 Retryable ACK)을 추가한다.
- [x] 독립 리뷰: 구현에 참여하지 않은 reviewer가 spec·계획과 `origin/develop...HEAD` diff를 대조한다. 확인 범위는 잠금 순서·transaction 경계, 예산 판정의 모든 호출처, metadata 병합·확인 시각, proxy 우회 경로, outbox 실패 격리, PENDING 재검증·UNIQUE, 역할별 route 노출, 로그 누출이다.
- [x] 보완: 지적마다 재현 테스트를 먼저 실패시킨 뒤 최소 수정한다(`bugfix: …`). 재현되지 않는 지적은 근거와 함께 이력에 남긴다.
- [x] 전체 회귀: `b5_gradle test --rerun-tasks`. exit 0과 JUnit XML 합산 tests/pass/fail/error/skip을 이력에 기록한다. RealUrlPilot·opt-in browser skip은 통과로 세지 않는다.
- [x] 문서 검증: `git diff --check`, 변경 문서의 상대 링크 존재 확인, INDEX 갱신.
- [x] 커밋: `docs: B5 계약과 구현 이력 기록`. author/committer 확인, clean 확인.
- [x] PR: `git push -u origin server/b5-analysis-runtime-recovery` 후 `gh pr create --base develop`. 본문에 범위·결정 6건·검증 결과·B11 이관 항목을 요약하고 끝에 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`를 붙인다. push·PR 생성 직전에 사용자 확인을 받는다.

## 자체 검토와 실행 인계

spec 대응: §1 역할은 Task 7·10, §2 예산·소진·Worker 응답은 Task 4(NeedsBrowser 분기·`failExhausted`·`not_before` 포함)와 Task 9(재예약 상한), §3 추출은 Task 2, §4 저장·병합·조회는 Task 1·3, §5 URL·DNS는 Task 5, §6 egress는 Task 6·7, §7 outbox는 Task 8, §8 maintenance·순환·PENDING은 Task 9·10, §9 TaskGateway는 Task 8, §10 관측은 Task 9·10의 report와 로그, §11 검증은 각 Task RED와 Task 11·12, §12 문서는 Task 12. Review Focus 다섯 항목은 Task 9·2·3·6/7·8의 RED에 넣었다. 타입 이름(`hasRetryBudget(now)`, `failExhausted`, `PendingMetadata.pageRead`, `DispatchReport`, `PendingRecoveryReport`, `MaintenanceReport`, `BudgetMaintenanceReport`, `TaskStatus`)은 모든 Task에서 같다.

**권장 실행 방식: Native.** Task 3→4→9→10이 같은 병합 helper·예산 함수·report 타입을 연속으로 확장하므로, 작업마다 새 implementer를 투입하면 인터페이스를 다시 읽는 비용이 크다. 이 세션에서 순서대로 구현하고 마지막에 독립 reviewer가 전체 변경을 검토한다.
