# Client C4 Product Detail & Web View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 홈의 "분류 중"·로컬 대기 줄에서 상품 상세(분석 중·완료·정보 보완 필요)와 로컬 대기 화면으로 들어가고, 분석이 끝나면 같은 화면이 상세로 바뀌며, 아직 서버에 없는 공유를 이 기기에서 지울 수 있게 한다(PR A). 이어서 원본 링크를 보안 규칙이 있는 앱 안 웹뷰로 연다(PR B).

**Architecture:** 서버 상품은 기존 `ItemDetailPresenter`를 확장하고, 로컬 대기는 coordinator view를 관찰하는 새 `LocalSubmissionDetailPresenter`가 맡는다. 화면 종류는 shared 순수 함수 `DetailKind`가 서버 상태(`lifecycleStatus` → `requiredAction`)로 정한다. 플랫폼 셸은 entry별 owner(Android entry `ViewModelStore`, iOS `WLEntryOwners`)와 "계정을 떠날 때만" 계정 범위 route를 걷어내는 규칙을 갖는다. 웹뷰는 각 플랫폼 native이며 판정은 플랫폼별 순수 함수다.

**Tech Stack:** Kotlin 2.3.21, AGP 9.0.0, Gradle 9.3.0, SQLDelight 2.4.1, Koin 4.2.2, Ktor 3.4.3, SKIE, kotlin.test·coroutines-test·Turbine. Android Compose(API 26+) + Coil 3(새 의존성, D5), iOS SwiftUI(17+) + WKWebView, Xcode 26.6.

