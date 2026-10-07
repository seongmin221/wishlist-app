# 디자인 시스템과 앱 뼈대 (C1)

> 상태: **확정** · 2026-10-06 · Android(Compose)와 iOS(SwiftUI)가 같은 이름·같은 규칙으로 구현한다.

디자인 값의 원본은 [디자인 결정](../../design/decisions.md), [모션 명세](../../../design/handoff/interactions/motion.md), 완성 화면 보드다. 이 문서는 그 값을 코드로 옮긴 구조를 설명한다. 플랫폼별 세부는 [android.md](android.md)·[ios.md](ios.md)의 "탭 셸과 화면 이동 (C1)" 절에 있다.

## 위치

| 영역 | Android (`client/android/src/main/kotlin/app/wishlist/android/`) | iOS (`client/ios/Wishlist/`) |
| --- | --- | --- |
| 토큰·서체·컴포넌트 | `designsystem/` | `DesignSystem/` |
| overlay(시트·확인창·메뉴) | `designsystem/overlay/` | `DesignSystem/Overlay/` |
| 탭 셸·라우터 | `navigation/` | `Navigation/` |
| 데모 화면 | `src/debug/kotlin/…/feature/demo/` | `Features/Demo/` (`#if DEBUG`) |
| 단위 테스트 | `src/test/kotlin/…` | `WishlistTests/` |

## 토큰 생성

```text
design/handoff/interactions/tokens.json  (모션: 시간·곡선·비율)  ┐
client/tools/design-tokens.json          (색·모서리·간격·목적 색) ┘→ client/tools/gen_tokens.py
    → android/…/designsystem/WishlistTokens.kt
    → ios/Wishlist/DesignSystem/WishlistTokens.swift
```

- `python3 client/tools/gen_tokens.py`로 생성하고 `--check`로 생성물이 원본과 같은지 검사한다(다르면 exit 1). 테스트는 `python3 -m unittest client/tools/test_gen_tokens.py`.
- 생성물은 커밋하고 손으로 고치지 않는다. 첫 줄에 `GENERATED … do not edit` 머리가 있다. 값이 더 필요하면 원본 JSON이나 생성기의 이름 표(`THEME_FIELDS`, `MOTION_MS`, `MOTION_RATIO`)에 더한다.
- `design-tokens.json`은 디자인 결정 값의 사본이다. 행마다 `source`에 결정 날짜를 적는다. 모션은 핸드오프 `tokens.json`을 그대로 읽는다.
- 이름은 두 플랫폼 공통 camelCase다. 예외: 곡선 `throw`는 두 언어의 예약어라 `throwAway`로 낸다. 모서리 `pill`은 999다(충분히 큰 값으로 완전한 알약 모양을 뜻함).
- 목적 색 key는 `coral, mustard, periwinkle, cyan, mint, pink` 순서로 고정한 초안이다. 서버 B3 계약에서 확정한다. 다크 테마의 목적·상태 테두리는 결정대로 라이트에만 있다.
- 생성기는 Python 표준 라이브러리만 쓴다(3.9 이상). 결정 근거는 [ADR-029](../../history/architecture/client/ADR-029-design-token-generator.md).
- 색 문자열은 `#RRGGBB`만 허용한다. 짧은 RGB·8자리 hex·형식 없는 값은 생성 단계에서 실패한다. `.github/workflows/client-checks.yml`이 토큰 테스트와 `--check`, Android/iOS 테스트·Debug/Release 빌드를 수행한다.

## 서체와 글자 스타일

