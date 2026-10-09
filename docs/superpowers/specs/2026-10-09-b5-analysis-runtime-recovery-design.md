# B5 비동기 분석과 운영 복구 설계

> 2026-10-09 · **spec 승인(2026-10-10) · 구현 계획 검토 대기** · 제품·운영 정책 확정

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
| 6 | durable retry outbox를 저장한 Worker Retryable은 ACK(204). ADR-009 backoff는 outbox 발행 예정 시각과 Cloud Tasks scheduleTime으로 유지 |

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
| queue | `TaskGateway.create`만, `AnalysisTask`에 예약 시각 없음 | `status` 조회 추가, `AnalysisTask.scheduleAt`(Cloud Tasks scheduleTime) 추가 |
| Worker 응답 | Retryable은 retry outbox 저장 후에도 503 | outbox 저장 시 204, backoff는 scheduleTime |

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

`LockedAnalysisJob.hasRetryBudget(lane, now)` 확장 함수(`AnalysisJobTransitions.kt`)를 `hasRetryBudget(now)`로 바꿔 lane 인자를 없앤다.

```text
attempts = attempt_count + browser_attempt_count
first    = least(first_attempt_at, first_browser_attempt_at)   -- null 무시
budget   = attempts < 3 && (first == null || now < first + 30분)
```

- claim·Worker Retryable·**finish의 NeedsBrowser 분기**·RUNNING 복구·PENDING 재예약·category stale replacement가 모두 이 함수를 쓴다. NeedsBrowser 분기(`AnalysisResultRepository.finishLocked`)는 `!browserAttempted`에 더해 예산을 확인하고, 소진이면 browser outbox를 만들지 않고 그 자리에서 FAILED_RETRYABLE(`ANALYSIS_RETRYABLE_FAILURE`)로 finish한다. lane별 카운터는 진단용으로 유지하며 claim은 해당 lane 카운터만 1 증가시킨다.
- 재예약은 attempt를 증가시키지 않는다. 새 claim만 증가시킨다.
- 예산 함수 자체는 schema 변경이 없다. stale replacement는 이미 두 lane 값을 승계한다.
- **claim 전 실패 상한.** 발행된 task가 claim 전에 계속 실패하면(Worker 인증 설정 오류, 시작 중 crash, claim 전 5xx) attempt=0·첫 시각 null이라 위 함수는 항상 참이다. 그래서 PENDING 재예약에 별도 상한을 둔다. `recovery_seq >= 3`이면 더 재예약하지 않고 FAILED_RETRYABLE로 끝낸다. `recovery_seq`는 claim 때 초기화하지 않는 **job 전체 누적 상한**이다. 사이에 claim이 있었던 정상 유실 3회로도 상한에 걸리지만, 단순하고 job 하나의 재예약 총량을 제한한다. category stale replacement로 만든 새 job은 0부터 시작한다(attempt 합산과 30분은 승계하므로 전체 상한은 유지된다). 재예약 1회에 Cloud Tasks 전달 3회와 5분 정체 판정이 붙으므로 3회면 약 30분이다.

**소진 시 metadata 반영.** 예산 소진으로 실패하는 모든 경로(claim Exhausted, finish의 Retryable·NeedsBrowser, RUNNING 복구, PENDING 재예약, stale replacement)는 공통 helper `failExhausted(jobId, itemId)`를 쓴다. 이 helper는 job FAILED와 상품 FAILED_RETRYABLE에 더해, job에 남은 pending metadata를 §4의 실패 병합 규칙(`existing ?: pending`, 보호 필드 유지)으로 반영한다. pending에 페이지 metadata가 있으면(`pending_canonical_url is not null`) 확인 시각과 가격 쌍도 §4 규칙대로 기록한다. 그래서 "이미 읽은 페이지 정보"가 실패 경로에 따라 버려지거나 남는 일이 없다. 수동 편집으로 version이 바뀌어 취소되는 경로(`failRetryableItem`만 호출)는 metadata를 반영하지 않는다.

