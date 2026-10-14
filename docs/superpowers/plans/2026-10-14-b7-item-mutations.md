# B7 상품 사용자 변경 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 공통 참조 검증·CAS·값 출처·membership을 먼저 만들고 ITEM-04 → ITEM-07 → ITEM-08 → ITEM-05 → ITEM-06 순서로 상품 사용자 변경을 완성한다. ITEM-03 단일 응답에 deletionImpact를 붙인다.

**Architecture:** 기존 Ktor·JDBC modular monolith의 `app/wishlist`에 변경 use case를 둔다. 값 계산(출처·override·review·no-op)은 순수 함수, 잠금·SQL은 repository, 순서·오류 판정은 service가 맡는다. 모든 명령은 transaction 하나에서 owner → 목적(ID순) → 상품 → media(ID순) → job 순서로 잠근다. 서명과 outbox 발행은 commit 뒤에 한다.

**Tech Stack:** Kotlin 2.3.21/JDK 17, Ktor 3.6.0, PostgreSQL 16, Flyway 11.20.0, Testcontainers 1.21.3. 의존성·버전 변경 없음.

**Spec:** [B7 설계](../specs/2026-10-14-b7-item-mutations-design.md). [제품·운영 결정](../../history/product-planning/mvp/decisions/b7-item-mutation-policy-2026-10-14.md).

> 상태: **계획 승인(2026-10-14) · Task 1~9 구현 완료**. 실행 기록·계획 대비 결정은 [구현 이력](../../history/architecture/server/b7-item-mutations-implementation-2026-10-14.md)에 있다. 아래 체크박스는 승인 당시 계획이다.

## Global Constraints

- 서버·docs만 변경한다. client/·design/handoff/·다른 worktree는 수정하지 않는다. V1~V18과 `V16__recoverable_read_indexes.kt`·`ReadIndexRollout.kt`는 수정하지 않는다. 새 migration은 V19다.
- 한도: 상품명·브랜드 1~200 code point(`UserTextRules.valid(text, 200)` + `!isBlank`), 재분석 owner별 60초 10건. 상수는 `WishlistItemLimits` 한 곳에 둔다.
- `WishlistItemPolicy`의 표는 바꾸지 않는다. 명령 허용은 잠근 행으로 다시 계산한 allowedActions로 판정한다.
- 기존 Worker·복구·정리 코드는 동작을 바꾸지 않는다. B7이 고치는 공용 helper(`MutationReceipts`, `WishlistItemViewMapper`)의 기존 호출은 해당 Task에서 명시적으로 고친다.
- 잠금: `OwnerStructureLock.lock`(FOR UPDATE 유지) → 목적 `for update`(ID순) → 상품 `for update` → media `for update`(ID순) → job `for update`(ID순). media 참조 확인은 잠금 뒤 별도 문장. 외부 호출 중 connection을 쥐지 않는다. 시각은 `clock_timestamp()`.
- 오류 code는 spec의 표를 따른다. 공개 응답에 내부 예외를 넣지 않는다.
- gradle은 한 번에 하나만 실행한다. 전체 회귀는 background로 한 번 돌린다. 회귀 전에 `date -u`와 `podman run --rm docker.io/library/postgres:16-alpine date -u`의 시각이 맞는지 확인한다. container 시작 실패(ContainerLaunchException)는 환경 문제로 구분해 기록한다.
- 커밋은 `feature(server): 한글 설명`/`bugfix: …`/`docs: …` + 빈 줄 + 간결한 한글 본문, 이슈 번호 없음. 첫 커밋(결정·spec·계획) 뒤 push하고 PR은 마지막 Task에서 develop 대상으로 연다.

## 파일 구조와 책임

경로의 `app/`는 `server/src/main/kotlin/app/`, 테스트는 `server/src/test/kotlin/app/` 기준이다(새 파일은 +).

