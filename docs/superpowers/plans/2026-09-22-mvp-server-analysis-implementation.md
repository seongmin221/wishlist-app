# MVP 서버 분석 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** URL 저장부터 안전한 비동기 추출·AI 분류·browser fallback·운영 검증까지 MVP 서버를 구현한다.

**Architecture:** 단일 Kotlin/JVM Ktor codebase 안에서 API, outbox dispatcher, 일반 Worker, browser Worker를 패키지와 실행 entry point로 분리한다. PostgreSQL의 `AnalysisJob`, `OutboxEvent`, 예산 reservation이 신뢰성의 기준이며 Cloud Tasks와 OpenAI는 교체 가능한 adapter 뒤에 둔다.

**Tech Stack:** Kotlin/JVM, Gradle, Ktor, PostgreSQL, Flyway, Testcontainers PostgreSQL, Cloud Run, Cloud Tasks, OpenAI Responses API, Playwright

**Spec:** `docs/superpowers/specs/2026-09-22-mvp-server-operation-and-evaluation-design.md`

## Global Constraints

- Ktor API와 Worker는 `server/` 단일 Kotlin/JVM build에 둔다. runtime service는 Flyway migration을 실행하지 않는다.
- API는 1 vCPU·1 GiB·concurrency 20·min 1·max 3, 일반 Worker는 concurrency 1·min 0·max 5, browser Worker는 2 vCPU·2 GiB·concurrency 1·min 0·max 2를 시작값으로 쓴다.
- 일반·browser Queue는 각각 초당 1 dispatch이며 동시 dispatch는 5·2다. Worker timeout은 90초, Task deadline은 105초다.
- 모든 외부 task와 LLM 결과 반영은 owner·generation·lifecycle·단계 claim을 DB transaction에서 재검증한다.
- Cloud Tasks retry는 `maxAttempts=3`, `minBackoff=10s`, `maxBackoff=600s`, `maxRetryDuration=1800s`다.
- release LLM 요청은 최대 입력 1,000·출력 80 token, `reasoning.effort=none`, `store=false`다. 실제 model snapshot·가격표 version을 기록한다.
- 일 1,000원·월 10,000원의 LLM hard cap은 DB reservation으로 강제하며 80%에 알린다.
- raw prompt·response, OpenAI key, DB credential을 로그·DB·소스에 저장하지 않는다.
- local은 Docker PostgreSQL, Firebase Auth Emulator, in-memory Task/OpenAI fake, fixture HTTP server를 사용한다.
- 각 task는 failing test → 최소 구현 → 통과 확인 → 해당 파일만 stage → 커밋 순서로 진행한다.

## Review Focus

- 동일한 `Idempotency-Key` 재전송은 하나의 항목만 만들고, 다른 URL 재사용은 `409 IDEMPOTENCY_KEY_REUSED`가 된다. (Task 2)
- 삭제 또는 새 generation 뒤 도착한 일반·browser task는 결과를 쓰지 않고 2xx로 끝난다. (Task 3, 4)
- OpenAI 전송 뒤 Worker가 종료되어도 만료 `IN_FLIGHT` reservation은 최대 비용으로 정산되어 cap을 넘지 않는다. (Task 5)
- 대상 페이지의 차단·navigation timeout은 browser 재시도가 아니라 `PARTIAL` 2xx가 된다. (Task 4)
- holdout의 사례별 점수는 개발자에게 노출되지 않으며 retired holdout은 다시 입력으로 쓰이지 않는다. (Task 6)

---

### Task 1: 서버 build·실행 경계와 빈 DB migration을 만든다

**Files:**
- Create: `server/settings.gradle.kts`, `server/build.gradle.kts`, `server/gradlew`, `server/gradle/wrapper/*`
- Create: `server/src/main/kotlin/app/Main.kt`, `server/src/main/resources/application.conf`
- Create: `server/src/main/resources/db/migration/V1__initial_analysis_tables.sql`
- Create: `server/src/test/kotlin/app/DatabaseMigrationTest.kt`
- Create: `server/docker-compose.local.yml`, `server/.env.example`

**Interfaces:**
- Produces `./gradlew test` and a `DatabaseFactory.dataSource()` used by every repository.
- Produces Flyway-owned schema namespaces `wishlist_items`, `analysis_jobs`, `outbox_events`, `llm_budget_windows`, `llm_budget_reservations`.