| 사례 | 기대 |
| --- | --- |
| general 1회 → NeedsBrowser | BROWSER_PENDING, browser 최대 2회 |
| general Retryable 2회 후 3번째에서 NeedsBrowser | finish의 NeedsBrowser 분기에서 소진 판정 → browser outbox 없이 job FAILED, 상품 FAILED_RETRYABLE |
| general Complete(metadata 저장) → AI Retryable → 31분 뒤 claim | claim Exhausted가 `failExhausted`로 저장된 metadata·확인 시각 반영 |
| general 1 + browser 2 실패 | 소진 |
| 첫 시도 31분 뒤 browser claim | Exhausted |
| stale replacement 뒤 새 generation | 합산값·첫 시각 승계 |
| RUNNING 복구·PENDING 재예약 | attempt 불변 |
| claim 전 실패로 PENDING 재예약 3회 후 다시 MISSING | 재예약 없이 FAILED_RETRYABLE |
| 재예약 → claim → Retryable … 사이에 claim이 있었던 유실 누적 3회 후 MISSING | 누적 상한으로 FAILED_RETRYABLE(claim이 recovery_seq를 초기화하지 않음) |
| Worker Retryable(outbox 저장) | 204 ACK, 원래 task 종료, retry outbox만 남음 |

**Worker 응답.** 확정 결정 6에 따라 응답을 다음처럼 나눈다.

| 상황 | DB | 응답 |
| --- | --- | --- |
| 유효 실행의 Retryable, 예산 남음 | PENDING + retry outbox(`not_before` 포함) 저장 | **204 ACK** |
| 유효 실행의 Retryable, 예산 소진 | `failExhausted` | 204 |
| claim 전 처리 마감, executor 포화 | 변경 없음 | 503 |
| 90초 Worker timeout(작업이 아직 끝나지 않음) | 늦게 끝나면 retry outbox, 멈추면 lease 복구 | 503 |
| 그 외 결과·Ignored·Exhausted | 기존 | 204 |

구현에서는 `WorkerDisposition`의 의미를 이렇게 고정한다. retry outbox를 저장한 Retryable은 `finishLocked`가 `ACKNOWLEDGE`를 반환한다. `RETRY`는 durable 기록이 없는 경우(claim 전 마감, executor 포화, 90초 timeout)만 뜻하며 route는 그대로 503으로 바꾼다. durable outbox가 있으면 진행은 outbox와 maintenance가 보장하므로 원래 task를 끝내 예산 이중 소비를 막는다. durable 기록이 없는 경우만 Cloud Tasks 재전달에 맡긴다. 90초 timeout은 늦은 finish가 outbox를 만들면 재전달과 겹칠 수 있다. 이때도 claim이 RUNNING 중복을 막고, 남는 위험은 늦은 finish와 재전달 사이 시점의 추가 attempt 1회뿐이다. 이 위험은 문서에 남긴다.

**retry backoff.** retry outbox의 `not_before`는 `DB 시각 + min(10초 × 2^(합산 attempts − 1), 600초)`다(1회 후 10초, 2회 후 20초). dispatcher는 `not_before`를 기다리지 않고 바로 발행하며 Cloud Tasks `scheduleTime`으로 넘긴다. 그래서 Scheduler 주기와 무관하게 ADR-009 backoff가 유지된다. RUNNING 복구·PENDING 재예약·browser fallback·신규 생성 outbox의 `not_before`는 null(즉시)이다.

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

**migration V17** (`V17__analysis_metadata_and_recovery.sql`). 현재 마지막 migration은 SQL `V15__wishlist_read_indexes.sql` 다음의 Kotlin Java-migration `server/src/main/kotlin/db/migration/V16__recoverable_read_indexes.kt`이므로(origin/develop 동일) 다음 번호는 V17이다.

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
create index analysis_jobs_running_recovery_idx on analysis_jobs (recovery_check_at nulls first, lease_until, id)
  where stage in ('GENERAL_RUNNING','BROWSER_RUNNING');