| 파일 | 책임 |
| --- | --- |
| + `server/src/main/resources/db/migration/V19__wishlist_item_mutations.sql` | `wishlist_items(owner_id,id)` unique, receipt `wishlist_item_id`·FK·단일 대상 CHECK |
| `app/persistence/MutationReceipts.kt` | `ReceiptTarget.WISHLIST_ITEM` |
| + `app/wishlist/WishlistItemLimits.kt` | 길이·재분석 한도 상수 |
| + `app/wishlist/ItemChanges.kt` | `ItemChanges`(FieldChange), `ItemChangePlan`(현재 행 + 변경 → 새 값·출처·override·no-op), `ReviewAfterEdit` |
| + `app/wishlist/ItemMutationRepository.kt` | 잠금 helper(목적·상품·media·job), 참조 검증 SQL, 상품 UPDATE, media 연결·해제, job 취소, 재분석 job/outbox |
| + `app/wishlist/ItemMutationService.kt` | ITEM-04/07/08/05 transaction 순서·오류(`ItemMutationException`) |
| + `app/wishlist/ReanalysisService.kt` | ITEM-06 receipt·한도·generation·dispatch |
| + `app/wishlist/DeletionImpact.kt` | 상세용 목적 영향 조회 |
| `app/wishlist/GetWishlistItemService.kt`, `WishlistItemRepository.kt` | 단일 조회를 REPEATABLE READ 읽기 transaction으로 바꾸고 deletionImpact를 같은 snapshot에서 포함(테스트용 pause hook) |
| `app/http/WishlistItemDtos.kt`, `WishlistItemViewMapper.kt` | `deletionImpact` DTO, 단일 응답 mapping |
| + `app/http/WishlistItemMutationRequestParser.kt` | 네 body 파서 |
| + `app/http/WishlistItemMutationRoutes.kt` | 다섯 route·오류 mapping |
| `app/Main.kt` | `apiRoutes`에 mutation route 조립(dispatch·signer 주입) |
| 테스트 + `wishlist/ItemChangePlanTest.kt`, `wishlist/ItemMutationServiceTest.kt`, `wishlist/ItemDeleteTest.kt`, `wishlist/ReanalysisServiceTest.kt`, `wishlist/ItemMutationConcurrencyTest.kt`, `wishlist/ItemMutationReadConsistencyTest.kt`, `http/WishlistItemMutationRequestParserTest.kt`, `http/WishlistItemMutationRoutesTest.kt`, `ItemMutationFlowTest.kt`, + `testutil/ItemMutationTestSupport.kt` | |
| 기존 테스트 | `DatabaseMigrationTest`(V19·upgrade), `ApiRouteExposureTest`(새 route), `WishlistItemViewMapperTest`·`WishlistDetailRoutesTest`·`WishlistRoutesTest`(deletionImpact) |

## 테스트 실행과 RED/GREEN 기록

```sh
b7_gradle() {
  env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    DOCKER_HOST=unix:///var/run/docker.sock \
    TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
    TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1 RUN_REAL_URL_PILOT=0 ./gradlew "$@"
}
```

각 Task는 테스트를 먼저 쓰고 실제로 실행해 RED를 확인한다. RED는 테스트 이름과 원인(assertion 실패 / 신규 symbol compile 실패)을 구분해 B7 구현 이력에 남긴다. GREEN은 실제 결과만 기록한다. 환경 원인 실패는 따로 적고 통과로 덮지 않는다. DB fixture는 `withAnalysisDatabase`를 쓴다.

## Task 1: V19 migration과 receipt 대상

**Files:** + V19, `MutationReceipts.kt`, `DatabaseMigrationTest`

- [ ] RED: `DatabaseMigrationTest`에 V19 적용 뒤 (a) 기존 category·purpose receipt 유지 (b) item receipt 저장 (c) 대상 0개·2개 CHECK 거절 (d) 다른 owner 상품 FK 거절 (e) V18 데이터에서 upgrade를 추가한다. migration 개수 assertion을 갱신한다.
- [ ] 실행해 실패 확인(V19 없음).
- [ ] V19와 `ReceiptTarget.WISHLIST_ITEM` 구현, GREEN.
- [ ] 커밋 `feature(server): 상품 변경 receipt 대상 migration 추가`.

## Task 2: 입력 파서와 변경 계산(순수)

**Files:** + `WishlistItemLimits.kt`, + `ItemChanges.kt`, + `WishlistItemMutationRequestParser.kt`, 테스트 2개

