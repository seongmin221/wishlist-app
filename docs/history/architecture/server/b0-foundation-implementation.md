# B0 서버 기반 구현과 검증 기록

> 2026-10-05 · `server/b0-foundation` · Task 1~9 · B1 인계

## 완료 범위

역사적 분기점은 제품 문서가 통합된 `4d31e3c`, Task 8까지 runtime 구현 HEAD는 `96786fb`다. Task 9는 전체 회귀·쓰기 감사·문서 정리다. main의 이후 client 변경은 이번 작업에 통합하지 않았다.

- 상품 상태·허용 행동의 순수 정책, 공통 DTO·가격 Decimal·공개 오류 계약.
- V8 상품 상태/출처와 V9 실행 token/lease/claimed version, owner 범위 상태 조회.
- 원자 claim, 중간 결과·후보 snapshot·AI 예약 보호, 최종 결과·사용자 값 보호, 만료 lease 복구.
- 요청의 blocking IO 경계, 역할별 bounded pool, client 재사용, 시작 실패/종료 자원 정리와 발행 gate.

37개 제품 API 중 생성 POST만 부분 구현돼 있고 36개 route는 후속 묶음이다. B0 완료가 제품 API 전체 완료를 뜻하지 않는다. 현재 생성 응답은 기존 문자열 mapper를 유지한다. 공개 GET 상세와 공통 mapper는 B1이다.

## 실제 검증

Docker 28.4.0(Colima), JDK 17.0.18, Gradle 9.7.1 환경에서 다음 명령을 실행했다.

```sh
cd server
JAVA_HOME=$(/usr/libexec/java_home -v 17) \
DOCKER_HOST=unix:///Users/user/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 \
RUN_REAL_URL_PILOT=0 ./gradlew test
```

결과: **BUILD SUCCESSFUL, 4분 21초 · 151개 중 150개 통과 · 실패/오류 0 · skip 1**. skip은 실제 외부 URL 8개를 호출하는 opt-in `RealUrlPilotTest`뿐이다. 실제 PostgreSQL migration·경합·budget 테스트는 환경 문제로 제외되지 않았다.

대표 실행 증거: DatabaseMigration 3, AnalysisClaimRepository 7, AnalysisJobReconciler 10, GeneralWorkerService 13, BrowserWorkerService 10, AiClassificationService 9, LlmBudgetService 6, WishlistItemPolicy 14, ApiError 10, DatabaseFactory 4, RuntimeResources 4개. 생성/replay·auth·health·Worker 204/503 회귀도 통과했다.

## 쓰기 경로 감사

대소문자 포함 item/job INSERT·UPDATE·DELETE와 pending/candidate 쓰기를 검색하고 각 transaction을 읽었다.

| 위치 | 확인한 보호/원자성 |
| --- | --- |
| CreateWishlistItemService | item/job/outbox 같은 transaction, 기존 owner/key 유지, commit과 connection 반환 후 동기 발행 |
| AnalysisClaimRepository | item→job 잠금, 현재 generation/관계/상태 재검증, DB 시각 lease, token 새 발급, 소진 시 현재 상품만 실패 |
| AnalysisPendingResultRepository | 같은 guard transaction에서 metadata/assignment/failure·source URL·후보 snapshot 접근; 최초 snapshot은 lease 재확인 |
| AnalysisWriteGuard | 명시적 transaction, owner/관계/generation/token/stage/lifecycle/manual/version/lease 검증 |
| AnalysisResultRepository | guard 뒤 item/job 반영; stale는 무변경 ACK; retry·fallback·최종 version 전이 원자적; USER 값과 명시적 목적 해제 보호 |
| AnalysisJobReconciler | 잠금 없는 발견 뒤 item→job SKIP LOCKED, 발견 identity/lease 재확인; 재예약 outbox 원자적; invalid는 job만 취소 |
| AI/budget | 예약은 claim guard와 window 잠금 후 재검증; 실제 발생 비용 정산은 상품 결과 저장과 독립 유지 |
| OutboxDispatcher | outbox 발행 상태만 변경; 취소 cleanup 후 예외 재전파; 종료 시 새 발행 admission 차단 |

processor/classifier/budget의 상품 쓰기 entry는 AnalysisClaim을 받는다. HTTP jobId/generation entry는 DB claim을 얻고 진행한다. 원격 gateway 자체는 상품 DB를 쓰지 않는다. updated_at 기반 복구와 Worker의 옛 최종 SQL은 남아 있지 않다.