- 글꼴: Do Hyeon(제목), IBM Plex Sans KR Regular·Medium·Bold(본문). 앱에 넣고 라이선스(OFL)를 함께 둔다. iOS는 PostScript 이름으로 부른다.
- 원본 TTF는 플랫폼당 9,419,184 bytes다. 임의의 사용자 입력과 오프라인 글자 측정을 보존하기 위해 현재 번들을 유지한다. 서브셋·다운로드 글꼴 검토 조건은 [C1 리뷰 기록](../../history/architecture/client/c1-review-2026-10-06.md)에 있다.
- 스타일(두 플랫폼 같은 이름): `display28`·`display20`(줄 높이 1.0), `display28TwoLine`·`display20TwoLine`(1.2), `display28Edit`(24px, 밑줄 편집), `title`(22/1.3), `body`(14/1.35), `bodyStrong`, `label`(12/1.35), `price`(18/1.0), `button`(16/1.25). `title`·`label`·`button`의 줄 높이는 보드에 없어 정한 값이다.
- **줄 칸 높이:** 한 줄 칸은 줄 높이, N줄 칸은 N × 줄 높이다(보드 CSS line-height와 같다). 부모가 자르면(clip) 안 된다.
  - Android: Compose `lineHeight`는 한 줄짜리 칸을 글꼴 고유 높이보다 줄이지 못한다. `Modifier.wlLineBox(style)`(Text 바로 앞, 가장 안쪽에 둔다)로 칸 높이를 줄 높이에 맞춘다.
  - iOS: `wlText(style)`은 위아래 padding(음수 가능, `@ScaledMetric`으로 Dynamic Type에 맞춤)으로 **한 줄** 상자만 맞춘다(`Text`·`TextField`). 여러 줄은 SwiftUI `Text`로 맞출 수 없다: 음수 `lineSpacing`은 0으로 잘려 Plex 여러 줄이 줄마다 약 2pt씩 커졌고(본문 3줄 61, 목표 56.7), iOS 26 `.lineHeight(.exact)`는 도현 글자를 줄 높이와 상관없이 아래로 붙이고 줄 상자 밖 잉크를 자른다(display28Edit·price). 그래서 두 플랫폼 `WLText`는 `maxLines`를 명시적으로 받으며 기본은 무제한(Android `Int.MAX_VALUE`, iOS `nil`)이다. iOS는 상위 environment의 줄 제한도 따르고, 최종 제한이 1이면 `Text` + `wlText`, 여러 줄이면 `WLMultilineText`(UILabel, `NSParagraphStyle` 최소 = 최대 줄 높이 + `baselineOffset` (줄 높이 − 글꼴 줄 높이)/2)로 그린다. 공개 API만 쓰고 두 OS에서 같은 경로다. 줄 수·말줄임·정렬은 SwiftUI environment를 따르고, VoiceOver에는 `accessibilityRepresentation`의 `Text` 하나로 보인다. UILabel은 자기 bounds 밖 잉크를 자르므로 그리는 면만 상자보다 넓힌다(`WLLabelBox`, 상자 크기 그대로). UIFont는 (PostScript 이름, 크기) 키의 `NSCache`(상한 128)로 재사용한다. 여러 줄 경로에는 SwiftUI 글자 기준선이 없어 `firstTextBaseline` 정렬 대신 같은 스타일끼리 `.top`으로 맞춘다(확인창 글머리표). `WLTypographyTests`가 한 줄·세 줄·줄바꿈 상자 높이, 1–3번째 줄 글자 위치, 접근성 요소를 검증한다. 이전 구현은 iOS 26.5·17.5에서 실행했으나 이번 수정은 로컬 Xcode 로딩 오류로 실행하지 못했다([리뷰 검증 기록](../../history/architecture/client/c1-review-2026-10-06.md)).
- **한글 아래 3px 밑줄:** 결정(2026-10-04)은 한글 글자 아래 끝과 밑줄 사이 3px다. 글꼴 파일을 분석한 한글 아래 깊이로 칸 아래 끝과 글자 사이 간격(`hangulBottomGapPx`·`hangulBottomGap`)을 계산해 밑줄 위치를 보정한다(`wlUnderline`, `wlUnderlined`, `WLUnderlineField`). 받침 없는 글자는 최대 약 3px 더 높이 떠 보인다(고정 밑줄의 한계).
- **글자 크기 배율:** Android 14부터 sp가 비선형으로 커진다(28sp는 1.3배에서 약 1.03배). 그래서 sp 값의 차이를 한 번에 px로 바꾸지 않고, 글자 크기·줄 높이를 각각 px로 바꾼 뒤 계산한다. 배경은 [QA-CLI-005](../../learning/client/q-and-a/QA-CLI-005-line-height-and-font-scale.md).
- **숫자 고정폭:** IBM Plex Sans KR에는 `tnum` 기능이 없지만 숫자 0–9의 폭이 모두 같아(600/1000em) 기본으로 고정폭이다. 가격은 `price` 스타일(또는 `PriceText`)로 그린다. Do Hyeon 숫자는 비례폭이라 가격에 쓰지 않는다.
- **가격:** `formatPrice`는 통화 코드를 trim·대문자로 정규화하며 알 수 없는 코드는 소수 2자리로 표시한다. ISO 4217 통화별 소수 자릿수(KRW·JPY 0, USD 2, KWD 3)를 플랫폼 통화 메타데이터에서 얻고 HALF_UP으로 반올림한다. iOS는 전체 ISO 통화별 자릿수를 정적 캐시에 보관해 가격마다 NumberFormatter를 만들지 않는다. C2 KMP domain에서 정규화·검증 책임을 통합한다. 정수 금액이면 소수는 생략한다(`USD 299`, `USD 19.99`). 큰 글자에서 한 줄을 넘으면 통화 코드와 금액 사이에서만 줄을 바꾼다(iOS `PriceText`는 `ViewThatFits`, Android는 공백 줄바꿈).

