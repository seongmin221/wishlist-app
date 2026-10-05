# 디자인 시스템과 앱 뼈대 (C1)

> 상태: **확정** · 2026-10-06 · Android(Compose)와 iOS(SwiftUI)가 같은 이름·같은 규칙으로 구현한다.

디자인 값의 원본은 [디자인 결정](../../design/decisions.md), [모션 명세](../../../design/handoff/interactions/motion.md), 완성 화면 보드다. 이 문서는 그 값을 코드로 옮긴 구조를 설명한다. 플랫폼별 세부는 [android.md](android.md)·[ios.md](ios.md)의 "탭 셸과 화면 이동 (C1)" 절에 있다.

## 위치

| 영역 | Android (`client/android/src/main/kotlin/app/wishlist/android/`) | iOS (`client/ios/Wishlist/`) |
| --- | --- | --- |
| 토큰·서체·컴포넌트 | `designsystem/` | `DesignSystem/` |
| overlay(시트·확인창·메뉴) | `designsystem/overlay/` | `DesignSystem/Overlay/` |
| 탭 셸·라우터 | `navigation/` | `Navigation/` |
| 데모 화면 | `feature/demo/` | `Features/Demo/` |
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

## 서체와 글자 스타일

- 글꼴: Do Hyeon(제목), IBM Plex Sans KR Regular·Medium·Bold(본문). 앱에 넣고 라이선스(OFL)를 함께 둔다. iOS는 PostScript 이름으로 부른다.
- 스타일(두 플랫폼 같은 이름): `display28`·`display20`(줄 높이 1.0), `display28TwoLine`·`display20TwoLine`(1.2), `display28Edit`(24px, 밑줄 편집), `title`(22/1.3), `body`(14/1.35), `bodyStrong`, `label`(12/1.35), `price`(18/1.0), `button`(16/1.25). `title`·`label`·`button`의 줄 높이는 보드에 없어 정한 값이다.
- **한 줄 칸 높이:** Compose `lineHeight`는 한 줄짜리 칸을 글꼴 고유 높이보다 줄이지 못한다. Android는 `Modifier.wlLineBox(style)`(Text 바로 앞, 가장 안쪽에 둔다)로 칸 높이를 줄 높이에 맞춘다. iOS는 `wlText(style)`이 `lineSpacing`과 위아래 padding(음수 가능)을 `@ScaledMetric`으로 Dynamic Type에 맞춰 준다. 부모가 자르면(clip) 안 된다.
- **한글 아래 3px 밑줄:** 결정(2026-10-04)은 한글 글자 아래 끝과 밑줄 사이 3px다. 글꼴 파일을 분석한 한글 아래 깊이로 칸 아래 끝과 글자 사이 간격(`hangulBottomGapPx`·`hangulBottomGap`)을 계산해 밑줄 위치를 보정한다(`wlUnderline`, `wlUnderlined`, `WLUnderlineField`). 받침 없는 글자는 최대 약 3px 더 높이 떠 보인다(고정 밑줄의 한계).
- **글자 크기 배율:** Android 14부터 sp가 비선형으로 커진다(28sp는 1.3배에서 약 1.03배). 그래서 sp 값의 차이를 한 번에 px로 바꾸지 않고, 글자 크기·줄 높이를 각각 px로 바꾼 뒤 계산한다. 배경은 [QA-CLI-005](../../learning/client/q-and-a/QA-CLI-005-line-height-and-font-scale.md).
- **숫자 고정폭:** IBM Plex Sans KR에는 `tnum` 기능이 없지만 숫자 0–9의 폭이 모두 같아(600/1000em) 기본으로 고정폭이다. 가격은 `price` 스타일(또는 `PriceText`)로 그린다. Do Hyeon 숫자는 비례폭이라 가격에 쓰지 않는다.
- **가격:** `formatPrice`는 KRW에 소수를 보이지 않고, 다른 통화는 소수가 0이 아닐 때만 보인다(`USD 299`, `USD 19.99`). 큰 글자에서 한 줄을 넘으면 통화 코드와 금액 사이에서만 줄을 바꾼다(iOS `PriceText`는 `ViewThatFits`, Android는 공백 줄바꿈).

## 컴포넌트

두 플랫폼이 같은 이름을 쓴다. 색은 `WLTheme`이 라이트·다크 생성 색을 고르고(`LocalWLColors`·`EnvironmentValues.wlColors`), 시트 위인지(`wlOnSheet`)에 따라 입력·보조 버튼 면이 `sheetField`로 바뀐다.

| 이름 | 역할 |
| --- | --- |
| `WLTheme`, `WLText` | 테마 색 제공, 스타일·한 줄 칸 보정을 적용한 글자 |
| `WLButton`(`primary`·`secondary`·`danger`), `WLButtonPair` | 알약 버튼(최소 52), 취소 왼쪽·주 버튼 오른쪽 1 : 1.4 |
| `WLCard`, `WLIconTile`, `WLChip`, `WLAddChip` | 카드 면, 아이콘 타일, 칩, 점선 추가 칩 |
| `WLInput`, `WLUnderlineField` | 라벨·안내가 있는 입력칸, 밑줄 편집 칸 |
| `PriceText`(`formatPrice`), `PurposeDot`(`WLPurposeColor`) | 가격, 목적 색 점 |
| `EmptyState`, `ExpandableGroup`(`WLChevron`), `Masonry2Col` | 빈 상태, 접고 펴는 묶음, 2열 엇갈림 배치 |