alter table outbox_events add column not_before timestamptz;
create index outbox_events_job_created_idx on outbox_events (analysis_job_id, created_at desc, id desc);
```

정확한 SQL은 계획에서 기존 migration 문체를 따라 확정한다. V16은 운영 데이터가 큰 `wishlist_items` index를 `create index concurrently`로 복구 가능하게 만든 경로다. 새 index의 대상인 `analysis_jobs`·`outbox_events`에도 그 방식이 필요한지 계획에서 판단해 기록한다. 기존 V1~V16은 수정하지 않는다. 기존 `analysis_jobs_recovery_idx (stage, lease_until, id)`는 남기고, RUNNING 순환 발견은 새 `analysis_jobs_running_recovery_idx`를 쓴다.

**임시 저장.** `AnalysisPendingResultRepository.saveMetadata`가 새 pending 컬럼도 저장한다. GENERAL 재claim은 기존처럼 pending metadata 전체(새 컬럼 포함)를 지우고, BROWSER 재claim은 유지한다.

**최종 반영.** `AnalysisResultRepository.finishLocked`의 기존 병합 규칙을 그대로 적용한다.

- brand: `BRAND`가 `user_override_fields`에 있으면 보호한다. 보호되지 않으면 기존 `mergedMetadata` 규칙을 그대로 쓴다. 성공은 `pending ?: existing`이라 새 값이 null이면 **기존 값을 유지**한다. PARTIAL·실패는 `existing ?: pending`이다.
- price/currency: 항상 한 쌍으로 다루며 한 칸만 바뀌지 않는다. 가격은 확인 시각에 묶인 값이므로 `metadata_checked_at`을 새로 기록하는 finish에서는 이번 읽기 결과 쌍으로 **교체**한다(결과가 null이면 null로 지움). 확인 시각을 기록하지 않는 finish는 기존 쌍을 유지한다. 이렇게 해야 확인 시각이 이전 가격을 새로 확인한 것처럼 보이지 않는다. 사용자 편집 대상이 아니므로 보호 필드가 없다. B5에서는 첫 분석이라 기존 값이 없지만 B7 재분석이 같은 규칙을 쓴다.
- merchant: brand와 같은 병합(보호 없음).
- `metadata_checked_at`: 이번 finish가 페이지에서 읽은 pending metadata를 가지고 있으면(`pending_canonical_url is not null`) finish transaction의 `clock_timestamp()`로 기록한다. 상태(READY/PARTIAL)와 optional 값의 null 여부와 무관하다. NeedsBrowser는 pending metadata를 저장하지 않으므로(`GeneralExtractionProcessor`는 `Complete`만 저장) "general NeedsBrowser → browser 실패로 PARTIAL"은 pending이 비어 있어 기록하지 않는다. 기록 여부는 lane이 아니라 pending 페이지 metadata의 존재로만 정한다. 페이지 metadata가 없는 PARTIAL·실패는 기존 값을 유지한다. 예산 소진 실패도 §2의 `failExhausted`로 같은 규칙을 따른다. `classified_at`으로 대체하지 않는다.
- category stale replacement, version 불일치, lease 만료 등 기존 무반영 경로는 그대로다.
- stale replacement는 두 경우로 나뉜다. 예산이 남아 새 generation으로 바뀌면 이번 실행의 pending metadata를 반영하지 않는다(새 generation이 다시 분석). replacement 시점에 예산이 소진돼 FAILED로 끝나면(`StaleCategoryReplacement.kt`의 소진 분기) `failExhausted`로 반영한다.

**조회.** 상세(`WishlistItemRepository`)와 목록·홈(`WishlistReadRepository`)이 공유하는 projection과 `WishlistItemRowMapper`가 새 컬럼을 읽는다(홈 목적 미리보기처럼 카드 표현을 쓰지 않는 조회는 제외). DTO는 이미 nullable `brand/price/currency/merchant/metadataCheckedAt`을 가진다. 가격은 기존 `DecimalJsonSerializer`로 JSON number다. 응답 필드 추가·이름 변경은 없다.

## 5. URL 안전과 실패 의미

- `UrlSafetyPolicy.validate`의 DNS 해석 실패(`UnknownHostException`, 빈 결과, 해석 시간 초과)는 `DnsLookupFailed`(새 예외, `UnsafeUrlException`과 별개)로 던진다. `InetAddress.getAllByName`에는 timeout이 없으므로, 기본 resolver를 bounded executor에서 실행하고 `WorkerExecution.remaining`(최대 5초)만 기다리는 wrapper를 추가한다. 시간 초과도 `DnsLookupFailed`다. `getAllByName`은 interrupt로 멈추지 않아 스레드가 묶일 수 있으므로 executor는 크기와 queue를 제한한다. 포화로 작업이 거부되면 이것도 `DnsLookupFailed`로 처리한다(일반 lane은 Retryable). 차단 주소·scheme·port·userinfo·local host·invalid URL은 기존 `UnsafeUrlException`이다.
- 실제 DNS 조회는 `UrlSafetyPolicy`에서만 일어난다. redirect 대상도 `HttpMetadataExtractor`가 `validate`로 다시 해석한다. `SafeHttpTransport`의 OkHttp `Dns`는 이미 검증한 pinned 주소만 돌려주고, 요청 host가 아닌 이름을 조회하려 하면 `UnsafeUrlException`을 던진다. 이것은 고정 주소 우회를 막는 불변식 위반이므로 차단(Terminal)으로 유지하며 DNS 실패로 나누지 않는다.
- 일반 lane: `DnsLookupFailed` → `ProcessingOutcome.Retryable`. 예산 소진 시 기존 경로로 FAILED_RETRYABLE(`ANALYSIS_RETRYABLE_FAILURE`). `UnsafeUrlException` → Terminal이며 pending failure code `BLOCKED_ADDRESS`를 같은 guard로 저장한 뒤 finish한다. 차단 주소뿐 아니라 scheme·port·userinfo 거부와 invalid URL도 모두 `BLOCKED_ADDRESS`로 저장한다. 해당하는 다른 공개 code가 없고, 사용자에게는 어느 쪽이든 "이 주소는 분석할 수 없음"이라 구분할 필요가 없다.
- browser lane: 대상 DNS·연결 실패·차단·navigation timeout·사이트 차단은 PARTIAL(확정 규칙). Playwright 실행 불가·proxy 시작 실패 등 Worker 인프라 장애는 Retryable.
- 공개 failure code 목록은 바꾸지 않는다.

## 6. browser runtime과 egress (WORK-02)

**접근 비교.** (A) 현재처럼 route에서 URL만 검사: Chromium이 DNS를 별도 조회해 rebinding을 막지 못한다. (B) **채택:** browser Worker 프로세스 내 pinning forward proxy. (C) 배포 egress firewall: B11에서 추가 방어로 적용하며 B5에서 검증할 수 없다.

**EgressProxy.** browser Worker가 소유하는 loopback 전용 HTTP proxy(`127.0.0.1`, 임의 port). loopback 전용이며 같은 컨테이너만 신뢰한다. loopback은 같은 컨테이너의 다른 프로세스(Chromium 포함)도 접근할 수 있으므로 별도 인증은 두지 않고, browser Worker 컨테이너에 다른 workload를 두지 않는 배포 조건을 B11에서 확인한다.

- `CONNECT host:port`와 절대 URI HTTP 요청만 받는다. host마다 `UrlSafetyPolicy.validate`로 검증한 주소 중 하나에만 실제 TCP 연결한다. 검증과 연결 사이에 DNS를 다시 조회하지 않는다.
- port는 80/443만 허용한다. 차단·DNS 실패는 `403 Forbidden`으로 거부한다. browser lane에서는 대상 실패가 모두 PARTIAL이므로 실패 종류를 따로 기록하지 않는다. 거부된 navigation은 Playwright 예외로 PARTIAL이 된다.
- 연결별 timeout은 `WorkerExecution.remaining`을 따르고, render 종료 시 열린 연결을 모두 닫는다. 응답 body 크기는 proxy가 아닌 page content 단계에서 기존 512KiB 규칙을 적용한다.

**Chromium 설정.** `--proxy-server=http://127.0.0.1:{port}`, `--proxy-bypass-list=<-loopback>`(loopback 우회 제거), `--disable-quic`, `--force-webrtc-ip-handling-policy=disable_non_proxied_udp`, context `serviceWorkers=BLOCK`, `acceptDownloads=false`. 기존 route 단위 URL 검사는 빠른 거부용으로 유지한다. 최종 URL은 다시 검증한다.

