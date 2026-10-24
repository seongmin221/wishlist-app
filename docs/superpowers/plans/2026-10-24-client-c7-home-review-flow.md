# C7 홈·연속 처리·목록 anchor 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** B4 ITEM-02 window·HOME-01·HOME-02를 KMP Fake/Remote로 연결하고, 홈 할 일·목적 요약, 분류·목적 확인/정보 보완 연속 처리(조회+모션, 명령은 C8), 카테고리 목록·목적 상세 anchor 복원을 Android·iOS에 구현한다.

**Architecture:** 기존 SessionLane Presenter·API 단위 repository·runtime gate·cache decorator를 확장한다. KMP가 window/큐/위치/넘김 기록을 소유하고, 플랫폼은 펼침·카드 드래그·전환 애니메이션·스크롤 anchor 측정만 소유한다.

**Tech Stack:** Kotlin 2.3 KMP(Ktor, kotlinx.serialization, SQLDelight, Koin, SKIE), Compose, SwiftUI(iOS 17).

**Spec:** [C7 설계](../specs/2026-10-24-client-c7-home-review-flow-design.md)

## Global Constraints

- 갱신은 진입·foreground·사용자 새로고침(및 이 신호로 돈 전송 조정기 refresh 완료)만. polling·주기 조회 금지.
- 홈 목적은 HOME-01 `recentPurposes` 그대로(최대 3, 빈 목적 포함), 미리보기 최대 4, null 이미지 placeholder.
- ‘처음부터 다시 보기’ = 이번 세션 넘김 기록만 초기화 + cursor 없는 첫 페이지 재조회. restart API·review 되돌리기 없음.
- 확정·보류는 임시 넘김(서버 저장 없음), 요약 `넘김 n`만, 안내 "확정·보류 저장은 다음 단계에서 연결돼요". 정보 보완은 건너뛰기만, 요약 `건너뜀 n`, 저장·재분석·입력 비활성. C8 명령을 완료로 표시하지 않는다.
- window: `before`/`after` 기본 20(0~20), page limit ITEM 40 / HOME 20. anchor는 cursor/limit과 함께 보내지 않는다.
- 모션: 카드 340 `throw` ±480 ±16°(회전 중심 x 50%, y 90%), 다음 묶음 delay 40 · 300 `emphasized` scale .94→1 translateY 14→0, 뒤 카드 최대 2장(-2.5°·3°, 8·14), 놓기 임계 `|dx| ≥ W×0.3` 또는 800/s, 미달 250 `emphasized` 복귀, 나가는 동안 입력 잠금. 보완 넘김 320 `standard` 내용만 -W / +W→0, 전환 중 버튼 잠금. 홈 화살표 200ms 180°.
- DEBUG: HOME-01/02 FAKE, RELEASE: UNAVAILABLE. 서버 파일·PR #22 파일 내용은 가져오지 않는다. #22와 겹치는 플랫폼 파일(WLNavHost, CategoryListScreen, strings.xml, Localizable.xcstrings, pbxproj, WLEntryOwners, HomeRowText)은 필요한 최소 변경만 하고 새 코드는 새 파일에 둔다.
- 커밋: `feature(kmp|android|ios): 한글 설명` + 빈 줄 + 짧은 본문 + `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- 빌드: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=$HOME/Library/Android/sdk ./client/gradlew -p client -Dorg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home <tasks>` (저장소 루트에서).

## Review Focus

1. 연속 처리 중 foreground에서 현재 카드가 다른 곳에서 처리돼 그룹에서 빠짐(`anchorResolved=false`) → 오류 없이 대체 카드부터 이어 가고, 이미 넘긴 카드는 다시 나오지 않는다. (Task 4 `foregroundFallbackSkipsPassed`)
2. 캐러셀 2번째 상품에서 시작 → 첫 카드가 그 상품, `n / 전체`가 `1 / (전체−1)`이며 restart 후 첫 상품부터. (Task 4 `startFromAnchorCountsFromThere`)
3. 계정 전환 중 늦게 온 HOME-01/HOME-02/window 응답 → 새 계정 화면에 게시되지 않는다. (Task 3·4·5 각 `staleSession…`)
4. 목록 맨 위 카드가 삭제된 뒤 당겨서 새로고침 → 대체 카드로 복원(`sameItem=false`)하고 오류 표시 없음. (Task 5 `refreshWithRemovedAnchorRestoresFallback`)
5. 홈 HOME-01 실패 → 마지막 요약 유지 + `refreshFailed`, 첫 실패면 할 일 영역 대신 재시도. (Task 3 `summaryFailureKeepsLast`)