- [ ] RED(파서): PATCH/manual/review/reanalysis body별로 필수·선택, unknown key(`price`·`currency`·`sourceUrl`·`merchant` 포함) 422, name null 거절·숫자 타입 거절, brand null 허용, categoryId null 거절·비catalog 비UUID 거절, purposeId/mediaId null 허용·비정규 UUID 거절, expectedVersion 0·문자열·소수 거절, PATCH 편집 필드 0개 거절, decision 값, reanalysis body `""`·`{}` 허용·그 외 거절, fields 정렬.
- [ ] RED(계산): 텍스트 규칙 — 저장값과 다른 name·brand만 공백·201자·줄바꿈·제어문자 거절, 200자 허용(서로게이트 쌍 포함), brand 빈 문자열 거절, 저장값과 같은 201자·줄바꿈 추출명은 허용. `ItemChangePlan` 표 — 같은 값 no-op·출처 유지(ITEM-07 이름·category는 같은 값도 USER), 같은 참조는 검증 생략 표시, brand null on null은 override 없음, 사진 연결은 IMAGE override 없음, category 설정 시 다른 category 컬럼 null, name/brand/category/purpose/media 각 출처·override, 목적 null on UNASSIGNED/AI → USER+null, 이미 USER+null이면 no-op, 사진 해제 시 외부 URL 유무에 따른 image_source·IMAGE override 제거, category 설정 시 missingReason null. `ReviewAfterEdit` 표 — PENDING/DEFERRED × AI category 남음/목적 AI 남음/둘 다 사용자 × category·목적 미변경, NOT_REQUIRED·CONFIRMED 불변.
- [ ] 실행해 compile 실패 RED 확인, 구현, GREEN.
- [ ] 커밋 `feature(server): 상품 변경 입력 파서와 값 계산 추가`.

## Task 3: 공통 잠금·참조 검증과 ITEM-04

**Files:** + `ItemMutationRepository.kt`, + `ItemMutationService.kt`(edit), + `testutil/ItemMutationTestSupport.kt`, + `ItemMutationServiceTest.kt`

- [ ] RED(실제 PostgreSQL): 오류 순서 — stale version + ARCHIVED 목적 → VERSION, PROCESSING + stale → VERSION, STATE 뒤 membership·media 불변, 참조 위반 여러 개면 category → 목적 → media 순서. 저장된 것과 같은 category·목적·media 재전송은 검증 없이 no-op. READY 이름·brand·category(public/custom)·목적·사진 편집이 한 UPDATE와 version +1, no-op version 불변, 404(없음·다른 owner·DELETED), 409 VERSION(currentVersion), 409 STATE(PROCESSING·ARCHIVED fixture), CATEGORY_NOT_AVAILABLE(삭제된 custom·다른 owner custom), PURPOSE_NOT_AVAILABLE(다른 owner·ARCHIVED fixture), MEDIA_NOT_AVAILABLE(PENDING·FAILED·DELETING·다른 owner·다른 상품이 사용 중), 사진 교체 시 이전 media `unreferenced_since` 기록·새 media null·`updated_at` 갱신, 사진 해제 시 외부 이미지 복귀, 목적 변경 시 이전 membershipVersion만·새 목적 membershipVersion+activity, 목적 해제 USER+null, CUSTOM_CATEGORY_DELETED READY 재지정 → requiredAction NONE/CLASSIFICATION_REVIEW, PARTIAL에서 이름·category PATCH 뒤 manualCompletionAt null·MANUAL_COMPLETE 유지, FAILED_RETRYABLE PATCH 뒤 REANALYZE 유지, 결정 3의 review 전이, 실패 시 rollback(목적 membership·media 변화 없음).
- [ ] 실행해 RED 확인, 구현, GREEN.
- [ ] 커밋 `feature(server): 상품 일반 편집 ITEM-04 구현`.

## Task 4: ITEM-07 직접 보완

**Files:** `ItemMutationService.kt`, `ItemMutationServiceTest.kt`

- [ ] RED: 이름은 있고 category만 없는 PARTIAL(AI_ABSTAINED)의 줄바꿈 섞인 201자 이상 추출명을 그대로 보내도 완료됨, PARTIAL·FAILED_RETRYABLE·FAILED_TERMINAL에서 완료 → manualCompletionAt·CONFIRMED·USER 이름/category·missingReason null, analysisStatus·failureCode·predicted 진단 유지, 같은 이름도 USER로 기록, 선택 brand/media/purpose 생략=유지·null=해제, READY·PROCESSING·이미 완료 409 STATE, 완료 뒤 allowedActions EDIT/DELETE만, 409 VERSION, 참조 오류.
- [ ] 실행해 RED 확인, 구현, GREEN.
- [ ] 커밋 `feature(server): 상품 직접 보완 ITEM-07 구현`.

## Task 5: ITEM-08 검토 결정

**Files:** `ItemMutationService.kt`, `ItemMutationServiceTest.kt`

