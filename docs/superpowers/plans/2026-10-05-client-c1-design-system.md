# C1 디자인 시스템과 앱 뼈대 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 두 앱에 핸드오프 토큰·서체·공통 컴포넌트·탭 셸·시트/확인창/화면 이동 모션을 갖춰, C3 이후 화면이 이 부품만으로 만들어지게 한다.

**Architecture:** 토큰은 `client/tools/`의 생성기가 `tokens.json`과 디자인 결정 값을 두 플랫폼 상수 파일로 만든다. 공통 컴포넌트와 overlay(시트·확인창·메뉴)·라우터·탭 셸은 각 플랫폼 native로 직접 구현하며 시스템 시트·탭·push 전환은 쓰지 않는다. iOS 라우터는 Task 5의 spike 결과로 방식을 확정한다.

**Tech Stack:** Python 3(생성기·unittest), Kotlin 2.3.21 / Compose BOM 2026.02.01 / AGP 9.0.0, SwiftUI(iOS 17+), Xcode 26.6.

**Spec:** [KMP 클라이언트 MVP 구현 로드맵 설계](../specs/2026-10-05-client-implementation-roadmap-design.md) — `디자인 시스템과 모션 기반` 절과 C1 행.

## Global Constraints

- 브랜치는 `client/c1-design-system`이고 `client/initial-setup`(d78d1c1 이후)에서 만든다. `client/initial-setup`이 main에 병합되기 전까지 기준이 main이 아니다.
- 값의 원본: 색·모서리·간격·서체는 [디자인 결정](../../design/decisions.md), 모션은 [`tokens.json`](../../../design/handoff/interactions/tokens.json)·[`motion.md`](../../../design/handoff/interactions/motion.md). 생성된 상수 파일을 손으로 고치지 않는다.
- 라이트: 바탕 #F8F8F8, 카드 #FFFFFF, 시트·확인창 #FFFFFF, 칩 #FFFFFF, 아이콘 타일 #F0F0F0, 사진 자리 #EEEEEE, 선 #E2E2E2, 글자 #1D1D1D, 보조 글자 #5E5E5E, 탭 바 #1D1D1D, 밑줄 #BDBDBD.
- 다크: 바탕 #1D1D1D, 카드 #312F30, 시트·확인창 #2A2A2A, 아이콘 타일 #1D1D1D(시트 위 입력·묶음 면 #3A3939), 칩·사진 자리 #3A3939, 선 #3A3939, 글자 #F4F3F0, 보조 글자 #A9A7A2, 탭 바 #312F30, 밑줄 #5A5958.
- 목적 6색 면(두 테마 같음): coral #F96857, mustard #F9CD61, periwinkle #7477FF, cyan #BBF3FE, mint #8ED8B0, pink #F4A6C6, 면 위 글자 #1D1D1D. 라이트 표시용 테두리: #F84D39, #B38107, #7477FF, #0398B5, #369C65, #EA5492.
- 상태 타일(면/아이콘) 라이트: 완료 #DCF1E4/#1F7A4D, 대기 #FBEFCB/#8A6200, 오프라인·외부 앱 #E2E5FA/#3E48B8, 실패 #FBE1DB/#B03A21, 분석 중 #DDF0F5/#1C6E82. 다크: #1E3A2B/#7DD3A5, #3D3216/#F2C95A, #262B4D/#A3ABF5, #432420/#F59A86, #1C3740/#86D3E6. 위험 버튼 #C62828 + 흰 글자.
- 모서리 xs 10 · s 14 · m 20 · l 28 · xl 36 · pill. 간격 4·8·12·16·20·24·32·40, 화면 좌우 여백 20, 터치 영역 최소 44.
- 서체는 Do Hyeon과 IBM Plex Sans KR 400·500·700만 쓴다(OFL). 숫자·가격은 Plex 700 tabular.
- scrim: 블러 12 + 어두운 막 라이트 0.24·다크 0.45, 열 때 400 ease-out, 닫을 때 260 ease-in. Android API 31 미만은 블러 없이 막만.
- 시스템 `.sheet`·`ModalBottomSheet`·`TabView`·`NavigationStack` 기본 push·Navigation 기본 좌우 전환을 쓰지 않는다.
- shared에 UI 의존성을 추가하지 않는다. 이번 단계에서 KMP 코드는 바꾸지 않는다.
- 커밋: 코드는 `feature(android): 한글 설명` 또는 `feature(ios): 한글 설명`, 기록만 바꾸면 `docs: 한글 설명`.