---

### Task 1: ITEM-02 window 계약 (모델·Fake·Remote·cache)

**Files:**
- Modify: `client/shared/src/commonMain/kotlin/app/wishlist/shared/model/CategoryCatalog.kt` (ItemPage)
- Modify: `.../repository/CategoryRepository.kt` (ItemListRepository)
- Modify: `.../data/fake/FakeStore.kt`, `.../data/fake/FakeCategoryRepository.kt`
- Create: `.../data/fake/FakeWindow.kt` (정렬된 목록에서 page/window를 만드는 순수 함수, HOME-02와 공유)
- Modify: `.../data/remote/CategoryMapper.kt` (`parseItemPage` → 카드 anchor·previous·anchor 필드), `.../data/remote/RemoteCategoryRepository.kt`
- Modify: `.../data/local/CachedItemListRepository.kt`, `.../di/RuntimeFacades.kt` (Gated/Unavailable)
- Test: `commonTest/.../data/fake/FakeWindowTest.kt`(신규), `data/remote/CategoryMapperTest.kt`, `data/remote/RemoteCategoryRepositoryTest.kt`, `repository/CategoryRepositoryContractTest.kt`, `data/remote/ScriptedCategoryServer.kt`, `data/local/CachedItemListRepositoryTest.kt`

**Interfaces — Produces:**
- `data class ItemAnchor(val requestedItemId: String, val resolvedItemId: String?, val resolved: Boolean)`
- `data class ItemPage(items, totalCount, nextCursor, previousCursor: String? = null, anchorCursors: Map<String, String> = emptyMap(), anchor: ItemAnchor? = null)`
- `ItemListRepository.byCategoryAround(categoryId: String, anchor: String, before: Int = 20, after: Int = 20): ClientResult<ItemPage>` and `byPurposeAround(purposeId, anchor, before, after)` (기본 구현 UNAVAILABLE, Fake/Remote/Cached/Gated 구현).
- `internal fun <T> fakeWindow(sorted: List<T>, key: (T) -> Pair<Instant, String>, prefix: String, cursor: String?, limit: Int?, anchor: String?, before: Int, after: Int): ClientResult<FakeSlice<T>>` — `FakeSlice(items, previousCursor, nextCursor, anchor: ItemAnchor?, anchorOf: (T) -> String)`. 카드 anchor token `"${prefix}a:${createdAt}|${id}"`, page token next `n:`, previous `p:`, 복귀 inclusive `ni:`/`pi:`.

규칙(서버 문서 그대로): 정렬 `createdAt DESC, id DESC`; anchor 위치가 scope에 있으면 resolved=true; 없으면 정렬상 다음(더 오래된) → 없으면 바로 앞(더 새로운) 항목, resolved=false; scope 비면 resolvedItemId=null, items=[]; 응답 = 대체 anchor 포함 최대 before+1+after; window 밖에 항목이 있을 때만 cursor; 경계 없는 첫 페이지 previous=null; 진행 방향이 비면 반대쪽 가장 가까운 항목을 가리키는 inclusive 복귀 cursor; 다른 scope·prefix의 token은 `INVALID_WISHLIST_CURSOR`(기존 Fake 코드 `INVALID_WISHLIST_ITEM_CURSOR` 유지 — 기존 테스트 호환), before/after 범위 밖 `INVALID_WISHLIST_ITEM_QUERY`.