- [ ] RED: CONFIRM/DEFER, 함께 보낸 category·목적이 저장값과 다르면 USER, 저장값과 같으면 기존 AI 출처 유지(no-op 필드), 안 보낸 AI 값 출처 유지, 목적 null on AI/UNASSIGNED는 USER+null, PENDING 아닌 상품·PARTIAL·수동 완료 409 STATE, DEFERRED가 HOME-02 검토 그룹에서 빠지고 다시 들어오지 않음(이후 PATCH name만 해도 DEFERRED 유지), 확정 상품에 이후 Worker 결과가 목적을 채우지 않음(CONFIRMED+PROCESSING은 B7 공개 명령으로 만들 수 없으므로 SQL fixture로 상태를 만든 뒤 기존 finish 경로로 검증), 409 VERSION.
- [ ] 실행해 RED 확인, 구현, GREEN.
- [ ] 커밋 `feature(server): 분류·목적 검토 ITEM-08 구현`.

## Task 6: ITEM-05 삭제와 deletionImpact

**Files:** `ItemMutationService.kt`(delete), + `DeletionImpact.kt`, `GetWishlistItemService.kt`, `CreateWishlistItemService.kt`(결과에 impact), `WishlistRoutes.kt`(함수형 시그니처), `WishlistItemDtos.kt`(`@EncodeDefault(NEVER)`), `WishlistItemViewMapper.kt`, + `ItemDeleteTest.kt`, 기존 mapper·detail·create route·read route 테스트

- [ ] RED: ACTIVE(READY·PROCESSING) 삭제 → DELETED·version+1·진행 중 job(`GENERAL_PENDING/RUNNING`, `BROWSER_PENDING/RUNNING`)만 CANCELLED·token 해제, 이전 generation의 COMPLETE·PARTIAL·FAILED job은 stage·진단 그대로 유지(재분석 이력이 있는 상품 fixture로 확인)·media 해제·목적 membership, 반복 삭제 변경 없음, 다른 owner 404, ARCHIVED 409, 삭제 뒤 이전 claim의 finish(성공·실패·retry)와 claim·reconciler·PENDING 복구가 상품을 바꾸지 않음, 삭제한 상품의 사진이 24시간 뒤 정리 대상, deletionImpact(목적 있음 candidate/remaining, 마지막 후보 remaining 0, 목적 없음 `{purpose:null}`, ARCHIVED는 key 없음), 목록 카드 JSON(ITEM-02·HOME-01 preview·HOME-02)에 key 자체가 없음, 생성·replay 응답에 포함, 생성 replay tombstone에는 key 없음. 일관성 경합: `GetWishlistItemService`에 테스트용 pause hook(상품 읽기와 impact 읽기 사이)을 두고 두 사례를 **따로** 실행한다. 이동과 추가를 한 테스트에 섞으면 후보 수 변화가 상쇄돼 잘못된 snapshot도 기대값을 만족하기 때문이다. (a) 이동만: 멈춘 동안 같은 상품을 목적 A→B로 옮겨 commit → 응답은 목적 A, candidateCount = 이동 전 수(N), remainingCount = N-1. 잘못된 snapshot이면 N-1·N-2가 나온다. (b) 추가만: 멈춘 동안 다른 상품을 A에 연결해 commit → 응답 candidateCount = N, remainingCount = N-1. 잘못된 snapshot이면 N+1이 나온다. 두 사례 모두 autocommit 읽기로는 실패함을 RED로 남긴다.
- [ ] 실행해 RED 확인, 구현, GREEN. 기존 mapper 테스트는 기대 JSON을 명시적으로 고친다.
- [ ] 커밋 `feature(server): 상품 삭제 ITEM-05와 삭제 영향 구현`.

## Task 7: ITEM-06 재분석

**Files:** + `ReanalysisService.kt`, `ItemMutationRepository.kt`, + `ReanalysisServiceTest.kt`

- [ ] RED: FAILED_RETRYABLE → PROCESSING·generation+1·failureCode null·version+1, job N+1 GENERAL_PENDING·outbox 하나·receipt, dispatch 호출(일반 예외는 성공 응답·경고 로그, `CancellationException`은 다시 던지되 item·job·outbox·receipt는 commit돼 있고 같은 key 재전송이 replay 200·job 하나·미발행 outbox 유지), replay 200·변화 없음·한도 미집계, 다른 상품 같은 key 409, 삭제 뒤 replay 404, READY·PARTIAL·FAILED_TERMINAL·PROCESSING·수동 완료 409 STATE와 그 뒤 receipt 없음, 11번째 신규 429·Retry-After·receipt 없음, 이전 generation claim의 성공·실패·retry·exhausted 무시, 새 generation이 합산 예산을 새로 받음, PATCH로 넣은 USER 이름·category와 목적 USER+null이 새 generation 성공 뒤 유지.
- [ ] 실행해 RED 확인, 구현, GREEN.
- [ ] 커밋 `feature(server): 상품 재분석 ITEM-06 구현`.