## Review Focus

- **다크 모드 전환 중 토큰:** 앱 실행 중 시스템 테마를 바꾸면 모든 면·글자·scrim 농도가 즉시 바뀌어야 한다. 하드코딩한 색이 남으면 한쪽 테마에서만 깨진다.
- **시트가 열린 상태에서 시스템 뒤로·막 누르기 연타:** 닫기 모션 중 다시 열기·닫기를 받으면 시트가 화면 중간에 멈추거나 scrim만 남는다. 전환 중 입력 차단(motion.md 구현 기본값)을 테스트한다.
- **공유 요소 전환 중 탭 전환·뒤로:** push 전환 420ms 동안 탭을 누르거나 뒤로 가면 자리 표시 면이 남는다. 전환 중에는 탭 바와 뒤로를 받지 않는다.
- **긴 한글·큰 글자 크기:** 시스템 글자 크기를 키우면 도현 28 제목·버튼 쌍이 넘친다. 데모 화면에 가장 긴 목적 이름 예시와 접근성 최대 크기를 넣어 확인한다.
- **Android API 26~30:** blur 미지원, predictive back 미지원 기기에서 일반 뒤로 버튼이 같은 뒤로 전환을 수행해야 한다.

---

### Task 1: 토큰 생성기

**Files:**
- Create: `client/tools/design-tokens.json` — Global Constraints의 색·모서리·간격·타일·목적 key를 담는 원본(디자인 결정 값 사본, 행마다 결정 날짜 주석 필드 `source`)
- Create: `client/tools/gen_tokens.py`
- Create: `client/tools/test_gen_tokens.py`
- Create (생성물): `client/android/src/main/kotlin/app/wishlist/android/designsystem/WishlistTokens.kt`, `client/ios/Wishlist/DesignSystem/WishlistTokens.swift`
- Modify: `client/README.md`(생성 명령 한 줄)

**Interfaces:**
- Produces(Kotlin): `object WishlistTokens { object Light; object Dark; object Purpose; object Status; object Radius; object Space; object Curve; object Motion }` — 색은 `androidx.compose.ui.graphics.Color`, 길이는 `Dp`, 곡선은 `CubicBezierEasing`, 시간은 `Int`(ms). 테마 색 묶음은 `data class WLColors(background, card, sheet, chip, iconTile, sheetField, photo, line, text, textSecondary, tabBar, underline, scrimDim)`의 `LightColors`/`DarkColors` 값.
- Produces(Swift): 같은 이름 구조의 `enum WishlistTokens`; 색은 `Color`, 길이는 `CGFloat`, 곡선은 `WLCurve(x1:y1:x2:y2:)` + `func animation(ms:) -> Animation`(`Animation.timingCurve`), `struct WLColors`와 `static let light/dark`.
- Produces: 목적 key `coral, mustard, periwinkle, cyan, mint, pink` 순서 고정(서버 B3 계약 전 초안).

- [ ] **Step 1: 실패하는 테스트 작성** — `test_gen_tokens.py`
  - `test_curves_match_motion_tokens`: 생성 Kotlin에 `CubicBezierEasing(0.2f, 1.25f, 0.4f, 1f)`(spring-sheet), Swift에 `WLCurve(x1: 0.2, y1: 1.25, x2: 0.4, y2: 1)`이 있다.
  - `test_light_dark_backgrounds`: Kotlin에 `Color(0xFFF8F8F8)`·`Color(0xFF1D1D1D)`, Swift에 `0xF8F8F8`·`0x1D1D1D` 정의가 있다.
  - `test_purpose_keys_order`: 두 출력의 목적 key 순서가 위 6개와 같다.
  - `test_generation_is_deterministic`: 두 번 생성한 결과가 바이트 단위로 같다.
  - `test_check_mode_detects_stale`: 출력 파일을 한 글자 바꾸면 `--check`가 종료 코드 1.