## 컴포넌트

두 플랫폼이 같은 이름을 쓴다. 색은 `WLTheme`이 라이트·다크 생성 색을 고르고(`LocalWLColors`·`EnvironmentValues.wlColors`), 시트 위인지(`wlOnSheet`)에 따라 입력·보조 버튼 면이 `sheetField`로 바뀐다.

| 이름 | 역할 |
| --- | --- |
| `WLTheme`, `WLText` | 테마 색 제공, 스타일·한 줄 칸 보정을 적용한 글자 |
| `WLButton`(`primary`·`secondary`·`danger`), `WLButtonPair` | 알약 버튼(최소 52), 취소 왼쪽·주 버튼 오른쪽 1 : 1.4 |
| `WLCard`, `WLIconTile`, `WLChip`, `WLAddChip` | 카드 면, 아이콘 타일, 칩, 점선 추가 칩 |
| `WLInput`, `WLUnderlineField` | 라벨·안내가 있는 입력칸, 밑줄 편집 칸 |
| `PriceText`(`formatPrice`), `PurposeDot`(`WLPurposeColor`) | 가격, 목적 색 점 |
| `EmptyState`, `ExpandableGroup`(`WLChevron`), `Masonry2Col`(`verticalGap`) | 빈 상태(시트 위에서는 타일이 `sheetField`), 접고 펴는 묶음, 2열 엇갈림 배치(열 간격과 세로 간격을 따로 줄 수 있음) |
| `WLTopBar`(`WLTopBarMetrics`) | 하위 화면 위쪽 바(뒤로·제목·⋯). 아래 "위쪽 바" 절 |
| `WLHeaderSheet`(`WLHeaderSheetState`, `WLSheetDetent`) | 고정된 위 면 + 끌어 올리는 목록 시트(목적 상세·아카이브 상세). 아래 "머리 시트" 절 |

iOS에는 Android `.copy(...)`에 해당하는 `WLTextStyle.resized`와 파생 스타일 `buttonMedium`·`bodyBold`·`buttonRegular`가 더 있다.

`WLInput.maxLength`는 UTF-16 길이가 아닌 grapheme 수다. 한도 초과 붙여넣기는 앞쪽 허용 길이를 남긴다. 한글 IME의 marked/composition 범위가 있으면 편집을 보존하고 확정 후 제한한다. Android는 단일 `TextFieldState`를 받고 `snapshotFlow`로 확정 상태를 제한한다. 부모 값과 내부 편집 값의 이중 소유나 지연 callback 반영이 없다. 현재 Compose의 InputTransformation은 들어오는 composition 범위를 공개하지 않고 composition-only commit이 transformation을 통과하지 않으므로 commit 관찰 경로를 쓴다. iOS는 직접 Binding과 `onChange` 절단, 앱 공용 UIKit 입력 알림·window별 약한 responder cache를 사용한다. Android 호스트 JDK의 `\X` 회귀 검사와 API 26 ICU·실기기 IME 결과는 구분한다.

## 위쪽 바 (`WLTopBar`)

모든 하위 화면의 뒤로·⋯·닫기 버튼 자리를 한 곳에서 정한다(결정 2026-10-07).

- **기준:** `safeTop` = 기기의 실제 위쪽 안전 영역(Android `WindowInsets.statusBars ∪ displayCutout`의 top, iOS `safeAreaInsets.top`). 보드 상태 바 44 같은 고정 숫자를 쓰지 않는다.
- **치수(`WLTopBarMetrics`):** 바는 `safeTop + 6`부터 높이 56(아래 끝 `safeTop + 62`), 좌우 `screenMargin` 20. 44 버튼은 바 안 세로 가운데라 윗변이 `safeTop + 12`다.
- **배치:** 왼쪽(뒤로) · 가운데 슬롯(제목 덩어리, 세로 가운데) · 오른쪽(⋯ 등, 간격 10). 가운데가 비어도 오른쪽은 끝에 붙는다. 큰 글자로 가운데가 56보다 크면 바가 늘어나고 버튼은 세로 가운데를 지킨다(Android).
- **배경:** 없으면 버튼만 떠 있다(상품 상세). 있으면 바와 상태 바 뒤까지 같은 색으로 칠해 스크롤 내용이 상태 바에 비치지 않는다(세부 유형 목록). iOS는 스크롤 안에 붙은 머리에서 상태 바 영역을 화면이 안전 영역 높이만큼 따로 칠한다.
- **쓰는 곳:** 세부 유형 목록(제목 20/700 + 개수 18/700 + 상위 이름 13), 상품 상세(사진 칸은 `safeTop + 68`부터), 목적 상세 머리 시트의 맨 윗줄(펼침 시 시트 윗변 = `safeTop + 62`, 타일·이름은 바 세로 가운데). 탭 첫 화면 제목(도현 28)도 같은 바의 세로 가운데(`safeTop + 20`)이고 부제는 바 바로 아래다.