**시간.** launch·navigation·content 추출·AI는 같은 `WorkerExecution`의 남은 80초를 공유한다. render는 렌더 결과만 반환하고 metadata의 guarded 저장은 `BrowserWorkerService`가 한 번 수행한다(기존 구조 유지).

**후보.** browser 경로의 AI 분류도 일반 경로와 같은 `AiClassificationService`·`CategoryCandidateProvider`·목적 후보·stale guard를 사용한다. 최초 candidate snapshot은 일반 lane에서 저장된 것을 재사용한다.

## 7. outbox 발행

`OutboxDispatcher.dispatchPending(limit, deadline)`:

- claim 대상에서 이번 실행에 실패한 이벤트 ID를 제외(`id <> all(?)`)하고 계속 진행한다. 반환은 `DispatchReport(published, failed)`.
- 건수(`limit`)와 시간(`deadline`) 중 먼저 도달하면 멈춘다. 실패 이벤트는 lease를 해제하므로 다음 실행에서 다시 시도된다.
- `not_before`가 있으면 `AnalysisTask.scheduleAt`으로 넘겨 Cloud Tasks `scheduleTime`을 설정한다. 이미 지난 시각이면 null로 보낸다.
- `dispatchEvent(id)`(B1 지정 발행)는 같은 claim·publish 경로를 유지한다. createTask RPC 5초 제한·취소 전파·로그 규칙은 그대로다.