- [ ] **Step 1: Write the failing empty-database migration test.**

```kotlin
@Test fun `all Flyway migrations apply to an empty postgres database`() {
    PostgreSqlContainer().use { db ->
        db.start()
        assertDoesNotThrow { migrate(db.jdbcUrl, db.username, db.password) }
        assertEquals(1, queryInt(db, "select count(*) from flyway_schema_history where success"))
    }
}
```

- [ ] **Step 2: Run the migration test and confirm it fails because the Gradle build and migration are absent.**

Run: `cd server && ./gradlew test --tests app.DatabaseMigrationTest`

Expected: compilation or test-discovery failure.

- [ ] **Step 3: Create the Gradle Kotlin/JVM Ktor project and V1 migration.**

`V1__initial_analysis_tables.sql` must create UUID primary keys, UTC timestamps, foreign keys, and these unique/index constraints: `(owner_id, client_submission_id)` unique on `wishlist_items`; `(wishlist_item_id, generation)` unique on `analysis_jobs`; unique `task_name` and dispatch lease index on `outbox_events`; `(window_type, window_start)` unique on `llm_budget_windows`; unique `request_id` and expired-reservation index on `llm_budget_reservations`.

```kotlin
fun Application.module() {
    install(ContentNegotiation) { json() }
    routing { get("/health") { call.respondText("ok") } }
}
```

`docker-compose.local.yml` must expose only local PostgreSQL with a named volume and read credentials from `.env`; `.env.example` contains non-secret sample values only.

- [ ] **Step 4: Run the migration test and health-route test.**

Run: `cd server && ./gradlew test --tests app.DatabaseMigrationTest --tests app.HealthRouteTest`

Expected: PASS.

- [ ] **Step 5: Commit the bootstrap.**

```text
feature(server): 서버 분석 기반 구성

Ktor 실행 환경과 Flyway 초기 스키마를 추가한다.
```

### Task 2: 저장 API와 원자적 AnalysisJob·Outbox 생성을 구현한다

**Files:**
- Create: `server/src/main/kotlin/app/wishlist/WishlistItem.kt`, `CreateWishlistItemService.kt`, `WishlistItemRepository.kt`
- Create: `server/src/main/kotlin/app/outbox/OutboxEvent.kt`, `OutboxRepository.kt`
- Create: `server/src/main/kotlin/app/http/WishlistRoutes.kt`, `ApiError.kt`
- Create: `server/src/test/kotlin/app/wishlist/CreateWishlistItemServiceTest.kt`
- Create: `server/src/test/kotlin/app/http/WishlistRoutesTest.kt`

**Interfaces:**
- Consumes `DatabaseFactory.dataSource()` from Task 1.
- Produces `CreateWishlistItemService.create(ownerId: UUID, key: UUID, sourceUrl: String): CreateResult` and one `GENERAL_ANALYSIS` outbox event in the same transaction.

- [ ] **Step 1: Write failing integration tests for first create, replay, and reused key.**

```kotlin
@Test fun `same owner and key returns original item without a second job`() { /* assert 201 then 200, one item/job/event */ }
@Test fun `same owner and key with different url returns idempotency conflict`() { /* assert 409 IDEMPOTENCY_KEY_REUSED */ }
@Test fun `two concurrent creates leave one item job and outbox event`() { /* submit both and count rows */ }
```

- [ ] **Step 2: Run the three tests and confirm they fail.**

Run: `cd server && ./gradlew test --tests app.wishlist.CreateWishlistItemServiceTest --tests app.http.WishlistRoutesTest`

Expected: FAIL because the route and service do not exist.

- [ ] **Step 3: Implement transaction boundaries and stable API response.**

Create `WishlistItem(PROCESSING, ACTIVE)` plus `AnalysisJob(generation=1, stage=GENERAL_PENDING, attemptCount=0)` and a `GENERAL_ANALYSIS` `OutboxEvent` in exactly one JDBC transaction. Validate only HTTP/HTTPS public URLs; return `422 INVALID_URL` before writing rows. Map replay to `200` with `Idempotency-Replayed: true`; map first creation to `201` with `Location`.

- [ ] **Step 4: Re-run Task 2 tests, then run all server tests.**

Run: `cd server && ./gradlew test`

Expected: PASS.