## 머리 시트 (`WLHeaderSheet`)

목적 상세·아카이브 상세의 "위 색 면 + 목록 시트" 구조다(결정 2026-10-07). 시스템 시트가 아니라 화면 안의 층이며 overlay가 아니다.

- **층:** 뒤의 머리 층은 고정이고 스크롤되지 않으며 끌기를 받지 않는다. 시트(손잡이 + 목록)만 시트를 움직인다. iOS는 머리 층을 스크롤 뷰 밖 고정 층으로 두고, 그 층의 눌림 영역을 화면 위 ~ 현재 시트 윗변으로 한정한다.
- **멈추는 높이:** `WLSheetDetent.resting`(머리 내용의 측정 높이 바로 아래)과 `expanded`(위쪽 바 아래 끝 `safeTop + 62`) 두 개뿐이다. 머리 높이가 바뀌면(그 자리 편집) resting이 260 `ease`로 따라가고, 시트가 resting에 있었으면 함께 내려간다. 이 낮은 높이는 끌기로 갈 수 없고 편집 시작 때만 생긴다. 펼친 상태에서 편집을 시작하려면 `animateTo(resting)`/`animate(to: .resting)` 후 머리를 늘린다.
- **끌기:** expanded 전에는 시트가 움직이고, 닿은 뒤 남은 끌기·플링은 목록 스크롤이 된다. 목록 맨 위에서 아래로 끌면 시트가 내려오고 resting 아래는 고무줄이다. 손잡이 탭은 `toggle()`. Android는 손잡이 drag + 목록 nested scroll로, 놓으면 300 `emphasized`(속도 우선) 스냅이다. iOS는 resting 자리를 비운 한 `ScrollView`로 구현하고 놓은 뒤 스냅은 시스템 감속 곡선이다(손잡이 탭·`animate(to:)`만 300 `emphasized`).
- **진행값:** `progress`(0 = resting, 1 = expanded)를 머리 슬롯에 넘겨 화면이 요소를 연속으로 바꾼다. 목적 상세는 뒤로·⋯ 제자리, 아이콘 타일 44 → 36·이름 도현 28 → 20이 윗줄로 이동, 설명·알약은 p 0 → 0.5에 사라지고 윗줄 `+`가 p 0.5 → 1에 나타난다. 진행값은 배치·그리기 단계에서 읽는다.
- **상태 유지·접근성:** 멈춘 높이는 저장되어 상세를 다녀와도 유지된다. 손잡이는 "목록 넓게 보기"/"헤더 펼치기"(영어 "Expand list"/"Show header") 버튼으로 읽힌다(Android 문자열 리소스, iOS `Localizable.xcstrings`). 사라진 요소는 누르기·접근성에서 빠진다.
- **테스트:** Android `WLHeaderSheetStateTest`, iOS `WLHeaderSheetTests`가 진행값·스냅·고무줄·머리 높이 변경 시 resting 이동을 검사한다.

## overlay

시트·확인창·메뉴는 시스템 `ModalBottomSheet`·`Dialog`·`.sheet`·`.alert`를 쓰지 않고 화면 위 한 `OverlayHost`가 직접 그린다(이유: [QA-CLI-004](../../learning/client/q-and-a/QA-CLI-004-custom-overlay.md)). 화면은 `LocalOverlayHostState`·`EnvironmentValues.overlayHostState`로 `OverlayHostState`를 받아 `showSheet`·`showDialog`·`showMenu(anchor:items:)`·`dismiss`·`dismissAll`을 부른다. 메뉴 위치는 `wlAnchor`로 탐침·좌표를 등록하고 누를 때 버튼의 window 좌표를 읽는다.