## 8. maintenance 실행 (OPS-01)

기존 `app/budget/BudgetMaintenanceService.kt`의 `MaintenanceReport`를 `BudgetMaintenanceReport`로 이름을 바꾸고, 새 `MaintenanceService.runOnce(): MaintenanceReport`가 아래 단계를 순서대로 실행한다. 단계별 예외는 격리해 로그(고정 문장·예외 타입)와 report에 남기고 다음 단계를 진행한다. route는 모든 단계가 정상이면 200, 하나라도 실패하면 500을 반환한다. 둘 다 JSON report를 담는다. 전체 실행 상한은 50초이며 각 단계는 남은 시간을 받는다.

1. **outbox backlog**: `dispatchPending(limit=100, deadline)`.
2. **만료 RUNNING 복구**: 기존 `AnalysisJobReconciler`에 합산 예산과 순환을 적용한다.
3. **오래된 PENDING 복구**: 아래 절.
4. **budget**: `BudgetMaintenanceService.runOnce()`.

**순환 진행(2·3 공통).** 후보 발견 SQL은 `recovery_check_at is null or recovery_check_at <= clock_timestamp()` 조건을 쓰고, 정렬은 PENDING이 `recovery_check_at nulls first, updated_at, id`, RUNNING이 `recovery_check_at nulls first, lease_until, id`다. 각각 V17의 partial index와 일치한다. skip-locked로 건너뛰거나 처리 중 예외가 난 후보는 rollback 후 별도 짧은 transaction에서 `recovery_check_at = clock_timestamp() + 1분`으로 미룬다. 이 갱신은 job 행만 바꾸며 owner→item→job 잠금 순서와 충돌하지 않도록 `for update skip locked`로 시도하고 잠겨 있으면 생략한다. 이렇게 앞 batch 전체가 계속 실패·잠김이어도 다음 실행은 뒤 후보를 읽는다. `recovery_check_at`은 공통 helper `transitionAnalysisJob`에서 null로 초기화한다. claim·finish·Worker Retryable·stale replacement·reconciler가 모두 이 helper를 거치므로 한 곳에서 빠짐없이 처리된다. helper를 쓰지 않는 두 경로(claim의 lease UPDATE, PENDING 재예약 UPDATE)는 각 SQL에서 직접 null로 둔다.

**PENDING 복구.**

1. 발견(잠금 없음, DB 시각): stage ∈ {GENERAL_PENDING, BROWSER_PENDING}, `updated_at <= now - 5분`, 순환 조건 충족, **최신 outbox(`created_at desc, id desc`)가 발행됐거나 없음**. batch 50. 미발행 outbox가 있는 job은 1단계 발행 대상이므로 SQL에서 제외한다. 그래서 Tasks 장애로 미발행 PENDING이 많이 쌓여도 발견 batch를 차지하지 않는다.
2. outbox가 없는 PENDING은 MISSING으로 취급한다.