## Task 8: HTTP route와 조립

**Files:** + `WishlistItemMutationRoutes.kt`, `Main.kt`, + `WishlistItemMutationRoutesTest.kt`, `ApiRouteExposureTest`

- [ ] RED: 다섯 route의 성공 status·body·`Idempotency-Replayed`, 401, 400(ID·key), 422 fields, 404, 409 code·currentVersion, 429 `Retry-After`, 응답의 사진 서명·`imageMediaId`, IO dispatcher 사용(`assertBlockingRouteIo`)·취소 전파, 공개 route 목록.
- [ ] 실행해 RED 확인, 구현, GREEN.
- [ ] 커밋 `feature(server): 상품 변경 route 연결`.

## Task 9: 경합·읽기 일관성·전체 흐름

**Files:** + `ItemMutationConcurrencyTest.kt`, + `ItemMutationReadConsistencyTest.kt`, + `ItemMutationFlowTest.kt`

- [ ] 경합(latch): 같은 version PATCH 두 개, PATCH와 review, 삭제 두 개, 같은 key 재분석 두 개(job 하나), 재분석과 삭제, 사진 연결과 media 정리(두 순서), 사진 교체 두 개가 같은 새 media.
- [ ] 읽기 일관성: 변경 뒤 ITEM-02 category·purpose·미지정 목록과 totalCount, HOME-01 group count·preview, HOME-02 그룹 소속, PUR-01/03 후보 수·membershipVersion·활동순, CAT-01 count. B4 anchor 복구 반복: ITEM-05로 anchor 삭제, ITEM-04 category 변경으로 anchor 범위 이동 뒤 ITEM-02·HOME-02 window가 다음 항목으로 복구(`anchorResolved=false`).
- [ ] 전체 흐름: fake 인증·fake Worker·local 저장소로 지연 생성(과거 clientCreatedAt, 응답 유실 뒤 같은 key 재전송) → 실패 → 재분석 → READY → DEFER → PATCH(사진 연결·해제) → 삭제. 기기 LocalSubmission은 클라이언트 범위임을 이력에 적는다.
- [ ] 실행해 기대와 다르면 원인을 기록하고 앞 Task 코드를 고친다(새 RED로 남김). GREEN.
- [ ] 커밋 `feature(server): 상품 변경 경합·일관성 검증 추가`.

## Task 10: 문서·전체 회귀·리뷰·PR

- [ ] spec 6절의 문서를 갱신한다(계약·목록·순서·INDEX·제품·client 연동 상태·이력).
- [ ] 시각 확인 뒤 전체 회귀를 background로 한 번 실행하고 결과를 이력에 기록한다.
- [ ] 독립 리뷰를 받고, 반영 전 영향받는 기존 테스트·동작을 먼저 정리한다. 설계 선택이 필요하면 사용자에게 묻는다. 반영 뒤 대상·전체 회귀를 다시 기록한다.
- [ ] 커밋 `docs: B7 상품 사용자 변경 문서 갱신`, push, develop 대상 PR.

## Review Focus

- 모든 명령이 owner → 목적 → 상품 → media → job 순서를 지키는지. owner 잠금 없이 목적 row를 갱신하는 경로가 없는지(B3 전제).
- media 연결이 media를 `for update`로 잠근 뒤 별도 문장으로 다른 상품 참조를 확인하는지, 해제·교체·삭제가 `unreferenced_since`를 빠뜨리지 않는지(빠지면 사진이 정리되지 않는다).
- no-op 판정이 출처·override·review 변화까지 포함하는지. 같은 값 재전송이 version을 올리거나 AI 출처를 USER로 바꾸지 않는지.
- 결정 3 review 규칙이 category·목적이 실제로 바뀐 경우에만 적용되는지.
- 재분석 replay가 한도·상태 검사보다 먼저인지, 실패한 transaction이 receipt를 남기지 않는지, dispatch 실패가 응답을 실패시키지 않는지.
- 삭제가 RUNNING job의 token을 해제해 Worker 중간·최종 쓰기를 모두 막는지.
- deletionImpact가 목록 카드 쿼리에 비용을 더하지 않는지.