- **상태 기계:** 항목마다 `Opening → Open → Closing → 제거`. 여러 개가 쌓일 수 있고(시트 위 확인창) `dismiss`는 맨 위만 닫는다. 화면의 모션이 끝나면 `onOpened`·`onClosed`를 부른다.
  - `dismiss`는 모든 항목이 `Open`이고 대상이 맨 위일 때만 받는다. 열고 닫는 중의 뒤로·막 누르기 연타는 무시한다.
  - `show*`는 `Opening` 중이면 무시하고, `Closing` 중이면 하나만 줄 세웠다가(나중 것이 앞 것을 대체) 닫기가 끝나면 연다. 메뉴 항목 → 확인창이 이 경로다.
  - `dismissAll`은 쌓인 overlay를 위에서부터 차례로 모두 닫는다. 맨 위가 이미 `Closing`이어도 받아(줄 세운 닫기) 그 닫기가 끝나면 다음을 닫는다. 확인창 `onConfirm`은 창이 닫히기 시작한 뒤 불려 그 안의 `dismiss()`는 무시되므로, "확인 → 아래 시트까지 닫기"는 `onConfirm`에서 `dismissAll()`을 부른다. 그 전에 줄 세운 `show*`는 버린다. 열리는 중이면 무시한다. 두 플랫폼이 같은 단위 테스트를 가진다.
  - 시트 끌기는 손잡이 줄에서만 시작하고, 놓을 때의 판단(`shouldDismissSheet`, `sheetDragEnd`)은 `Open`일 때만 동작한다. 닫는 중에 취소된 끌기가 되돌림 애니메이션으로 닫기를 끊지 않는다.
  - **속도 토큰 단위는 dp·pt/초다.** `sheetDragDismissVelocity` 1000은 1000dp/s(Android)·1000pt/s(iOS)다. iOS `DragGesture.velocity`는 pt/s라 그대로 쓰고, Android `draggable`의 px/s는 `sheetDragEndPx`가 density로 나눠 판단한다.
- **시트 높이:** 내용 높이만큼만 올라오고 상한은 min(760, 위 안전 영역 아래부터 화면 아래(키보드가 있으면 키보드 위)까지 − 24)이다(`sheetMaxHeight`, 24는 구현 기본값). 넘치는 내용은 시트 안에서 스크롤한다. 키보드가 올라오면 시트가 키보드 위로 올라간다: Android는 `imePadding` + Activity `windowSoftInputMode="adjustResize"`(없으면 창이 한 번 더 밀려 시트와 키보드 사이에 틈이 생겼다), iOS는 아래 홈 표시줄 안전 영역만 무시(`.ignoresSafeArea(.container, edges: .bottom)`)하고 키보드 영역은 지킨다.
- **입력 차단:** 어떤 항목이든 열고 닫는 중이면 맨 위 `InputBlocker`가 모든 터치를 먹는다. 막(scrim)은 사라지는 동안에도 터치를 먹는다. 막 누르기는 시트만 닫는다. 확인창은 버튼으로만, 메뉴는 바깥 누르기로 닫힌다.
- **뒤로·escape:** Android는 overlay가 있는 동안(닫히는 중 포함, `isShowing`) overlay의 `BackHandler`만 켜지고 라우터의 `PredictiveBackHandler`는 꺼진다(`navBackEnabled`). 등록 순서에 기대지 않아 overlay 아래에서 새 탭 스택이 그려져 나중에 등록되어도 뒤로가 그 아래 화면을 pop하지 않는다. 뒤로는 무시되는 경우에도 소비해 앱이 애니메이션 중에 꺼지지 않는다. iOS는 맨 위 층 컨테이너의 `.accessibilityAction(.escape)`가 닫는다.
- **모션:** 시트 열기 480 `springSheet`(면을 80 아래까지 늘려 지나침 때 틈이 안 보이게), 닫기 260 accelerate, 되돌림 300. 막은 400 ease-out / 260 ease-in, 블러 12 × 진행, 다크 막 .45. 확인창 200(scale .96 → 1) / 150. 메뉴 150, anchor 쪽 모서리에서 커진다. 모든 값이 생성 토큰이다.
- **iOS 완료 신호:** `withAnimation(completionCriteria: .removed)`의 완료 콜백으로 `onOpened`·`onClosed`를 부른다. 토큰 시간만큼 sleep해서 화면 완료를 추정하지 않는다. 취소된 Task는 상태 기계를 진행시키지 않는다.
- **Reduce Motion:** iOS는 시스템 설정을 읽어 시트·확인창·메뉴·막·탭 알약·스크롤·접고 펴기 애니메이션을 생략한다. 공유 요소와 탭 전환은 최종 상태로 즉시 이동하며 전환 완료·포커스 처리는 유지한다.

## 라우터와 탭 셸

Navigation Compose·`NavigationStack`·`TabView`를 쓰지 않는다. 두 플랫폼 모두 전환 상태 기계 `WLNavigator`와 이를 그리는 `WLNavHost`·`WLTabBar`로 구성한다. Android는 Compose snapshot 상태, iOS는 Observation을 사용한다. iOS 방식의 결정은 [ADR-028](../../history/architecture/client/ADR-028-ios-custom-router.md)이다.