- [ ] **Step 1: 실패 테스트** — `FakeWindowTest`: `anchorInScopeReturnsBothSides`(10개, anchor 5번째, before=2 after=2 → ids[2..6], resolved=true, previous/next non-null), `removedAnchorFallsToOlderThenNewer`(anchor 삭제 → 다음 오래된 항목, 맨 끝 삭제 → 바로 앞), `emptyScopeKeepsRequestedOnly`, `firstPageHasNoPrevious`, `emptiedDirectionReturnsInclusiveReturnCursor`, `foreignTokenRejected`, `boundsRejected`(before=21). Contract test(Fake+MockEngine 공통) `categoryWindowAroundAnchor`, `purposeWindowAroundAnchor`, `previousCursorLoadsNewer`. Mapper test: 카드 `anchorCursor`가 `anchorCursors[item.id]`, `requestedAnchorItemId/resolvedAnchorItemId/anchorResolved` → `ItemAnchor`, page 모드 null. Remote test: around 요청 query가 정확히 `categoryId=..&anchor=..&before=20&after=20`(cursor·limit 없음).
- [ ] **Step 2:** `./client/gradlew ... :shared:jvmTest`(또는 `:shared:testDebugUnitTest`, 기존 host task) — 새 테스트 FAIL 확인.
- [ ] **Step 3:** 구현. 기존 `itemsByCategory/itemsByPurpose`는 `fakeWindow`로 재작성(기존 cursor 테스트 통과 유지). Cached decorator는 around 결과도 upsert.
- [ ] **Step 4:** host 테스트 PASS.
- [ ] **Step 5:** 커밋 `feature(kmp): ITEM-02 window·anchor 계약 연결`.

### Task 2: HOME-01/02 repository (모델·Fake·Remote·cache·runtime)

**Files:**
- Create: `.../model/HomeSummary.kt`, `.../repository/HomeRepository.kt`, `.../data/fake/FakeHomeRepository.kt`, `.../data/remote/HomeMapper.kt`, `.../data/remote/RemoteHomeRepository.kt`, `.../data/local/CachedHomeRepository.kt`
- Modify: `FakeStore.kt`(home summary/group), `di/RepositoryBindings.kt`(FAKE/REMOTE 구현 집합에 HOME_01, HOME_02), `di/SharedModules.kt`, `di/SharedRuntime.kt`(`homeRepository()`, `resolvedBackend`), `di/RuntimeFacades.kt`(Gated/Unavailable), Android `di/AppRuntimeConfig.kt` + iOS `AppRuntimeConfig.swift`의 DEBUG 바인딩, `android/src/testDebug/.../AppRuntimeConfigDebugTest.kt`
- Test: `commonTest/.../repository/HomeRepositoryContractTest.kt`(신규, Fake + `ScriptedHomeServer` MockEngine), `data/remote/HomeMapperTest.kt`, `data/local/CachedHomeRepositoryTest.kt`, `di/SharedModulesTest.kt`

**Interfaces — Produces:**
- `data class HomePreview(val item: WishlistItem, val anchorCursor: String)`
- `data class HomeGroupSummary(val group: HomeActionGroup, val count: Int, val previews: List<HomePreview>)`
- `data class HomeSummary(val groups: List<HomeGroupSummary>, val recentPurposes: List<PurposeSummary>)` — groups는 항상 3개, `HomeActionGroup.entries` 순서.
- `interface HomeRepository { suspend fun summary(): ClientResult<HomeSummary>; suspend fun group(group: HomeActionGroup, cursor: String? = null, limit: Int = 20): ClientResult<ItemPage>; suspend fun groupAround(group: HomeActionGroup, anchor: String, before: Int = 20, after: Int = 20): ClientResult<ItemPage> }`
- `SharedRuntime.homeRepository(): HomeRepository`
- Remote: `GET /v1/home`(query 없음), `GET /v1/home/action-items?group=<NAME>&limit=20` / `&cursor=` / `&anchor=&before=&after=`. 오류 코드 `INVALID_HOME_QUERY`, `INVALID_HOME_CURSOR` 보존. recentPurposes는 기존 PUR-01 summary item mapper 재사용(UI key 변환 포함). 알 수 없는 group 값의 원소는 무시, 빠진 그룹은 count 0.

Fake 규칙: 그룹 = `homeActionGroup(item.requiredAction)`(ACTIVE만), 정렬 `createdAt DESC, id DESC`, previews = 그룹 최신 4개 + HOME-02 anchor token, recentPurposes = 목적 activity 순 최대 3(PUR-01과 같은 summary 생성 재사용). 기기 pending은 넣지 않는다. BoardSeeds가 보드처럼 분류·목적 확인 3 / 정보 보완 3 / 분류 중 1이 되도록 시드를 확인·보완(기존 C5/C6 시드 숫자 보존; 부족하면 새 시드 항목 추가).