- [ ] **Step 2: 실패 확인** — Run: `python3 -m unittest client/tools/test_gen_tokens.py` · Expected: FAIL(모듈 없음)
- [ ] **Step 3: `gen_tokens.py` 구현** — `main(argv)`: 인자 없으면 두 파일을 쓰고, `--check`면 생성 결과와 기존 파일을 비교해 다르면 1을 반환. 입력은 `design/handoff/interactions/tokens.json`과 `client/tools/design-tokens.json`. 출력 맨 위에 `// GENERATED by client/tools/gen_tokens.py — do not edit`.
- [ ] **Step 4: 통과 확인** — 같은 명령 · Expected: OK
- [ ] **Step 5: 커밋** — `feature(android): 디자인 토큰 생성기와 플랫폼 상수 추가`(iOS 파일 포함)

### Task 2: 서체와 텍스트 스타일

**Files:**
- Create: `client/android/src/main/res/font/do_hyeon.ttf`, `ibm_plex_sans_kr_regular.ttf`, `ibm_plex_sans_kr_medium.ttf`, `ibm_plex_sans_kr_bold.ttf`
- Create: `client/ios/Wishlist/Resources/Fonts/` 같은 4개 파일, `client/ios/Wishlist/Resources/Fonts/OFL.txt`, `client/android/src/main/assets/licenses/OFL.txt`
- Create: `client/android/src/main/kotlin/app/wishlist/android/designsystem/WLTypography.kt`, `client/ios/Wishlist/DesignSystem/WLTypography.swift`
- Modify: `client/ios/Wishlist.xcodeproj/project.pbxproj`(리소스 추가, `UIAppFonts`를 담을 `Wishlist/Info.plist`를 `INFOPLIST_FILE`로 지정하고 기존 `INFOPLIST_KEY_*` 생성은 유지)

**Interfaces:**
- Produces: 두 플랫폼 공통 스타일 이름 `display28`(도현 28, 행간 1줄 1.0·2줄 1.2), `display20`, `title`(Plex 700), `body`(Plex 400), `bodyStrong`(Plex 500), `label`(Plex 500, 자간 0), `price`(Plex 700 tabular), `button`(Plex 700). 각 크기는 보드 HTML의 해당 요소 px 값을 그대로 옮기고, 스타일마다 출처 보드를 주석으로 단다.
- Produces: 그 자리 편집 밑줄 보정 — 한글 아래 끝 ↔ 밑줄 3px. 도현 28은 줄 높이 24(디자인 결정 2026-10-04).
- Kotlin: `object WLType { val display28: TextStyle … }`, Swift: `extension Font { static let wlDisplay28 … }` + 행간·tabular를 적용하는 `View.wlText(_ style: WLTextStyle)`.

- [ ] **Step 1:** Google Fonts 저장소(`github.com/google/fonts`의 `ofl/dohyeon`, `ofl/ibmplexsanskr`)에서 TTF와 OFL을 받아 위 경로에 둔다. 파일명은 소문자·밑줄(Android 리소스 규칙).
- [ ] **Step 2:** 두 플랫폼 스타일 정의. 숫자 tabular는 Android `fontFeatureSettings = "tnum"`, iOS `.monospacedDigit()`.
- [ ] **Step 3: 검증** — `./gradlew :android:assembleDebug :android:lintDebug`와 iOS simulator build가 성공한다. 데모(Task 4·6)에서 "KRW 1,190,000"이 고정폭으로, 도현 제목이 보드와 같은 폭으로 그려지는지 스크린샷으로 확인한다. iOS에서 `UIFont.familyNames`에 `Do Hyeon`, `IBM Plex Sans KR`가 포함되는지 디버그 로그로 1회 확인한다.
- [ ] **Step 4: 커밋** — `feature(android): 앱 서체와 텍스트 스타일 추가`, `feature(ios): 앱 서체와 텍스트 스타일 추가`

