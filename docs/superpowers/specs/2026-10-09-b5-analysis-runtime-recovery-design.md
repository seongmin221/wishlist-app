# B5 비동기 분석과 운영 복구 설계

> 2026-10-09 · **설계 대화 승인 · spec 검토 대기** · 제품·운영 정책 확정

## 목표와 범위

일반 Worker → browser fallback → 결과 반영 → 중단 복구를 실제 runtime 역할로 조립한다. [구현 순서](../../architecture/server/mvp-api-implementation-order.md#b5--비동기-분석과-운영-복구)의 내부 순서 WORK-01 후보/metadata 공급 → WORK-02 browser runtime → OPS-01 maintenance runtime·outbox/reconciler/budget 연결을 따른다. 새 제품 API는 없다.

[B5 제품·운영 결정](../../history/product-planning/mvp/decisions/b5-analysis-runtime-policy-2026-10-09.md)은 2026-10-09 사용자 답변으로 확정했다. 착수 대조는 [B5 착수 기록](../../history/architecture/server/b5-runtime-recovery-preparation-2026-10-09.md)에 있다.

**범위 밖:** 클라우드 IAM·OIDC invoker·Scheduler 실호출·배포 egress firewall(B11), Product cache·중복 후보(B9 이후), C4 클라이언트, 사용자 재분석 API(B7). client/와 design/handoff/는 수정하지 않는다.

**통과 조건:** 새 역할 config와 API의 internal route 비노출, outbox 중복 발행·process 중단·lease 만료 회복, fallback deadline·generation 예산 보존, 이전 실행 ACK, 사용자 값 보호, local runtime + fake queue 전체 흐름, 실제 PostgreSQL 경합·upgrade.

## 확정 결정 요약

| # | 결정 |
| --- | --- |
| 1 | 재시도 예산은 generation 전체 **general+browser 실행 합산 최대 3회**, 30분은 두 lane 중 가장 이른 첫 시도부터. browser fallback 첫 실행도 1회로 센다 |
| 2 | 가격이 범위·offer별로 다르면 null. merchant는 `offers.seller.name` → `og:site_name`, 없으면 null. `metadataCheckedAt`은 페이지를 읽어 metadata를 최종 반영한 시각 |
| 3 | 페이지 canonical은 최종 URL과 같은 등록 도메인(eTLD+1)일 때만 채택. fragment·승인 tracking query만 제거 |
| 4 | 발행된 PENDING의 queue 확인은 정체 5분부터. 조회 실패 시 1분, task 살아 있음 확인 시 5분 뒤 재검사 |
| 5 | 일반 lane의 DNS 조회 실패는 Retryable. 차단 주소·scheme·port는 FAILED_TERMINAL |

시간 값은 추가 실행을 막는 상한이며 분석 단계 순서(general → 필요 시 browser 1회 → 반영)나 작업 순서를 바꾸지 않는다.

## 현재 코드와의 차이

| 영역 | 현재 | B5 변경 |
| --- | --- | --- |
| 역할 | `RuntimeRole` = LOCAL_HEALTH/API/GENERAL_WORKER. browser route는 조건부, Main 미연결 | `BROWSER_WORKER`, `MAINTENANCE` 추가 |
| 예산 | `hasRetryBudget(lane)`가 lane별 3회·30분 | 합산 판정 단일 함수 |
| metadata | title/description/image/최종 fetch URL | brand·price/currency·merchant·canonical 정책·metadataCheckedAt |
| URL | DNS 실패도 `UnsafeUrlException` → Terminal, failure code 없음 | DNS 실패 분리, 차단은 `BLOCKED_ADDRESS` 기록 |
| browser | route 단위 URL 검사, Chromium이 DNS를 별도 조회 | 프로세스 내 pinning proxy 경유 |
| outbox | `dispatchPending`이 첫 실패에서 batch 중단 | 실패 이벤트만 제외하고 계속 |
| reconciler | RUNNING만, 정렬 첫 batch 고정 | `recovery_check_at` 순환 + PENDING 복구 |
| queue | `TaskGateway.create`만 | `status` 조회 추가 |

## 1. 실행 역할

`APP_ROLE` 허용값: `api`, `general-worker`, `browser-worker`, `maintenance`.

| 역할 | route | 소유 자원 | pool 기본 | 필수 env |
| --- | --- | --- | ---: | --- |
| api | 공개 API, `/health` | pool·Firebase·Tasks client | 5 | 기존 |
| general-worker | `/internal/worker/general`, `/health` | pool·SafeHttpTransport·WorkerExecution | 2 | 기존 |
| browser-worker | `/internal/worker/browser`, `/health` | pool·EgressProxy·WorkerExecution | 2 | DB, OpenAI(일반 Worker와 동일) |
| maintenance | `POST /internal/maintenance/run`, `/health` | pool·Tasks client | 2 | DB. production은 Tasks 설정(`TASKS_PROJECT_ID`, `GENERAL_WORKER_URL`, `BROWSER_WORKER_URL`, `TASKS_CALLER_SERVICE_ACCOUNT`) 필수 |

- 각 역할은 자기 route만 등록한다. API는 `/internal/**`를 노출하지 않고, 일반 Worker는 browser route를 노출하지 않는다(현재 `workerRoutes`의 조건부 browser 인자를 역할별 함수로 분리).
- local maintenance에 Tasks 설정이 없으면 시작을 거부하지 않고, 테스트가 주입하는 `TaskGateway`를 사용한다. `module()`은 테스트 주입점을 유지한다.
- 종료 순서는 기존 규칙(역순 close, ApplicationStopping에서 admission 차단)을 따른다. maintenance도 `runIfOpen` 안에서만 실행한다.
- 기존 `BudgetMaintenanceService.main` 단발 CLI는 유지한다.

## 2. generation 재시도 예산

`AnalysisJobTransitions.hasRetryBudget(now)`로 lane 인자를 없앤다.

```text
attempts = attempt_count + browser_attempt_count
first    = least(first_attempt_at, first_browser_attempt_at)   -- null 무시
budget   = attempts < 3 && (first == null || now < first + 30분)
```

- claim·Worker Retryable·RUNNING 복구·PENDING 재예약·category stale replacement가 모두 이 함수를 쓴다. lane별 카운터는 진단용으로 유지하며 claim은 해당 lane 카운터만 1 증가시킨다.
- 재예약은 attempt를 증가시키지 않는다. 새 claim만 증가시킨다.
- schema 변경 없음. stale replacement는 이미 두 lane 값을 승계한다.

| 사례 | 기대 |
| --- | --- |
| general 1회 → NeedsBrowser | BROWSER_PENDING, browser 최대 2회 |
| general Retryable 2회 후 3번째에서 NeedsBrowser | 예산 소진 → job FAILED, 상품 FAILED_RETRYABLE |
| general 1 + browser 2 실패 | 소진 |
| 첫 시도 31분 뒤 browser claim | Exhausted |
| stale replacement 뒤 새 generation | 합산값·첫 시각 승계 |
| RUNNING 복구·PENDING 재예약 | attempt 불변 |

## 3. metadata 추출 (WORK-01)

`Metadata`를 다음으로 확장한다. 일반 HTTP와 browser가 같은 `HttpMetadataExtractor` 파서를 쓴다.

```kotlin
data class Metadata(
    val title: String?, val description: String?, val imageUrl: String?,
    val canonicalUrl: String,
    val brand: String? = null, val price: BigDecimal? = null, val currency: String? = null,
    val merchant: String? = null,
)
```

**공통.** 출처 우선순위는 JSON-LD `Product` → OpenGraph `product:*`·`og:*`이고 충돌 시 JSON-LD를 쓴다. 제목·본문 heuristic이나 AI로 brand·가격·merchant를 만들지 않는다. 값은 trim하고 blank는 null이다. 길이 상한(brand·merchant 200자)을 넘으면 null이다. 이름이 서로 다른 `Product` 노드가 둘 이상이면 brand·price·currency는 null이다(title 결정은 기존 규칙).

| 필드 | 근거 순서 | null 조건 |
| --- | --- | --- |
| brand | `brand`가 문자열이면 그 값, 객체면 `brand.name` → `meta[property=product:brand]` | 위 공통 조건 |
| price/currency | `offers`가 단일 `Offer`(또는 배열의 모든 Offer가 같은 price·priceCurrency)일 때 `price`+`priceCurrency` → `product:price:amount`+`product:price:currency` | `AggregateOffer`, offer별 값 상이, 음수·지수·숫자 아님, scale>4, 정수부 15자리 초과, `java.util.Currency`에 없는 코드. 둘 중 하나라도 무효면 둘 다 null |
| merchant | `offers.seller.name` → `og:site_name` | 둘 다 없음 |
| canonicalUrl | `link[rel=canonical]` → `og:url`. 최종 URL 기준 resolve 후 아래 조건 만족 시 채택 | 미충족이면 정규화한 최종 URL |

가격은 문자열을 `BigDecimal`로 직접 파싱하고 부동소수점을 거치지 않는다. 천 단위 구분자 등 숫자 형식이 아닌 값은 null이다. 통화는 대문자로 정규화한다. 환산하지 않는다.

**canonical 채택 조건.** http/https, userinfo 없음, host가 IP literal이 아님, 기본 외 port 없음, OkHttp `HttpUrl.topPrivateDomain()`이 최종 URL과 같음(어느 쪽이든 null이면 불채택). 이 주소는 요청하지 않으므로 DNS 검증은 하지 않는다.

**정규화(최종 URL·canonical 공통).** scheme·host 소문자, fragment 제거, 승인 tracking query 제거(`utm_*`, `fbclid`, `gclid`, `msclkid`, `igshid`, `mc_cid`, `mc_eid`), 그 밖의 query는 순서·값 그대로 보존. 승인 목록은 상수로 두고 테스트로 고정한다.

## 4. 저장과 반영

**migration V17** (`V17__analysis_metadata_and_recovery.sql`):

```sql
alter table wishlist_items
  add column product_brand text, add column product_price numeric(19,4),
  add column product_currency char(3), add column merchant_name text,
  add column metadata_checked_at timestamptz,
  add constraint wishlist_items_price_currency_check
    check ((product_price is null) = (product_currency is null)),
  add constraint wishlist_items_price_check check (product_price is null or product_price >= 0),
  add constraint wishlist_items_currency_check check (product_currency is null or product_currency ~ '^[A-Z]{3}$');
alter table analysis_jobs
  add column pending_brand text, add column pending_price numeric(19,4),
  add column pending_currency char(3), add column pending_merchant text,
  add column recovery_seq integer not null default 0,
  add column recovery_check_at timestamptz,
  add constraint analysis_jobs_pending_price_currency_check
    check ((pending_price is null) = (pending_currency is null));
create index analysis_jobs_pending_recovery_idx on analysis_jobs (recovery_check_at nulls first, updated_at, id)
  where stage in ('GENERAL_PENDING','BROWSER_PENDING');
create index outbox_events_job_created_idx on outbox_events (analysis_job_id, created_at desc, id desc);
```

정확한 SQL은 계획에서 기존 migration 문체와 index 정책(V15/V16 운영 rollout 여부)을 확인해 확정한다. 기존 V1~V16은 수정하지 않는다.

**임시 저장.** `AnalysisPendingResultRepository.saveMetadata`가 새 pending 컬럼도 저장한다. GENERAL 재claim은 기존처럼 pending metadata 전체(새 컬럼 포함)를 지우고, BROWSER 재claim은 유지한다.

**최종 반영.** `AnalysisResultRepository.finishLocked`의 기존 병합 규칙을 그대로 적용한다.

- brand: `BRAND`가 `user_override_fields`에 있으면 보호한다. 성공은 보호되지 않은 새 값을, PARTIAL·실패는 기존 nonnull 값을 유지하고 빈 칸만 채운다.
- price/currency: 한 쌍으로 병합한다. 둘 다 있는 쪽만 채택해 한 칸만 바뀌는 일이 없게 한다. 사용자 편집 대상이 아니므로 보호 필드가 없다.
- merchant: brand와 같은 병합(보호 없음).
- `metadata_checked_at`: 이번 finish가 페이지에서 읽은 pending metadata를 가지고 있으면(`pending_canonical_url is not null`) finish transaction의 `clock_timestamp()`로 기록한다. 상태(READY/PARTIAL)와 optional 값의 null 여부와 무관하다. 페이지를 얻지 못한 PARTIAL·실패는 기존 값을 유지한다. `classified_at`으로 대체하지 않는다.
- category stale replacement, version 불일치, lease 만료 등 기존 무반영 경로는 그대로다.

**조회.** 상세(`WishlistItemRepository`)와 목록·홈(`WishlistReadRepository`)이 공유하는 projection과 `WishlistItemRowMapper`가 새 컬럼을 읽는다(홈 목적 미리보기처럼 카드 표현을 쓰지 않는 조회는 제외). DTO는 이미 nullable `brand/price/currency/merchant/metadataCheckedAt`을 가진다. 가격은 기존 `DecimalJsonSerializer`로 JSON number다. 응답 필드 추가·이름 변경은 없다.

## 5. URL 안전과 실패 의미

- `UrlSafetyPolicy.validate`의 DNS 해석 실패(`UnknownHostException`, 해석 timeout, 빈 결과)는 `DnsLookupFailed`(새 예외, `UnsafeUrlException`과 별개)로 던진다. 차단 주소·scheme·port·userinfo·local host는 기존 `UnsafeUrlException`이다.
- 일반 lane: `DnsLookupFailed` → `ProcessingOutcome.Retryable`. 예산 소진 시 기존 경로로 FAILED_RETRYABLE(`ANALYSIS_RETRYABLE_FAILURE`). `UnsafeUrlException` → Terminal이며 pending failure code `BLOCKED_ADDRESS`를 같은 guard로 저장한 뒤 finish한다.
- browser lane: 대상 DNS·연결 실패·차단·navigation timeout·사이트 차단은 PARTIAL(확정 규칙). Playwright 실행 불가·proxy 시작 실패 등 Worker 인프라 장애는 Retryable.
- 공개 failure code 목록은 바꾸지 않는다.

## 6. browser runtime과 egress (WORK-02)

**접근 비교.** (A) 현재처럼 route에서 URL만 검사: Chromium이 DNS를 별도 조회해 rebinding을 막지 못한다. (B) **채택:** browser Worker 프로세스 내 pinning forward proxy. (C) 배포 egress firewall: B11에서 추가 방어로 적용하며 B5에서 검증할 수 없다.

**EgressProxy.** browser Worker가 소유하는 loopback 전용 HTTP proxy(`127.0.0.1`, 임의 port, 인증 token 없이 프로세스 내부만 bind).

- `CONNECT host:port`와 절대 URI HTTP 요청만 받는다. host마다 `UrlSafetyPolicy.validate`로 검증한 주소 중 하나에만 실제 TCP 연결한다. 검증과 연결 사이에 DNS를 다시 조회하지 않는다.
- port는 80/443만 허용한다. 차단·DNS 실패는 연결 거부로 응답하고, 실패 종류를 해당 render에 기록해 PARTIAL 판정에 쓴다.
- 연결별 timeout은 `WorkerExecution.remaining`을 따르고, render 종료 시 열린 연결을 모두 닫는다. 응답 body 크기는 proxy가 아닌 page content 단계에서 기존 512KiB 규칙을 적용한다.

**Chromium 설정.** `--proxy-server=http://127.0.0.1:{port}`, `--proxy-bypass-list=<-loopback>`(loopback 우회 제거), `--disable-quic`, `--force-webrtc-ip-handling-policy=disable_non_proxied_udp`, context `serviceWorkers=BLOCK`, `acceptDownloads=false`. 기존 route 단위 URL 검사는 빠른 거부용으로 유지한다. 최종 URL은 다시 검증한다.

**시간.** launch·navigation·content 추출·AI는 같은 `WorkerExecution`의 남은 80초를 공유한다. render는 렌더 결과만 반환하고 metadata의 guarded 저장은 `BrowserWorkerService`가 한 번 수행한다(기존 구조 유지).

**후보.** browser 경로의 AI 분류도 일반 경로와 같은 `AiClassificationService`·`CategoryCandidateProvider`·목적 후보·stale guard를 사용한다. 최초 candidate snapshot은 일반 lane에서 저장된 것을 재사용한다.

## 7. outbox 발행

`OutboxDispatcher.dispatchPending(limit, deadline)`:

- claim 대상에서 이번 실행에 실패한 이벤트 ID를 제외(`id <> all(?)`)하고 계속 진행한다. 반환은 `DispatchReport(published, failed)`.
- 건수(`limit`)와 시간(`deadline`) 중 먼저 도달하면 멈춘다. 실패 이벤트는 lease를 해제하므로 다음 실행에서 다시 시도된다.
- `dispatchEvent(id)`(B1 지정 발행)는 같은 claim·publish 경로를 유지한다. createTask RPC 5초 제한·취소 전파·로그 규칙은 그대로다.

## 8. maintenance 실행 (OPS-01)

`MaintenanceService.runOnce(): MaintenanceReport`가 아래 단계를 순서대로 실행한다. 단계별 예외는 격리해 로그(고정 문장·예외 타입)와 report에 남기고 다음 단계를 진행한다. route는 모든 단계가 정상이면 200, 하나라도 실패하면 500을 반환한다. 둘 다 JSON report를 담는다. 전체 실행 상한은 50초이며 각 단계는 남은 시간을 받는다.

1. **outbox backlog**: `dispatchPending(limit=100, deadline)`.
2. **만료 RUNNING 복구**: 기존 `AnalysisJobReconciler`에 합산 예산과 순환을 적용한다.
3. **오래된 PENDING 복구**: 아래 절.
4. **budget**: `BudgetMaintenanceService.runOnce()`.

**순환 진행(2·3 공통).** 후보 발견 SQL은 `recovery_check_at is null or recovery_check_at <= clock_timestamp()` 조건과 `recovery_check_at nulls first, updated_at, id` 정렬을 쓴다. skip-locked로 건너뛰거나 처리 중 예외가 난 후보는 rollback 후 별도 짧은 transaction에서 `recovery_check_at = clock_timestamp() + 1분`으로 미룬다. 이 갱신은 job 행만 바꾸며 owner→item→job 잠금 순서와 충돌하지 않도록 `for update skip locked`로 시도하고 잠겨 있으면 생략한다. 이렇게 앞 batch 전체가 계속 실패·잠김이어도 다음 실행은 뒤 후보를 읽는다. 상태 전이·재예약 시 `recovery_check_at`은 null로 초기화한다.

**PENDING 복구.**

1. 발견(잠금 없음, DB 시각): stage ∈ {GENERAL_PENDING, BROWSER_PENDING}, `updated_at <= now - 5분`, 순환 조건 충족. 각 후보의 최신 outbox(`created_at desc, id desc`)를 함께 읽는다. batch 50.
2. 최신 outbox가 미발행이면 건너뛴다(1단계 발행 대상). outbox가 없으면 MISSING으로 취급한다.
3. transaction 밖에서 `TaskGateway.status(task)`를 호출한다.
   - `ALIVE` → `recovery_check_at = now + 5분`.
   - 조회 예외 → 로그 후 `recovery_check_at = now + 1분`. 상품 상태는 바꾸지 않는다.
   - `MISSING` → 4단계.
4. owner→item→job 잠금 후 재검증: job의 item·generation·stage·`updated_at`, 최신 outbox ID가 발견 당시와 같고, item이 `canAnalyze(generation)`(ACTIVE·PROCESSING·현재 generation·수동 완료 아님). 하나라도 다르면 무변경.
   - item이 삭제·보관·수동 완료·generation 변경 → job CANCELLED, outbox 없음.
   - 예산 소진 → job FAILED + 상품 FAILED_RETRYABLE(version +1). 예산 정산은 건드리지 않는다.
   - 예산 남음 → `recovery_seq = recovery_seq + 1`, `updated_at`·`recovery_check_at=null` 갱신, 같은 transaction에서 outbox insert. task 이름 `{analysis|browser}-{jobId}-{generation}-pending-{recovery_seq}`. stage·attempt·item은 바꾸지 않는다.
5. 동시 maintenance는 잠금 재검증과 task_name UNIQUE로 재예약 1건만 남는다. UNIQUE 위반은 해당 후보만 rollback하고 무변경으로 센다.

## 9. TaskGateway

```kotlin
enum class TaskStatus { ALIVE, MISSING }
interface TaskGateway {
    fun create(task: AnalysisTask)
    fun status(task: AnalysisTask): TaskStatus   // 조회 장애는 예외
}
```

- `CloudTasksGateway.status`: `getTask(queue/tasks/{name})` 성공 → ALIVE, `NotFoundException` → MISSING. getTask RPC도 재시도 없이 총 5초.
- 테스트용 `InMemoryTaskQueue`(testutil): create 기록·중복 이름 무시, task 제거(유실/소진), 조회 장애 주입, 전달(Worker 호출) helper.
- `fun interface`를 일반 interface로 바꾸므로 기존 lambda 사용처를 갱신한다.

## 10. 오류·관측

- 로그는 jobId·eventId·예외 타입·단계 이름만 남기고 메시지·URL·SQL은 남기지 않는다.
- report 필드: published/failedEvents, recoveredRunning, pendingRescheduled/pendingFailed/pendingCancelled/pendingAlive/lookupFailed, expiredReservations, deliveredAlerts, failedSteps.

## 11. 검증

테스트를 먼저 작성하고 미구현으로 실패함을 확인한다. 실제 PostgreSQL(Testcontainers)을 쓰는 테스트를 실행하지 못하면 해당 작업을 통과로 보지 않는다.

| 영역 | 주요 사례 |
| --- | --- |
| 역할 config | 4개 역할 env 검증, API의 `/internal/**` 404, general의 browser route 404, maintenance route 존재 |
| 예산 | 위 2절 표 전체를 claim·finish·reconciler·PENDING·replacement에서 |
| 추출 | 단일 Offer, AggregateOffer, 상이 offer 배열, 동일 offer 배열, 통화 무효·누락, 지수·음수·scale, brand 문자열/객체, 복수 Product, seller/site_name/없음, canonical 동일·다른 eTLD+1·IP·userinfo·상대경로, tracking query 제거·variant 보존 |
| 저장 | pending 저장·GENERAL 재claim 삭제·BROWSER 유지, 성공/PARTIAL 병합, BRAND override 보호, price 쌍 병합, metadataCheckedAt 기록·유지, CHECK 위반 거부, 상세·목록·홈 응답 값 |
| URL | DNS 실패 → general Retryable / browser PARTIAL, 차단 → Terminal+`BLOCKED_ADDRESS` |
| egress | proxy가 검증 IP로만 연결, DNS가 공인→loopback으로 바뀌는 rebinding 시도 차단, 사설 subresource 차단, port 제한, render 종료 시 연결 정리 |
| outbox | 첫 이벤트 실패 후 다음 발행, 시간 상한, 지정 발행 회귀, 동시 dispatcher 중복 없음 |
| maintenance | 단계 실패 격리·500, 미발행 browser fallback 발행, queue 소진/유실 → 재예약, ALIVE·조회 장애 → 무변경과 재검사 시각, 예산 소진 → FAILED_RETRYABLE, 삭제/보관/수동 완료 → CANCELLED, 105초 마감 후 중복 ACK → 원래 실행 Retryable outbox 유지 |
| 경합(PostgreSQL) | 동시 maintenance 2개 재예약 1건, 복구 vs claim/finish/편집/삭제, 앞 batch 잠금 시 뒤 후보 진행, RUNNING 순환 |
| upgrade | V16 데이터 → V17, 기존 행 기본값·CHECK |
| 전체 흐름 | API 생성 → InMemoryTaskQueue → general(local HTTP 서버) → NeedsBrowser → browser → READY/PARTIAL + metadata 응답, task 유실 → maintenance → 완료 |
| 회귀 | `./gradlew test` 전체(B0~B4) |

실제 Playwright 테스트는 Chromium이 설치된 환경에서 opt-in으로 실행하고, 미설치로 실행하지 못하면 결과 기록에 미실행으로 남긴다. egress proxy 자체는 Playwright 없이 단위·통합 테스트로 검증한다.

## 12. 문서 갱신

계약 문서(`wishlist-item-state-api.md` 가격 단위·통화 확정, `wishlist-item-read-api.md` metadata null 문구), `extraction-pipeline.md`, `analysis-pending-recovery.md`, `runtime-resources.md`, `overview.md`, `wishlist-state-persistence.md`, `mvp-api-inventory.md`(WORK/OPS 상태), `mvp-api-implementation-order.md`(B5 상태·미결정 해결 표), INDEX, B5 구현 이력, 필요한 learning Q&A(pinning proxy·generation 예산).