- [ ] **Step 1: 실패 테스트** — contract: `summaryFixedOrderWithEmptyGroups`, `summaryAtMostFourPreviewsNewestFirst`, `summaryRecentPurposesAtMostThreeIncludesEmpty`, `previewAnchorOpensGroupWindow`, `informationGroupIncludesCategoryAssignmentAndReassignment`, `groupPagesNewestFirst`, `groupAroundRemovedAnchorFallsBack`, `invalidGroupCursorRejected`(`INVALID_HOME_CURSOR`), `staleSessionNotPublished`(계정 전환 중 응답은 SESSION_CHANGED). Mapper: 서버 문서 HOME-01 예시 JSON(빈 그룹·빈 목적) 파싱, 알 수 없는 group 무시. Bindings: RELEASE에서 HOME FAKE 거절, DEBUG FAKE 허용.
- [ ] **Step 2:** host 테스트 FAIL 확인.
- [ ] **Step 3:** 구현.
- [ ] **Step 4:** `:shared:allTests` PASS, Android `:android:testDebugUnitTest` 바인딩 테스트 PASS.
- [ ] **Step 5:** 커밋 `feature(kmp): HOME-01·HOME-02 Fake·Remote 연결`.

### Task 3: HomePresenter 할 일·목적 요약

**Files:**
- Modify: `.../presentation/HomePresenter.kt`, `.../presentation/HomeState.kt`, `.../di/SharedRuntime.kt`(`homePresenter()`에 `homeRepository()`·session 주입)
- Test: `commonTest/.../presentation/HomePresenterTest.kt`

**Interfaces:**
- Consumes: Task 2 `HomeRepository`, `HomeSummary`.
- Produces:
  - `data class HomeGroupCard(val group: HomeActionGroup, val count: Int, val previews: List<HomePreview>)`
  - `HomeState.LoggedIn(val pending: List<HomeRow>, val groups: List<HomeGroupCard>, val purposes: List<PurposeSummary>, val todoCount: Int, val summaryLoaded: Boolean, val summaryError: ClientError?, val refreshing: Boolean, val refreshFailed: Boolean)` — `groups`는 count>0만, 고정 순서 `CLASSIFICATION_REVIEW, INFORMATION_COMPLETION, ANALYSIS_IN_PROGRESS`(보드 위→아래). `pending`은 기기 미전송(기존 local 행). `todoCount = pending.size + 세 그룹 count 합`.
  - intents: `appeared()`(홈이 보일 때 HOME-01 조회), `foreground()`(= coordinator refresh + HOME-01), 기존 `refresh()/refreshNow()`(coordinator refresh 후 HOME-01, 둘 다 끝나야 refreshing=false), `retry()`, `tick()`. coordinator `refreshes` 증가 시 HOME-01 재조회(single-flight, 진행 중이면 한 번 더 예약). 
  - 계정 전환: summary 초기화, 옛 응답 무시. LoggedOut 그대로.

- [ ] **Step 1: 실패 테스트** — `todoCountSumsPendingAndGroups`(pending 1 + 3/3/1 → 8), `groupsHideZeroAndKeepBoardOrder`, `purposesComeFromSummary`, `refreshRunsCoordinatorThenSummary`, `coordinatorRefreshReloadsSummaryOnce`(refreshes 2번 빠르게 → 조회 ≤2, 동시 1), `summaryFailureKeepsLast`(성공 후 실패 → groups 유지, refreshFailed=true; 첫 실패 → summaryError), `staleSessionSummaryDropped`, `noTimerRequests`(시간만 흘려도 HOME-01 호출 0 — `tick()` 포함).
- [ ] **Step 2:** FAIL 확인. **Step 3:** 구현(기존 C3 테스트 통과 유지, `processing` 필드 사용처는 플랫폼 Task에서 교체). **Step 4:** PASS. **Step 5:** 커밋 `feature(kmp): 홈 할 일·목적 요약 Presenter`.

### Task 4: HomeFlowPresenter 연속 처리 큐

**Files:**
- Create: `.../presentation/HomeFlowPresenter.kt`, `.../presentation/HomeFlowState.kt`
- Modify: `.../di/SharedRuntime.kt`(`homeFlowPresenter()`)
- Test: `commonTest/.../presentation/HomeFlowPresenterTest.kt`

**Interfaces — Produces:**
- `data class HomeFlowCard(val item: WishlistItem, val anchorCursor: String)`
- `enum class HomeFlowPhase { LOADING, ACTIVE, DONE, ERROR }`
- `data class HomeFlowState(val group: HomeActionGroup?, val phase: HomeFlowPhase, val current: HomeFlowCard?, val upcoming: List<HomeFlowCard>, val position: Int, val total: Int, val passed: Int, val error: ClientError?, val refreshing: Boolean)` — `upcoming` 최대 2, `position = passed + 1`(DONE이면 passed), `total = passed + remaining`.
- `class HomeFlowPresenter : SessionLane` intents: `start(group: HomeActionGroup, startAnchor: String?)`(CLASSIFICATION_REVIEW·INFORMATION_COMPLETION만, 그 외 무시), `pass()`, `restart()`, `foreground()`, `retry()`, `close()`.