iOS에는 Android `.copy(...)`에 해당하는 `WLTextStyle.resized`와 파생 스타일 `buttonMedium`·`bodyBold`·`buttonRegular`가 더 있다.

## overlay

시트·확인창·메뉴는 시스템 `ModalBottomSheet`·`Dialog`·`.sheet`·`.alert`를 쓰지 않고 화면 위 한 `OverlayHost`가 직접 그린다(이유: [QA-CLI-004](../../learning/client/q-and-a/QA-CLI-004-custom-overlay.md)). 화면은 `LocalOverlayHostState`·`EnvironmentValues.overlayHostState`로 `OverlayHostState`를 받아 `showSheet`·`showDialog`·`showMenu(anchor:items:)`·`dismiss`를 부른다. 메뉴 위치는 `wlAnchor`로 버튼의 window 좌표를 읽는다.

- **상태 기계:** 항목마다 `Opening → Open → Closing → 제거`. 여러 개가 쌓일 수 있고(시트 위 확인창) `dismiss`는 맨 위만 닫는다. 화면의 모션이 끝나면 `onOpened`·`onClosed`를 부른다.
  - `dismiss`는 모든 항목이 `Open`이고 대상이 맨 위일 때만 받는다. 열고 닫는 중의 뒤로·막 누르기 연타는 무시한다.
  - `show*`는 `Opening` 중이면 무시하고, `Closing` 중이면 하나만 줄 세웠다가(나중 것이 앞 것을 대체) 닫기가 끝나면 연다. 메뉴 항목 → 확인창이 이 경로다.
  - 시트 끌기는 손잡이 줄에서만 시작하고, 놓을 때의 판단(`shouldDismissSheet`, `sheetDragEnd`)은 `Open`일 때만 동작한다. 닫는 중에 취소된 끌기가 되돌림 애니메이션으로 닫기를 끊지 않는다.
- **입력 차단:** 어떤 항목이든 열고 닫는 중이면 맨 위 `InputBlocker`가 모든 터치를 먹는다. 막(scrim)은 사라지는 동안에도 터치를 먹는다. 막 누르기는 시트만 닫는다. 확인창은 버튼으로만, 메뉴는 바깥 누르기로 닫힌다.
- **뒤로·escape:** Android는 overlay의 `BackHandler`를 내용 뒤에 등록해 라우터의 뒤로보다 먼저 받는다. 뒤로는 무시되는 경우에도 소비해 앱이 애니메이션 중에 꺼지지 않는다. iOS는 맨 위 층 컨테이너의 `.accessibilityAction(.escape)`가 닫는다.
- **모션:** 시트 열기 480 `springSheet`(면을 80 아래까지 늘려 지나침 때 틈이 안 보이게), 닫기 260 accelerate, 되돌림 300. 막은 400 ease-out / 260 ease-in, 블러 12 × 진행, 다크 막 .45. 확인창 200(scale .96 → 1) / 150. 메뉴 150, anchor 쪽 모서리에서 커진다. 모든 값이 생성 토큰이다.

## 라우터와 탭 셸

Navigation Compose·`NavigationStack`·`TabView`를 쓰지 않는다. 두 플랫폼 모두 순수 상태 기계 `WLNavigator`와 이를 그리는 `WLNavHost`·`WLTabBar`로 구성한다. iOS 방식의 결정은 [ADR-028](../../history/architecture/client/ADR-028-ios-custom-router.md)이다.

- **상태:** 탭(`WLTab`: 홈·카테고리·목적)마다 독립 스택(`WLRoute`)을 가진다. `push(route, sourceKey)`·`pop()`·`selectTab(tab)`은 전환을 시작하고 상태를 바로 바꾼다. 루트에서 `pop()`은 false(시스템에 맡김)다. 현재 탭을 다시 고르면 전환 없이 맨 위 스크롤 요청을 낸다.
- **전환 종료:** 화면이 모션을 끝내면 `finishTransition()`을 명시적으로 부른다(자기 전환일 때만). 그동안 `push`·`pop`·`selectTab`·끌기 시작은 모두 false이고 `InputBlocker`가 화면을 막는다. 모션이 끊겨도 `finally`에서 끝내 입력이 잠기지 않는다.
- **공유 요소:** 사진이 있는 이동은 사진이 카드 자리 → 상세 자리로 커지고(420/360 `emphasized`), 사진이 없는 이동은 누른 요소 뒤의 자리 표시 면이 떠오름 80 → 화면 전체 420으로 커지며 색·모서리가 다음 화면 바탕으로 바뀐다. 다음 화면 내용은 커짐 시작 190 뒤부터 230 동안 나타난다. 탭 바는 내용과 같은 시간표로 사라진다.
  - Android: `SharedTransitionLayout` + 탭마다 `SeekableTransitionState`. 사진과 면 모두 `sharedElement`.
  - iOS: 원래 자리·상세 자리 사각형을 phase 하나로 보간해 전환 층에 그린다. 사각형은 전환을 시작할 때만 UIKit 탐침에서 읽는다.