## Migration과 legacy 적용 순서

`4d31e3c` 대비 V1~V7 파일 변경은 없다. 빈 DB와 V7 데이터 upgrade 테스트를 실제 PostgreSQL로 통과했다. V8은 UUID·owner·생성 key·URL·createdAt·version·기존 metadata·job/outbox·예산 데이터를 보존하고 현재 상태/출처를 확장한다. 목적 자원이 없는 legacy predicted purpose는 진단 값으로 남긴다. V9는 기존 RUNNING의 token/claimed version을 null, lease를 만료로 두어 옛 실행을 유효한 새 claim으로 인정하지 않는다.

운영 적용 시 다음 순서가 필요하다. **이번 작업에서는 운영 적용하지 않았다.**

1. queue dispatch와 새 작업 유입을 정지하고 구 일반/browser Worker의 실행을 drain한 뒤 중지한다. 구 revision으로 트래픽이 들어가지 않게 한다.
2. 복구 지점을 확보하고 전용 migration 계정으로 V8/V9를 별도 적용한다. 실패하면 앱 배포를 중단하고 queue를 정지 상태로 유지한다.
3. 보호 코드가 적용된 서비스만 배포한다. B5에서 준비할 browser/maintenance runtime도 같은 실행 계약을 사용해야 한다.
4. 새 코드로 만료 legacy 실행을 복구하고 smoke 검증 후 queue를 재개한다. lease·재예약·실패율·예산 정산을 관측한다.

구 Worker의 unguarded SQL은 migration만으로 막히지 않으므로 구/새 Worker 혼재를 허용하지 않는다. 새 schema와 구 코드를 섞는 단순 rollback 대신 복구 지점·호환성을 확인해야 한다.

## B1 구현 인계

구현 순서는 [전체 구현 순서](../../../architecture/server/mvp-api-implementation-order.md)의 B1을 따른다.

1. clientCreatedAt와 optional metadata 입력 규칙을 계약에 맞춰 정리한다.
2. 기존 `WishlistItemStateRepository`를 재사용해 owner+item ID로 전체 상세 projection(URL·metadata·시각 포함)을 읽는다. 일반 상세에서 DELETED는 404, 다른 owner도 404다. 생성 replay tombstone 정책은 별도로 보존한다.
3. 기존 정책·DTO·Decimal·ApiError·IO/pool을 사용해 상세 mapper와 GET을 먼저 구현한다. DTO를 중복 생성하지 않는다.
4. 생성/replay도 같은 mapper로 실제 상태와 metadata를 반환하게 한다. 현재 상수/null 문자열 응답을 교체한다.
5. CREATED 201+Location, replay 200+replay header, 같은 key/다른 URL 409, 다른 owner 404, 상태/수동완료/재지정/USER purpose null, job/outbox 중복 없음 회귀를 확인한다.

category/purpose 실자원·owner FK·후보 version은 B2/B3에서 연결한다. 아직 없는 정보를 임의 label/ID로 채우지 않는다. generic extraction/retry의 failureCode가 null인 기존 경로는 B1 공개 실패 mapper와 B5 실패 분류에서 안전한 코드 계약으로 보완한다.

## 후속 범위와 미실행 항목

- B1 공개 상세·공통 mapper·clientCreatedAt, B2/B3 실자원 CRUD/FK/후보 version.
- B5 전체 retry 3회·30분 합산과 browser/maintenance runtime·Scheduler/private 인증. B0는 기존 lane별 한도를 유지한다.
- B6 media, B7 변경/재분석, B8 삭제 영향, B9 목록/cache/중복, B10 archive, B11 배포/용량 검증.
- 실제 외부 URL pilot, 운영 Firebase/Cloud IAM/스토리지/AI 통합, 부하 시험과 운영 rollout은 미실행이다.
- push·PR·merge·배포는 실행하지 않았다.

## 최종 리뷰

독립 reviewer(`gpt-6-astra`, high)가 `4d31e3c..96786fb` 전체 diff와 Task 9 문서를 검토했다. **Critical/Important/Minor 0, B0 범위에서 병합 가능** 판정이다. XML 결과와 diff check도 독립 확인했다. 다섯 Review Focus(재claim의 옛 쓰기 차단·stale 비용 정산·legacy 복구·잠금 경합·DTO/IO/자원 회귀)를 모두 확인했다.

리뷰가 판단을 보류한 후속 기능/운영 항목 8개는 아래 Ruling으로 각각 처리했다. Deferred minors는 없다.

## 구현 중 판단 기록