- **상태:** 탭(`WLTab`: 홈·카테고리·목적)마다 독립 스택(`WLRoute`)을 가진다. `push(route, sourceKey)`·`pop()`·`selectTab(tab)`은 전환을 시작하고 상태를 바로 바꾼다. 루트에서 `pop()`은 false(시스템에 맡김)다. 현재 탭을 다시 고르면 전환 없이 맨 위 스크롤 요청을 낸다.
- **경로 책임:** Android는 `WLRoute` 인터페이스, iOS는 목적지와 화면 정책을 담은 `WLRoute` 값이다. feature가 `pushStyle`·`showsTabBar`를 정하고 앱 renderer가 화면을 고른다. core는 데모 id 앞머리로 화면을 선택하지 않는다.
- **루트 제공·복원:** 앱 루트가 navigator를 소유하고 overlay 바깥에서 제공한다. Android saver는 탭·스택·route codec·칸 id·nextId와 상태 정리 대상 목록을 복원해 새 상세가 예전 상세의 저장 상태를 받지 않게 한다. iOS navigation 저장·복원은 후속 과제다.
- **전환 종료:** 화면이 모션을 끝내면 모듈 내부 `finishTransition()`을 명시적으로 부른다(자기 전환일 때만). 그동안 `push`·`pop`·`selectTab`·끌기 시작은 모두 false이고 `InputBlocker`가 화면을 막는다. 모션이 끊겨도 `finally`에서 끝내 입력이 잠기지 않는다.
- **화면 이동:** 사진이 있는 이동은 사진이 카드 자리 → 상세 자리로 커진다(420/360 `emphasized`, 상세의 나머지는 페이드, 아래 화면은 그대로). 사진이 없는 이동은 가로 밀기다(결정 2026-10-07): 위 칸 W → 0, 아래 칸 0 → −0.25W, 열기 360·뒤로 300 `emphasized`(`pushSlideOpen`·`pushSlideBack`·`pushSlideParallax`). 밀리는 화면은 처음부터 불투명하고 페이드·그림자가 없다. 두 화면이 모두 탭 바를 보이면 탭 바는 고정이고, 한쪽만 보이면 진행값과 함께 옅어지거나 나타난다. 경로 정책은 `WLPushStyle.photo`/`.slide`(Android `Photo`/`Slide`)다.
  - Android: `SharedTransitionLayout` + 탭마다 `SeekableTransitionState`. 사진만 `sharedElement`.
  - iOS: 원래 자리·상세 자리 사각형을 phase 하나로 보간해 전환 층에 그린다. 사각형은 전환을 시작할 때만 UIKit 탐침에서 읽는다. 밀기는 `WLSlideOffset`.
  - 사진 상세는 원래 사진 비율로 fit된 사진만 공유 요소 경계로 삼는다(정사각 칸 안 aspect-fit). 그래야 전환 마지막 프레임에 크기·모양이 바뀌지 않는다.
  - 이전의 자리 표시 면 전환(누른 요소의 면이 화면 전체로 커짐)은 끝까지 자연스럽지 않아 2026-10-07에 코드와 토큰을 지웠다.
- **끌어서 뒤로:** 같은 pop 전환을 손가락 진행값으로 되감는다. 50% 이상이거나 빠르게 놓으면(초당 1.5 진행 이상) 확정, 아니면 되돌린다(남은 비율만큼, 하한 120ms).
  - Android: `PredictiveBackHandler`. 진행값이 없는 뒤로(API 33 미만, 3버튼, 화면의 뒤로 버튼)는 pop 전환을 처음부터 재생한다.
  - iOS: window 수준 `UIPanGestureRecognizer`(왼쪽 20pt, 오른쪽 가로 우세). 스크롤 pan이 이 인식기의 실패를 기다린다. 전환 중이거나 overlay가 열려 있으면 받지 않는다. 배경은 [QA-CLI-007](../../learning/client/q-and-a/QA-CLI-007-swiftui-gesture-vs-scrollview.md).
- **스택 칸 유지:** 스택의 모든 칸이 살아 있다. Android는 `SaveableStateHolder`(탭 → 칸 두 겹), iOS는 세 탭의 모든 칸을 한 `ZStack`에 펼친다. 스크롤·입력 상태와 공유 요소의 원래 자리가 깊이 2 이상에서도 남는다.
- **등록 생명주기:** iOS 사진·면 builder/spec은 UIKit 탐침 갱신 때 최신 값으로 교체하고 window 이탈 때 해제한다. 숨김은 key별 관찰 객체이며 잠깐 window에서 빠져도 정체성을 유지하고 최종 해제 때 제거한다. Android 면 등록은 값이 바뀔 때만 상태를 쓰고 소유자별 lease로 해제한다. 이전 소유자의 dispose가 새 등록을 지우지 않는다. 면 애니메이션 상태는 draw 단계에서 읽고 matched source만 애니메이션·painter를 만든다. painter 객체는 재사용하지만 프레임마다 outline·renderer를 만드는 비용과 전체 matching probe 비용은 C3에서 측정한다.