### Task 3: Android 공통 컴포넌트와 overlay

**Files:**
- Create: `client/android/src/main/kotlin/app/wishlist/android/designsystem/` — `WLTheme.kt`(기존 `ui/WishlistTheme.kt` 대체, `LocalWLColors`), `WLButton.kt`, `WLCard.kt`, `WLIconTile.kt`, `WLChip.kt`, `WLInput.kt`, `WLUnderlineField.kt`, `PriceText.kt`, `PurposeDot.kt`, `EmptyState.kt`, `ExpandableGroup.kt`, `Masonry2Col.kt`
- Create: `…/designsystem/overlay/` — `OverlayHost.kt`, `Scrim.kt`, `WLBottomSheet.kt`, `WLConfirmDialog.kt`, `WLMenu.kt`
- Delete: `client/android/src/main/kotlin/app/wishlist/android/ui/WishlistTheme.kt`(내용은 `WLTheme.kt`로 이동)
- Test: `client/android/src/test/kotlin/app/wishlist/android/designsystem/PriceFormatTest.kt`, `overlay/SheetDragDecisionTest.kt`

**Interfaces:**
- `@Composable fun WLTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)`; `val LocalWLColors: ProvidableCompositionLocal<WLColors>`.
- `enum class WLButtonKind { Primary, Secondary, Danger }`; `@Composable fun WLButton(text: String, kind: WLButtonKind, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true)`; `@Composable fun WLButtonPair(cancelText, primaryText, primaryKind, onCancel, onPrimary)` — 왼쪽 취소, 오른쪽 주 동작이 더 넓음.
- `fun formatPrice(amount: BigDecimal, currency: String): String` → `"KRW 549,000"`, `"USD 299"`, `"USD 19.99"` (C2에서 KMP `domain`으로 옮긴다. 이번에는 컴포넌트 미리보기용).
- `class OverlayHostState { fun showSheet(content: @Composable () -> Unit); fun showDialog(spec: WLDialogSpec); fun dismiss(); val isAnimating: Boolean }`, `@Composable fun OverlayHost(state: OverlayHostState, content: @Composable () -> Unit)` — 화면 루트에 한 번 둔다. scrim은 뒤 콘텐츠에만 `Modifier.blur(12.dp)`(API 31+) + 막.
- `data class WLDialogSpec(val title: String, val bullets: List<String>, val cancelText: String, val confirmText: String, val confirmKind: WLButtonKind, val onConfirm: () -> Unit)`
- `fun shouldDismissSheet(dragDistance: Float, sheetHeight: Float, velocity: Float): Boolean` — 25% 또는 1000/s.

- [ ] **Step 1: 실패하는 테스트** — `PriceFormatTest`: 위 세 예시와 `formatPrice(1190000, "KRW") == "KRW 1,190,000"`. `SheetDragDecisionTest`: (100, 400, 0) → true, (99, 400, 999) → false, (10, 400, 1000) → true.
- [ ] **Step 2: 실패 확인** — `./gradlew :android:testDebugUnitTest` · Expected: FAIL(미정의)
- [ ] **Step 3: 구현** — 시트 열기 `tween(480, easing = spring-sheet)` + 시트 면을 아래로 80 더 그림, 닫기 `tween(260, accelerate)`, scrim 400/260, 확인창 `opacity·scale(.96→1)` 200 fade-in / 150 ease-in, 메뉴 150 ease-out(버튼 쪽 모서리 기준 scale). 애니메이션 중에는 `isAnimating=true`로 입력을 막는다. 시스템 뒤로는 `BackHandler`로 가장 위 overlay를 닫는다.
- [ ] **Step 4: 통과 확인** — 같은 명령 · Expected: PASS, 그리고 `:android:lintDebug` 오류 0
- [ ] **Step 5: 커밋** — `feature(android): 공통 컴포넌트와 시트·확인창 overlay 구현`