- [ ] **Step 5: Commit the API slice.**

```text
feature(server): 비동기 상품 저장 API 구현

멱등 저장과 분석 작업·outbox의 원자적 생성을 추가한다.
```

### Task 3: Outbox dispatcher와 일반 Worker의 claim·재시도를 구현한다

**Files:**
- Create: `server/src/main/kotlin/app/tasks/TaskGateway.kt`, `CloudTasksGateway.kt`, `OutboxDispatcher.kt`
- Create: `server/src/main/kotlin/app/analysis/AnalysisJobRepository.kt`, `GeneralWorkerService.kt`, `RetryPolicy.kt`
- Create: `server/src/main/kotlin/app/http/WorkerRoutes.kt`
- Create: `server/src/test/kotlin/app/tasks/OutboxDispatcherTest.kt`, `server/src/test/kotlin/app/analysis/GeneralWorkerServiceTest.kt`

**Interfaces:**
- Consumes `OutboxEvent` and `AnalysisJob` from Task 2.
- Produces `dispatchPending(limit: Int)` and `runGeneral(jobId: UUID, generation: Int): WorkerDisposition`.

- [ ] **Step 1: Write failing tests for lease dispatch, duplicate delivery, deadline exhaustion, deletion, and stale generation.**

```kotlin
@Test fun `dispatcher uses deterministic task name and marks event published only after gateway success`() { }
@Test fun `stale or deleted task returns acknowledge without writing result`() { }
@Test fun `third retryable attempt stores FAILED_RETRYABLE and acknowledges`() { }
```

- [ ] **Step 2: Run the Worker and dispatcher tests and confirm they fail.**

Run: `cd server && ./gradlew test --tests app.tasks.OutboxDispatcherTest --tests app.analysis.GeneralWorkerServiceTest`

Expected: FAIL because dispatcher and worker are absent.

- [ ] **Step 3: Implement DB claim before side effects.**

Use `SELECT ... FOR UPDATE SKIP LOCKED` (or an equivalent conditional update) to lease an outbox event. Give Cloud Tasks deterministic names `analysis-{jobId}-{generation}`. Before processing, atomically claim only `GENERAL_PENDING → GENERAL_RUNNING` after validating owner, generation, and `ACTIVE` lifecycle. A stale claim returns HTTP 204/2xx. Before returning non-2xx for retryable failure, enforce `attemptCount < 3` and `firstAttemptAt + 30 minutes`; otherwise persist `FAILED_RETRYABLE` and return 2xx.

- [ ] **Step 4: Run focused and full tests.**

Run: `cd server && ./gradlew test`

Expected: PASS.

- [ ] **Step 5: Commit dispatcher and Worker reliability.**

```text
feature(server): 분석 작업 발행과 재시도 구현

outbox 복구와 Worker 중복·재시도 제한을 추가한다.
```

### Task 4: 안전한 추출과 durable browser fallback을 구현한다

**Files:**
- Create: `server/src/main/kotlin/app/extraction/UrlSafetyPolicy.kt`, `HttpMetadataExtractor.kt`, `Metadata.kt`
- Create: `server/src/main/kotlin/app/browser/BrowserWorkerService.kt`, `PlaywrightGateway.kt`
- Modify: `server/src/main/kotlin/app/analysis/GeneralWorkerService.kt`, `app/tasks/OutboxDispatcher.kt`
- Create: `server/src/test/kotlin/app/extraction/UrlSafetyPolicyTest.kt`, `server/src/test/kotlin/app/browser/BrowserWorkerServiceTest.kt`

**Interfaces:**
- Produces `ExtractionResult.Complete | NeedsBrowser | Partial` and `runBrowser(jobId, generation)`.
- `NeedsBrowser` causes `BROWSER_PENDING`, `browserAttempted=true`, and `BROWSER_ANALYSIS` outbox insertion in one transaction.

- [ ] **Step 1: Write failing tests for blocked addresses, redirect validation, atomic browser handoff, and browser error classification.**

```kotlin
@Test fun `private redirect target is rejected before fetch`() { }
@Test fun `needs browser stores stage attempted flag and outbox together`() { }
@Test fun `site navigation timeout stores PARTIAL then acknowledges task`() { }
@Test fun `database commit failure leaves no terminal state and requests retry`() { }
```