3. transaction 밖에서 `TaskGateway.status(task)`를 호출한다.
   - `ALIVE` → `recovery_check_at = now + 5분`.
   - 조회 예외 → 로그 후 `recovery_check_at = now + 1분`. 상품 상태는 바꾸지 않는다.
   - `MISSING` → 4단계.
4. owner→item→job 잠금 후 재검증: job의 item·generation·stage·`updated_at`, 최신 outbox ID가 발견 당시와 같고, item이 `canAnalyze(generation)`(ACTIVE·PROCESSING·현재 generation·수동 완료 아님). 하나라도 다르면 무변경.
   - item이 삭제·보관·수동 완료·generation 변경 → job CANCELLED, outbox 없음.
   - 예산 소진 또는 `recovery_seq >= 3` → `failExhausted`(job FAILED, 상품 FAILED_RETRYABLE·version +1, pending metadata 반영). 예산 정산은 건드리지 않는다.
   - 예산 남음 → `recovery_seq = recovery_seq + 1`, `updated_at`·`recovery_check_at=null` 갱신, 같은 transaction에서 outbox insert. task 이름 `{analysis|browser}-{jobId}-{generation}-pending-{recovery_seq}`. stage·attempt·item은 바꾸지 않는다.
5. 동시 maintenance는 잠금 재검증과 task_name UNIQUE로 재예약 1건만 남는다. UNIQUE 위반은 해당 후보만 rollback하고 무변경으로 센다.

**정체 범위.** 발행이 계속 실패하는 동안의 PENDING은 30분이 지나도 PROCESSING에 머문다. 이는 의도한 동작이다. 30분·3회 예산은 실제 Worker 실행에 대한 상한이고, 발행되지 않은 job은 아직 한 번도 실행되지 않았다(attempt 0이면 첫 시도 시각도 없음). Tasks가 회복되면 1단계가 발행을 이어 간다. 장기 미발행은 outbox 실패 지표로 관측한다. 반면 발행됐지만 claim 전에 계속 사라지는 job은 `recovery_seq` 상한으로 끝난다.

## 9. TaskGateway

```kotlin
data class AnalysisTask(val name: String, val jobId: UUID, val generation: Int, val type: String, val scheduleAt: Instant? = null)
enum class TaskStatus { ALIVE, MISSING }
fun interface TaskGateway {
    fun create(task: AnalysisTask)
    // 조회 장애는 예외. 기본 구현은 항상 조회 실패로 취급해 재예약하지 않는다.
    fun status(task: AnalysisTask): TaskStatus = throw UnsupportedOperationException("Task lookup unavailable")
}
```

- `CloudTasksGateway.status`: `getTask(queue/tasks/{name})` 성공 → ALIVE, `NotFoundException` → MISSING. getTask RPC도 재시도 없이 총 5초.
- 테스트용 `InMemoryTaskQueue`(testutil): create 기록·중복 이름 무시, task 제거(유실/소진), 조회 장애 주입, 전달(Worker 호출) helper.
- `fun interface`와 기본 `status`를 유지해 기존 lambda 사용처(`TaskGateway { … }`)를 바꾸지 않는다. 조회를 지원하지 않는 gateway는 조회 실패와 같아 PENDING을 재예약하지 않으므로 안전한 기본값이다.

## 10. 오류·관측

- 로그는 jobId·eventId·예외 타입·단계 이름만 남기고 메시지·URL·SQL은 남기지 않는다.
- `MaintenanceReport` 필드: publishedEvents/failedEvents, recoveredRunning, pendingRescheduled/pendingFailed/pendingRescheduleExhausted/pendingCancelled/pendingAlive/lookupFailed, `budget: BudgetMaintenanceReport?`(expiredReservations·deliveredAlerts, 단계 실패 시 null), failedSteps.

## 11. 검증

테스트를 먼저 작성하고 미구현으로 실패함을 확인한다. 실제 PostgreSQL(Testcontainers)을 쓰는 테스트를 실행하지 못하면 해당 작업을 통과로 보지 않는다.