### Task 4: Android 탭 셸·라우터·화면 이동 데모

**Files:**
- Create: `client/android/src/main/kotlin/app/wishlist/android/navigation/` — `WLRoute.kt`, `WLNavigator.kt`, `WLNavHost.kt`, `WLTabBar.kt`, `SharedTransitionKeys.kt`
- Create: `client/android/src/main/kotlin/app/wishlist/android/feature/demo/` — `DemoHomeScreen.kt`, `DemoCategoryScreen.kt`, `DemoPurposeScreen.kt`, `DemoDetailScreen.kt`(debug 빌드에서만 진입), `DemoContent.kt`(보드의 긴 목적 이름·가격 예시)
- Modify: `client/android/src/main/kotlin/app/wishlist/android/ui/WishlistApp.kt`(→ `WLTheme { OverlayHost { WLNavHost() } }`), `MainActivity.kt`
- Test: `client/android/src/test/kotlin/app/wishlist/android/navigation/WLNavigatorTest.kt`

**Interfaces:**
- `enum class WLTab { Home, Category, Purpose }`; `sealed interface WLRoute { val showsTabBar: Boolean }`(C1에서는 `TabRoot(tab)`, `DemoDetail(id: String, hasPhoto: Boolean)`만).
- `class WLNavigator { val currentTab: StateFlow<WLTab>; fun stack(tab: WLTab): List<WLRoute>; fun selectTab(tab: WLTab); fun push(route: WLRoute, sourceKey: String); fun pop(): Boolean; val isTransitioning: Boolean }` — 탭마다 독립 스택. 현재 탭을 다시 선택하면 `scrollToTopRequests: SharedFlow<WLTab>`에 내보낸다.
- 화면 이동은 `SharedTransitionLayout` + `AnimatedContent`로 직접 구현한다. 사진 있음은 `sharedElement`(420 emphasized, 뒤로 360), 사진 없음은 누른 면 `sharedBounds` + lift(80, 그림자 0 8 24 14%) → 확대 420, 다음 내용 190ms 지연 230 ease-out. 뒤로 가기 제스처는 `PredictiveBackHandler` 진행값으로 같은 전환을 되감고 50% 기준으로 확정. Navigation Compose의 기본 전환은 쓰지 않는다.
- 탭 전환: 이전 90 ease-in 페이드 → 새 탭 210 fade-in + scale .97→1, 알약 250 standard, 글자색은 125에 바꿈. 탭 상태는 `rememberSaveableStateHolder`.

- [ ] **Step 1: 실패하는 테스트** — `WLNavigatorTest`: 탭별 스택 독립(Home에 push 후 Category 전환·복귀 시 Home 스택 유지), 루트에서 `pop()==false`, 같은 탭 재선택 시 scroll-to-top 이벤트, `isTransitioning` 동안 `push`·`selectTab` 무시.
- [ ] **Step 2: 실패 확인** — `./gradlew :android:testDebugUnitTest --tests '*WLNavigatorTest'` · Expected: FAIL
- [ ] **Step 3: 구현** — 위 Interfaces 대로. 데모 홈에는 시트·확인창·메뉴 열기 버튼, 사진 카드·사진 없는 칩 push, 가장 긴 목적 이름 카드를 둔다.
- [ ] **Step 4: 통과·실행 확인** — 테스트 PASS, `./gradlew :android:installDebug` 후 API 36 에뮬레이터에서 라이트·다크, 글자 크기 최대, Review Focus 1~3·5를 수동 확인한다. API 26~30 에뮬레이터 이미지가 있으면 blur 없는 막과 뒤로 버튼 전환을 확인하고, 없으면 미검증으로 기록한다.
- [ ] **Step 5: 화면 비교** — 데모 시트·push 모션을 화면 녹화해 `MotionSheetB`, `MotionPushB`, `MotionPushPlaceholder`, `MotionTabC` 보드(`design/handoff/interactions/reference/boards/`)와 나란히 비교하고 차이를 PR 설명에 적는다.
- [ ] **Step 6: 커밋** — `feature(android): 탭 셸과 공유 요소 화면 이동 구현`