아래 기록은 계획 실행 ledger의 모든 Ruling을 보존한다. 각 행의 비용은 판단이 잘못됐을 때 또는 선택에 따른 부담이다.

- Ruling: 최신 origin/main을 base로 선택 — 서버 코드는 초기 셋업과 같고 계획에서 요청한 최신 제품 변경이 이미 통합돼 있기 때문 — 비용: 새 main의 문서 정책과 계획 차이를 대조해야 한다.
- Ruling: 실행 범위는 Task 1 — 사용자가 직전에 어떤 작업부터 진행하는지 물었고 Task 1을 제시한 뒤 진행을 승인했기 때문 — 비용: 나머지 B0 Task는 후속 작업으로 남는다.
- Ruling: category 없는 READY도 누락 사유가 없으면 INFORMATION_COMPLETION으로 fallback — 불완전 legacy 상태가 검토/NONE로 숨지 않게 하려는 공통 누락 정책 — 비용: future migration에서 reason을 명확히 보관해야 한다.
- Ruling: 공통 DB test fixture를 선행 보완 — 기존 B0 DB 회귀31 실패를 실제 DB 테스트로 해소하고 Task 2 계약을 검증하려면 host-port 준비가 필요 — 비용: 테스트 시작 시 호스트 연결까지 기다려 실행 시간이 소폭 늘 수 있음. production code/schema 변경 없음.
- Ruling: JsonPrimitive price를 BigDecimal+JsonUnquotedLiteral serializer로 교체 — JsonPrimitive(Number)가 Double로 변환해 소수 원문을 잃는 실패를 관찰 — 비용: JSON 전용 custom serializer 유지.
- Ruling: cancellation 검증은 API envelope와 engine 진단 응답 구분 — Ktor3.6.0 test engine은 취소에 기본 HTTP500을 대응하고 throwOnException도 이 기본 대응을 바꾸지 않음(로컬 bytecode 확인) — 비용: engine 진단 형식에 의존하는 integration assertion. 실제 support는 취소 예외를 rethrow.
- Task4 start base d8150fd. Scope Task4 after user accepted next-task proposal. RED: missing AnalysisClaim/Lane/Result/repository/guard compile failure. Tests target duplicateclaim, stale identity/version/lease, lock retention, limits and pendingcleanup. Ruling: guard requires explicit transaction — caller-owned locks cannot protect writes with autoCommit — cost: callers must begin transaction (already plan requirement). Database time is read after both locks, avoiding lease evaluation before lock waits.
- Task5 start baseabcfdfa. Scope Task5 after user accepted next proposal. RED: stale tests show missingclaim signatures/ProcessingOutcome.Stale/ReserveResult.Stale. Corrected testfixture canonicalUrl nullable typo (Metadata requiresString). No productchange. Ruling: Workers must use Task4claim now and guard existing finish transaction before writes — passing validclaim and preventing stale callback outcomes requires this bridge before Task6; existing finalfield-policy SQL stays untilTask6 — cost: remove transitional finalcode nexttask. Browser nullable render means stale distinguished by worker guard, not fabricatedmetadata.
- Ruling: Complete with no usable final category normalizes to Partial+AI_INVALID_CANDIDATE (preserveexplicit user missingreason) — READY requires usablecategory, oldcode ACKleftRUNNING — cost: faultyprocessor Complete is surfaced as manualcompletion instead of strandingjob.
- Ruling: failure/partial metadata preserves existingnon-null values, fills missingvalues frompending; USER/override protectednulls kept — spec sayspreserveexistingmetadata, stillshow usefulpartialextract — cost: metadatarefreshonpartial awaits successfulanalysis ormanualedit.
- Ruling: browser infrastructure exception maps guardedRetryable/503 likegeneral; cancellation rethrows — Task6step3 requirescurrentfaultRETRY503 andstalefaultACK, previousbrowserthrow reliedon500/reconciler — cost: existinginfrastructuretest changes to immediate lane retry; lease recovery remainsTask7.
- Ruling: legacy execution identity absent (token and claimedversion null) can recover with expired or null lease — V9 migrates V7 RUNNING to expired lease, pre-identity fixture may have null lease — cost: malformed legacy rows treated as interrupted execution but no valid claim can write through them.
- Ruling: discover without locks then per-candidate transaction takes item SKIP LOCKED followed by job SKIP LOCKED and rechecks discovered identity/lease — shared lock order and avoid waiting on active writes — cost: busy candidates defer until next scheduled scan. Recovery count includes only committed transitions.
- Ruling: add default-false skipLocked argument to shared internal lock helpers — reuse same typed item/job fields without duplicate lock SQL; current claim/guard callers retain blocking behavior — cost: helpers now offer explicit recovery locking mode.
- Ruling: BrowserWorkerServiceTest has no updated_at-based recovery fixture after Task6 exception/retry change; shared new recovery matrix exercises bothlanes — no redundant browser test edits — cost: browser recovery coverage is in AnalysisJobReconcilerTest rather than Worker class.
- Ruling: route overloads accept production synchronous functions while existing concrete service overloads delegate — permits boundary behavior testing without fake subclasses or test-only hooks, keeps Main service signatures — cost: one additional route assembly API.
- Ruling: late RuntimeResources ownership rejects and closes a new resource; duplicate identity only closes once even after shutdown — avoids leak in registration/shutdown race — cost: caller must handle registration failure. Failure from close is propagated with suppressed others; stopped-event log is fixed text with no exception/secrets.
- Ruling: Firebase app initialized by this module is owned/deleted; pre-existing default app reused without deleting someone else's resource — avoid SDK executor leak while preserving global app ownership — cost: startup still relies on existing default project matching deployment config.
- Ruling: extend Task8 to CreateWishlistItemService release-before-dispatch — bounded pool creates real starvation if postcommit callback keeps creation connection; preserve synchronous dispatch and durable outbox — cost: snapshot returned reflects creation transaction before task dispatch rather than rereading after external work. Cancellation after commit must propagate while replay remains durable.
- Ruling: serialize dispatch admission and stopping with RuntimeResources gate; stop drains already admitted synchronous dispatch then denies new work — atomic shutdown boundary, no background dispatch — cost: stopping waits for admitted Cloud Tasks call. Extend OutboxDispatcher cancellation cleanup/rethrow to honor real route composition, not only direct callbackfake.
- Task9 Ruling: 최종 whole-branch review를 Task9 커밋 전에 한 번 수행 — 계획 Step5의 문서 검토와 실행 skill의 최종 리뷰를 합쳐 runtime 전체와 최종 문서를 함께 검토 — 비용: 문서는 리뷰 시 미커밋이므로 최종 diff와 커밋을 다시 대조해야 한다.