알고리즘(Presenter 안, lane-confined):
- `passedIds: Set<String>`(이번 세션), `queue: List<HomeFlowCard>`(현재 포함 이후), `next: String?`(HOME-02 nextCursor).
- start: anchor 없으면 `group(g)`, 있으면 `groupAround(g, anchor)` → anchor 앞 항목 ID를 passedIds가 아닌 `skippedBefore`(카운트 안 함)로 두고 queue = resolved부터.
- `remaining = totalCount − before`; `before` = window에 previousCursor 없으면 queue 첫 카드 앞 window 항목 수, 있으면 `passedIds.size + skippedBefore.size`.
- pass: current를 passedIds에 넣고 queue에서 제거, passed+1. queue가 2 이하이고 `next`가 있으면 다음 page를 붙인다(passedIds·skippedBefore 제외). queue 비고 next 없음 → DONE.
- foreground: current가 있으면 `groupAround(g, current.anchorCursor)`로 queue 재구성(resolved부터, passedIds·skippedBefore 제외); fallback이 앞쪽 항목이면 그 항목 이후만. 결과 queue 비면 DONE. DONE 상태 foreground는 아무것도 하지 않는다.
- restart: passedIds·skippedBefore·passed 초기화 후 `group(g)` 첫 페이지.
- 실패: 시작 실패 → ERROR(retry로 같은 요청), 갱신 실패 → 현재 큐 유지 `refreshing=false`(오류 미표시, 다음 신호에 재시도).

- [ ] **Step 1: 실패 테스트** — `startShowsFirstWithStackOfTwo`(3개 → current+upcoming 2, `1 / 3`), `passAdvancesAndCounts`(pass → `2 / 3`, passed 1), `lastPassEndsDone`(DONE, passed 3), `startFromAnchorCountsFromThere`(5개 중 2번째 anchor → current=2번째, `1 / 4`; restart → 1번째, `1 / 5`, passed 0), `restartClearsOnlySession`(restart가 서버 상태 변경 호출 없음 — fake 호출 기록), `foregroundKeepsCurrentByAnchor`(다른 곳에서 앞쪽 신규 저장 → current 유지), `foregroundFallbackSkipsPassed`(current 제거 → 다음 오래된 항목, 넘긴 ID 미재등장), `appendsNextPageNearEnd`(25개, page 20 → 넘기다 21번째 도달), `emptyGroupIsDone`(0개 → DONE passed 0), `startFailureThenRetry`, `staleSessionFlowDropped`, `ignoresOtherGroups`(ANALYSIS_IN_PROGRESS start 무시), `noRequestWithoutSignal`(시간 경과만으로 조회 0).
- [ ] **Step 2:** FAIL 확인. **Step 3:** 구현. **Step 4:** PASS. **Step 5:** 커밋 `feature(kmp): 연속 처리 조회 큐와 임시 넘김`.

### Task 5: 목록 anchor 갱신 (CategoryList·PurposeDetail)

**Files:**
- Create: `.../presentation/ItemWindow.kt` (두 Presenter가 공유하는 lane-confined window 상태: items, previous/next cursor, anchorCursors, prepend/append/replace 병합)
- Modify: `.../presentation/CategoryListPresenter.kt`, `.../presentation/PurposeDetailPresenter.kt`
- Test: `commonTest/.../presentation/ItemWindowTest.kt`(신규), `CategoryListPresenterTest.kt`, `PurposePresenterTest.kt`

**Interfaces — Produces:**
- `data class AnchorRestore(val resolvedItemId: String?, val sameItem: Boolean, val token: Long)` — 플랫폼이 token 변경 시 한 번 적용.
- State 추가 필드(두 상태 공통): `hasNewer: Boolean`, `prepending: Boolean`, `restore: AnchorRestore?`.
- Intents: `refresh(anchorItemId: String?)`(기존 `refresh()`는 `refresh(null)`), `refreshNow(anchorItemId: String?)`, `foreground(anchorItemId: String?)`(진행 중 요청 있으면 무시, 머리도 조용히 갱신), `loadNewer()`.
- 규칙: anchorItemId의 anchorCursor가 있으면 `*Around(anchor, 20, 20)`로 items 전체 교체 + restore(resolved, sameItem = anchor.resolved); 없으면 첫 페이지 교체 + restore(null,false). `loadNewer`는 previousCursor로 받아 앞에 붙이고 ID 중복 제거. 이어 붙이기·gone·계정 규칙은 기존 유지.