### Task 5: iOS 라우터 spike (진행/전환 판단 관문)

**Files:**
- Create: `client/ios/Wishlist/Navigation/Spike/RouterSpikeView.swift`(Task 7에서 실제 라우터로 대체 후 삭제)
- Create: `docs/history/architecture/client/ios-router-spike-2026-10-05.md`

**Interfaces:**
- 확인할 질문 하나: "`NavigationStack` 없이 `[WLRoute]` 상태 + ZStack 오버레이 + `matchedGeometryEffect`로 (a) 사진·면 공유 요소 push/pop 420/360, (b) 왼쪽 가장자리 끌어 뒤로(진행 따라 축소·50% 확정), (c) VoiceOver 포커스 이동과 '뒤로' 동작(`accessibilityAction(.escape)`), (d) 탭별 스택 유지를 iOS 17 simulator에서 만족하는가?"

- [ ] **Step 1:** 사진 카드 1개·칩 1개·상세 1개만 있는 spike 화면으로 (a)~(d)를 구현한다.
- [ ] **Step 2:** iOS 17.x와 26.x simulator에서 각 항목을 확인한다(VoiceOver는 Accessibility Inspector 또는 실제 VoiceOver).
- [ ] **Step 3:** 결과를 spike 기록에 적는다. 하나라도 실패하면 대안 `NavigationStack` + `.navigationTransition`(iOS 18+) / iOS 17은 페이드 대체를 기록하고, 디자인 결정과의 차이를 `docs/design/decisions.md`에 추가할지 사용자에게 묻는다. **사용자 확인 전 Task 7을 시작하지 않는다.**
- [ ] **Step 4: 커밋** — `docs: iOS 라우터 spike 결과 기록`

### Task 6: iOS 공통 컴포넌트와 overlay

**Files:**
- Create: `client/ios/Wishlist/DesignSystem/` — `WLTheme.swift`(`@Environment(\.colorScheme)`로 `WLColors` 선택, `EnvironmentValues.wlColors`), `WLButton.swift`, `WLCard.swift`, `WLIconTile.swift`, `WLChip.swift`, `WLInput.swift`, `WLUnderlineField.swift`, `PriceText.swift`, `PurposeDot.swift`, `EmptyState.swift`, `ExpandableGroup.swift`, `Masonry2Col.swift`
- Create: `client/ios/Wishlist/DesignSystem/Overlay/` — `OverlayHost.swift`, `Scrim.swift`, `WLBottomSheet.swift`, `WLConfirmDialog.swift`, `WLMenu.swift`
- Create: `client/ios/WishlistTests/` 타깃과 `PriceFormatTests.swift`, `SheetDragDecisionTests.swift`
- Modify: `client/ios/Wishlist.xcodeproj/project.pbxproj`(파일·테스트 타깃·scheme test action)

**Interfaces:**
- Android Task 3과 같은 이름·의미: `enum WLButtonKind { primary, secondary, danger }`, `WLButtonPair`, `func formatPrice(_ amount: Decimal, currency: String) -> String`, `@Observable final class OverlayHostState { func showSheet<V: View>(@ViewBuilder _ content: () -> V); func showDialog(_ spec: WLDialogSpec); func dismiss(); var isAnimating: Bool }`, `struct WLDialogSpec`, `func shouldDismissSheet(dragDistance: CGFloat, sheetHeight: CGFloat, velocity: CGFloat) -> Bool`.
- scrim은 뒤 콘텐츠에 `.blur(radius: 12)` + 막. 시트 곡선은 `WishlistTokens.Curve.springSheet.animation(ms: 480)`.