| 영역 | 주요 사례 |
| --- | --- |
| 역할 config | 4개 역할 env 검증, API의 `/internal/**` 404, general의 browser route 404, maintenance route 존재 |
| 예산 | 위 2절 표 전체를 claim·finish(NeedsBrowser 분기 포함)·reconciler·PENDING·replacement에서, 각 소진 경로의 `failExhausted` metadata 반영, 수동 편집 취소 경로는 미반영 |
| Worker 응답 | 예산 남은 Retryable 204+`not_before` 10초/20초, 소진 204, claim 전 마감·executor 포화·90초 timeout 503, dispatcher의 scheduleTime 전달·지난 시각 null |
| 추출 | 단일 Offer, AggregateOffer, 상이 offer 배열, 동일 offer 배열, 통화 무효·누락, 지수·음수·scale, brand 문자열/객체, 복수 Product, seller/site_name/없음, canonical 동일·다른 eTLD+1·IP·userinfo·상대경로, tracking query 제거·variant 보존 |
| 저장 | pending 저장·GENERAL 재claim 삭제·BROWSER 유지, 성공/PARTIAL 병합, BRAND override 보호, 성공 시 brand null은 기존 유지, 확인 시각 기록 시 price 쌍 교체(null 포함)·미기록 시 유지, metadataCheckedAt 기록·유지(general NeedsBrowser → browser 실패 PARTIAL은 pending이 없어 미기록), RUNNING·PENDING 경로별 `recovery_check_at` 초기화, CHECK 위반 거부, 상세·목록·홈 응답 값 |
| URL | DNS 실패·resolver 시간 초과·resolver executor 포화 → general Retryable / browser PARTIAL, 차단·scheme·port·userinfo·invalid URL → Terminal+`BLOCKED_ADDRESS`, pinned Dns 불변식 위반 → Terminal |
| egress | proxy가 검증 IP로만 연결, DNS가 공인→loopback으로 바뀌는 rebinding 시도 차단, 사설 subresource 차단, port 제한, render 종료 시 연결 정리 |
| outbox | 첫 이벤트 실패 후 다음 발행, 시간 상한, 지정 발행 회귀, 동시 dispatcher 중복 없음 |
| maintenance | 단계 실패 격리·500, 미발행 PENDING 50건 초과 시에도 발행된 뒤 후보 검사, claim 전 실패 반복 시 재예약 3회 후 FAILED_RETRYABLE, 미발행 browser fallback 발행, queue 소진/유실 → 재예약, ALIVE·조회 장애 → 무변경과 재검사 시각, 예산 소진 → FAILED_RETRYABLE, 삭제/보관/수동 완료 → CANCELLED, 105초 마감 후 중복 ACK → 원래 실행 Retryable outbox 유지 |
| 경합(PostgreSQL) | 동시 maintenance 2개 재예약 1건, 복구 vs claim/finish/편집/삭제, 앞 batch 잠금 시 뒤 후보 진행, RUNNING 순환 |
| upgrade | V16 데이터 → V17, 기존 행 기본값·CHECK |
| 전체 흐름 | API 생성 → InMemoryTaskQueue → general(local HTTP 서버) → NeedsBrowser → browser → READY/PARTIAL + metadata 응답, task 유실 → maintenance → 완료 |
| 회귀 | `./gradlew test` 전체(B0~B4) |

실제 Playwright 테스트는 Chromium이 설치된 환경에서 opt-in으로 실행하고, 미설치로 실행하지 못하면 결과 기록에 미실행으로 남긴다. egress proxy 자체는 Playwright 없이 단위·통합 테스트로 검증한다.

## 12. 문서 갱신

계약 문서(`wishlist-item-state-api.md` 가격 단위·통화 확정, `wishlist-item-read-api.md` metadata null 문구), `extraction-pipeline.md`, `analysis-pending-recovery.md`(503 유지 문단을 ACK 전환과 재예약 상한으로 교체), `runtime-resources.md`, `overview.md`, `wishlist-state-persistence.md`(Retryable 응답 문구), `mvp-api-inventory.md`(WORK/OPS 상태, Retryable 응답 문구), `mvp-api-implementation-order.md`(B5 상태·미결정 해결 표), INDEX, B5 구현 이력, 필요한 learning Q&A(pinning proxy·generation 예산).