- Final: Ruling: 생성/replay 전체 응답·상세 GET·clientCreatedAt — 승인한 B1에서 완성하며 B0에서는 기존 POST 동작과 재사용 기반을 제공 — 비용: 현재 응답의 상수/null 필드는 제품 계약을 완성하지 못한다.
- Final: Ruling: generic extraction/retry의 null failureCode — 기존 실패 mapping을 유지하고 B1 공개 mapper/B5 실패 분류에서 안전한 코드를 보완 — 비용: 현재 일부 실패에는 구체적인 공개 사유가 없다.
- Final: Ruling: category/purpose 실자원 ownership·삭제/version·FK — 실자원과 후속 삭제 연계가 생기는 B2/B3 이후 검증 — 비용: 현재 snapshot은 실제 자원 삭제/수정의 최종 유효성을 보장하지 않는다.
- Final: Ruling: snapshot transaction 안의 향후 DB 후보 조회 — 현재 runtime은 local catalog 공급이며 DB 연계 시 connection을 공유하거나 pool 경계를 설계 — 비용: 별도 connection을 무조건 열면 bounded pool에서 대기/고갈이 생길 수 있다.
- Final: Ruling: browser/maintenance runtime·Scheduler/private 인증·generation 전체 3회/30분 — 승인한 B5 범위로 남기고 현재 lane별 한도를 유지 — 비용: B0 단독으로 제품 운영 경로와 전체 retry 정책이 완성되지 않는다.
- Final: Ruling: unknown usage/만료 reservation의 보수적 최대 정산 — Task5의 기존 예산 정책 보존 요구에 따라 유지 — 비용: 실제 사용량 미상인 경우 최대 비용이 계상될 수 있다.
- Final: Ruling: 사용자 변경 route·media·cache/list/archive — 후속 묶음에서 구현하며 B0 DB fixture로 결과 무효화 경계를 검증 — 비용: 해당 제품 동작은 아직 공개 API로 이용할 수 없다.
- Final: Ruling: 운영 IAM·외부 AI/browser·용량·rollout 실행 — 승인된 local B0 범위 밖이므로 별도 검증/배포 단계에서 확인 — 비용: local 통과가 운영 준비 완료를 보장하지 않는다.
