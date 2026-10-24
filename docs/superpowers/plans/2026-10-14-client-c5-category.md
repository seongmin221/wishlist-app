# Client C5 Category Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 카테고리 탭(레일 + 세로 페이저 + 칩)에서 세부 유형의 2열 엇갈림 상품 목록으로 가고 카드에서 C4 상세로 간다. 지금 보는 상위 아래에 직접 만든 세부 카테고리를 추가·편집하고, C8에서 쓸 카테고리 선택 시트(+ 신규 카테고리)를 완성한다(PR A). 이어서 카드 사진 → 상세 사진 공유 요소 전환을 붙인다(PR B).

**Architecture:** API 단위 repository(`CategoryRepository` CAT-01~04, `ItemListRepository` ITEM-02)를 Fake·Remote 두 구현과 공통 계약 테스트로 두고, 화면별 shared Presenter 넷(`CategoryHomePresenter`, `CategoryListPresenter`, `CategoryFormPresenter`, `CategoryPickerPresenter`)이 state를 소유한다. 입력 길이·금지 문자(`CategoryInputRules`), 카드 마지막 줄(`CardMetaLine`), 상대 시각(D15 주 단위)은 shared 순수 함수다. 플랫폼은 C4 entry owner 패턴으로 Presenter를 소유하고, 사진 배치(페이지 단위 skeleton·디코드 상한·동시 6개)와 시트 닫기 guard를 더한다.

**Tech Stack:** Kotlin 2.3.21, AGP 9.0.0, Gradle 9.3.0, SQLDelight 2.4.1, Koin 4.2.2, Ktor 3.4.3, SKIE, kotlin.test·coroutines-test·Turbine. Android Compose(API 26+) + Coil 3.5.0, iOS SwiftUI(17+), Xcode 26.6. **새 의존성 없음.**