**Spec:** [C4 설계](../specs/2026-10-09-client-c4-product-detail-design.md)(D1~D20). 함께 볼 것: [KMP 알려진 한계](../../architecture/client/kmp.md#알려진-한계와-인계-단계), [상품 상세 Presenter 기반](../../architecture/client/kmp.md#상품-상세-presenter-기반), [상태 API 홈 조치 상태](../../architecture/wishlist-item-state-api.md#홈-조치-상태), [QA-CLI-001](../../learning/client/q-and-a/QA-CLI-001-webview-navigation.md), [디자인 결정](../../design/decisions.md), 보드 `design/handoff/screens/boards/{FProductDetail,FProductProcessing,FWebView,FWebViewShare,FWebViewExternal}{L,D}.dc.html`(문구 원본 `design/canvas-fresh/gen_product.py`·`gen_account.py`).

## Global Constraints

- 상태: **초안 v1 (2026-10-10) · 사용자 검토 대기.**
- 작업 공간 `/Users/user/orca/workspaces/wishlist-app/client-c4-product-detail`, 브랜치 `client/c4-product-detail`(PR A, base `develop`). PR B는 Task 13 시작 때 `client/c4-product-detail` 위에 `client/c4-webview`를 만들고 base를 A로 연다. A merge 뒤 `origin/develop`으로 rebase하고 base를 `develop`으로 바꾼다(D20). 시작 HEAD `abae37d`(origin/develop, PR #14 merge). merge는 사용자 승인 없이 하지 않는다. 다른 워크스페이스는 수정·삭제하지 않는다.
- Kotlin **2.3.21**, Android **API 26+**, iOS **17+**, JDK **17**. 새 의존성은 **Coil 3만**(D5, 사용자 승인). iOS는 의존성 추가 없음. 공유 코드에 Compose/SwiftUI 의존성 없음.
- 서버 mutation(ITEM-04~08) 호출 없음. `allowedActions`가 있어도 편집·서버 삭제·다시 분석 UI를 두지 않는다(D2).
- 인증은 fake만(실서버·Firebase는 "인증 연결" 단계).
- domain·Presenter·LocalStore·Coordinator는 TDD. 새 테스트마다 **해당 수정만 되돌려 실패하는지 확인**하고 task 기록에 남긴다. `commonTest`는 Android host와 iOS simulator 모두 실제 실행하고 `NO-SOURCE`/`SKIPPED`를 통과로 기록하지 않는다.
- 전송 coordinator의 재시도 타이머·200ms 게시 간격 때문에 virtual time 테스트에서 실패를 고정해 볼 때는 `advanceUntilIdle` 대신 `runCurrent`를 쓴다(계속 실패하면 끝나지 않는다).
- 커밋: `feature(kmp|android|ios): 한글 설명` / `bugfix: …` / `docs: …` + 빈 줄 + 짧은 본문 + `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. 이슈 번호가 생기면 `[#번호]` 접두사.
- 새 문구는 아래 [문구 표](#문구-표)의 한국어·영어를 그대로 쓴다. 보드 문구가 기준이다.
- 화면 부품은 C1 디자인 시스템(`WLConfirmDialog`, `WLText`, `PriceText`, 토큰)과 `WLNavigator` push slide를 쓴다. 시스템 alert·NavigationStack은 쓰지 않는다.
- Android 에뮬레이터 `emulator-5554`(en-US) 조작 전 `adb shell dumpsys window | grep mCurrentFocus`로 `app.wishlist.android` 포커스를 확인하고 다른 앱은 건드리지 않는다. 플랫폼 CI job은 꺼져 있으므로 로컬 검증 결과를 PR에 적는다.

## 검증 명령 약어

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
# client/
./gradlew -Porg.gradle.java.installations.paths="$JAVA_HOME" :shared:testAndroidHostTest :shared:iosSimulatorArm64Test
```

- 이 맥에서 `/usr/libexec/java_home -v 17`은 25를 돌려주므로 쓰지 않는다. macOS에는 `timeout` 명령이 없다.
- **KMP_TEST** = 위 두 task. RED는 `:shared:testAndroidHostTest --tests "*<Class>*"`, GREEN 때 두 runtime 전체를 실행해 `shared/build/test-results/*/*.xml`에서 test 수·fail·skip을 센다.
- **ANDROID_CHECK** = `:android:testDebugUnitTest :android:testReleaseUnitTest :android:assembleDebug :android:assembleRelease :android:lintDebug`.
- **IOS_TEST** = `xcrun simctl spawn <udid> defaults write com.apple.Accessibility ApplicationAccessibilityEnabled -bool true` 후 `xcodebuild test -project client/ios/Wishlist.xcodeproj -scheme Wishlist -destination 'platform=iOS Simulator,id=<udid>' -derivedDataPath client/ios/DerivedData CODE_SIGNING_ALLOWED=NO`.
- 시뮬레이터 iPhone 17 Pro `AFBA9C17-206B-4EA6-A508-EF6E0CE2D7B0`(실행 때 `xcrun simctl list devices`로 다시 확인).
- **Baseline(2026-10-09, 이 공간):** host 434 · simulator 431 · Android unit debug/release 각 94 · XCTest 122, 실패·skip 0, assemble·lint 통과.

## Review Focus

1. **로그인 순간의 로컬 대기 화면:** 로그인 전 대기 화면을 연 채 로그인하면 flush가 즉시 그 행을 보내 첫 계정 view에 행이 없을 수 있다. 화면은 닫히지 않고 `MovedTo`로 상세가 된다(Task 6 `movedToEvenWhenTheFirstSignedInViewNoLongerHasTheRow`, Task 7/8 Navigator `signingInKeepsAccountScopedRoutes`).
2. **삭제와 전송이 겹칠 때:** flush가 queue를 읽은 뒤 사용자가 지운 행은 POST되지 않고 그 뒤의 행은 같은 flush에서 간다. SUBMITTING이 먼저면 삭제가 거절되고 화면은 `MovedTo`를 기다린다(Task 3 `deletedAfterQueueReadIsNeverPostedAndLaterRowsStillGo`, `deleteRefusesASubmittingRow`).
3. **대문자·비정규 id:** 다른 플랫폼이나 딥링크가 대문자 UUID를 주어도 cache miss·stale 행·refresh 중 항목 사라짐이 없다(Task 2 `uppercaseIdReadsAndRemovesTheCanonicalRow`, Task 4 `uppercaseIdRefreshKeepsTheShownItem`).
4. **망가진 cache:** 다른 build가 남긴 모르는 enum·깨진 시각의 행이 상세 GET과 홈 목록을 막지 않는다(Task 2 `undecodableRowIsDroppedAndTheNetworkAnswerIsCached`, `undecodableProcessingRowDoesNotHideTheOthers`).
5. **악성 페이지(PR B):** `intent://`의 component/selector, 최상위 `data:`/`javascript:`/`file:` 이동, `/ ? # %`가 든 URL token이 기기 컴포넌트 실행·피싱·route 파싱 오류로 이어지지 않는다(Task 13 `IntentSanitizerTest`, `WebNavigationPolicyTest`, `WebViewRouteCodecTest`).

## 문구 표

키는 Android `strings.xml` 이름이고, iOS는 같은 키를 `.`로 바꾼다(`detail_open_original` → `detail.open.original`). 기존 키(`row_processing`, `row_*`, `time_*`, `dialog_cancel`, `webview_clear_line_irreversible`, `home_original`)는 재사용한다.

| 키 | 한국어 | English |
| --- | --- | --- |
| `detail_back` | 뒤로 | Back |
| `detail_more` | 더보기 | More |
| `detail_open_original` | 원본 보기 | View original |
| `detail_price_checked` | %1$s 확인한 가격이에요. 지금 가격은 원본에서 확인해 주세요. | Price checked %1$s. Check the current price on the original page. |
| `detail_category` | 카테고리 | Category |
| `detail_category_empty` | 골라 주세요 | Choose one |
| `detail_purpose` | 목적 | Purpose |
| `detail_purpose_none` | 목적 미지정 | No purpose |
| `detail_name_empty` | 제품명 · 입력해 주세요 | Name · Add one |
| `detail_saved_just_now` | 방금 저장 | Saved just now |
| `detail_saved_today` | 오늘 저장 | Saved today |
| `detail_saved_date` | %1$d월 %2$d일 저장 | Saved %1$d/%2$d |
| `detail_saved_date_year` | %1$d년 %2$d월 %3$d일 저장 | Saved %2$d/%3$d/%1$d |
| `detail_processing_note` | 정보를 가져오는 동안은 편집할 수 없어요. 끝나면 앱을 다시 열거나 새로고침할 때 반영돼요. | You can't edit while we fetch the details. They appear when you reopen the app or refresh. |
| `detail_incomplete_info` | 상품 정보를 다 가져오지 못했어요 | We couldn't get all the product details |
| `detail_incomplete_category` | 카테고리를 정하지 못했어요 | We couldn't choose a category |
| `detail_incomplete_reassign` | 카테고리가 삭제되어 다시 골라야 해요 | The category was deleted. Choose a new one |
| `detail_error_network` | 불러오지 못했어요 | Couldn't load |
| `detail_error_server` | 잠시 후 다시 시도해 주세요 | Please try again in a moment |
| `detail_retry` | 다시 시도 | Try again |
| `detail_not_found` | 삭제된 상품이에요 | This item was deleted |
| `detail_close` | 닫기 | Close |
| `local_delete` | 삭제 | Delete |
| `local_delete_title` | 링크를 삭제할까요? | Delete this link? |
| `local_delete_target` | %1$s · 이 기기에만 있어요 | %1$s · Only on this device |
| `local_delete_line_unsent` | 아직 보내지 않은 링크예요 | This link hasn't been sent yet |
| `local_delete_failed` | 지우지 못했어요 | Couldn't delete |
| `home_row_open_detail` | 상세 보기 | View details |
| `home_row_open_original` | 원본 열기 | Open original |
| `webview_close` (PR B) | 닫기 | Close |
| `webview_back` | 뒤로 | Back |
| `webview_forward` | 앞으로 | Forward |
| `webview_reload` | 새로고침 | Reload |
| `webview_stop` | 중지 | Stop |
| `webview_share` | 공유 | Share |
| `webview_open_browser` | 외부 브라우저로 열기 | Open in browser |
| `webview_copy_link` | 링크 복사 | Copy link |
| `webview_share_other` | 다른 앱으로 공유 | Share to another app |
| `webview_link_copied` | 링크를 복사했어요 | Link copied |
| `webview_load_failed` | 페이지를 열 수 없어요 | Can't open this page |
| `webview_external_title` | 외부 앱을 열까요? | Open another app? |
| `webview_external_line_app` | 다른 앱이 열려요 | Another app will open |
| `webview_external_line_tap` | 직접 누르지 않았다면 취소해 주세요 | If you didn't tap it, cancel |
| `webview_external_open` | 열기 | Open |

상세 삭제 버튼 문구는 `local_delete`를 확인 버튼에도 쓴다. 가격 확인 시점(`detail_price_checked`의 `%1$s`)과 대기 줄 상대 시각은 기존 `time_*` 문구로 만든다.

## 파일 구조와 책임

`S` = `client/shared/src/commonMain/kotlin/app/wishlist/shared`, `T` = `client/shared/src/commonTest/kotlin/app/wishlist/shared`, `L` = `client/localdb/src/commonMain/sqldelight/app/wishlist/shared/data/local`, `A` = `client/android/src/main/kotlin/app/wishlist/android`, `AT` = `client/android/src/test/kotlin/app/wishlist/android`, `I` = `client/ios/Wishlist`, `IT` = `client/ios/WishlistTests`.

| 파일 | 책임 |
| --- | --- |
| `L/Wishlist.sq`, `L/2.sqm` | v3: `item_cache`에 목적 표시 3열(끝에 추가), `selectItemBySubmission`, `deleteDeletableSubmission` |
| `S/data/remote/ItemDtos.kt`, `ItemMapper.kt`, `S/model/WishlistItem.kt` | 목적 `name/colorKey/iconKey` 보존 |
| `S/repository/LocalStore.kt`, `S/data/local/SqlLocalStore.kt`, `S/di/RuntimeFacades.kt` | GET 전용 `>=` upsert, 해독 불가 행 self-heal, `cachedItemBySubmission`, `deleteSubmission` |
| `S/data/local/CachedGetItemRepository.kt` | id 정규화 |
| `S/submission/SubmissionCoordinator.kt` | `deleteLocal`, flush NOT_FOUND 건너뛰기, `requestViewPublish` |
| `S/presentation/ItemDetailPresenter.kt`, `ItemDetailState.kt` | 계정 전환 정책, `refresh`, 새는 취소, id 정규화, 성공 hook |
| `S/domain/DetailKind.kt`(새), `S/domain/DisplayFormat.kt` | 화면 종류 표, 저장 시점 분류 |
| `S/presentation/LocalSubmissionDetailPresenter.kt`, `LocalDetailState.kt`(새) | 로컬 대기 화면 상태·outcome·삭제 |
| `S/presentation/HomeState.kt`, `HomePresenter.kt` | `HomeRow.target` |
| `S/di/SharedRuntime.kt` | 새 Presenter 연결, 상세 hook |
| `A/navigation/*`, `A/ui/AppRoutes.kt`, `A/ui/AppRoute.kt`, `A/ui/WishlistApp.kt` | 계정 범위 표시·정리, `replaceTop`, entry별 `ViewModelStore`, route·codec |
| `A/feature/detail/*` | 상세·로컬 대기 화면, owner, 문구 매핑, 이미지 |
| `I/Navigation/*`, `I/AppRoutes.swift`, `I/ContentView.swift`, `I/WishlistApp.swift` | 같은 내비게이션 규칙, `WLEntryOwners`, runtime environment |
| `I/Features/Detail/*`, `I/Platform/RemoteImage.swift` | 상세·로컬 대기 화면, owner, 문구 매핑, 이미지 로더 |
| (PR B) `A/feature/web/*`, `I/Features/Web/*` | 웹뷰 화면·정책·intent 정화·공유 시트·외부 앱 확인 |

---

# PR A — 상세·분석 중·로컬 대기 (묶음 1~3)

## 묶음 1: 데이터·인계 결함

### Task 1: 목적 표시 경로와 SQLite v3

**Files:**
- Modify: `S/data/remote/ItemDtos.kt:58`, `S/data/remote/ItemMapper.kt:79-82`, `S/model/WishlistItem.kt:57`, `L/Wishlist.sq:19-53`, `S/data/local/SqlLocalStore.kt`(`toRow`/`toModel`)
- Create: `L/2.sqm`
- Test: `T/data/remote/ItemMapperTest.kt`, `T/data/local/SchemaMigrationTest.kt`, `T/data/local/LocalStoreContractTest.kt`

**Interfaces:**
- Produces: `data class ItemPurpose(val id: String?, val source: ValueSource, val name: String? = null, val colorKey: String? = null, val iconKey: String? = null)`. wire 값은 원문(대문자 `CORAL`) 그대로.

- [ ] **Step 1: 실패하는 테스트.** `ItemMapperTest`:

```kotlin
@Test fun purposeDisplayFieldsAreKeptVerbatim() {
    val item = parse(itemJson(purpose = """{"id":"$purposeId","name":"출퇴근 헤드폰","colorKey":"CORAL","iconKey":"HEART","source":"USER"}"""))
    assertEquals(ItemPurpose(purposeId, ValueSource.USER, "출퇴근 헤드폰", "CORAL", "HEART"), item.purpose)
}

@Test fun missingPurposeDisplayFieldsReadAsNull() {
    val item = parse(itemJson(purpose = """{"source":"UNASSIGNED"}"""))
    assertEquals(ItemPurpose(null, ValueSource.UNASSIGNED), item.purpose)
}
```

`SchemaMigrationTest`(기존 v1 테스트 옆):

```kotlin
@Test fun migratesV2ItemRowsToV3WithNullPurposeDisplay() {
    // v2 schema를 손으로 만들고 item 행 하나를 넣은 뒤 PRAGMA user_version = 2
    // WishlistDatabase.Schema.migrate(driver, 2, 3) → selectItem: purpose_name/color/icon == null, 나머지 열 보존
}

@Test fun migratesV1AllTheWayToV3() { /* 기존 v1 fixture → migrate(1, 3) → item_cache에 새 3열 존재 */ }
```

`LocalStoreContractTest`: 목적 표시가 있는 item을 `upsertItem` → `cachedItem`이 같은 `ItemPurpose`를 돌려준다.

- [ ] **Step 2: RED 확인.** `:shared:testAndroidHostTest --tests "*ItemMapperTest*" --tests "*SchemaMigrationTest*"` → 컴파일 실패 또는 assertion 실패.
- [ ] **Step 3: 구현.**
  - `PurposeDto(val id: String? = null, val name: String? = null, val colorKey: String? = null, val iconKey: String? = null, val source: String? = null)`. mapper가 그대로 옮긴다.
  - `L/2.sqm`(ALTER는 열을 **끝에** 붙이므로 `Wishlist.sq`의 CREATE TABLE에도 `client_created_at` 뒤에 같은 순서로 둔다. `INSERT OR REPLACE INTO item_cache VALUES ?`가 열 순서에 기댄다):

```sql
ALTER TABLE item_cache ADD COLUMN purpose_name TEXT;
ALTER TABLE item_cache ADD COLUMN purpose_color_key TEXT;
ALTER TABLE item_cache ADD COLUMN purpose_icon_key TEXT;
```

  - `toRow`/`toModel`에 세 열을 더한다.
- [ ] **Step 4: GREEN.** 같은 테스트 통과 → KMP_TEST 전체. 기존 `ItemDtoContractTest`·`RepositoryContractTest`가 깨지지 않는지 본다.
- [ ] **Step 5: 되돌림 확인.** mapper의 새 필드 복사만 지우면 `purposeDisplayFieldsAreKeptVerbatim`이 실패하는지 확인하고 기록.
- [ ] **Step 6: 커밋.** `feature(kmp): 상품 목적 표시 정보 보존과 캐시 v3`

### Task 2: cache 규칙 — GET 전용 같은 version 갱신, 해독 불가 행, UUID 정규화, submission 조회

**Files:**
- Modify: `S/repository/LocalStore.kt`, `S/data/local/SqlLocalStore.kt:186-195,242-245`, `S/data/local/CachedGetItemRepository.kt`, `S/di/RuntimeFacades.kt:79-130`, `L/Wishlist.sq`
- Test: `T/data/local/LocalStoreContractTest.kt`, `T/data/local/CachedGetItemRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `LocalStore.upsertItem` KDoc: "ITEM-03 GET 결과 전용. 같은 version이면 덮어쓴다(목적·카테고리 편집은 item version을 올리지 않는다). 더 작은 version은 버린다." `accept`는 `>` 유지.
  - `suspend fun cachedItemBySubmission(snapshot: SessionSnapshot, submissionId: String): ClientResult<WishlistItem?>` — `gatedForAccount`(비로그인 UNAUTHENTICATED). `Success(null)` = 찾지 못함.
  - `cachedItem`·`processingItems`·`cachedItemBySubmission`은 해독할 수 없는 행을 같은 transaction에서 지우고 없는 것으로 친다.

- [ ] **Step 1: 실패하는 테스트.**

```kotlin
// LocalStoreContractTest
@Test fun getUpsertReplacesTheSameVersion() = runTest {
    h.login("a"); val s = h.snapshot()
    h.store.upsertItem(s, item(version = 3, name = "옛 이름"))
    h.store.upsertItem(s, item(version = 3, name = "새 이름"))
    assertEquals("새 이름", h.store.cachedItem(s, itemId).value()!!.product.name)
}

@Test fun getUpsertStillIgnoresAnOlderVersion() = runTest { /* v3 뒤 v2 → v3 유지 */ }

@Test fun acceptKeepsANewerSameVersionCacheRow() = runTest {
    // upsertItem(v3, "GET 결과") 뒤 같은 submission의 accept(v3, "멱등 응답") → "GET 결과" 유지
}

@Test fun undecodableRowIsDroppedOnRead() = runTest {
    // raw SQL로 analysis_status = 'NOT_A_STATUS' 행을 넣는다 → cachedItem == Success(null), 행이 지워졌다
}

@Test fun undecodableProcessingRowDoesNotHideTheOthers() = runTest {
    // PROCESSING 정상 행 1 + 시각이 깨진 PROCESSING 행 1 → processingItems == [정상 행], 깨진 행 삭제
}

@Test fun uppercaseIdReadsAndRemovesTheCanonicalRow() = runTest {
    // 소문자 id 행 저장 → cachedItem(s, id.uppercase()) 찾음, removeCachedItem(s, id.uppercase(), v) 지움
}

@Test fun cachedItemBySubmissionFindsTheAcceptedItem() = runTest { /* accept 뒤 대문자 submission id로도 찾음 */ }
@Test fun cachedItemBySubmissionIsNullWhenAbsent() = runTest { /* Success(null) */ }
@Test fun cachedItemBySubmissionNeedsAnAccount() = runTest { /* 비로그인 → UNAUTHENTICATED */ }

// CachedGetItemRepositoryTest — 기존 cache_read_failure_is_returned_without_calling_delegate를 둘로
@Test fun store_failure_is_returned_without_calling_delegate() = runTest { /* DB 실패(LOCAL_STORE_FAILURE) → delegate 호출 0 */ }
@Test fun undecodableRowIsDroppedAndTheNetworkAnswerIsCached() = runTest {
    // 깨진 행(version 99) → get → delegate 호출 1, 응답(version 2)이 cache에 남음
}
@Test fun uppercaseIdUsesTheCanonicalCacheRow() = runTest { /* get(id.uppercase()) → delegate는 소문자 id로, cache 행 하나 */ }
```

- [ ] **Step 2: RED 확인.** `--tests "*LocalStoreContractTest*" --tests "*CachedGetItemRepositoryTest*"`.
- [ ] **Step 3: 구현.**
  - `upsertItem` 경로는 `upsertFromGet(account, item)`(private, `existing == null || item.version >= existing`), `accept`는 기존 `upsertIfNewer`(>) 그대로.
  - 해독: `Item_cache.toModel()`을 `decodeOrNull()`로 감싸 `IllegalArgumentException`·`IllegalStateException`(valueOf·requireNotNull·Instant.parse)을 null로 바꾼다. null이면 `deleteItem(account, item_id)`를 같은 transaction에서 실행한다. `processingItems`는 `mapNotNull`. DB 예외는 지금처럼 `leased`가 `LOCAL_STORE_FAILURE`로 바꾼다.
  - id 정규화: store의 id 인자(`cachedItem`·`removeCachedItem`·`cachedItemBySubmission`)는 `canonicalUuidOrNull(id) ?: id`로 바꿔 쓴다. 저장 행의 id는 서버·Fake가 준 소문자 그대로다.
  - SQL: `deleteItem: DELETE FROM item_cache WHERE account_id = ? AND item_id = ?;`, `selectItemBySubmission: SELECT * FROM item_cache WHERE account_id = ? AND lower(client_submission_id) = ? LIMIT 1;`
  - `CachedGetItemRepository.get(id)`: 첫 줄에서 `val key = canonicalUuidOrNull(id) ?: id`로 바꾸고 이후 모든 호출에 `key`를 쓴다.
  - `GatedLocalStore`·`ClosedLocalStore`에 `cachedItemBySubmission`을 더한다.
- [ ] **Step 4: GREEN.** 해당 테스트 → KMP_TEST 전체.
- [ ] **Step 5: 되돌림 확인.** `>=`를 `>`로, `decodeOrNull` 삭제 호출을 빼고, 정규화를 빼서 각 테스트가 실패하는지 확인하고 기록.
- [ ] **Step 6: 커밋.** `bugfix: 상품 캐시의 같은 버전 갱신과 손상 행·대문자 id 처리`

### Task 3: 로컬 삭제, flush 건너뛰기, close 뒤 DB 미접촉

**Files:**
- Modify: `S/repository/LocalStore.kt`, `S/data/local/SqlLocalStore.kt`, `S/di/RuntimeFacades.kt`, `S/submission/SubmissionCoordinator.kt:250-275`, `L/Wishlist.sq`, `T/di/RuntimeTestSupport.kt:40-64`
- Test: `T/data/local/LocalStoreContractTest.kt`, `T/submission/SubmissionCoordinatorTest.kt`, `T/di/RuntimeCloseLeaseTest.kt`

**Interfaces:**
- Produces:
  - `suspend fun deleteSubmission(snapshot: SessionSnapshot, submissionId: String): ClientResult<Unit>` — `gated(snapshot)`(계정 없이 동작). 로그인: binding이 그 계정 또는 null, 비로그인: binding null만. 상태 `PENDING`/`FAILED`만. SUBMITTING → `CONFLICT/SUBMISSION_IN_FLIGHT`, 없거나 binding이 다르면 `NOT_FOUND/SUBMISSION_NOT_FOUND`.
  - `SubmissionCoordinator.deleteLocal(submissionId: String): ClientResult<Unit>`(suspend, coordinator dispatcher) — store 삭제 후 성공이면 `publishView()`.
  - `SqlLocalStore.SUBMISSION_IN_FLIGHT = "SUBMISSION_IN_FLIGHT"`.
  - `CountingDriver.queries`·`executes` 카운터(Volatile Int).

- [ ] **Step 1: 실패하는 테스트.**

```kotlin
// LocalStoreContractTest
@Test fun deleteRemovesPendingAndFailedRowsOfTheAccount() = runTest { /* PENDING·FAILED 각각 Success, pending()에서 사라짐 */ }
@Test fun deleteRefusesASubmittingRow() = runTest { /* markSubmission SUBMITTING → CONFLICT/SUBMISSION_IN_FLIGHT, 행 유지 */ }
@Test fun signedOutDeleteRemovesOnlyUnboundRows() = runTest {
    // 비로그인: 미귀속 행 Success, 계정 "a"에 묶인 행 NOT_FOUND(행 유지)
}
@Test fun deleteNeverTouchesAnotherAccountsRow() = runTest { /* "a" 행을 "b" 로그인으로 지우기 → NOT_FOUND */ }

// SubmissionCoordinatorTest
@Test fun deleteLocalPublishesAtOnce() = runTest { /* deleteLocal 직후 view.local에 없음(runCurrent) */ }
@Test fun deletedAfterQueueReadIsNeverPostedAndLaterRowsStillGo() = runTest {
    // 행 A, B. prepareFlush가 queue를 읽은 직후(store hook) A를 deleteSubmission → 대본 ITEM-01 호출은 B 하나, B accept
}
@Test fun aSubmittingRowCannotBeDeleted() = runTest { /* ITEM-01을 붙잡아 SUBMITTING 동안 deleteLocal → CONFLICT, POST는 계속 */ }

// RuntimeCloseLeaseTest — 기존 close 뒤 호출 테스트에 단언 추가
@Test fun storeCallsAfterCloseNeverTouchTheDb() = runTest {
    // driver를 연 runtime → close → 이전 queries/executes 값 기록 → 모든 LocalStore 메서드 호출(새 deleteSubmission·cachedItemBySubmission 포함)
    // → 결과 RUNTIME_NOT_READY, queries/executes 증가 0
}
```

- [ ] **Step 2: RED 확인.** `--tests "*LocalStoreContractTest*" --tests "*SubmissionCoordinatorTest*" --tests "*RuntimeCloseLeaseTest*"`.
- [ ] **Step 3: 구현.**
  - SQL `selectSubmission`으로 읽고 상태·binding을 판정한 뒤 `deleteSubmission(id)`를 같은 transaction에서 실행한다(`gated`가 session gate 안).
  - flush 루프의 `send()`: `markSubmission(SUBMITTING)`이 `NOT_FOUND`면 `return true`(그 행만 건너뛴다). 다른 실패는 지금처럼 `false`.

```kotlin
when (val marked = store.markSubmission(snapshot, id, SubmissionStatus.SUBMITTING, null, null)) {
    is ClientResult.Failure -> return marked.error.kind == ErrorKind.NOT_FOUND // deleted since the queue was read
    is ClientResult.Success -> Unit
}
```

  - `CountingDriver`: `executeQuery`에서 `queries++`, `execute`를 override해 `executes++`.
- [ ] **Step 4: GREEN.** 해당 테스트 → KMP_TEST 전체.
- [ ] **Step 5: 되돌림 확인.** NOT_FOUND 건너뛰기를 빼면 `deletedAfterQueueRead…`의 B가 같은 flush에서 가지 않아 실패, 비로그인 gate를 `gatedForAccount`로 바꾸면 `signedOutDelete…`가 실패하는지 확인하고 기록.
- [ ] **Step 6: 커밋.** `feature(kmp): 로컬 대기 항목 삭제와 삭제된 행 건너뛰기`

## 묶음 2: Presenter

### Task 4: ItemDetailPresenter — 계정 전환·refresh·새는 취소·정규화·성공 hook

**Files:**
- Modify: `S/presentation/ItemDetailPresenter.kt`, `S/di/SharedRuntime.kt:213-214`
- Test: `T/presentation/ItemDetailPresenterTest.kt`, `T/di/SharedModulesTest.kt:312-345`, `IT/ItemDetailPresenterOwnerTests.swift:34-46`, `IT/SharedInteropTests.swift:60-80`, `AT/feature/detail/ItemDetailPresenterOwnerTest.kt`

**Interfaces:**
- Consumes: Task 2의 정규화 cache.
- Produces:
  - 생성자 `ItemDetailPresenter(repository, session, dispatcher, onLoaded: () -> Unit = {})` — `onLoaded`는 GET 성공 state를 게시한 뒤 lane에서 호출. 공개 생성자 시그니처의 기본값이라 기존 호출은 그대로.
  - `fun refresh()` — 마지막 id가 있으면 `start(lastId)`(항목 유지), 없으면 무시.
  - 계정 전환 시 `lastId = null`.
  - 새는 취소의 오류 코드 `const val DETAIL_STEP_FAILURE = "DETAIL_STEP_FAILURE"`(같은 파일, internal).
  - runtime: `itemDetailPresenter()`가 `onLoaded = { submissions().requestViewPublish() }`를 넘긴다(Task 6에서 coordinator에 추가하는 internal 메서드. 이 task에서는 `SubmissionCoordinator.requestViewPublish()`를 `internal fun requestViewPublish() = requestPublish()`로 먼저 만든다).

- [ ] **Step 1: 실패하는 테스트.**

```kotlin
@Test fun retryAfterAnAccountChangeDoesNothing() = runTest {
    val p = signedIn(); p.load(itemId); runCurrent(); repository.calls.last().succeed(item); runCurrent()
    launch { session.changeAccount("account-b") }; runCurrent()
    p.retry(); p.refresh(); runCurrent()
    assertEquals(1, repository.calls.size); assertEquals(ItemDetailState.Initial, p.state.value)
}

@Test fun refreshKeepsTheShownItemAndRepeatsTheLastId() = runTest {
    // load → succeed(item) → refresh → state(item, loading=true) → succeed(refreshed) → loaded(refreshed)
}

@Test fun refreshBeforeTheFirstLoadDoesNothing() = runTest { /* calls 0 */ }

@Test fun aStrayCancellationBecomesAnError() = runTest {
    // repository.onCall = { throw CancellationException("stray") } → state: loading=false, error UNAVAILABLE/DETAIL_STEP_FAILURE
}

@Test fun aRealCancellationStillPropagates() = runTest { /* 기존 취소 전파 테스트 유지: 새 load가 이전 요청을 취소해도 오류 state 아님 */ }

@Test fun uppercaseIdRefreshKeepsTheShownItem() = runTest {
    // load(itemId.uppercase()) → repository 호출 id == itemId → succeed(item) → refresh → loading 중에도 item 유지
}

@Test fun onLoadedRunsAfterEverySuccessfulLoad() = runTest { /* 성공 2회 → hook 2회, 실패 → 0회 */ }
```

`SharedModulesTest.runtime_presenter_reads_the_gated_get_facade_and_follows_the_runtime_session`: 계정 변경 뒤 `retry()`가 NOT_FOUND를 기대하던 부분을 "요청 없음, Initial"로 바꾼다. iOS `ItemDetailPresenterOwnerTests`·`SharedInteropTests`의 계정 전환 뒤 retry 기대도 같다.

- [ ] **Step 2: RED 확인.** `--tests "*ItemDetailPresenterTest*" --tests "*SharedModulesTest*"`.
- [ ] **Step 3: 구현.**

```kotlin
private fun followSession(current: SessionSnapshot) {
    if (current == observedSession) return
    observedSession = current
    invalidate()
    lastId = null // D3: a previous account's item is never requested again
    mutableState.value = ItemDetailState.Initial
}

fun load(id: String) { scope.launch { start(canonicalUuidOrNull(id) ?: id) } }
fun refresh() { scope.launch { lastId?.let { start(it) } } }

// start(): repository 호출
val result = try {
    withContext(dispatcher) { repository.get(id) }
} catch (e: CancellationException) {
    currentCoroutineContext().ensureActive() // a real cancel propagates
    ClientResult.Failure(ClientError(ErrorKind.UNAVAILABLE, DETAIL_STEP_FAILURE))
}
// 게시 뒤
if (result is ClientResult.Success) onLoaded()
```

  `retry()`는 `refresh()`와 같은 본문(이름만 유지, KDoc에 "오류 뒤 다시 시도").
- [ ] **Step 4: GREEN.** KMP_TEST, ANDROID_CHECK의 `:android:testDebugUnitTest`, IOS_TEST.
- [ ] **Step 5: 되돌림 확인.** `lastId = null`, 취소 catch, 정규화를 각각 빼면 대응 테스트가 실패하는지 확인하고 기록.
- [ ] **Step 6: 커밋.** `feature(kmp): 상세 Presenter 계정 전환 정책과 새로고침`

### Task 5: DetailKind, 저장 시점 분류, 홈 줄 target

**Files:**
- Create: `S/domain/DetailKind.kt`
- Modify: `S/domain/DisplayFormat.kt`, `S/presentation/HomeState.kt`, `S/presentation/HomePresenter.kt:100-115`
- Test: `T/domain/DetailKindTest.kt`(새), `T/domain/DisplayFormatTest.kt`, `T/presentation/HomePresenterTest.kt`

**Interfaces:**
- Produces:

```kotlin
enum class DetailKind { PROCESSING, READY, INCOMPLETE, GONE }

/** Why an INCOMPLETE item needs work; the platform maps it to its sentence. */
enum class DetailNotice { INFORMATION_MISSING, CATEGORY_UNDECIDED, CATEGORY_DELETED }

data class DetailPresentation(val kind: DetailKind, val notice: DetailNotice?)

object DetailKinds {
    /** lifecycle first (non-ACTIVE always has requiredAction NONE), then requiredAction; UNKNOWN falls back to the fields. */
    fun of(item: WishlistItem): DetailPresentation
}

sealed interface SavedLabel {
    data object JustNow : SavedLabel
    data object Today : SavedLabel
    data class OnDate(val year: Int?, val month: Int, val day: Int) : SavedLabel // year only when it differs from now's
}

// DisplayFormat
fun saved(at: Instant, now: Instant, utcOffsetSeconds: (Instant) -> Int): SavedLabel

sealed interface HomeRowTarget {
    data class Local(val submissionId: String) : HomeRowTarget
    data class Item(val itemId: String) : HomeRowTarget
}
// HomeRow에 val target: HomeRowTarget 추가(key 다음)
```

가격은 기존 shared `PriceFormatter`(`domain/PriceFormatter`, 플랫폼 `PriceText`)를 그대로 쓴다. spec §2의 가격 표시 항목은 이것으로 충족한다(새 함수 없음).

- [ ] **Step 1: 실패하는 테스트.** `DetailKindTest`는 표 전체를 고정한다.

```kotlin
@Test fun everyLifecycleAndRequiredActionHasOneKind() {
    for (life in LifecycleStatus.entries) for (action in RequiredAction.entries) {
        val p = DetailKinds.of(itemFixture(lifecycle = life).copy(requiredAction = action))
        val expected = when {
            life == LifecycleStatus.DELETED -> DetailPresentation(DetailKind.GONE, null) // defensive: GET 404s, accept drops it
            life == LifecycleStatus.ARCHIVED -> DetailPresentation(DetailKind.READY, null)
            action == RequiredAction.ANALYSIS_IN_PROGRESS -> DetailPresentation(DetailKind.PROCESSING, null)
            action == RequiredAction.INFORMATION_COMPLETION -> DetailPresentation(DetailKind.INCOMPLETE, DetailNotice.INFORMATION_MISSING)
            action == RequiredAction.CATEGORY_ASSIGNMENT -> DetailPresentation(DetailKind.INCOMPLETE, DetailNotice.CATEGORY_UNDECIDED)
            action == RequiredAction.CATEGORY_REASSIGNMENT -> DetailPresentation(DetailKind.INCOMPLETE, DetailNotice.CATEGORY_DELETED)
            action == RequiredAction.CLASSIFICATION_REVIEW, action == RequiredAction.NONE -> DetailPresentation(DetailKind.READY, null)
            else -> null // UNKNOWN: below
        }
        if (expected != null) assertEquals(expected, p, "$life/$action")
    }
}

@Test fun unknownActionFallsBackToTheFields() {
    // UNKNOWN + analysis PROCESSING → PROCESSING; UNKNOWN + name null → INCOMPLETE/INFORMATION_MISSING;
    // UNKNOWN + category id null → INCOMPLETE/INFORMATION_MISSING; UNKNOWN + 둘 다 있음 → READY
}

@Test fun failedButCompletedItemIsReady() { /* FAILED_TERMINAL + requiredAction NONE → READY */ }
```

`DisplayFormatTest`: `saved` — 30초 전 JustNow, 같은 날 3시간 전 Today, 어제 → OnDate(null, m, d), 작년 → OnDate(year, m, d), 2월 29일·12월 31일→1월 1일 경계, 음수 offset. `HomePresenterTest`: 로컬 줄 `target == Local(id)`, 분류 중 줄 `target == Item(id)`.
- [ ] **Step 2: RED 확인.**
- [ ] **Step 3: 구현.** 날짜는 kotlinx-datetime 없이 `localDay`(기존)에서 civil date로 바꾼다(Howard Hinnant `civil_from_days`):

```kotlin
private fun civil(day: Long): Triple<Int, Int, Int> {
    val z = day + 719_468; val era = z.floorDiv(146_097); val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val y = yoe + era * 400; val doy = doe - (365 * yoe + yoe / 4 - yoe / 100); val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt(); val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    return Triple((if (m <= 2) y + 1 else y).toInt(), m, d)
}
```

- [ ] **Step 4: GREEN.** KMP_TEST.
- [ ] **Step 5: 되돌림 확인.** lifecycle 검사를 requiredAction 뒤로 옮기면 ARCHIVED/DELETED 행이 실패하는지 확인.
- [ ] **Step 6: 커밋.** `feature(kmp): 상세 화면 종류와 저장 시점 표시`

### Task 6: LocalSubmissionDetailPresenter와 runtime 연결

**Files:**
- Create: `S/presentation/LocalSubmissionDetailPresenter.kt`, `S/presentation/LocalDetailState.kt`
- Modify: `S/di/SharedRuntime.kt`, `S/submission/SubmissionCoordinator.kt`(공개 `deleteLocal`, `lookupAccepted`), `S/presentation/HomePresenter.kt`(`rowStatus()`를 `internal fun LocalSubmission.rowStatus()` top-level로 옮겨 공유)
- Test: `T/presentation/LocalSubmissionDetailPresenterTest.kt`(새), `T/di/SharedModulesTest.kt`

**Interfaces:**
- Consumes: Task 2 `cachedItemBySubmission`, Task 3 `deleteLocal`, Task 5 `DisplayFormat`.
- Produces:

```kotlin
data class LocalDetailRow(val submissionId: String, val host: String, val sourceUrl: String, val savedAt: RelativeTime, val status: RowStatus)

sealed interface LocalDetailOutcome {
    data class MovedTo(val itemId: String) : LocalDetailOutcome
    data object Deleted : LocalDetailOutcome
    data object RemovedOnServer : LocalDetailOutcome // D19: accepted as DELETED, nothing cached
    data object Gone : LocalDetailOutcome             // lookup failed, or vanished while signed out
}

data class LocalDetailState(
    val row: LocalDetailRow?,
    val canDelete: Boolean,
    val deleting: Boolean,
    val deleteFailed: Boolean,
    val outcome: LocalDetailOutcome?,
) { companion object { val Initial = LocalDetailState(null, false, false, false, null) } }

class LocalSubmissionDetailPresenter internal constructor(
    view: StateFlow<SubmissionView?>,
    private val lookup: suspend (SessionSnapshot, String) -> ClientResult<WishlistItem?>, // store.cachedItemBySubmission
    private val delete: suspend (String) -> ClientResult<Unit>,                          // coordinator.deleteLocal
    private val session: AuthSession,
    private val clock: Clock,
    private val utcOffsetSeconds: (Instant) -> Int,
    dispatcher: CoroutineDispatcher,
) : Presenter {
    val state: StateFlow<LocalDetailState>
    fun load(submissionId: String)
    fun delete()
    fun tick()
    override fun close()
}

// SharedRuntime
fun localSubmissionDetailPresenter(): LocalSubmissionDetailPresenter
```

- **판정 규칙(spec §2):** view를 받을 때마다 lane에서
  1. 행(정규 UUID 비교)이 있으면 `row`·`canDelete = status in (WAITING_NETWORK, RETRYING, NEEDS_SIGN_IN, FAILED, LOCAL_ONLY)`(즉 SUBMITTING만 false) 갱신.
  2. 행이 없고 `outcome == null`이면:
     - 이전 view의 accountId가 non-null이고 지금과 다르면 아무것도 하지 않는다(셸이 닫는다).
     - 지금 view의 accountId가 null이면 `Gone`.
     - 아니면 `lookup(snapshot, id)`: `Success(item)` → `MovedTo(item.id)`, `Success(null)` → `RemovedOnServer`, `Failure` → `Gone`.
  3. outcome이 정해지면 이후 view는 무시한다.
- `delete()`: `canDelete`가 아니거나 `deleting`이면 무시. `deleting = true` → `delete(id)` → Success면 `Deleted`, `CONFLICT/SUBMISSION_IN_FLIGHT`면 `deleting = false`만(곧 MovedTo), 그 밖은 `deleting = false, deleteFailed = true`(플랫폼이 "지우지 못했어요"를 짧게 보이고 다음 view에서 false로 돌림).
- 첫 view가 null(ready 전)이면 `row = null`, outcome 없음.

- [ ] **Step 1: 실패하는 테스트.** 실제 SQLite store + 대본 ITEM-01 + coordinator(기존 `SubmissionCoordinatorTest` harness 재사용, virtual time, `runCurrent` 위주):

```kotlin
@Test fun showsTheRowAndItsWaitReason()
@Test fun submittingRowCannotBeDeleted()
@Test fun acceptedAsProcessingMovesToTheItem()
@Test fun acceptedAsReadyMovesToTheItemToo()                  // ITEM-01 멱등 응답 READY
@Test fun acceptedAsDeletedIsRemovedOnServer()                // D19
@Test fun lookupFailureClosesWithoutTheDeletedNotice()        // lookup = { _, _ -> Failure } → Gone
@Test fun vanishingWhileSignedOutIsGone()
@Test fun movedToEvenWhenTheFirstSignedInViewNoLongerHasTheRow() {
    // 비로그인 미귀속 행 표시 → 로그인 → ITEM-01 즉시 성공 → 첫 accountId=A view에 행 없음 → MovedTo
}
@Test fun leavingTheAccountDecidesNothing()                   // A → B: outcome null 유지
@Test fun signedOutDeleteWorks()                              // D17·Task 3: 비로그인 delete → Deleted
@Test fun deleteRacingASendWaitsForMovedTo()                  // IN_FLIGHT → deleting false, 이후 MovedTo
@Test fun tickRecomputesSavedAt()
@Test fun closeIgnoresLaterIntents()
```

`SharedModulesTest`: `localSubmissionDetailPresenter()`가 runtime의 coordinator view·session·store를 쓰는지(DEBUG runtime에서 `createUnboundPending(1)` → load → row 보임).
- [ ] **Step 2: RED 확인.**
- [ ] **Step 3: 구현.** 단일 lane(`dispatcher.limitedParallelism(1)`), `SupervisorJob`, view 수집 + `recompute` tick, `HomePresenter`와 같은 `close()`. 상대 시각은 `DisplayFormat.relative`.
- [ ] **Step 4: GREEN.** KMP_TEST 전체.
- [ ] **Step 5: 되돌림 확인.** 건너뛰기 조건을 "accountId가 달라지면"으로 넓히면 `movedToEvenWhen…`이, `Success(null)`/`Failure` 구분을 합치면 `lookupFailure…`가 실패하는지 확인하고 기록.
- [ ] **Step 6: 커밋.** `feature(kmp): 로컬 대기 상세 Presenter`

### 중간 확인 지점 A1

- [ ] KMP_TEST·ANDROID_CHECK·IOS_TEST 전체 실행, 건수 기록(XML·xcodebuild 요약).
- [ ] 독립 리뷰 1회(`superpowers:requesting-code-review`, 범위 Task 1~6). 지적은 `superpowers:receiving-code-review`로 검증 후 반영.
- [ ] 사용자에게 묶음 1·2 결과와 리뷰 반영을 보고하고 묶음 3 진행을 확인받는다.

## 묶음 3: 화면·내비게이션

### Task 7: Android 내비게이션 기반

**Files:**
- Modify: `A/navigation/WLRoute.kt`, `A/navigation/WLNavigator.kt`, `A/navigation/WLNavHost.kt:200-285`, `A/ui/AppRoutes.kt`, `A/ui/WishlistApp.kt`
- Create: `A/navigation/WLEntryViewModelStores.kt`
- Test: `AT/navigation/WLNavigatorTest.kt`, `AT/navigation/WLEntryViewModelStoresTest.kt`(새), `AT/ui/AppRouteCodecTest.kt`(새 또는 기존)

**Interfaces:**
- Produces:

```kotlin
interface WLRoute { val showsTabBar: Boolean; val pushStyle: WLPushStyle; val accountScoped: Boolean get() = false }

// WLNavigator
/** Pops, in every tab, the first account-scoped route and everything above it; no transition. Returns removed entry ids. */
internal fun dropAccountScoped(): List<Long>
/** Replaces the current tab's top entry (cross-fade); the replaced entry's id is reported as removed. */
fun replaceTop(route: WLRoute): Boolean
/** Ids of entries removed by pop/commitBackGesture/replaceTop/dropAccountScoped since the last call. */
internal fun drainRemoved(): List<Long>

internal data class ItemDetailRoute(val itemId: String) : WLRoute { showsTabBar=false; pushStyle=Slide; accountScoped=true }
internal data class LocalSubmissionRoute(val submissionId: String) : WLRoute { … accountScoped=true }
// ProductionRouteCodec: ["item", id] / ["local", id]

/** Activity-scoped holder (a ViewModel) of one ViewModelStore per back-stack entry id. */
internal class WLEntryViewModelStores : ViewModel() {
    fun storeFor(entryId: Long): ViewModelStore
    fun clear(entryId: Long)
    override fun onCleared()
}
```

- **계정 떠남 판정(D3):** `WishlistApp`이 `account.state`의 accountId를 관찰해 이전 값이 non-null이고 새 값과 다르면 `navigator.dropAccountScoped()`(순수 함수 `shouldDropAccountScoped(previous, next)`). 같은 계정 재로그인은 로그아웃을 거치므로(A → null → A, C3 한계 표의 "두 계정 사이 로그아웃 상태 게시") 이 비교로 잡힌다. 그래도 세대만 바뀌는 경로가 생기면 상세 Presenter가 `Initial`이 되므로, 화면은 `item == null && !loading && error == null`을 받으면 스스로 pop한다(Task 10·11, 안전장치).
- `WLNavHost`는 entry마다 `CompositionLocalProvider(LocalViewModelStoreOwner provides owner(entry.id))`. 전환이 끝날 때(`finishTransition`) `drainRemoved()`의 id를 `clear`.

- [ ] **Step 1: 실패하는 테스트.**

```kotlin
@Test fun leavingAnAccountDropsAccountScopedRoutesInEveryTab()
@Test fun signingInKeepsAccountScopedRoutes()          // 셸 함수 shouldDrop(previous=null, next="a") == false
@Test fun nonScopedRoutesBelowStay()                    // [root, Settings, ItemDetail, Web] → [root, Settings]
@Test fun replaceTopSwapsTheTopAndReportsTheOldId()
@Test fun removedIdsCoverPopGestureReplaceAndDrop()
@Test fun entryStoreIsClearedWhenItsEntryIsRemoved()    // ViewModel.onCleared 호출 확인
@Test fun itemAndLocalRoutesRoundTripThroughTheCodec()
```

- [ ] **Step 2~4: RED → 구현 → GREEN** (`:android:testDebugUnitTest --tests …`, 이후 ANDROID_CHECK).
- [ ] **Step 5: 되돌림 확인.** `shouldDrop`의 이전 값 null 검사를 빼면 `signingIn…`이 실패.
- [ ] **Step 6: 커밋.** `feature(android): 계정 범위 화면 정리와 화면별 ViewModel 수명`

### Task 8: iOS 내비게이션 기반

**Files:**
- Modify: `I/Navigation/WLRoute.swift`, `I/Navigation/WLNavigator.swift`, `I/Navigation/WLNavHost.swift`, `I/AppRoutes.swift`, `I/ContentView.swift`, `I/WishlistApp.swift`
- Create: `I/Navigation/WLEntryOwners.swift`
- Test: `IT/WLNavigatorTests.swift`, `IT/WLEntryOwnersTests.swift`(새)

**Interfaces:**
- Produces:

```swift
struct WLRoute { let destination: AnyHashable; let showsTabBar: Bool; let pushStyle: WLPushStyle; var accountScoped: Bool = false }
extension WLNavigator {
    func dropAccountScoped() -> [Int]   // removed entry ids, no transition
    func replaceTop(_ route: WLRoute) -> Bool
    func drainRemoved() -> [Int]
}
enum AppDestination: Hashable { case settings, login, item(String), local(String) } // item/local: accountScoped true

/// Owners per back-stack entry; ZStack keeps views alive, so lifetime follows the stack, not the view.
@MainActor final class WLEntryOwners {
    init(runtime: SharedRuntime)
    func itemDetail(_ entryId: Int) -> ItemDetailPresenterOwner
    func localDetail(_ entryId: Int) -> LocalSubmissionDetailPresenterOwner
    func close(_ ids: [Int])            // close() and forget
}
static func shouldDropAccountScoped(previous: String?, next: String?) -> Bool // previous != nil && previous != next
```

- `WishlistApp`가 runtime을 `ContentView`에 environment(`\.wlRuntime`)로 넘기고, `ContentView`가 `WLEntryOwners`를 `@State`로 만든다. `.onChange(of: account.state.account?.accountId)`에서 `shouldDrop…`이면 `entryOwners.close(navigator.dropAccountScoped())`. 전환 완료 콜백에서 `entryOwners.close(navigator.drainRemoved())`.
- [ ] **Step 1: 실패하는 테스트.** Task 7과 같은 7개(XCTest 이름 `test…`), `WLEntryOwnersTests`: 같은 id는 같은 owner, `close`가 owner의 Presenter를 닫는다(이후 `retry` 무시), replaceTop으로 사라진 로컬 owner가 닫힌다.
- [ ] **Step 2~4: RED → 구현 → GREEN** (IOS_TEST).
- [ ] **Step 5: 되돌림 확인.** 위와 같은 규칙으로 기록.
- [ ] **Step 6: 커밋.** `feature(ios): 계정 범위 화면 정리와 화면별 owner 수명`

### Task 9: 이미지 로더(Coil 3, iOS RemoteImage)

**Files:**
- Modify: `client/gradle/libs.versions.toml`, `client/android/build.gradle.kts:39-46`
- Create: `A/feature/detail/ProductPhoto.kt`, `I/Platform/RemoteImage.swift`, `I/Features/Detail/ProductPhoto.swift`
- Test: `AT/feature/detail/ProductPhotoTest.kt`(placeholder 결정 순수 함수), `IT/RemoteImageTests.swift`

**Interfaces:**
- Produces: Android `@Composable fun ProductPhoto(imageUrl: String?, modifier: Modifier)` — url이 없거나 실패·로딩이면 placeholder(카드색 면 + 중립 상품 아이콘, D9). iOS `struct ProductPhoto: View { let imageUrl: String? }`, `final class RemoteImageLoader { static let shared; func image(for url: URL, maxPixel: CGFloat) async -> UIImage? }`(URLSession `URLCache` 50MB disk, `NSCache` 메모리, `CGImageSourceCreateThumbnailAtIndex` 다운샘플링).
- [ ] **Step 1: 의존성.** catalog에 `coil = "3.x"`(구현 시점 최신 안정 3.x, 정확한 버전과 Kotlin 2.3.21·AGP 9 호환을 `:android:assembleDebug`로 확인하고 [호환성 기록](../../history/architecture/client/)에 남김), `coil-compose`, `coil-network-okhttp`. Ktor 의존성은 넣지 않는다.
- [ ] **Step 2: 실패하는 테스트.** `photoSourceFor(null) == Placeholder`, `photoSourceFor("not a url") == Placeholder`, `photoSourceFor("https://…") == Remote`; iOS `RemoteImageTests.testDownsamplesToTheRequestedPixelSize`(테스트 번들의 1200px PNG를 `file://`로 → 최대 변 300px), `testInvalidDataReturnsNil`.
- [ ] **Step 3~4: 구현 → GREEN.** ANDROID_CHECK, IOS_TEST.
- [ ] **Step 5: 커밋.** `feature(android): 상품 사진 로더 Coil 3 추가` / `feature(ios): 상품 사진 로더`(플랫폼별 두 커밋)

### Task 10: Android 상세·분석 중·로컬 대기 화면과 홈 진입점

**Files:**
- Create: `A/feature/detail/ItemDetailScreen.kt`, `A/feature/detail/LocalSubmissionScreen.kt`, `A/feature/detail/LocalSubmissionPresenterOwner.kt`, `A/feature/detail/DetailText.kt`, `A/feature/detail/DetailScaffold.kt`
- Modify: `A/ui/AppRoute.kt`, `A/feature/home/HomeScreen.kt:229-276`, `HomeLoggedInContent.kt`, `HomeLoggedOutContent.kt`, `android/src/main/res/values/strings.xml`, `values-en/strings.xml`(또는 기존 locale 구조)
- Test: `AT/feature/detail/DetailTextTest.kt`, `AT/feature/detail/LocalSubmissionPresenterOwnerTest.kt`, `AT/feature/home/HomeRowTextTest.kt`

**Interfaces:**
- Consumes: Task 4~9 전부.
- Produces: `DetailText`(순수 함수): `noticeText(DetailNotice): Int`, `savedText(SavedLabel): ResText`, `errorText(ClientError): Int`(NETWORK·TIMEOUT → `detail_error_network`, NOT_FOUND → `detail_not_found`, 그 밖 → `detail_error_server`), `priceCheckedText(RelativeTime): ResText`.

- **화면 규칙**
  - 공통 `DetailScaffold`: 위쪽 바 56(안전 영역 + 6, 버튼 좌우 20), 뒤로(`detail_back`), 선택적 ⋯(`detail_more`), 본문 스크롤, 하단 고정 "원본 보기"(PR A: `Intent.ACTION_VIEW`), 탭 바 숨김(route).
  - `ItemDetailScreen(itemId)`: `viewModel(factory = ItemDetailPresenterOwner.factory(runtime))`(entry store), 처음 한 번 `load`. `PullToRefreshBox` → `refresh()`. `LifecycleResumeEffect`의 두 번째 이후 resume → `refresh()`.
    - `state.item == null && loading` → 틀만. `item == null && error != null` → NOT_FOUND면 `detail_not_found` + 닫기, 그 밖 `errorText` + `detail_retry`. `item == null && !loading && error == null`(Initial: 계정 세대 변경) → 즉시 `pop()`.
    - `item != null`: `DetailKinds.of(item)`. PROCESSING: 사진 자리 분석 중 타일 + `row_processing`, 제목 host, `detail_processing_note`, 저장 시점. READY/INCOMPLETE: `ProductPhoto`, 브랜드(없으면 숨김), 이름(INCOMPLETE에서 없으면 host + `detail_name_empty`), `PriceText`(없으면 숨김) + `metadataCheckedAt`이 있으면 `detail_price_checked`, 정보 카드(카테고리 이름 또는 `detail_category_empty` / 목적 색 점 + 이름 또는 `detail_purpose_none`), INCOMPLETE notice 한 줄, 저장 시점. GONE → `detail_not_found`. ⋯ 없음.
    - 오류인데 항목이 있으면 항목 유지 + 짧은 안내(C1 토스트 부품, 없으면 상단 안내 줄).
    - 목적 색: `purposeColor(colorKey: String?)` = `colorKey?.lowercase()`가 `PurposeKeys.colorKeys`에 있으면 그 토큰, 아니면 중립(`textSecondary`). 표시 계층 함수 하나에 둔다.
  - `LocalSubmissionScreen(submissionId)`: PROCESSING 틀 + 대기 타일과 `HomeRowText`의 상태 문구, host 제목, 저장 시점, ⋯에 `local_delete`(`canDelete` false면 비활성). 확인창(`WLConfirmDialog`): `local_delete_title` / 썸네일 자리 + `local_delete_target` / 글머리표 `local_delete_line_unsent`·`webview_clear_line_irreversible` / `dialog_cancel`·`local_delete`(빨강). outcome: `MovedTo` → `navigator.replaceTop(ItemDetailRoute(id))`, `Deleted` → `pop()`, `RemovedOnServer` → `detail_not_found` + 닫기, `Gone` → `pop()`. `deleteFailed` → `local_delete_failed` 짧은 안내.
  - 홈: 로그인 뒤 분류 중 줄 `clickable` → `push(ItemDetailRoute(target.itemId))`, 로컬 줄(로그인 전·후) → `push(LocalSubmissionRoute(id))`. 접근성 `home_row_open_detail`, "원본" 버튼 `home_row_open_original`(PR A는 `ACTION_VIEW` 그대로).
- [ ] **Step 1: 실패하는 테스트.** `DetailTextTest`: `DetailNotice.entries`·`SavedLabel` 각 형태·`ErrorKind.entries` 전수 → 키. `LocalSubmissionPresenterOwnerTest`: owner가 `ViewModelStore.clear()`에서 Presenter를 닫는다(C3 owner 테스트처럼 runtime 경유). `HomeRowTextTest`: 새 접근성 키.
- [ ] **Step 2~4: RED → 구현 → GREEN.** ANDROID_CHECK.
- [ ] **Step 5: 에뮬레이터 확인.** DEBUG hook으로 분석 중 → 5초 뒤 당겨서 새로고침 → 상세 전환, 로컬 대기 → 로그인 → MovedTo, 로컬 삭제(로그인 전·후), 로그아웃 시 상세 닫힘, 보드 L/D 비교 캡처(`docs/history/architecture/client/` 검증 기록 경로).
- [ ] **Step 6: 커밋.** `feature(android): 상품 상세·분석 중·로컬 대기 화면`

### Task 11: iOS 상세·분석 중·로컬 대기 화면과 홈 진입점

**Files:**
- Create: `I/Features/Detail/ItemDetailScreen.swift`, `LocalSubmissionScreen.swift`, `LocalSubmissionPresenterOwner.swift`, `DetailText.swift`, `DetailScaffold.swift`
- Modify: `I/ContentView.swift`(AppRoute에 `.item`/`.local`), `I/Features/Home/HomeScreen.swift:174-219`, `HomeLoggedInView.swift`, `HomeLoggedOutView.swift`, `I/Localizable.xcstrings`
- Test: `IT/DetailTextTests.swift`, `IT/LocalSubmissionPresenterOwnerTests.swift`, `IT/HomeRowTextTests.swift`

**Interfaces:** Task 10과 같은 규칙. owner는 `WLEntryOwners`에서 얻는다. `@MainActor @Observable LocalSubmissionPresenterOwner(runtime:)`는 `row`, `canDelete`, `deleting`, `deleteFailed`, `outcome`을 다시 게시하고 `close()`/`deinit`에서 닫는다. 새로고침은 `.refreshable { await owner.refreshNow() }`가 아니라 `refresh()` 뒤 state의 `loading`이 false가 될 때까지 기다리는 owner 메서드(`func refreshAndWait() async`). foreground는 `scenePhase` `.active` 복귀(C3 `ForegroundTransitions` 재사용) 때 `refresh()`. "원본 보기"는 PR A에서 `openURL`.
- [ ] **Step 1~4:** Task 10과 같은 테스트(XCTest)와 RED → 구현 → GREEN(IOS_TEST). `DetailText.resolve`는 C3 `HomeText.resolve`처럼 읽은 표의 언어로 format.
- [ ] **Step 5: 시뮬레이터 확인.** Task 10 Step 5와 같은 흐름(`wl.fake.delayItem01`, `wl.fake.pendingCount`, `simctl launch --terminate-running-process`).
- [ ] **Step 6: 커밋.** `feature(ios): 상품 상세·분석 중·로컬 대기 화면`

### Task 12: PR A 전체 검증·문서·리뷰·draft PR

**Files:**
- Modify: `docs/architecture/client/kmp.md`(상세 Presenter 절 갱신, 로컬 대기 Presenter 절 추가, 한계 표 상태 열 채움), `android.md`·`ios.md`(C4 절), `docs/superpowers/specs/2026-10-05-client-implementation-roadmap-design.md`(C3 행 "merge 완료(PR #12)", C4 행 "PR A 구현"), `docs/design/decisions.md`(D2~D4·D6·D8~D10·D12~D17·D19 날짜 2026-10-09/10), `docs/architecture/client/server-integration-status.md`(ITEM-03 C4 사용), `docs/architecture/client/c3-performance-checks.md`(C4 측정 결과 절)
- Create: `docs/history/architecture/client/c4-verification-2026-10-10.md`, 필요한 INDEX 항목

- [ ] **Step 1:** KMP_TEST·ANDROID_CHECK·IOS_TEST·`linkReleaseFrameworkIosArm64`·iOS Release simulator build·`python3 client/tools/gen_tokens.py --check`·`python3 -m unittest client/tools/test_gen_tokens.py`. 건수를 기록(baseline 434/431/94/122 대비).
- [ ] **Step 2:** 성능 baseline: 상세 깊이 1/3/5 push/pop 20회, 이미지 있음·없음 메모리(Android `dumpsys meminfo`, iOS Instruments Allocations 요약). 통과 기준 없음.
- [ ] **Step 3:** 문서 갱신. 한계 표: UUID·새는 취소·해독 불가·close 뒤 DB 단언 "해결(커밋)", 계정 전환 retry "결정 D3(커밋)", `.theRelease`·`RemoteConfig` "C12로 이월". C5/C6 인계(같은 version 표시 metadata·겹친 GET 역전), 실기기 확인 항목(D16·`window.opener`)은 PR B 문서에.
- [ ] **Step 4:** 독립 리뷰(`superpowers:requesting-code-review`, 브랜치 전체 `abae37d..HEAD`). 반영 후 수정 테스트의 되돌림 확인.
- [ ] **Step 5:** 사용자에게 push·draft PR 생성 확인 → `git push -u origin client/c4-product-detail`, `gh pr create --draft --base develop`(본문에 범위·결정·로컬 검증 건수·남은 점, 끝에 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`).
- [ ] **Step 6: 커밋.** `docs: C4 상세 단계 검증과 문서 갱신`

---

# PR B — 원본 링크 웹뷰 (묶음 4)

Task 13 시작 전: `git switch -c client/c4-webview`(PR A HEAD에서).

### Task 13: 웹뷰 정책·intent 정화·route codec (순수 로직)

**Files:**
- Create: `A/feature/web/WebNavigationPolicy.kt`, `A/feature/web/IntentSanitizer.kt`, `A/feature/web/WebViewRoute.kt`, `I/Features/Web/WebNavigationPolicy.swift`, `I/Features/Web/WebViewRoute.swift`
- Modify: `A/ui/AppRoutes.kt`(codec `["web", base64url]`), `I/AppRoutes.swift`(`case web(URL)`, accountScoped)
- Test: `AT/feature/web/WebNavigationPolicyTest.kt`, `AT/feature/web/IntentSanitizerTest.kt`, `AT/feature/web/WebViewRouteCodecTest.kt`, `IT/WebNavigationPolicyTests.swift`, `IT/WebViewRouteTests.swift`

**Interfaces:**
- Produces:

```kotlin
enum class WebDecision { LOAD_INSIDE, BLOCK, OPEN_EXTERNAL, CONFIRM_EXTERNAL }
object WebNavigationPolicy {
    /** [mainFrame]: top-level navigation; [userGesture]: Android hasGesture(), iOS .linkActivated. */
    fun decide(scheme: String, mainFrame: Boolean, userGesture: Boolean, isAboutBlank: Boolean): WebDecision
}
// main frame: http/https → LOAD_INSIDE; about:blank → LOAD_INSIDE; data/blob/javascript/file/content/about(그 밖) → BLOCK
// sub-frame/resources: http/https/data/blob/about → LOAD_INSIDE; javascript/file/content → BLOCK
// 그 밖 scheme(intent, tel, mailto, market, itms-apps, 결제 앱): gesture → OPEN_EXTERNAL, 아니면 CONFIRM_EXTERNAL

sealed interface SanitizedIntent { data class External(val intent: Intent) : SanitizedIntent; data class Fallback(val url: String) : SanitizedIntent; data object Drop : SanitizedIntent }
object IntentSanitizer {
    /** parseUri(URI_INTENT_SCHEME) → component=null, selector=null, addCategory(BROWSABLE); http(s) data or unresolvable → browser_fallback_url (http/https only) or Drop. */
    fun sanitize(uri: String, resolvable: (Intent) -> Boolean): SanitizedIntent
}

internal data class WebViewRoute private constructor(val url: String) : WLRoute {
    override val showsTabBar = false; override val pushStyle = WLPushStyle.Slide; override val accountScoped = true
    companion object { fun of(url: String): WebViewRoute? /* http/https + host only */ ; fun encode/decode via Base64.UrlSafe, no padding }
}
```

- [ ] **Step 1: 실패하는 테스트.**
  - `WebNavigationPolicyTest`: scheme × mainFrame × gesture 표 전수(위 규칙).
  - `IntentSanitizerTest`: `intent://scan/#Intent;scheme=zxing;component=com.evil/.Steal;end` → component·selector null + BROWSABLE, `intent:#Intent;SEL;component=…;end`(selector) 정화, `S.browser_fallback_url=https%3A%2F%2Fm.shop.com`이고 해석 불가 → `Fallback("https://m.shop.com")`, fallback이 `javascript:`면 `Drop`, http data를 가리키는 intent → Fallback 또는 Drop.
  - `WebViewRouteCodecTest`: `https://shop.com/a/b?x=1&y=%20#frag`·한글 path·emoji query 왕복, `javascript:alert(1)`·`file:///etc`·host 없는 URL은 `of` == null, 잘못된 base64·`web/` 뒤 빈 token decode == null.
  - iOS: 같은 policy 표와 route 왕복·검증.
- [ ] **Step 2~4: RED → 구현 → GREEN.** ANDROID_CHECK, IOS_TEST.
- [ ] **Step 5: 되돌림 확인.** component/selector 제거를 빼면 `IntentSanitizerTest`, main frame `data:` BLOCK을 빼면 policy 테스트가 실패하는지 기록.
- [ ] **Step 6: 커밋.** `feature(android): 웹뷰 탐색 정책과 intent 정화` / `feature(ios): 웹뷰 탐색 정책`

### Task 14: Android 웹뷰 화면

**Files:**
- Create: `A/feature/web/WebViewScreen.kt`, `A/feature/web/WebViewSettings.kt`, `A/feature/web/WebShareSheet.kt`
- Modify: `A/ui/AppRoute.kt`, `A/platform/WebViewDataCleaner.kt`(같은 기본 프로필임을 KDoc에 명시)
- Test: `AT/feature/web/WebViewSettingsTest.kt`

**Interfaces:**
- Produces: `fun WebSettings.applyWishlistDefaults()` — `javaScriptEnabled = true`, `domStorageEnabled = true`, `allowFileAccess = false`, `allowContentAccess = false`, `setSupportMultipleWindows(false)`, `mixedContentMode = MIXED_CONTENT_NEVER_ALLOW`; `CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)`. `addJavascriptInterface`는 어디에서도 부르지 않는다(lint 규칙 대신 코드 검색을 PR 체크리스트에 둔다).
- 화면: `AndroidView(WebView)`를 entry 수명에 두고(`rememberSaveable` 아님, entry `ViewModel`에 WebView 보관해 구성 변경에도 유지, `onCleared`에서 `destroy()`), 위쪽 막대(닫기·자물쇠(https)·`DisplayFormat.host`·제목·진행 선), 아래쪽 막대(뒤로/앞으로/새로고침↔중지/공유), `BackHandler`(canGoBack → goBack, 아니면 pop). `shouldOverrideUrlLoading` → `WebNavigationPolicy` + `IntentSanitizer`. `CONFIRM_EXTERNAL` → `WLConfirmDialog`(`webview_external_*`, 열기 먹색). `startActivity` 실패(`ActivityNotFoundException`)는 아무 일도 하지 않음. main frame `onReceivedError` → `webview_load_failed` + `detail_retry` 덮개. 공유 시트: 현재 URL로 `ACTION_VIEW`·클립보드(+ API 33 미만에서만 `webview_link_copied`)·`ACTION_SEND` chooser.
- [ ] **Step 1: 실패하는 테스트.** `WebViewSettingsTest`(Robolectric 없이 `WebSettings` 가짜를 만들 수 없으면 `applyWishlistDefaults`가 쓰는 값 묶음 `WishlistWebDefaults`를 순수 data로 두고 그것을 테스트): 파일·content 접근 false, 다중 창 false, mixed content NEVER.
- [ ] **Step 2~4: RED → 구현 → GREEN.** ANDROID_CHECK.
- [ ] **Step 5: 커밋.** `feature(android): 원본 링크 웹뷰`

### Task 15: iOS 웹뷰 화면

**Files:**
- Create: `I/Features/Web/WebViewScreen.swift`, `I/Features/Web/WebViewModel.swift`, `I/Features/Web/WebShareSheet.swift`
- Modify: `I/ContentView.swift`(AppRoute `.web`), `I/Platform/WebViewDataCleaner.swift`(같은 `.default()` store를 상수로 공유)
- Test: `IT/WebViewStoreTests.swift`

**Interfaces:**
- Produces: `enum WishlistWebStore { static var dataStore: WKWebsiteDataStore { .default() } }` — cleaner와 웹뷰가 같은 값을 쓴다. `@MainActor final class WebViewModel: NSObject, WKNavigationDelegate, WKUIDelegate`(entry owner로 `WLEntryOwners.web(entryId)`에 보관, `close()`에서 delegate 해제·`stopLoading`).
- 규칙: `allowsBackForwardNavigationGestures = true`, 기록이 없을 때만 셸 pop 제스처. `decidePolicyFor` → policy(`navigationAction.targetFrame?.isMainFrame ?? true`, `.linkActivated`). 외부 → `UIApplication.shared.open(url, options: [:]) { ok in }`(`canOpenURL` 쓰지 않음, false면 아무 일도 하지 않음). `createWebViewWith` → `webView.load(navigationAction.request)`, nil 반환. 진행 선 `estimatedProgress` KVO, 제목 `title` KVO. main frame `didFailProvisionalNavigation`/`didFail` → 실패 덮개. 공유 시트: `UIApplication.open`·`UIPasteboard`+`webview_link_copied`·`UIActivityViewController`.
- [ ] **Step 1: 실패하는 테스트.** `testWebViewAndCleanerShareTheDefaultStore`(`WebViewModel`이 만든 configuration의 `websiteDataStore === WishlistWebStore.dataStore`이고 `.isPersistent`), `testNewWindowLoadsInPlace`(가짜 navigationAction으로 `createWebViewWith`가 nil 반환·load 호출).
- [ ] **Step 2~4: RED → 구현 → GREEN.** IOS_TEST.
- [ ] **Step 5: 커밋.** `feature(ios): 원본 링크 웹뷰`

### Task 16: 원본 링크 교체, PR B 검증·문서·draft PR

**Files:**
- Modify: 상세·로컬 대기 "원본 보기"와 홈 "원본"(Android `ACTION_VIEW` → `WebViewRoute.of(url)?.let(navigator::push)`, iOS `openURL` → `.web`), `docs/architecture/client/android.md`·`ios.md`(웹뷰 절), `docs/design/decisions.md`(D16 문구·`window.opener` 메모), `docs/learning/client/q-and-a/`(웹뷰 보안 규칙 Q&A가 새로 답해졌다면 `QA-CLI-00N-webview-security.md` + INDEX), C4 검증 기록에 PR B 절
- [ ] **Step 1:** 교체 후 ANDROID_CHECK·IOS_TEST.
- [ ] **Step 2: 기기 확인.** FWebView·Share·External L/D 비교, 기록 뒤로/앞으로, `target=_blank` 링크가 같은 웹뷰에 열림, 자동 리다이렉트 외부 scheme에 확인창, 사용자 탭 외부 scheme 바로 열림, 링크 복사 안내, 로그아웃 시 웹뷰 닫힘·로그인 시 유지, 설정 "웹뷰 데이터 삭제" 뒤 쿠키 사라짐(테스트 페이지에서 `document.cookie` 확인). 실기기 확인 항목(D16 결제 확인창 빈도, `window.opener`)은 기록에 "미확인 · 실기기"로 남긴다.
- [ ] **Step 3:** 전체 검증(Task 12 Step 1과 같음), 독립 리뷰, 반영.
- [ ] **Step 4:** 사용자 확인 뒤 `git push -u origin client/c4-webview`, `gh pr create --draft --base client/c4-product-detail`. A merge 뒤 `git rebase --onto origin/develop <A의 마지막 커밋> client/c4-webview`, `gh pr edit --base develop`.
- [ ] **Step 5: 커밋.** `docs: C4 웹뷰 검증과 문서 갱신`

---

## Self-Review 기록

- **Spec 범위:** §1 데이터 → Task 1~3, §2 Presenter → Task 4~6, §3 화면·내비게이션 → Task 7~11, §4 웹뷰 → Task 13~16, §5 검증·문서 → Task 12·16, D1~D20 모두 대응. spec과 다른 점: 가격은 새 함수 대신 기존 `PriceFormatter` 재사용(Task 5). "같은 계정 재로그인"은 로그아웃을 거치는 accountId 변화로 잡고 상세 화면의 `Initial` pop을 안전장치로 둔다(Task 7). Task 12 문서에 적는다. `processingItems`의 해독 불가 행도 같은 규칙으로 처리(Task 2)하는 것은 spec §1의 확장이다.
- **Placeholder:** 없음. Coil 버전은 Task 9 Step 1에서 확인해 기록한다(구현 시점 최신 안정 3.x).
- **Type 일관성:** `cachedItemBySubmission`(Task 2 ↔ 6), `deleteLocal`·`SUBMISSION_IN_FLIGHT`(3 ↔ 6), `requestViewPublish`(4 ↔ 6), `DetailKinds.of`·`DetailNotice`·`SavedLabel`(5 ↔ 10·11), `LocalDetailOutcome`(6 ↔ 10·11), `dropAccountScoped`·`replaceTop`·`drainRemoved`(7·8 ↔ 10·11·16), `WebViewRoute.of`(13 ↔ 16).