- [ ] **Step 2: Run the extraction and browser tests and confirm they fail.**

Run: `cd server && ./gradlew test --tests app.extraction.UrlSafetyPolicyTest --tests app.browser.BrowserWorkerServiceTest`

Expected: FAIL because extraction and browser services are absent.

- [ ] **Step 3: Implement ordered extraction and browser state machine.**

Accept only HTTP(S), reject loopback/private/link-local/metadata addresses, revalidate every redirect, cap redirect count/body size/timeouts, and parse JSON-LD → OpenGraph → HTML metadata → heuristic. Only incomplete metadata with `browserAttempted=false` can create browser work. Browser Worker atomically claims `BROWSER_PENDING → BROWSER_RUNNING`. Site block, navigation timeout, target DNS/connection errors, and insufficient metadata persist `PARTIAL` then return 2xx. Only failure to persist terminal state from DB/runtime infrastructure returns non-2xx.

- [ ] **Step 4: Run tests with fixture HTTP server and all server tests.**

Run: `cd server && ./gradlew test`

Expected: PASS.

- [ ] **Step 5: Commit extraction and browser isolation.**

```text
feature(server): 안전한 상품 추출과 browser fallback 구현

SSRF 방어와 durable browser 작업 전이를 추가한다.
```

### Task 5: OpenAI adapter와 장애 복구 가능한 예산 guard를 구현한다

**Files:**
- Create: `server/src/main/kotlin/app/ai/ClassificationGateway.kt`, `OpenAiResponsesGateway.kt`, `ClassificationSchema.kt`
- Create: `server/src/main/kotlin/app/budget/LlmBudgetService.kt`, `BudgetReconciler.kt`, `PriceTable.kt`
- Modify: `server/src/main/kotlin/app/analysis/GeneralWorkerService.kt`, `server/src/main/kotlin/app/analysis/AnalysisJobRepository.kt`
- Create: `server/src/test/kotlin/app/budget/LlmBudgetServiceTest.kt`, `server/src/test/kotlin/app/ai/ClassificationGatewayTest.kt`

**Interfaces:**
- Produces `BudgetReservation(requestId, state, maximumCost)` and `ClassificationResult.Assigned | Abstained | Unusable | Retryable`.
- `reserveBeforeCall(job, requestId, priceTable): ReserveResult` is the only route to an OpenAI call.

- [ ] **Step 1: Write failing tests for concurrent cap, response settlement, expired reservation, schema rejection, and price-version guard.**

```kotlin
@Test fun `only one concurrent reservation can consume the remaining ceiling`() { }
@Test fun `expired reserved is released but expired in flight settles maximum cost`() { }
@Test fun `unknown purpose id becomes PARTIAL without retry`() { }
@Test fun `unapproved price table version cannot be selected for production`() { }
```

- [ ] **Step 2: Run the budget and adapter tests and confirm they fail.**

Run: `cd server && ./gradlew test --tests app.budget.LlmBudgetServiceTest --tests app.ai.ClassificationGatewayTest`

Expected: FAIL because the budget and OpenAI adapters are absent.

- [ ] **Step 3: Implement reservation and classification flow.**

In one transaction conditionally reserve both daily and monthly windows using the maximum cost for 1,000 input and 80 output tokens; persist request UUID, job/generation, model snapshot, price-table version, `RESERVED`, and a 120-second lease. Immediately before network transmission change it to `IN_FLIGHT`. On a received response settle actual usage and release the difference. The one-minute reconciler releases expired `RESERVED`; it settles expired `IN_FLIGHT` at maximum cost. Reservation refusal writes `PARTIAL/AI_BUDGET_EXCEEDED` and makes no network request. Validate Structured Output IDs against the saved candidate snapshot; refusal/content-filter/invalid ID writes `PARTIAL`, retryable transport/429/5xx follows Task 3, and 4xx configuration errors write `FAILED_TERMINAL`.

- [ ] **Step 4: Run focused tests and full test suite.**

Run: `cd server && ./gradlew test`

Expected: PASS.

- [ ] **Step 5: Commit AI and budget control.**

```text
feature(ai): AI 분류 예산 보호 구현

구조화 분류와 장애 복구 가능한 예산 예약을 추가한다.
```

### Task 6: 평가·배포·관측 자산을 구현하고 출시 검증을 자동화한다