**Spec:** [C5 설계](../specs/2026-10-14-client-c5-category-design.md)(D1~D15). 함께 볼 것: [카테고리 API 계약](../../architecture/server/category-management-api.md), [ITEM-02](../../architecture/server/wishlist-item-read-api.md#item-02-범위와-표시), [디자인 결정](../../design/decisions.md), [screens.md 카테고리](../../../design/handoff/interactions/screens.md), [설계 검토 기록](../../history/architecture/client/c5-category-design-review-2026-10-14.md), 보드 `design/handoff/screens/boards/{FCategoryHome,FCategoryAddSheet,FCategoryList,FCategoryListCustom,FCategoryListCustomEmpty,FCategoryEditSheet,FProductCategoryPicker,FProductCategoryCreate}{L,D}.dc.html`(문구 원본 `design/canvas-fresh/gen_category.py`·`gen_product.py`), taxonomy 원본 `server/src/main/resources/db/migration/V11__category_management.sql`.

## Global Constraints

- 상태: **확정 v1 (2026-10-14).** 실행 방식: 직접 진행(필요하면 묶음 3·4를 subagent로 병렬). **Android 기기 확인은 사용자 지시(2026-10-14)로 당분간 에뮬레이터 `Medium_Phone_API_36.0`(en-US)로 한다.** 실기기 Flip6 확인은 연결되면 PR 전에 다시 한다.
- 진행(2026-10-15): **PR A Task 0~19 구현·로컬 검증 완료. #21은 2026-10-24 MERGED(`0b95e90`).** Android·iOS 화면은 병렬 worktree에서 만들고 이 브랜치로 가져왔다([PR A 기록](../../history/architecture/client/c5-pr-a-implementation-2026-10-15.md)). PR B #22(Task 20~23)는 Draft OPEN이며 develop 미병합이다. C5 전체 완료가 아니다.
- 진행(2026-10-14): **Task 0~9 완료(묶음 1·2).** Baseline host 501 · simulator 498 · Android unit 183 · XCTest 211. A1 시점 host 633 · simulator 630 · Android unit 183 · XCTest 214, 실패·skip 0. 묶음 3(Android)부터 진행.
- 작업 공간 `/Users/user/orca/workspaces/wishlist-app/client-c5-category`, 브랜치 `client/c5-category`(PR A, base `develop`). PR B는 Task 20 시작 때 `client/c5-category` 위에 `client/c5-photo-transition`을 만들고 base를 A로 연다. A merge 뒤 `origin/develop`으로 rebase하고 base를 `develop`으로 바꾼다(D4). 시작 HEAD `3b13232`(origin/develop `e2653f6` + docs 2커밋). merge는 사용자 승인 없이 하지 않는다. 다른 워크스페이스는 수정·삭제하지 않는다. 이슈 #18(웹뷰 순수 로직 shared 이전)은 건드리지 않는다.
- Kotlin **2.3.21**, Android **API 26+**, iOS **17+**, JDK **17**. 새 의존성 없음(multiplatform-settings 대신 `app_state`, Nuke 없음). 공유 코드에 Compose/SwiftUI 의존성 없음.
- 서버 mutation은 CAT-03·CAT-04만. ITEM-04(상품 카테고리 저장)·CAT-05/06(삭제)은 호출하지 않는다(D1·D7).
- 인증은 fake만. 앱 DEBUG는 CAT-01~04·ITEM-02 FAKE, RELEASE는 UNAVAILABLE. REMOTE는 MockEngine 테스트에서만 조립한다(D2).
- domain·Presenter·LocalStore·Fake·Remote는 TDD. 새 테스트마다 **해당 수정만 되돌려 실패하는지 확인**하고 task 기록에 남긴다. `commonTest`는 Android host와 iOS simulator 모두 실제 실행하고 `NO-SOURCE`/`SKIPPED`를 통과로 기록하지 않는다.
- virtual time 테스트에서 실패를 고정해 볼 때는 `advanceUntilIdle` 대신 `runCurrent`를 쓴다. Fake rate limit(60초)·skeleton 3초는 주입 Clock·test dispatcher로 검증한다.
- 커밋: AGENTS.md 형식(`feature(kmp|android|ios): 한글 설명` / `bugfix: …` / `docs: …` + 빈 줄 + 짧은 본문 + `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`). 플랫폼별로 나눠 커밋한다(shared / Android / iOS / docs).
- 새 문구는 아래 [문구 표](#문구-표)의 한국어·영어를 그대로 쓴다. 보드 문구가 기준이다.
- 화면 부품은 C1 디자인 시스템(`WLBottomSheet`, `WLConfirmDialog`, `WLMenu`, `WLChip`, `WLInput`, `Masonry2Col`, `EmptyState`, `PriceText`, `PurposeDot`, 토큰)과 `WLNavigator`를 쓴다. 시스템 sheet·alert·NavigationStack은 쓰지 않는다.
- 리뷰 결과는 PR 코멘트로 올리지 않는다. 판단이 필요한 충돌·테스트 실패가 있으면 멈추고 사용자에게 묻는다.

## 검증 명령 약어

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
# client/
./gradlew -Porg.gradle.java.installations.paths="$JAVA_HOME" :shared:testAndroidHostTest :shared:iosSimulatorArm64Test
```

- `/usr/libexec/java_home -v 17`은 25를 돌려주므로 쓰지 않는다. macOS에는 `timeout` 명령이 없다.
- **KMP_TEST** = 위 두 task. RED는 `:shared:testAndroidHostTest --tests "*<Class>*"`, GREEN 때 두 runtime 전체를 실행해 `shared/build/test-results/*/*.xml`에서 test 수·fail·skip을 센다.
- **ANDROID_CHECK** = `:android:testDebugUnitTest :android:testReleaseUnitTest :android:assembleDebug :android:assembleRelease :android:lintDebug`.
- **IOS_TEST** = `xcrun simctl spawn <udid> defaults write com.apple.Accessibility ApplicationAccessibilityEnabled -bool true` 후 `xcodebuild test -project client/ios/Wishlist.xcodeproj -scheme Wishlist -destination 'platform=iOS Simulator,id=<udid>' -derivedDataPath client/ios/DerivedData CODE_SIGNING_ALLOWED=NO`. 시뮬레이터 iPhone 17 Pro(실행 때 `xcrun simctl list devices`로 udid 확인).
- **ANDROID_DEVICE** = USB 실기기 Galaxy Z Flip6 `adb -s R3CX70ABZHP`(에뮬레이터 아님). 조작 전 `adb -s R3CX70ABZHP shell dumpsys window | grep mCurrentFocus`로 `app.wishlist.android` 포커스를 확인하고 다른 앱은 건드리지 않는다. 화면이 둘이라 `adb -s R3CX70ABZHP shell dumpsys SurfaceFlinger --display-id`로 id를 확인하고 `adb -s R3CX70ABZHP exec-out screencap -d <display id> -p > shot.png`로 찍는다. 기기가 연결돼 있지 않으면 멈추고 사용자에게 연결을 요청한다. (2026-10-14: 당분간 에뮬레이터 `emulator-5554`를 쓰고 screencap `-d`는 필요 없다.)
- **Baseline:** Task 0에서 이 공간 기준으로 다시 잰다(C4 PR #16 기록: host 501 · simulator 498 · Android unit debug/release 183 · XCTest 211).

## Review Focus

1. **응답을 잃은 생성:** 같은 payload 재시도는 같은 key라 중복 카테고리가 생기지 않고, 20개 한도 선검사가 그 replay를 막지 않는다(Task 8 `lostResponseRetryReusesKeyAndReplays`, `replayIsNotBlockedByLimitPrecheck`).
2. **편집 409 복구:** 최신 상세를 받기 전에는 옛 version으로 다시 보내지 않고, 받은 값이 보낸 PATCH의 기대 결과와 같으면 `Saved`로 끝난다(Task 8 `conflictRefetchFailureBlocksSave`, `conflictWithAlreadyAppliedValuesEndsSaved`).
3. **목록 위치 보존:** 편집 뒤 `applyEdited`·`refreshHeader`가 이어 붙인 페이지·cursor·카드 높이를 지우지 않는다(Task 7 `refreshHeaderKeepsAppendedPages`).
4. **사진 메모리·취소:** 긴 사진도 디코드 픽셀이 열 폭² × 3 이하이고, iOS 화면 취소가 실제 다운로드를 끊으며 실제 동시 작업이 6개를 넘지 않는다(Task 10 `decodeSizeIsCapped`, Task 15 `RemoteImageLoaderCancellationTests`).
5. **IME 조합:** code point 상한 직전의 한글 조합이 깨지지 않고, emoji 묶음이 반쪽으로 남지 않는다(Task 10·15 `codePointLimitKeepsComposition`, `codePointLimitCutsAtGraphemeBoundary`).
6. **(PR B) 전환 사진:** 메모리 cache를 비워도 push 첫 프레임이 카드 사진, pop 첫 프레임이 상세 사진이다(Task 20~22).

## 문구 표

키는 Android `strings.xml` 이름(기본 `values/` = 한국어, `values-en/` = 영어)이고, iOS `Localizable.xcstrings`는 같은 키를 `.`로 바꾼다(`category_title` → `category.title`). 기존 키(`detail_retry`, `detail_close`, `detail_error_network`, `detail_error_server`, `detail_not_found`의 분류 규칙, `dialog_cancel`, `time_*`)는 재사용한다.

| 키 | 한국어 | English |
| --- | --- | --- |
| `time_weeks` | %1$d주 전 | %1$dw ago |
| `time_date` | %1$d월 %2$d일 | %1$d/%2$d |
| `time_date_year` | %1$d년 %2$d월 %3$d일 | %2$d/%3$d/%1$d |
| `category_title` | 카테고리 | Categories |
| `category_total` | 상품 %1$d개 | %1$d items |
| `category_add_chip` | 추가 | Add |
| `category_signed_out_body` | 로그인하면 저장한 상품이 카테고리별로 모여요 | Sign in to see your saved items by category |
| `category_signed_out_action` | 로그인 | Sign in |
| `category_empty_title` | 아직 상품이 없어요 | No items yet |
| `category_empty_body` | 상품을 공유해 저장하면 여기에 모여요 | Share a product to save it here |
| `category_empty_action` | 새 카테고리 만들기 | Create a category |
| `category_list_custom_suffix` | %1$s · 직접 만든 카테고리 | %1$s · Your category |
| `category_list_more` | 카테고리 편집 | Edit category |
| `category_menu_edit` | 편집 | Edit |
| `category_list_empty_body` | 상품 편집에서 이 카테고리를 고르면 여기에 모여요. | Choose this category when editing an item to see it here. |
| `category_card_unconfirmed` | 분류·목적 미확정 | Category and purpose not confirmed |
| `category_card_no_purpose` | 목적 미지정 | No purpose |
| `category_card_checked` | %1$s 확인 | Checked %1$s |
| `category_load_more_failed` | 더 불러오지 못했어요 | Couldn't load more |
| `category_partial_failed` | 일부 정보를 불러오지 못했어요 | Some details couldn't be loaded |
| `category_loading` | 불러오는 중 | Loading |
| `category_gone` | 삭제된 카테고리예요 | This category was deleted |
| `category_form_create_title` | 신규 카테고리 | New category |
| `category_form_edit_title` | 세부 카테고리 편집 | Edit category |
| `category_form_parent_label` | 상위 카테고리 · 필수 | Parent category · Required |
| `category_form_name_label` | 이름 · 필수 | Name · Required |
| `category_form_name_hint` | 최대 40자 | Up to 40 |
| `category_form_name_placeholder` | 예: 오디오 케이블·DAC | e.g. Audio cables & DACs |
| `category_form_desc_label` | 설명 · 선택 | Description · Optional |
| `category_form_desc_hint` | 최대 200자 | Up to 200 |
| `category_form_desc_placeholder` | 어떤 상품을 넣을지 적어 두면 AI 분류에 참고해요 | Describe what belongs here to help AI sorting |
| `category_form_examples_label` | 포함 예시 · 선택 | Examples · Optional |
| `category_form_examples_hint` | 최대 5개 | Up to 5 |
| `category_form_examples_count` | %1$d / 5 | %1$d / 5 |
| `category_form_example_placeholder` | 예시를 입력하고 추가 | Type an example and add |
| `category_form_example_remove` | 예시 빼기 | Remove example |
| `category_form_create_note` | 공용 카테고리는 바뀌지 않고, 나만 쓰는 세부 카테고리로 추가돼요. | Shared categories stay the same. This adds a category just for you. |
| `category_form_picker_note` | ‘%1$s’ 아래에 나만 쓰는 세부 카테고리를 만들어요. 공용 카테고리는 바뀌지 않아요. | Creates a category just for you under ‘%1$s’. Shared categories stay the same. |
| `category_form_usage` | 내 세부 카테고리 %1$d / %2$d | Your categories %1$d / %2$d |
| `category_form_edit_scope` | ‘%1$s’ 아래 · 상품 %2$d개 | Under ‘%1$s’ · %2$d items |
| `category_form_rename_note` | 이름을 바꾸면 상품 %1$d개에 바로 반영돼요 | Renaming updates %1$d items right away |
| `category_form_create` | 만들기 | Create |
| `category_form_save` | 저장 | Save |
| `category_form_err_duplicate` | 같은 상위 카테고리에 이미 있는 이름이에요 | This name is already used in this parent category |
| `category_form_err_limit` | 세부 카테고리는 20개까지 만들 수 있어요 | You can create up to 20 categories |
| `category_form_err_rate` | 잠시 후 다시 만들어 주세요 | Please try again in a moment |
| `category_form_err_chars` | 쓸 수 없는 문자가 들어 있어요 | Contains characters that can't be used |
| `category_form_err_parent` | 상위 카테고리를 다시 골라 주세요 | Choose the parent category again |
| `category_form_err_save` | 저장하지 못했어요. 다시 시도해 주세요 | Couldn't save. Please try again |
| `category_form_conflict_loaded` | 다른 곳에서 바뀐 내용을 불러왔어요 | Loaded changes made elsewhere |
| `category_form_conflict_failed` | 다른 곳에서 바뀌었어요. 최신 내용을 불러오지 못했어요 | This changed elsewhere. Couldn't load the latest version |
| `category_form_discard_title` | 변경 사항을 버릴까요? | Discard changes? |
| `category_form_discard_body` | 편집한 내용이 저장되지 않아요 | Your edits won't be saved |
| `category_form_discard_keep` | 계속 편집 | Keep editing |
| `category_form_discard_confirm` | 버리기 | Discard |
| `category_picker_title` | 카테고리 선택 | Choose category |
| `category_picker_add_chip` | 카테고리 | Category |
| `category_close` | 닫기 | Close |
| `category_back` | 뒤로 | Back |

## 파일 구조와 책임

`S` = `client/shared/src/commonMain/kotlin/app/wishlist/shared`, `T` = `client/shared/src/commonTest/kotlin/app/wishlist/shared`, `L` = `client/localdb/src/commonMain/sqldelight/app/wishlist/shared/data/local`, `A` = `client/android/src/main/kotlin/app/wishlist/android`, `AD` = `client/android/src/debug/kotlin/app/wishlist/android`, `AT` = `client/android/src/test/kotlin/app/wishlist/android`, `I` = `client/ios/Wishlist`, `IT` = `client/ios/WishlistTests`.

| 파일 | 책임 |
| --- | --- |
| `S/core/ClientError.kt`, `S/data/remote/ApiErrorMapper.kt` | `fields: Set<String>`, `details.fields` 읽기 |
| `S/model/CategoryCatalog.kt`(새) | `CategoryCatalog`·`CategoryGroup`·`CategoryEntry`·`CategoryKind`·`CustomCategoryDetail`·`CategoryCreated`·`CategoryCommand`·`CategoryPatch`·`ItemPage` |
| `S/data/remote/CategoryDtos.kt`·`CategoryMapper.kt`·`RemoteCategoryRepository.kt`·`RemoteItemListRepository.kt`(새) | CAT-01~04·ITEM-02 wire |
| `S/repository/CategoryRepository.kt`·`ItemListRepository.kt`(새), `CatalogRepository.kt` | API 단위 인터페이스, 카테고리·목록 메서드 제거(목적 조회만 남김) |
| `S/domain/CategoryInputRules.kt`·`CardMetaLine.kt`(새), `S/domain/DisplayFormat.kt` | 입력 규칙, 카드 줄, `RelativeTime.Weeks`·`OnDate`(D15) |
| `S/data/fake/FakeTaxonomy.kt`·`FakeCategoryRepository.kt`·`FakeItemListRepository.kt`(새), `FakeStore.kt`, `BoardSeeds.kt`, `FakeControls.kt` | 서버 규칙 재현, 69개 seed, 120개 fixture hook |
| `S/repository/LocalStore.kt`, `S/data/local/SqlLocalStore.kt`, `S/data/local/CachedItemListRepository.kt`(새) | 카드 cache `>=` 쓰기, 마지막 상위 |
| `S/presentation/Category*Presenter.kt`·`Category*State.kt`(새) | Presenter 넷 |
| `S/di/RepositoryBindings.kt`, `SharedModules.kt`, `SharedRuntime.kt`, `RuntimeFacades.kt` | binding·facade·factory |
| `A/designsystem/WLInput.kt`, `A/designsystem/overlay/*` | `InputLimitUnit`, 시트 닫기 guard |
| `A/feature/category/*`(새) | 첫 화면·목록·시트·picker·owner·문구·사진 배치 |
| `A/navigation/*`, `A/ui/AppRoute(s).kt`, `A/di/AppRuntimeConfig.kt` | `CategoryListRoute`·codec, DEBUG binding |
| `AD/feature/demo/*`, `AD/ui/VariantRoutes.kt` | 카테고리 데모 제거, picker 데모 진입 |
| `I/Platform/RemoteImage.swift`, `I/DesignSystem/WLInput.swift`, `I/DesignSystem/Overlay/*` | 폭 기준 요청·취소·동시 제한, code point 제한, 시트 guard |
| `I/Features/Category/*`(새), `I/Navigation/*`, `I/AppRoutes.swift`, `I/ContentView.swift`, `I/AppRuntimeConfig.swift`, `I/Features/Demo/*` | 같은 화면·route·owner·데모 정리 |
| (PR B) `A/navigation/SharedTransitionKeys.kt`, `A/feature/{category,detail}/*`, `I/Navigation/WLSharedElement.swift`, `I/Features/{Category,Detail}/*`, 두 플랫폼 `PhotoHandoff`(새) | 사진 공유 요소·handoff·방향별 공급원 |

---

# PR A — 카테고리 기능 (묶음 1~4)

### Task 0: Baseline

- [ ] 기기·도구 확인: `adb devices`에 `R3CX70ABZHP`, `xcrun simctl list devices`에서 iPhone 17 Pro udid. 없으면 멈추고 묻는다.
- [ ] KMP_TEST·ANDROID_CHECK·IOS_TEST·`python3 client/tools/gen_tokens.py --check`를 실행해 건수·실패·skip을 이 plan 상태 줄에 기록한다. 실패가 있으면 멈추고 묻는다.

## 묶음 1: 데이터

### Task 1: 필드 오류, 카테고리 모델·DTO·mapper

**Files:**
- Modify: `S/core/ClientError.kt`, `S/data/remote/ApiErrorMapper.kt`
- Create: `S/model/CategoryCatalog.kt`, `S/data/remote/CategoryDtos.kt`, `S/data/remote/CategoryMapper.kt`
- Test: `T/data/remote/ApiErrorMapperTest.kt`, `T/data/remote/CategoryMapperTest.kt`(새)

**Interfaces (Produces):**

```kotlin
data class ClientError(..., val fields: Set<String> = emptySet())
enum class CategoryScope { SELECT, BROWSE }
enum class CategoryKind { PUBLIC, CUSTOM, UNKNOWN }
data class CategoryEntry(val id: String, val name: String, val parentId: String, val kind: CategoryKind, val rawKind: String, val displayOrder: Int, val itemCount: Int)
data class CategoryGroup(val id: String, val name: String, val displayOrder: Int, val itemCount: Int, val categories: List<CategoryEntry>)
data class CategoryCatalog(val scope: CategoryScope, val taxonomyVersion: String, val groups: List<CategoryGroup>, val customUsedCount: Int, val customLimit: Int)
data class CustomCategoryDetail(val id: String, val name: String, val parentId: String, val description: String?, val examples: List<String>, val itemCount: Int, val version: Int)
data class CategoryCreated(val category: CustomCategoryDetail, val customUsedCount: Int, val customLimit: Int)
data class CategoryCommand(val parentId: String, val name: String, val description: String?, val examples: List<String>)
data class CategoryPatch(val name: String? = null, val description: Patch<String?> = Patch.Unchanged, val examples: List<String>? = null)
data class ItemPage(val items: List<WishlistItem>, val totalCount: Int, val nextCursor: String?)
```

- [ ] **Step 1: 실패하는 테스트.** `ApiErrorMapperTest`: `details.fields = ["description","examples"]` → `fields == setOf("description","examples")`; `fields`가 문자열이 아닌 원소를 섞으면 문자열만; `fields`가 객체·없음 → 빈 집합; 기존 `currentVersion` 경로 그대로. `CategoryMapperTest`: 계약 문서의 CAT-01 BROWSE 예시·CAT-03 응답·CAT-02/04 응답을 그대로 파싱, 모르는 kind `"SHARED"` → `UNKNOWN`(rawKind 보존), id는 canonical 소문자 UUID(custom), C/G-ID는 그대로, description null·examples `[]`.
- [ ] **Step 2: RED.** `--tests "*ApiErrorMapperTest*" --tests "*CategoryMapperTest*"`.
- [ ] **Step 3: 구현.** `Patch`는 지금 `S/data/fake/FakeControls.kt`의 internal sealed 타입이다. `S/model/Patch.kt`로 옮겨 공개하고 Fake가 그것을 쓴다. mapper는 필수 필드 누락을 `INVALID_RESPONSE`로 한다(C2 `ItemMapper` 규칙).
- [ ] **Step 4: GREEN** → KMP_TEST 전체.
- [ ] **Step 5: 되돌림 확인.** `details.fields` 읽기만 지우면 `fieldsAreReadFromDetails`가 실패하는지 기록.
- [ ] **Step 6: 커밋.** `feature(kmp): 카테고리 모델과 필드 오류 전달`

### Task 2: 입력 규칙, 카드 줄, 주 단위 상대 시각(D15)

**Files:**
- Create: `S/domain/CategoryInputRules.kt`, `S/domain/CardMetaLine.kt`
- Modify: `S/domain/DisplayFormat.kt`(`RelativeTime.Weeks(value)`, `RelativeTime.OnDate(year?, month, day)`), 두 플랫폼 상대 시각 문구(`A/feature/home/HomeRowText.kt`, `I/Features/Home/HomeRowText.swift`, 문자열 `time_weeks`·`time_date`·`time_date_year`)
- Test: `T/domain/CategoryInputRulesTest.kt`, `T/domain/CardMetaLineTest.kt`, `T/domain/DisplayFormatTest.kt`, `AT/feature/home/HomeRowTextTest.kt`, `IT/HomeRowTextTests.swift`

**Interfaces (Produces):**

```kotlin
object CategoryInputRules {
    const val NAME_MAX = 40; const val DESCRIPTION_MAX = 200; const val EXAMPLES_MAX = 5; const val EXAMPLE_MAX = 60
    fun codePoints(text: String): Int
    fun validate(command: CategoryCommand): Set<String>          // 서버 field 이름: name·description·examples·parentId
    fun hasForbidden(text: String, allowLineBreaks: Boolean): Boolean
}
sealed interface PurposeLabel { data class Named(val name: String, val colorKey: String?) : PurposeLabel; data object None : PurposeLabel }
data class CardMetaLine(val purpose: PurposeLabel, val checked: RelativeTime?)   // checked null이면 구분점 없음
fun cardMetaLine(item: WishlistItem, now: Instant, utcOffsetSeconds: (Instant) -> Int): CardMetaLine
```

- [ ] **Step 1: 실패하는 테스트.**
  - `CategoryInputRulesTest`: 한글 40자 통과·41자 실패, emoji(surrogate 쌍) 하나 = 1, ZWJ 가족 emoji = 5~7 code point, NFD 한글은 자모 수, 이름의 `\n`·단독 `\r`·U+200B·U+FEFF·U+202A·U+2066·짝 없는 surrogate 금지, 설명의 LF·CRLF 허용·CRLF는 2, U+2028·U+3164·U+200E 허용, 공백뿐인 이름·빈 예시 → 해당 field, 예시 6개 → `examples`.
  - `CardMetaLineTest`: 목적 있음·없음 × 확인 시각 있음·null(4조합), 목적 이름 null인 연결(이름 없는 목적 = `None`이 아니라 `Named("")`이 되지 않게 `None`).
  - `DisplayFormatTest`: 6일 = `Days(6)`, 7일 = `Weeks(1)`, 27일 = `Weeks(3)`, 28일 = `OnDate(null, m, d)`, 해가 다르면 `OnDate(year, …)`, 기존 방금·분·시간·어제 불변.
  - 플랫폼 문구 테스트: `Weeks`·`OnDate` 키 ko·en, C4 `detail_price_checked`에 `OnDate`가 들어가도 문장이 된다.
- [ ] **Step 2: RED.**
- [ ] **Step 3: 구현.** `relative`의 days 분기에 7·28 경계를 더하고 `OnDate`는 `saved`와 같은 civil 계산을 쓴다. 두 플랫폼의 `when`/`switch`에 새 case를 더해 빌드를 유지한다.
- [ ] **Step 4: GREEN** → KMP_TEST, ANDROID_CHECK, IOS_TEST.
- [ ] **Step 5: 되돌림 확인.** 7일 경계만 되돌려 `sevenDaysIsOneWeek` 실패 기록.
- [ ] **Step 6: 커밋(셋).** `feature(kmp): 카테고리 입력 규칙과 주 단위 상대 시각`, `feature(android): 주 단위 상대 시각 문구`, `feature(ios): 주 단위 상대 시각 문구`

### Task 3: repository 인터페이스, Fake 규칙, seed

**Files:**
- Create: `S/repository/CategoryRepository.kt`, `S/repository/ItemListRepository.kt`, `S/data/fake/FakeTaxonomy.kt`, `S/data/fake/FakeCategoryRepository.kt`, `S/data/fake/FakeItemListRepository.kt`, `T/repository/CategoryRepositoryContractTest.kt`, `T/repository/ItemListRepositoryContractTest.kt`, `T/data/fake/FakeCategoryRepositoryTest.kt`
- Modify: `S/data/fake/FakeStore.kt`(owner별 custom 목록·receipt·생성 시각 기록), `S/data/fake/BoardSeeds.kt`, `S/data/fake/FakeControls.kt`(`seedLongCategory(categoryId, count = 120)`), `S/repository/CatalogRepository.kt`·`FakeCatalogRepository.kt`(카테고리·목록 메서드 제거), `T/data/fake/BoardSeedsTest.kt`, `IT/SharedInteropTests.swift:184`(목록 호출을 새 repository로)

**Interfaces (Produces):**

```kotlin
interface CategoryRepository {
    suspend fun catalog(scope: CategoryScope, parentId: String? = null): ClientResult<CategoryCatalog>      // CAT-01
    suspend fun custom(id: String): ClientResult<CustomCategoryDetail>                                       // CAT-02
    suspend fun create(command: CategoryCommand, idempotencyKey: String): ClientResult<CategoryCreated>      // CAT-03
    suspend fun update(id: String, expectedVersion: Int, patch: CategoryPatch): ClientResult<CustomCategoryDetail> // CAT-04
}
interface ItemListRepository {
    suspend fun byCategory(categoryId: String, cursor: String? = null, limit: Int = 40): ClientResult<ItemPage> // ITEM-02
}
```

오류 code 문자열은 서버와 같게 `ClientError.code`에 둔다(`CATEGORY_NAME_DUPLICATE`, `CATEGORY_LIMIT_REACHED`, `CATEGORY_CREATE_RATE_LIMITED` + `retryAfterSeconds`, `CATEGORY_VERSION_CONFLICT` + `currentVersion`, `CATEGORY_NOT_FOUND`, `INVALID_CATEGORY_INPUT` + `fields`, `INVALID_CATEGORY_PARENT`, `IDEMPOTENCY_KEY_REUSED`).

- [ ] **Step 1: 실패하는 테스트.** abstract `CategoryRepositoryContractTest`(fixture: owner 전환·Clock 진행·상품 배치):
  - SELECT는 11개 상위·87개 공용 + custom 전부(상품 0 포함), BROWSE는 표시 상품이 있는 공용 leaf + 모든 custom, leaf가 있는 상위만, 빈 계정은 빈 groups, parentId 필터, 없는 parent → `VALIDATION/INVALID_CATEGORY_PARENT`.
  - count: 이름 공백·ARCHIVED·DELETED·다른 owner 제외, PROCESSING이라도 이름 있으면 포함, group = leaf 합.
  - custom 순서: 첫 custom displayOrder = 공용 leaf 수, 다음은 +1, 생성 순.
  - 생성: 201 결과, 같은 key 같은 payload → 같은 id(replay, 한도·rate보다 먼저), 같은 key 다른 payload → `IDEMPOTENCY_KEY_REUSED`, 공백·대소문자만 다른 이름 같은 parent → `CATEGORY_NAME_DUPLICATE`, 다른 parent는 허용, 21번째 → `CATEGORY_LIMIT_REACHED`, 60초 안 6번째 → `RATE_LIMITED` + `retryAfterSeconds ≥ 1`, 61초 뒤 통과, 422 + `fields`.
  - CAT-02 404, PATCH: version 불일치 → `CATEGORY_VERSION_CONFLICT` + `currentVersion`, 동일 내용 → version 유지, 실제 변경 → +1, description null 해제·examples `[]`, 이름 변경이 상품 category name에 반영.
  - `ItemListRepositoryContractTest`: `createdAt DESC, id DESC`, limit 40 → `nextCursor`, 마지막 페이지 null, 이름 없는 상품 제외, totalCount = CAT count, 없는 custom → `CATEGORY_NOT_FOUND`, limit 0·101 → VALIDATION.
  - `FakeCategoryRepositoryTest`가 contract를 Fake로 실행한다. `BoardSeedsTest`: BROWSE 칩 count가 보드 숫자(합계 69)와 같다, 레고는 0이어도 BROWSE에 있다.
- [ ] **Step 2: RED.**
- [ ] **Step 3: 구현.**
  - `FakeTaxonomy`: `V11__category_management.sql`의 G001~G011·C001~C087 이름·순서를 그대로 옮긴다. 손으로 옮긴 표를 test가 87개·11개·ID 연속으로 확인한다.
  - normalized 이름: trim + 공백 collapse + `lowercase()`(NFC 없음, kmp.md 한계 표에 적는다). 길이·금지 문자는 `CategoryInputRules`.
  - `BoardSeeds`: 칩 숫자만큼 READY 상품(보드 이름이 있는 헤드폰 8·DAC 2는 그 이름·가격, 나머지는 `"<카테고리 이름> <n>"`, 사진 없음, 가격은 카테고리별 고정값). `BoardDisplayMetadata.categoryChipCounts` 삭제.
  - `FakeControls.seedLongCategory`: 지정 카테고리에 120개(10개마다 세로로 긴 사진 URL `https://seed.wishlist.invalid/tall/<n>.jpg`, 3초 넘게 걸리는 URL 1개)를 넣는다. DEBUG 전용.
- [ ] **Step 4: GREEN** → KMP_TEST.
- [ ] **Step 5: 되돌림 확인.** replay 판정을 한도 검사 뒤로 옮기면 `replayComesBeforeLimit`가 실패하는지 기록.
- [ ] **Step 6: 커밋.** `feature(kmp): 카테고리·목록 Fake와 공통 계약`

### Task 4: Remote CAT-01~04·ITEM-02와 binding

**Files:**
- Create: `S/data/remote/RemoteCategoryRepository.kt`, `S/data/remote/RemoteItemListRepository.kt`, `T/data/remote/ScriptedCategoryServer.kt`, `T/data/remote/RemoteCategoryRepositoryTest.kt`
- Modify: `S/data/remote/ItemDtos.kt`(ITEM-02 page DTO: `items[{item, anchorCursor}]`, `totalCount`, `nextCursor`, `previousCursor`), `S/di/RepositoryBindings.kt`(`FAKE_IMPLEMENTED_APIS`·`REMOTE_IMPLEMENTED_APIS`에 CAT_01~04·ITEM_02), `S/di/SharedModules.kt`, `S/di/SharedRuntime.kt`(`categoryRepository()`, `itemListRepository()` gated facade), `S/di/RuntimeFacades.kt`, `A/di/AppRuntimeConfig.kt`, `I/AppRuntimeConfig.swift`, `T/di/SharedModulesTest.kt`, `AT/.../AppRuntimeConfigDebugTest.kt`

- [ ] **Step 1: 실패하는 테스트.** `ScriptedCategoryServer`(MockEngine)가 계약 문서의 상태 코드·헤더·body를 재현하고 contract 두 개를 Remote로 실행한다. Remote 전용: `Idempotency-Key` 헤더, 201·200 replay 같은 결과, `Retry-After: 37` → 37, PATCH body가 바뀐 필드만(`description: null` 명시, 생략 필드 없음), ITEM-02 query(`categoryId`, `cursor`, `limit`), 422 `fields`. `SharedModulesTest`: DEBUG FAKE·REMOTE 조립, RELEASE UNAVAILABLE, 미구현 API FAKE 거절은 그대로.
- [ ] **Step 2: RED.**
- [ ] **Step 3: 구현.** 카드 `item`은 기존 `ItemMapper`로 읽는다. 앱 DEBUG binding: CAT_01~04·ITEM_02 FAKE 추가.
- [ ] **Step 4: GREEN** → KMP_TEST, ANDROID_CHECK(binding 테스트), IOS_TEST.
- [ ] **Step 5: 되돌림 확인.** PATCH 직렬화에서 `explicitNulls` 처리를 되돌리면 `patchSendsExplicitNullDescription`이 실패하는지 기록.
- [ ] **Step 6: 커밋(셋).** `feature(kmp): 카테고리·목록 Remote와 binding`, `feature(android): DEBUG 카테고리 Fake binding`, `feature(ios): DEBUG 카테고리 Fake binding`

### Task 5: 카드 cache 쓰기와 마지막 상위

**Files:**
- Create: `S/data/local/CachedItemListRepository.kt`
- Modify: `S/repository/LocalStore.kt`(`lastCategoryGroup(snapshot)`, `setLastCategoryGroup(snapshot, groupId)`, `upsertItemsFromGet(snapshot, items)`), `S/data/local/SqlLocalStore.kt`, Gated·Closed store, `S/di/SharedRuntime.kt`(목록 facade를 decorator로 한 번 감쌈)
- Test: `T/data/local/LocalStoreContractTest.kt`, `T/data/local/CachedItemListRepositoryTest.kt`

- [ ] **Step 1: 실패하는 테스트.** 카드 쓰기는 `>=`(같은 version이면 덮음, 작은 version은 버림), 한 transaction, cache 쓰기 실패는 목록 결과를 실패로 만들지 않는다(로그만), 계정 A의 마지막 상위가 B에 보이지 않음, 비로그인은 `UNAUTHENTICATED`, Closed store는 DB 미접촉.
- [ ] **Step 2: RED → Step 3: 구현.** key `category.lastGroup.<accountId>`(`app_state`). schema 변경 없음.
- [ ] **Step 4: GREEN** → KMP_TEST. **Step 5: 되돌림 확인.** `>=`를 `>`로 바꾸면 `sameVersionCardRefreshesDisplay` 실패 기록.
- [ ] **Step 6: 커밋.** `feature(kmp): 목록 카드 캐시와 마지막 상위 저장`

### 중간 확인 지점 D1

KMP_TEST 건수와 묶음 1 커밋 목록을 상태 줄에 남긴다. 사용자 정지 없이 진행한다(문제가 있을 때만 멈춤).

## 묶음 2: Presenter

공통 규칙(넷 모두): 생성자 `internal`, `SharedRuntime` factory, 단일 lane(`limitedParallelism(1)`), 조회는 마지막 요청 승리, 응답 발행은 `AuthSession.withCurrent`, session 변경 시 진행 요청 취소 + 처음 상태, 새는 `CancellationException`은 `UNAVAILABLE/…_STEP_FAILURE`, 멱등 close·close 뒤 intent 무시. 테스트는 C4 `ItemDetailPresenterTest` 형태(Turbine + test dispatcher).

### Task 6: CategoryHomePresenter

**Files:** Create `S/presentation/CategoryHomePresenter.kt`, `CategoryHomeState.kt`, `T/presentation/CategoryHomePresenterTest.kt`

```kotlin
data class CategoryHomeState(val signedIn: Boolean, val groups: List<CategoryGroup>, val totalCount: Int,
    val currentGroupId: String?, val loading: Boolean, val error: ClientError?, val refreshFailed: Boolean)
// intents: load(), refresh(), retry(), showGroup(groupId)
```

- [ ] **Step 1~5 (TDD):** 비로그인 → 요청 없음·`signedIn=false`; 로그인 → BROWSE(parent 없음); 빈 groups; `currentGroupId` = 저장된 상위가 groups에 있으면 그것, 없으면 첫 상위; `showGroup`이 저장(같은 값 반복은 한 번); `refresh` 실패는 항목 유지 + `refreshFailed`; 첫 load 실패 → error; 계정 변경 → 초기화 후 새 계정 load; 로그인 null → A에 자동 load. 되돌림: 저장된 상위 무시하면 `restoresLastGroup` 실패 기록.
- [ ] **Step 6: 커밋.** `feature(kmp): 카테고리 첫 화면 Presenter`

### Task 7: CategoryListPresenter

**Files:** Create `S/presentation/CategoryListPresenter.kt`, `CategoryListState.kt`, `T/presentation/CategoryListPresenterTest.kt`

```kotlin
data class CategoryListHint(val categoryId: String, val name: String, val parentName: String, val kind: CategoryKind)
data class CategoryListHeader(val name: String, val parentName: String, val kind: CategoryKind, val count: Int?)
data class CategoryListState(val header: CategoryListHeader, val custom: CustomCategoryDetail?, val items: List<WishlistItem>,
    val totalCount: Int?, val hasMore: Boolean, val appending: Boolean, val appendFailed: Boolean,
    val loading: Boolean, val itemsError: ClientError?, val partialFailed: Boolean, val gone: Boolean)
// intents: load(hint), loadMore(), refresh(), refreshHeader(), applyEdited(detail), retry()
```

- [ ] **Step 1~5 (TDD):** hint로 즉시 머리; 머리(CAT-01 BROWSE parent 지정 + custom이면 CAT-02)와 ITEM-02 첫 페이지를 병렬; count 우선순위 CAT-01 > ITEM-02 totalCount; 머리만 실패 → hint 유지·오류 없음(`load`), `refresh`에서 머리만 실패 → `partialFailed`; ITEM-02만 실패 → `itemsError`, `retry()`는 실패한 쪽만; `loadMore` 진행 중 중복 무시·실패 → `appendFailed`·재시도는 같은 cursor; `refresh`는 이어 붙인 페이지 버림; `refreshHeaderKeepsAppendedPages`(3페이지 붙인 뒤 `refreshHeader` → items·cursor 그대로); `applyEdited`가 이름 즉시 반영 후 `refreshHeader`; CAT-02 또는 ITEM-02의 `CATEGORY_NOT_FOUND` → `gone`; 공용 leaf가 BROWSE에서 빠짐 → hint + totalCount 0 빈 목록; 계정 변경 → 초기화. 되돌림: `refreshHeader`에서 items 초기화를 넣으면 실패 기록.
- [ ] **Step 6: 커밋.** `feature(kmp): 카테고리 목록 Presenter`

### Task 8: CategoryFormPresenter

**Files:** Create `S/presentation/CategoryFormPresenter.kt`, `CategoryFormState.kt`, `T/presentation/CategoryFormPresenterTest.kt`

```kotlin
enum class FormMode { CREATE, EDIT }
enum class FormSource { TAB, PICKER }
enum class FormPhase { LOADING, READY, LOAD_FAILED, CONFLICT_UNRESOLVED }
sealed interface FormResult { data class Created(val detail: CustomCategoryDetail) : FormResult; data class Saved(val detail: CustomCategoryDetail) : FormResult; data object Gone : FormResult }
data class CategoryFormState(val mode: FormMode, val phase: FormPhase, val parents: List<CategoryGroup>, val parentId: String?,
    val name: String, val description: String, val examples: List<String>, val pendingExample: String,
    val dirty: Boolean, val canSubmit: Boolean, val submitting: Boolean, val fieldErrors: Set<String>,
    val message: FormMessage?, val usage: Usage?, val itemCount: Int?, val result: FormResult?)
// intents: startCreate(parentId?, source, select: CategoryCatalog?), startEdit(id), retryLoad(),
//          setParent, setName, setDescription, setPendingExample, commitExample(), removeExample(i), submit(), consumeResult()
```

- [ ] **Step 1~5 (TDD):**
  - SELECT 공급: `startCreate`가 SELECT 조회(빈 계정·BROWSE에 상위 하나뿐인 계정에서도 11개), `select` 인자가 있으면 조회 없음, 실패 → `LOAD_FAILED`·입력 유지·`retryLoad`는 SELECT만, LOADING 동안 `canSubmit=false`.
  - 편집 첫 조회: CAT-02 실패 → `LOAD_FAILED`, 404 → `Gone`.
  - 예시: `commitExample` 빈 값·중복 무시, 5개면 무시, 제출 때 `pendingExample` 포함.
  - dirty: 처음 값과 비교(되돌리면 false), 편집은 dirty일 때만 제출.
  - `canSubmit`: phase READY && !submitting && 규칙 통과 && 상위 && (편집 dirty) && (생성: used < limit || payload == lastSent).
  - 제출: 진행 중 재제출 무시(`doubleSubmitIsIgnored`), key·payload snapshot(`samePayloadReusesKey`, `changedPayloadGetsNewKey`), `lostResponseRetryReusesKeyAndReplays`, `replayIsNotBlockedByLimitPrecheck`.
  - 오류: 중복 → `fieldErrors={name}` + duplicate 문구, 한도, 429, 422 `fields`별(`name/description/examples` 칸, `parentId` 상위, 그 밖 공통), 칸을 고치면 그 오류 제거.
  - 409: 재조회 동안 잠금 → 성공이면 교체 + `conflict_loaded`(`conflictReplacesForm`); 최신 값이 보낸 PATCH 기대 결과와 같으면 `Saved`(`conflictWithAlreadyAppliedValuesEndsSaved`); 재조회 실패 → `CONFLICT_UNRESOLVED`·초안 유지·`canSubmit=false`·`retryLoad`로 재조회(`conflictRefetchFailureBlocksSave`); 재조회 404 → `Gone`.
  - close 중 제출 → 요청 취소·결과 버림, 계정 변경 → 초기화.
  - 되돌림: `canSubmit`의 replay 예외를 지우면 `replayIsNotBlockedByLimitPrecheck` 실패 기록.
- [ ] **Step 6: 커밋.** `feature(kmp): 카테고리 생성·편집 폼 Presenter`

### Task 9: CategoryPickerPresenter와 runtime 연결

**Files:** Create `S/presentation/CategoryPickerPresenter.kt`, `CategoryPickerState.kt`, `T/presentation/CategoryPickerPresenterTest.kt`; Modify `S/di/SharedRuntime.kt`(`categoryHomePresenter()`, `categoryListPresenter()`, `categoryFormPresenter()`, `categoryPickerPresenter()`), `T/di/SharedModulesTest.kt`, `IT/SharedInteropTests.swift`

- [ ] **Step 1~5 (TDD):** `open(selectedId?)` SELECT 조회, 선택 칩의 상위를 연다(없으면 마지막 상위, 그것도 없으면 첫 상위), `showGroup`, `select` → `Selected(entry)`, `onCreated(detail)` → SELECT 재조회 후 `Selected`(재조회 실패해도 detail로 `Selected`), 조회 실패 → error·retry. runtime: 네 factory가 gated facade·session·io dispatcher로 만든다. Swift interop: 각 Presenter state Flow 수집·close·`ClientError.fields`가 `Set<String>`으로 보임.
- [ ] **Step 6: 커밋(둘).** `feature(kmp): 카테고리 선택 Presenter와 runtime 연결`, `feature(ios): 카테고리 Presenter interop 테스트`

### 중간 확인 지점 A1

KMP_TEST(두 runtime)·IOS_TEST 건수를 상태 줄에 남기고, 묶음 3·4는 Presenter 공개 면만 보고 병렬로 진행할 수 있다(subagent 실행이면 Android·iOS를 따로 맡긴다).

## 묶음 3: Android 화면

### Task 10: Android 공용 부품 — code point 입력, 시트 닫기 guard, 사진 배치 로직

**Files:**
- Modify: `A/designsystem/WLInput.kt`(`enum class InputLimitUnit { Grapheme, CodePoint }`, 기본 Grapheme), `A/designsystem/overlay/OverlayHost.kt`·`WLBottomSheet.kt`(`showSheet(..., dismissGuard: () -> SheetDismiss)`; `SheetDismiss { Allow, Block, Confirm }`)
- Create: `A/feature/category/PhotoPagePlanner.kt`(순수), `A/feature/category/CategoryPhotoLoader.kt`
- Test: `AT/designsystem/InputLimitTest.kt`, `AT/designsystem/overlay/*`, `AT/feature/category/PhotoPagePlannerTest.kt`

- [ ] **Step 1: 실패하는 테스트.**
  - `InputLimitTest`: `codePointLimitEnd` — 40 code point 넘는 붙여넣기는 마지막 grapheme 경계에서 자름, ZWJ emoji가 상한을 넘으면 통째로 빠짐(`codePointLimitCutsAtGraphemeBoundary`), 조합 중(composition 있음)에는 자르지 않고 확정 뒤 자름(`codePointLimitKeepsComposition`), Grapheme 기본 동작 불변.
  - overlay: guard `Block`이면 막 누르기·끌기·뒤로 모두 닫지 않음(끌기는 snap back), `Confirm`이면 `onConfirmDismiss` 콜백만, `Allow`는 기존과 같음.
  - `PhotoPagePlannerTest`: 원본 크기 다 오면 배치(`ratio = h/w`, EXIF 회전된 크기 사용), 3초 시간 초과 → 모르는 카드 1:1, 사진 없는 카드 즉시 1:1, 배치 뒤 늦은 크기는 높이 불변, `decodeSizeIsCapped`(폭 W 열 → 디코드 픽셀 ≤ W×W×3, 비율 유지 축소), 동시 6개(planner가 요청 허가를 6개만 내줌, 취소하면 다음 허가).
- [ ] **Step 2: RED → Step 3: 구현.** `CategoryPhotoLoader`는 Coil 요청을 semaphore(6)로 감싸고, 원본 크기는 decoder 단계에서 `BitmapFactory.Options.inJustDecodeBounds` 값을 기록하는 `Decoder.Factory` 래퍼로 얻는다(Coil 결과 bitmap 크기 대신). 디코드 크기는 planner가 준 `Size`.
- [ ] **Step 4: GREEN** → ANDROID_CHECK. **Step 5: 되돌림 확인.** guard `Block`을 무시하면 `blockedSheetIgnoresScrimTap` 실패 기록.
- [ ] **Step 6: 커밋.** `feature(android): code point 입력 제한·시트 닫기 guard·사진 배치 계산`

### Task 11: Android route·codec·owner

**Files:** Create `A/feature/category/CategoryRoutes.kt`(`CategoryListRoute(hint)`: `showsTabBar=true`, `Slide`, `accountScoped=true`), `A/feature/category/CategoryOwners.kt`(ViewModel owner 넷); Modify `A/ui/AppRoutes.kt`(codec `category/<id>/<base64url(hint json)>`), `A/ui/AppRoute.kt`(`WLTab.Category` 루트 → `CategoryHomeScreen`, `CategoryListRoute` → `CategoryListScreen`)
**Test:** `AT/ui/AppRouteCodecTest.kt`(hint에 `/ ? # %`·한글 왕복, 잘못된 token null), `AT/navigation/WLNavigatorTest.kt`(계정 떠날 때 목록 닫힘, 탭 바 유지 slide), owner close 테스트.
- [ ] TDD 후 커밋. `feature(android): 카테고리 목록 route와 owner`

### Task 12: Android 첫 화면과 추가 시트

**Files:** Create `A/feature/category/CategoryHomeScreen.kt`, `CategoryRail.kt`, `CategoryPager.kt`, `CategoryFormSheet.kt`(생성·편집 공용 폼), `CategoryText.kt`(문구 선택 순수 함수); 문자열 ko·en
**Test:** `AT/feature/category/CategoryTextTest.kt`(키 전수 ko·en), `RailLabelTest`(가운뎃점·띄어쓰기 뒤에서만 줄바꿈 — `·` 뒤 zero-width space 삽입 함수)

- [ ] 화면: 머리(도현 28 + "상품 N개"), 레일 124dp, 세로 페이저(페이지 = 상위, `VerticalPager` + snap 300 `emphasized`, 페이지 안 스크롤 먼저), 칩 + 점선 `+ 추가`, 당겨서 새로고침(첫 페이지 위에서만), foreground 새로고침(`ForegroundSignals`), 로그인 전(D5)·빈 탭(D6)·로딩 틀·오류. 칩 → `CategoryListRoute(hint)`.
- [ ] 추가 시트: `CategoryFormSheet`(상위 11개 칩 skeleton → 칩, 이름·설명·예시 `WLInput(limitUnit = CodePoint)`, 완료 키로 예시, 안내, 취소·만들기). guard: 제출 중 `Block`, dirty `Confirm` → "변경 사항을 버릴까요?". 만들면 D9(시트 닫고 `showGroup(parentId)` + `refresh()`).
- [ ] 기기 확인(ANDROID_DEVICE): FCategoryHome·AddSheet L/D, 페이저 스냅·레일 이동·재시작 뒤 마지막 상위, 로그인 전·빈 탭(DEBUG 로그아웃·빈 계정 hook).
- [ ] 커밋. `feature(android): 카테고리 첫 화면과 추가 시트`

### Task 13: Android 목록과 편집 시트

**Files:** Create `A/feature/category/CategoryListScreen.kt`, `CategoryCard.kt`, `CategorySkeleton.kt`
- [ ] 화면: sticky 머리(뒤로, 이름 + 개수, 보조 줄, custom만 ⋯ → `WLMenu` "편집"), `Masonry2Col` + 페이지 단위 skeleton(Task 10 planner), 카드(사진·D13 ✳·이름·`PriceText`(없으면 숨김)·`CardMetaLine`), 끝 근처에서 `loadMore`, 실패 줄 "더 불러오지 못했어요 · 다시 시도", 빈 custom·빈 공용, `gone` → 안내 + 닫기, 당겨서 새로고침, 탭 재누름 맨 위(`WLScrollToTopEffect`), 상세 왕복 위치 유지. 카드 → `ItemDetailRoute`(slide).
- [ ] 편집 시트: 같은 `CategoryFormSheet`의 EDIT 모드(범위 줄, 예시 칩 ×, "n / 5", 이름 변경 안내, `LOAD_FAILED`·`CONFLICT_UNRESOLVED` 다시 시도). 결과 `Saved` → `applyEdited` + 첫 화면 owner `refresh()`, `Gone` → 시트 닫고 목록 `gone`.
- [ ] 기기 확인: FCategoryList·ListCustom·ListCustomEmpty·EditSheet L/D, `seedLongCategory` 120개 이어 붙이기·skeleton·긴 사진·느린 사진, 상세 왕복, 편집 이름 반영·409(DEBUG FakeControls로 version 올림)·한도·중복.
- [ ] 커밋. `feature(android): 카테고리 목록과 편집 시트`

### Task 14: Android 선택 시트, DEBUG 진입, 데모 제거

**Files:** Create `A/feature/category/CategoryPickerSheet.kt`; Modify `AD/feature/demo/*`(카테고리 데모 두 화면 삭제, `DemoContent`의 목적 데모가 쓰는 부분은 유지), `AD/ui/VariantRoutes.kt`(DEBUG 설정 아래 "카테고리 선택 시트" 데모 줄 → 시트 열고 결과를 데모 줄에 표시)
- [ ] 시트: 레일 120dp + 칩(선택 먹색, 체크 없음), `+ 카테고리` → 같은 시트가 폼(뒤로 + 닫기, 안내, "내 세부 카테고리 n / 20"), 높이 변화 300 `emphasized`, 생성 → 선택·닫힘(D14).
- [ ] 기기 확인: FProductCategoryPicker·Create L/D.
- [ ] ANDROID_CHECK. 커밋. `feature(android): 카테고리 선택 시트와 데모 정리`

## 묶음 4: iOS 화면

### Task 15: iOS 공용 부품 — RemoteImage, code point 입력, 시트 guard, 사진 배치

**Files:** Modify `I/Platform/RemoteImage.swift`, `I/DesignSystem/WLInput.swift`, `I/DesignSystem/Overlay/{OverlayHost,WLBottomSheet}.swift`; Create `I/Features/Category/PhotoPagePlanner.swift`
**Test:** `IT/RemoteImageLoaderCancellationTests.swift`, `IT/WLInputTests.swift`(새), `IT/OverlayHostStateTests.swift`·`IT/SheetDragDecisionTests.swift`, `IT/PhotoPagePlannerTests.swift`

- [ ] **RemoteImage:** `image(for:width:scale:maxPixels:) async -> (UIImage, CGSize)?`(원본 픽셀 크기는 EXIF 회전 적용). 진행 중 작업마다 기다리는 수, `withTaskCancellationHandler`로 감소, 0이면 내부 `Task.cancel()`. 로더 전체 비동기 semaphore(6): 다운로드·디코드 동안 허가 보유, 대기 중 취소는 대기열에서 제거. stub `URLProtocol` 테스트: 모두 취소 → 내부 요청 취소, 하나만 취소 → 계속, 동시 실행 ≤ 6, 대기 중 취소 → 시작 안 함, 취소 뒤 같은 key 재요청 → 새 작업.
- [ ] **WLInput:** `limitUnit: InputLimitUnit`(기본 grapheme). marked text 동안 자르지 않음(기존 `WLInputComposition` 유지), 확정 뒤 code point 상한 안 마지막 grapheme 경계에서 자름.
- [ ] **시트 guard·planner:** Android Task 10과 같은 규칙·테스트.
- [ ] IOS_TEST. 커밋. `feature(ios): 이미지 요청 취소·동시 제한, code point 입력, 시트 guard`

### Task 16: iOS route·owner·첫 화면·추가 시트

**Files:** Modify `I/AppRoutes.swift`(`AppDestination.categoryList(hint)`, 계정 범위·탭 바 유지 slide), `I/Navigation/WLEntryOwners.swift`, `I/ContentView.swift`(카테고리 탭 루트 → `CategoryHomeScreen`, release `PlainTabRoot`는 목적 탭만); Create `I/Features/Category/{CategoryHomeScreen,CategoryRail,CategoryPager,CategoryFormSheet,CategoryText,CategoryOwners}.swift`, 문자열
- [ ] Android Task 11·12와 같은 동작. 세로 페이저는 `ScrollView` + `scrollTargetBehavior(.paging)` 대신 페이지 안 스크롤을 먼저 소비해야 하므로 직접 만든 페이저(드래그 진행값 + 300 `emphasized` 스냅)로 한다. 테스트: `CategoryTextTests`(키 ko·en), route·owner close, `WLNavigatorTests` 계정 범위.
- [ ] 시뮬레이터 확인(FCategoryHome·AddSheet L/D). 커밋. `feature(ios): 카테고리 첫 화면과 추가 시트`

### Task 17: iOS 목록과 편집 시트

- [ ] Android Task 13과 같은 범위. 카드 사진은 `RemoteImage.image(for:width:scale:maxPixels:)`와 planner. 시뮬레이터 확인(목록 넷 L/D, 120개 fixture). 커밋. `feature(ios): 카테고리 목록과 편집 시트`

### Task 18: iOS 선택 시트, DEBUG 진입, 데모 제거

- [ ] Android Task 14와 같은 범위(`I/Features/Demo/DemoCategoryScreen.swift` 삭제, `DemoContent`의 목적 데모 부분 유지, DEBUG 설정에 picker 데모). IOS_TEST·iOS Release simulator build. 커밋. `feature(ios): 카테고리 선택 시트와 데모 정리`

### Task 19: PR A 전체 검증·문서·리뷰·draft PR

- [ ] KMP_TEST·ANDROID_CHECK·IOS_TEST·`linkReleaseFrameworkIosArm64`·iOS Release simulator build·`gen_tokens --check`·`test_gen_tokens.py`. 건수를 기록한다.
- [ ] 예외 경로 수동 확인(두 플랫폼): 비행기 모드에서 첫 화면·목록·다음 페이지·생성(응답 유실 흉내: DEBUG `delayCategoryCreate` + 연결 끊기 → 같은 입력 재시도 → 하나만 생김)·편집 409·계정 떠날 때 목록·시트 닫힘, 입력·IME(한글 상한 직전 조합, emoji 붙여넣기).
- [ ] 성능 기록: [c3-performance-checks.md](../../architecture/client/c3-performance-checks.md)에 120개 목록 스크롤·최대 메모리(긴 사진 포함), 두 플랫폼.
- [ ] 문서: `kmp.md`·`android.md`·`ios.md` C5 절과 한계 표(NFC 근사, refresh가 이어 붙인 페이지 버림, foreground 목록 갱신 없음, skeleton 3초, 같은 version 역전, 응답 유실 뒤 다른 입력 재제출 중복, 상한 근처 emoji, Android 모듈 분리 안 함, iOS 로더 유지), multiplatform-settings 예정 문구 정리, `server-integration-status.md`(CAT-01~04·ITEM-02 Fake/Remote/MockEngine), 로드맵 C5 행, `docs/design/decisions.md`(D5~D15), `docs/history/architecture/client/c5-pr-a-implementation-2026-10-14.md`·화면 확인 기록(스크린샷 `c5-shots/`), learning Q&A(멱등 key와 payload snapshot, code point와 grapheme), 관련 INDEX, 이 plan 상태 줄.
- [ ] push 전 독립 리뷰(`/code-review`) → 반영 → push → draft PR(base `develop`, 로컬 검증 결과·스크린샷, 리뷰 결과는 PR 코멘트로 올리지 않음). 커밋은 플랫폼별 `bugfix:`/`docs:`.

---

# PR B — 사진 공유 요소 전환 (묶음 5)

브랜치 `client/c5-photo-transition`(A 위). base A로 draft PR, A merge 뒤 develop rebase(D4).

### Task 20: 로더 캐시 계약과 PhotoHandoff

**Files:** Create `A/navigation/PhotoHandoff.kt`, `I/Navigation/PhotoHandoff.swift`; Modify `I/Platform/RemoteImage.swift`(주소별 색인 `bestCached(for:)`, NSCache 축출 delegate로 색인 정리), Android 상세 사진 요청(`placeholderMemoryCacheKey`)
**Test:** `AT/navigation/PhotoHandoffTest.kt`, `IT/PhotoHandoffTests.swift`, `IT/RemoteImageIndexTests.swift`
- [ ] TDD: handoff 넣기(entry id → 이미지)·상세 사진이 그려지고 모션이 끝나면 놓기·전환 취소 때 놓기·entry 제거 때 놓기·역방향 칸(pop 시작 때 상세 이미지), `bestCached` 큰 것 우선·축출 정리, 카드 key 전달. DEBUG hook `clearImageMemory`.
- [ ] 커밋(둘). `feature(android): 전환 사진 handoff`, `feature(ios): 전환 사진 handoff와 주소별 캐시 색인`

### Task 21: Android 카드 → 상세 공유 요소

- [ ] 카드 tap → `ItemDetailRoute`를 `WLPushStyle.Photo`로(목록 진입만, 홈 "분류 중"은 slide 유지), `SharedTransitionLayout` sharedElement key(`SharedTransitionKeys`), 상세 사진 자리 공급 순서 handoff → cache 자리표시 → placeholder, pop 때 카드 칸이 역방향 handoff(상세 이미지)를 먼저 그림, 끌어서 뒤로(`PredictiveBackHandler`) 진행·취소. 420/360ms 곡선.
- [ ] 기기 확인(ANDROID_DEVICE): 메모리 cache 비운 상태에서 push·pop·끌기 취소 첫 프레임, 사진 없는 카드, 로더 호출 수·네트워크 다운로드 수.
- [ ] 커밋. `feature(android): 카드 사진 공유 요소 전환`

### Task 22: iOS 카드 → 상세 공유 요소

- [ ] `WLSharedPhotoTarget`이 지금 그리는 사진을 target key로 registry에 등록, `WLPhotoFlight` 공급원을 방향별 순수 함수로(push = source handoff, pop·끌기 = target, 없으면 다른 쪽, 그다음 placeholder, 끌기 취소 중 target 유지). 테스트: 방향별 선택 표, target 등록·해제.
- [ ] 시뮬레이터 확인(같은 항목). 커밋. `feature(ios): 카드 사진 공유 요소 전환`

### Task 23: PR B 검증·문서·draft PR

- [ ] ANDROID_CHECK·IOS_TEST·Release build, 성능 기록(상세 깊이 push/pop 20회 메모리), 문서(`android.md`·`ios.md` 전환 절, `docs/history/architecture/client/c5-pr-b-implementation-*.md`, 디자인 결정 반영 여부, INDEX), 독립 리뷰, push, draft PR(base `client/c5-category`).

## Self-Review 기록

- spec D1~D15와 검토 3회 항목을 task에 대응시켰다: SELECT 공급(Task 8), 사진 캐시·메모리·취소(Task 10·15·20), 제출·편집 복구(Task 8), code point 입력(Task 10·15), 확인 시각 null(Task 2), 필드 오류(Task 1·8), 머리 갱신(Task 7), 방향별 전환 사진(Task 21·22), 120개 fixture(Task 3).
- spec에 없던 발견: 시트 overlay에 닫기 guard가 없어(막 누르기·끌기·뒤로가 바로 닫음) Task 10·15에 넣었다. D15로 C4 상세 가격 안내 문구도 바뀐다(Task 2).
- 실행 방식은 사용자 확인 대상이다(기본: subagent-driven, 묶음 3·4 병렬).