- [ ] **Step 1: 실패 테스트** — `ItemWindowTest`: `replaceAroundKeepsCursors`, `prependDedupes`, `appendDedupes`. Presenter: `refreshWithAnchorUsesWindow`(페이지 2개 불러온 뒤 30번째 anchor refresh → around 호출, items = window, restore.sameItem=true), `refreshWithRemovedAnchorRestoresFallback`(sameItem=false, itemsError 없음), `refreshWithoutAnchorUsesFirstPage`, `loadNewerPrepends`, `foregroundIgnoredWhileLoading`, `staleSessionWindowDropped`(카테고리·목적 각각). 기존 C5/C6 테스트 전부 유지.
- [ ] **Step 2:** FAIL 확인. **Step 3:** 구현. **Step 4:** `:shared:allTests` PASS(host + iOS simulator). **Step 5:** 커밋 `feature(kmp): 카테고리·목적 목록 anchor 갱신`.

### Task 6: Android 홈·연속 처리·목록 anchor

**Files:**
- Modify: `android/.../feature/home/HomeScreen.kt`, `HomeLoggedInContent.kt`(있으면; 없으면 Create), `HomePresenterOwner.kt`, `HomeRowText.kt`(최소)
- Create: `android/.../feature/home/HomeTodoCard.kt`(접힘/펼침·캐러셀), `HomePurposeSummary.kt`, `android/.../feature/flow/{HomeFlowRoute.kt, HomeFlowOwner.kt, ReviewFlowScreen.kt, ReviewCardStack.kt, FillFlowScreen.kt, FlowSummary.kt, CardThrowMath.kt}`
- Modify(최소): `ui/AppRoute.kt`/`AppRoutes.kt`(flow route), `navigation/WLNavHost.kt`(route 등록), `feature/category/CategoryListScreen.kt`, `feature/purpose/PurposeDetailScreen.kt`, `res/values/strings.xml`, `res/values-en/strings.xml`, `WishlistApplication.kt`(foreground 신호를 화면에 전달하는 기존 경로 확인)
- Test: `android/src/test/.../feature/flow/CardThrowMathTest.kt`(임계·방향·회전), `HomeFlowOwnerTest.kt`, `feature/home/HomeTodoTextTest.kt`, `ui/AppRouteCodecTest.kt`

**Interfaces:** Consumes Task 3 `HomeState.LoggedIn`, Task 4 `HomeFlowPresenter/HomeFlowState`, Task 5 intents·`AnchorRestore`.
- `CardThrowMath.decide(dx: Float, velocityX: Float, width: Float): ThrowDecision` (`Right`/`Left`/`Return`), `rotationFor(dx, width) = dx / width * 16f`.
- 홈 foreground: 홈이 최상단이면 `owner.foreground()`; 연속 처리·목록 화면도 각자 최상단일 때 foreground intent. 홈 재등장(appeared)은 C6 목적 홈 재등장 패턴을 따른다.
- 목록: 첫 보이는 카드 key → `refresh(id)`/`foreground(id)`, `restore` token 적용으로 `scrollToItem(index, offset)`(sameItem이면 기존 offset), 맨 위 도달 시 `loadNewer()`.

- [ ] **Step 1:** 순수 로직 실패 테스트(CardThrowMath: dx=0.3W → Right, 0.29W & v=799 → Return, v=-800 → Left; rotation(W/2)=8°; 텍스트: 요약 `넘김 3`, `건너뜀 2`, 위치 `2 / 5`, 할 일 `할 일 8개`). Run `:android:testDebugUnitTest` FAIL.
- [ ] **Step 2:** 화면 구현(보드 L/D 값·모션 값 Global Constraints 그대로). 비활성 요소: 리뷰 `변경`, 보완 `저장하고 다음`·`다시 분석하기`·입력칸(alpha `DISABLED_ALPHA`, 접근성 상태 "사용할 수 없음"), 리뷰 안내 줄 문구.
- [ ] **Step 3:** `:android:testDebugUnitTest :android:assembleDebug :android:testReleaseUnitTest :android:assembleRelease :android:lintDebug` PASS(lint 새 경고 0).
- [ ] **Step 4:** 에뮬레이터 390×844 L/D 스크린샷: FHome(접힘·펼침), ReviewFlow(카드·드래그 중·요약), FillFlow(첫 상품·요약), 카테고리 목록 anchor 복원 전후 → `docs/history/architecture/client/c7-shots/`.
- [ ] **Step 5:** 커밋 `feature(android): 홈 할 일·연속 처리·목록 anchor 화면`.