**Files:**
- Create: `ai/taxonomy/v1.json`, `ai/prompts/classification-v1.md`, `ai/evaluations/README.md`, `ai/evaluations/development.jsonl`
- Create: `server/src/test/kotlin/app/evaluation/DevelopmentEvaluatorTest.kt`, `server/src/test/kotlin/app/load/AnalysisLoadScenarioTest.kt`
- Create: `infra/cloudrun/api.yaml`, `infra/cloudrun/worker.yaml`, `infra/cloudrun/browser-worker.yaml`, `infra/cloudtasks/queues.yaml`
- Create: `.github/workflows/server.yml`, `.github/workflows/deploy-production.yml`
- Modify: `docs/architecture/server/overview.md`, `docs/architecture/ai/overview.md`

**Interfaces:**
- Produces versioned taxonomy/prompt metadata, development-only evaluation command, and production deployment configuration.
- Holdout labels live outside the repository and are readable only by the independent evaluator; the command returns only release pass/fail.

- [ ] **Step 1: Write failing tests for development score calculations and load acceptance boundaries.**

```kotlin
@Test fun `purpose false-link denominator contains only assigned predictions`() { }
@Test fun `multiple allowed purpose ids are accepted`() { }
@Test fun `load report fails when backlog recovery exceeds two minutes`() { }
```

- [ ] **Step 2: Run evaluator and load tests and confirm they fail.**

Run: `cd server && ./gradlew test --tests app.evaluation.DevelopmentEvaluatorTest --tests app.load.AnalysisLoadScenarioTest`

Expected: FAIL because evaluator and scenario runner are absent.

- [ ] **Step 3: Implement evaluation, local fakes, deployment manifests, and CI.**

Keep 120 labeled development metadata snapshots in `ai/evaluations/development.jsonl`; do not commit holdout content or labels. The evaluator must calculate category exact accuracy, assigned-purpose false-link rate, purpose allowed-ID link rate, and abstain rate. The holdout runner accepts an evaluator-controlled input path and prints only `RELEASE_PASS` or `RELEASE_FAIL`. Add fake HTTP/OpenAI scenarios for 200 general requests, 20 browser requests, and 60 mixed requests; assert the specification’s success, p95, retry, duplicate, concurrency, and backlog thresholds. CI must run Flyway validation and server tests. Production workflow must use GitHub OIDC, run `flyway validate → migrate`, then deploy; it must not run migrations from application startup.

- [ ] **Step 4: Run verification commands.**

Run: `cd server && ./gradlew test && ./gradlew flywayValidate`

Run: `ruby -e 'bad=[]; Dir["docs/**/*.md"].each{|f| File.read(f).scan(/\[[^\]]*\]\(([^)]+)\)/).flatten.each{|u| next if u =~ /^(https?:|#)/; p=File.expand_path(u.split("#",2)[0],File.dirname(f)); bad << "#{f}: #{u}" unless File.exist?(p)}}; abort bad.join("\n") unless bad.empty?; puts "all local links resolve"'`

Expected: all commands pass; the load report satisfies the specified gates before production deployment.

- [ ] **Step 5: Commit release controls.**

```text
feature(server): 출시 검증과 배포 자동화 구성

AI 평가, 부하 시험, migration 배포 절차를 추가한다.
```

## Plan Self-Review

- Spec coverage: Task 1 covers Ktor/Flyway/local foundation; Task 2 covers asynchronous creation; Task 3 covers outbox, idempotency and retry; Task 4 covers extraction and browser durability; Task 5 covers snapshot AI and hard budget cap; Task 6 covers evaluation, load gates, IAM-based deployment and observability inputs.
- Explicit gaps deferred by product scope: Firebase authentication endpoint implementation, category/purpose CRUD, archive, mobile clients, and manual completion UI are existing MVP concerns but are not part of this server-analysis design.
- Review focus coverage: Task 2 tests idempotency; Tasks 3–4 test stale and browser error delivery; Task 5 tests reservation recovery; Task 6 tests holdout isolation and load thresholds.
- Type consistency: Task 2 introduces `AnalysisJob`; Tasks 3–5 consume `jobId` and `generation`; Task 4 alone creates browser outbox events; Task 5 alone authorizes LLM transmission through `reserveBeforeCall`.
- Placeholder scan: this plan contains no deferred implementation markers.