## 접근성

- 가려진 층(뒤 화면, 다른 탭, 숨은 탭 바, overlay 아래 내용, 아래 overlay)은 두 플랫폼 모두 접근성 트리에서 뺀다.
- Android 규칙:
  - overlay가 있으면(닫히는 중 포함) 뒤 화면 전체에 `clearAndSetSemantics {}`(`wlAccessibilityCovered`)를 건다. TalkBack 클릭은 막·`InputBlocker`(포인터만 막음)를 거치지 않으므로 숨기지 않으면 시트 아래 목록·탭 바를 누를 수 있었다.
  - 맨 위가 아닌 overlay 층과 열고 닫는 중인 층도 숨긴다. 전환 중에는 트리가 잠깐 비지만, TalkBack 클릭이 전환 중 입력 차단을 우회하지 못한다.
  - 시트·확인창·메뉴에 `paneTitle`(호출자가 넘긴 시트 제목, 확인창 제목, 현지화된 메뉴 이름)을 줘 열릴 때 창이 바뀐 것을 알린다.
  - 확인(에뮬레이터 API 36, `uiautomator dump`): 시트가 열리면 시트 요소만, 시트 위 확인창이면 확인창 요소만 남는다.
- iOS 규칙:
  - 숨김은 가려지는 잎 층마다 건다. 층 숨김은 `wlAccessibilityCovered(covered)` 하나로 한다. 가려지면 `.ignore` + `accessibilityHidden(true)`, 보이면 `.contain` + `accessibilityHidden(false)`다.
  - 조상에 맨 `accessibilityHidden(false)`를 걸지 않는다. 후손의 `accessibilityHidden(true)`가 취소된다. `.contain` 컨테이너 뒤에 건 경우는 취소되지 않는다.
  - `.isModal`을 쓰지 않는다. 형제인 탭 바까지 트리에서 빠진다.
  - 상세 칸 컨테이너의 escape(두 손가락 문지르기)가 `pop()`이다. 칸 `ZStack`은 자식이 하나면 접혀 escape 경로에서 빠지므로 보이지 않는 형제를 둔다.
  - 맨 위가 아닌 overlay와 열고 닫는 중인 overlay도 숨긴다. 접근성 클릭이 전환 중 터치 차단을 우회하지 못한다.
  - push가 끝나면 상세 제목에 VoiceOver 포커스를 준다(`wlArrivalFocus`).
  - 배경은 [QA-CLI-006](../../learning/client/q-and-a/QA-CLI-006-swiftui-accessibility-hiding.md).
- 입력칸(`WLInput`)의 라벨과 실제 편집 문자열은 별도로 유지한다. iOS는 칸 라벨·힌트를 주고 보이는 라벨을 숨긴다. Android는 칸의 `contentDescription`을 덮어쓰지 않고 보이는 라벨·안내와 native `editableText`를 유지한다. 실기기에서 비어 있는 칸의 이름과 입력한 내용·선택을 함께 읽는지 확인한다.
- Android `WLChip`은 `selectable(selected, role = Role.Button)`, `ExpandableGroup`은 `stateDescription`("펼침"/"접힘")을 제공한다. iOS의 선택 trait·펼침 값과 같은 상태를 알린다. 시트·메뉴·닫기·펼침/접힘·탭 이름은 두 플랫폼 한영 리소스에서 읽는다.
- 실기기 VoiceOver·TalkBack(포커스 이동·문지르기)은 아직 확인하지 않았다. 아래 "알려진 한계"의 확인 목록을 본다.

## 플랫폼 차이

| 항목 | Android | iOS |
| --- | --- | --- |
| 막 블러 | API 31 이상 `BlurEffect`, 31 미만은 블러 없이 어둡게만 | 항상 `.blur` |
| 뒤로 | 시스템 predictive back(`enableOnBackInvokedCallback`, 진행값은 API 33 이상), 3버튼·하드웨어 뒤로 | 왼쪽 가장자리 끌기, 화면의 뒤로 버튼, VoiceOver escape |
| 공유 요소 | Compose shared element API | phase 보간 전환 층 |
| 키보드 | `adjustResize` + `imePadding` | 키보드 안전 영역 |
| 테마 변경 | `configChanges="uiMode"`로 Activity 유지. 글자 크기·회전 변경은 Activity를 다시 만들지만 저장된 탭 스택·id를 복원한다(열린 overlay는 초기화) | 다시 만들지 않아 스택·시트 유지 |
| 탭 바 아래 여백 | `max(24, 내비게이션 막대 + 8)` | 화면 아래 끝에서 24(보드 값) |
| 탭 글자 크기 상한 | 글자 배율 1.3 | Dynamic Type `xxLarge` |
| 루트에서 뒤로 | 앱 종료(시스템) | 동작 없음 |