- [ ] **Step 1: 실패하는 테스트** — Android Task 3 Step 1과 같은 입력·기대값.
- [ ] **Step 2: 실패 확인** — `xcodebuild test -project client/ios/Wishlist.xcodeproj -scheme Wishlist -destination 'platform=iOS Simulator,name=iPhone 17 Pro' CODE_SIGNING_ALLOWED=NO`(`DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`) · Expected: FAIL
- [ ] **Step 3: 구현** — 모션 값은 Task 3과 같다.
- [ ] **Step 4: 통과 확인** — 같은 명령 · Expected: TEST SUCCEEDED
- [ ] **Step 5: 커밋** — `feature(ios): 공통 컴포넌트와 시트·확인창 overlay 구현`

### Task 7: iOS 탭 셸·라우터·화면 이동 데모

**Files:**
- Create: `client/ios/Wishlist/Navigation/` — `WLRoute.swift`, `WLNavigator.swift`, `WLNavHost.swift`, `WLTabBar.swift`
- Create: `client/ios/Wishlist/Features/Demo/` — Android Task 4와 같은 데모 4화면 + `DemoContent.swift`
- Modify: `client/ios/Wishlist/WishlistApp.swift`, `ContentView.swift`(→ `WLTheme { OverlayHost { WLNavHost() } }`)
- Delete: `client/ios/Wishlist/Navigation/Spike/`
- Test: `client/ios/WishlistTests/WLNavigatorTests.swift`

**Interfaces:**
- Android `WLNavigator`와 같은 의미: `enum WLTab`, `enum WLRoute`, `@Observable final class WLNavigator { var currentTab; func stack(_:) -> [WLRoute]; func selectTab(_:); func push(_:sourceKey:); func pop() -> Bool; var isTransitioning: Bool; var scrollToTopRequest: WLTab? }`.
- 방식은 Task 5 결과를 따른다. 탭 전환은 세 탭을 ZStack에 모두 두고 `opacity`·`scaleEffect`로 바꾼다.

- [ ] **Step 1: 실패하는 테스트** — Android `WLNavigatorTest`와 같은 4개 사례.
- [ ] **Step 2: 실패 확인** — Task 6 Step 2 명령 · Expected: FAIL
- [ ] **Step 3: 구현**
- [ ] **Step 4: 통과·실행 확인** — 테스트 통과, iPhone 17 Pro(iOS 26.5)와 iOS 17 simulator에서 Android Task 4 Step 4와 같은 항목 확인.
- [ ] **Step 5: 화면 비교** — Android Task 4 Step 5와 같은 비교.
- [ ] **Step 6: 커밋** — `feature(ios): 탭 셸과 공유 요소 화면 이동 구현`

### Task 8: 문서와 PR

**Files:**
- Create: `docs/architecture/client/design-system.md`(토큰 생성 흐름, 컴포넌트 목록, overlay·라우터 구조, 플랫폼 차이: API 31 미만 blur, iOS 라우터 방식)
- Modify: `docs/architecture/client/INDEX.md`, `client/README.md`(토큰 생성 명령, 데모 진입), 로드맵 spec의 C1 행에 완료 표시

- [ ] **Step 1:** 문서를 작성하고 `docs/history`에 의미 있는 결정(라우터 방식, 토큰 생성기)만 남긴다.
- [ ] **Step 2: 전체 검증** — `python3 -m unittest client/tools/test_gen_tokens.py && python3 client/tools/gen_tokens.py --check`, `cd client && ./gradlew :android:testDebugUnitTest :android:assembleDebug :android:lintDebug :shared:allTests`, iOS `xcodebuild test`. Expected: 모두 성공.
- [ ] **Step 3:** `git diff --check` 후 커밋 `docs: 디자인 시스템과 앱 뼈대 구조 기록`, `client/c1-design-system` 푸시와 PR 생성(모션 비교 녹화·스크린샷 첨부).