### Task 7: iOS 홈·연속 처리·목록 anchor

**Files:**
- Modify: `ios/Wishlist/Features/Home/HomeScreen.swift`, 홈 owner 파일, `HomeRowText.swift`(최소)
- Create: `ios/Wishlist/Features/Home/{HomeTodoCard.swift, HomePurposeSummary.swift}`, `ios/Wishlist/Features/Flow/{HomeFlowOwner.swift, ReviewFlowScreen.swift, ReviewCardStack.swift, FillFlowScreen.swift, FlowSummary.swift, CardThrowMath.swift}`
- Modify(최소): `AppRoutes.swift`, `Navigation/WLNavHost.swift`, `Features/Category/CategoryListScreen.swift`·`CategoryOwners.swift`, `Features/Purpose/PurposeDetailScreen.swift`·`PurposeOwners.swift`, `Resources/Localizable.xcstrings`, `Wishlist.xcodeproj/project.pbxproj`
- Test: `ios/WishlistTests/{CardThrowMathTests.swift, HomeFlowOwnerTests.swift, HomeTodoTextTests.swift}`

**Interfaces:** Task 6과 같은 순수 로직 이름·값(`CardThrowMath.decide`, `rotation`). foreground는 기존 `scenePhaseChanged` 패턴, 목록 위치는 `scrollPosition(id:)` + `restore.token` 1회 적용, 위쪽 끝 `loadNewer()`.

- [ ] **Step 1:** 순수 로직 실패 XCTest(Task 6 Step 1과 같은 값). `xcodebuild test -project client/ios/Wishlist.xcodeproj -scheme Wishlist -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro'` FAIL.
- [ ] **Step 2:** 화면 구현(Task 6 Step 2와 동일 규칙).
- [ ] **Step 3:** XCTest 전체 PASS, Release simulator build PASS.
- [ ] **Step 4:** 시뮬레이터 L/D 스크린샷(Task 6 Step 4 목록) → `c7-shots/`.
- [ ] **Step 5:** 커밋 `feature(ios): 홈 할 일·연속 처리·목록 anchor 화면`.

### Task 8: 통합 검증·문서

**Files:** `docs/architecture/client/server-integration-status.md`(ITEM-02 window·HOME-01/02 행, C6 MERGED #23 `896fbc2` 갱신, C7 절), `docs/architecture/client/kmp.md`(C7 절·C5/C6 한계 표의 C7 항목 해결 표시), `android.md`·`ios.md`(화면 위치), `docs/history/architecture/client/c7-home-review-flow-implementation-2026-10-24.md`(신규) + `INDEX.md`, `c7-shots/INDEX.md`, `docs/superpowers/specs/INDEX.md`·`plans/INDEX.md`·`docs/INDEX.md` 현재 작업 줄, 로드맵 C6 행(MERGED)·C7 행, `docs/architecture/client/INDEX.md`, `docs/product/inspect-and-edit-a-product.md`/`organize-candidates.md`에 C7 임시 넘김 사용자 답변(제품 정책 아님, C7 구현 한정) 반영 여부 판단 — 제품 규칙은 바꾸지 않고 history에만 기록.

- [ ] **Step 1:** `:shared:allTests :shared:linkReleaseFrameworkIosArm64`, Android 전체, iOS test + Release build, `python3 client/tools/gen_tokens.py --check`, `git diff --check` 실행·결과 기록.
- [ ] **Step 2:** 예외 수동 확인(DebugControls failNext HOME_01/HOME_02/ITEM_02, 빈 그룹, 오프라인, anchor fallback)과 결과 기록.
- [ ] **Step 3:** 문서 갱신. **Step 4:** 커밋 `docs: C7 홈·연속 처리 구현 기록과 연동 상태 갱신`.