- **끌어서 뒤로:** 같은 pop 전환을 손가락 진행값으로 되감는다. 50% 이상이거나 빠르게 놓으면(초당 1.5 진행 이상) 확정, 아니면 되돌린다(남은 비율만큼, 하한 120ms).
  - Android: `PredictiveBackHandler`. 진행값이 없는 뒤로(API 33 미만, 3버튼, 화면의 뒤로 버튼)는 pop 전환을 처음부터 재생한다.
  - iOS: window 수준 `UIPanGestureRecognizer`(왼쪽 20pt, 오른쪽 가로 우세). 스크롤 pan이 이 인식기의 실패를 기다린다. 전환 중이거나 overlay가 열려 있으면 받지 않는다. 배경은 [QA-CLI-007](../../learning/client/q-and-a/QA-CLI-007-swiftui-gesture-vs-scrollview.md).
- **스택 칸 유지:** 스택의 모든 칸이 살아 있다. Android는 `SaveableStateHolder`(탭 → 칸 두 겹), iOS는 세 탭의 모든 칸을 한 `ZStack`에 펼친다. 스크롤·입력 상태와 공유 요소의 원래 자리가 깊이 2 이상에서도 남는다.

## 접근성

- 가려진 층(뒤 화면, 다른 탭, 숨은 탭 바, overlay 아래 내용, 아래 overlay)은 접근성 트리에서 뺀다.
- iOS 규칙:
  - 숨김은 가려지는 잎 층마다 건다. 층 숨김은 `wlAccessibilityCovered(covered)` 하나로 한다. 가려지면 `.ignore` + `accessibilityHidden(true)`, 보이면 `.contain` + `accessibilityHidden(false)`다.
  - 조상에 맨 `accessibilityHidden(false)`를 걸지 않는다. 후손의 `accessibilityHidden(true)`가 취소된다. `.contain` 컨테이너 뒤에 건 경우는 취소되지 않는다.
  - `.isModal`을 쓰지 않는다. 형제인 탭 바까지 트리에서 빠진다.
  - 상세 칸 컨테이너의 escape(두 손가락 문지르기)가 `pop()`이다. 칸 `ZStack`은 자식이 하나면 접혀 escape 경로에서 빠지므로 보이지 않는 형제를 둔다.
  - push가 끝나면 상세 제목에 VoiceOver 포커스를 준다(`wlArrivalFocus`).
  - 배경은 [QA-CLI-006](../../learning/client/q-and-a/QA-CLI-006-swiftui-accessibility-hiding.md).
- 실기기 VoiceOver(포커스 이동·문지르기)는 아직 확인하지 않았다. 포커스가 옮겨 가지 않으면 전환 끝에 `.screenChanged`를 보낸다([spike 기록](../../history/architecture/client/ios-router-spike-2026-10-05.md) 권고).

## 플랫폼 차이

| 항목 | Android | iOS |
| --- | --- | --- |
| 막 블러 | API 31 이상 `BlurEffect`, 31 미만은 블러 없이 어둡게만 | 항상 `.blur` |
| 뒤로 | 시스템 predictive back(`enableOnBackInvokedCallback`, 진행값은 API 33 이상), 3버튼·하드웨어 뒤로 | 왼쪽 가장자리 끌기, 화면의 뒤로 버튼, VoiceOver escape |
| 공유 요소 | Compose shared element API | phase 보간 전환 층 |
| 테마 변경 | `configChanges="uiMode"`로 Activity 유지. 글자 크기·회전 변경은 Activity를 다시 만들어 탭 스택이 초기화된다 | 다시 만들지 않아 스택·시트 유지 |
| 탭 바 아래 여백 | `max(24, 내비게이션 막대 + 8)` | 화면 아래 끝에서 24(보드 값) |
| 탭 글자 크기 상한 | 글자 배율 1.3 | Dynamic Type `xxLarge` |
| 루트에서 뒤로 | 앱 종료(시스템) | 동작 없음 |

## 데모

- 탭 첫 화면이 곧 데모다. 시트·확인창·⋯ 메뉴(삭제 → 확인창), 사진 카드(사진 이동), 칩·목적 카드(면 이동), 가장 긴 목적 이름, 긴 가격(`KRW 1,190,000`)을 담는다. iOS 데모에만 칩 목록 → 상품 상세(깊이 2)가 있다.
- debug 빌드에서만 보인다(Android `BuildConfig.DEBUG`, iOS `#if DEBUG`). release는 탭 이름만 보이는 빈 첫 화면이다.