## 알려진 한계

C1에서 고치지 않고 남긴 것. C3 첫 실제 화면 전에 다시 본다.

- **navigation 복원 계약:** Android feature route는 `AppRouteCodec`에 등록해야 한다. 버전 변경 뒤 route 데이터 migration 정책은 실제 기능 모델과 함께 정한다. iOS navigator 저장·복원은 아직 없다.
- **overlay 저장·복원:** `SheetEntry.content`·확인 콜백 등 UI 람다는 저장 가능한 데이터가 아니다. 프로세스 종료 복원을 구현할 때 overlay의 종류·id·입력 데이터만 저장하고 화면 content와 동작은 현재 feature 상태에서 다시 연결해야 한다. 오래된 상태를 capture하지 않도록 열린 overlay의 데이터 갱신도 함께 설계한다.
- **외부 진입:** 두 플랫폼 모두 앱 루트 navigator 제공을 완료했다. C3 공유 intent/Share Extension의 pending URL 복구와 구체적인 목적지 연결은 별도 기능이다.
- `WLScrollToTopEffect`는 `ScrollState`만 받는다. Lazy 목록은 `LazyListState` 오버로드가 필요하다.
- `Masonry2Col`은 lazy가 아니다. 긴 목록에서는 바꾼다. Android는 유한한 폭이 필수이며 가로 스크롤에 넣을 때 호출부에서 `width`를 지정해야 한다(잘못된 제약은 명시적 오류). iOS의 제안 폭이 없으면 자식의 intrinsic 폭으로 계산하고 Layout cache로 sizeThatFits/placeSubviews 측정을 재사용한다.
- `sheetStepResize` 토큰을 아직 쓰지 않는다. 단계가 있는 시트에서 내용 높이가 바뀌면 시트 높이가 튄다.
- `WLMenu`는 화면 아래 가까이에서 위로 뒤집히지 않는다.
- 메뉴 anchor는 Android `WLMenuAnchor`·iOS `WLAnchor` 참조 객체다. 레이아웃 좌표는 등록만 하고 누를 때 window 좌표를 읽는다. 사진 이동 원래 쪽의 `sourceKey`는 Android 인자, iOS environment다.
- iOS의 모든 탭·스택 칸 유지, 입력 responder cache와 겹친 입력 소유, Android 공유 요소 비용과 Gradle 모듈 경계는 [C3 성능·구조 확인](c3-performance-checks.md)을 따른다.
- 실기기 한글 IME·TalkBack: 39/40자 근처 조합·삭제·커서 이동·초과 붙여넣기, 입력칸이 라벨뿐 아니라 입력한 내용과 선택을 읽는지 확인한다.
- 실기기 VoiceOver 확인 목록: 시트 트리의 라벨 없는 Group, 칸 `ZStack`의 숨은 형제 escape 우회, push 뒤 포커스가 안 옮겨 가면 `.screenChanged` 보완([spike 기록](../../history/architecture/client/ios-router-spike-2026-10-05.md) 권고).

## 데모

- 탭 첫 화면이 곧 데모다. 홈은 컴포넌트 데모(시트 → 확인창 → `dismissAll`, ⋯ 메뉴, 사진 카드, 칩, 가장 긴 목적 이름, 긴 가격 `KRW 1,190,000`)이고, 카테고리·목적 탭과 그 하위 화면은 보드(FCategoryHome·FCategoryList·FProductDetail·FPurposeHome·FPurposeDetail)를 따른다: 카테고리 세로 페이징, 2열 엇갈림 목록, 상품 상세, 겹쳐 쌓인 목적 카드, 머리 시트를 쓰는 목적 상세. 데모 경로는 상품(사진 이동, 탭 바 숨김)·세부 유형 목록·목적 상세(가로 밀기, 탭 바 보임)로 나뉜다. 상품 사진 자리 표시 색은 UI 토큰이 아닌 이미지 견본이라 보드 값을 그대로 쓴다.
- 데모는 Android debug source set, iOS `#if DEBUG`에만 있다. release에는 데모 경로·화면 코드가 포함되지 않으며 탭 이름만 보이는 빈 첫 화면이다.
