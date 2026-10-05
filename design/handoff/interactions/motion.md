# 모션 명세

화면 전환과 작은 동작의 시간·곡선·움직이는 대상을 구현 단위로 적은 문서다. 값은 [디자인 결정](../../../docs/design/decisions.md)의 `2026-10-05` 행과 [완성 화면](../screens/README.md) 보드의 `transition`이 원본이고, 같은 값을 [tokens.json](tokens.json)에 기계가 읽는 형태로 두었다.

## 표기

- **시간**은 ms, **거리**는 pt(iOS)·dp(Android)와 같은 값이다. 보드는 390×844 기준이라 화면 폭 `390`은 실제 기기 폭(`W`)으로 바꿔 읽는다.
- **곡선**은 CSS `cubic-bezier(x1, y1, x2, y2)`다. 두 플랫폼 모두 같은 4개 값으로 그대로 옮긴다.
  - SwiftUI: `Animation.timingCurve(x1, y1, x2, y2, duration: 초)`
  - Compose: `tween(durationMillis = ms, delayMillis = 지연, easing = CubicBezierEasing(x1, y1, x2, y2))`
- y 값이 1보다 큰 곡선(`spring-sheet`)은 끝에서 목표를 조금 지나쳤다 돌아온다. 스프링 API로 바꾸지 말고 같은 cubic-bezier를 쓴다. 지나치는 동안 빈 곳이 보이지 않게 움직이는 면을 그만큼 더 길게 그린다.
- `ease`·`ease-in`·`ease-out`은 CSS 기본값이다: `ease` = `(.25,.1,.25,1)`, `ease-in` = `(.42,0,1,1)`, `ease-out` = `(0,0,.58,1)`.
- 각 모션의 프레임별 모습은 [reference/strips/](reference/strips/)의 PNG로, 실제 움직임은 [reference/boards/](reference/boards/)의 보드 HTML을 브라우저로 열어 반복 재생으로 본다(손가락 자리는 회색 원).

## 곡선 이름

| 이름 | cubic-bezier | 성격 | 쓰는 곳 |
| --- | --- | --- | --- |
| `standard` | `(.32,.72,0,1)` | 빠르게 출발해 길게 감속(iOS 시트 곡선) | 탭 바 알약, 정보 보완 넘김, 공유 카드 나타남 |
| `spring-sheet` | `(.2,1.25,.4,1)` | 감속하며 살짝 지나쳤다 돌아옴 | 바텀시트 열기 |
| `accelerate` | `(.4,0,1,1)` | 천천히 출발해 빨라지며 나감 | 바텀시트 닫기, 공유 카드 사라짐 |
| `emphasized` | `(.2,0,0,1)` | 강한 감속 | 화면 이동(공유 요소), 다음 카드 올라옴 |
| `fade-in` | `(0,0,.2,1)` | 감속 페이드 | 탭 전환 들어옴 |
| `throw` | `(.5,0,.9,.5)` | 가속하며 던짐 | 연속 처리 카드 나감 |

## 화면 전환

### 1. 바텀시트 열기·닫기

[strip 열기](reference/strips/sheet-open.png) · [strip 닫기](reference/strips/sheet-close.png) · [보드](reference/boards/MotionSheetB.dc.html)

| 단계 | 대상 | 속성 | 시간 | 곡선 |
| --- | --- | --- | --- | --- |
| 열기 | 시트 | `translateY(시트 높이 → 0)` | 480 | `spring-sheet` |
| 열기 | 뒤 막 | `opacity 0 → 1` (블러 12 + 어두운 막) | 400 | `ease-out` |
| 닫기 | 시트 | `translateY(0 → 시트 높이)` | 260 | `accelerate` |
| 닫기 | 뒤 막 | `opacity 1 → 0` | 260 | `ease-in` |

- 시트는 내용 높이만큼만 올라온다(최대 760). 위 모서리 36, 시트 색 불투명.
- 열 때 지나치는 거리는 약 6%다. 시트 배경을 화면 아래로 80 더 그려 둔다.
- 뒤 막은 시트 뒤 화면에만 걸린다: 블러 12, 어두운 막 라이트 `rgba(0,0,0,.24)`·다크 `rgba(0,0,0,.45)`.
- 닫는 방법: 뒤 막 누르기, 시트 안 닫기·취소, 시트 끌어내리기, 시스템 뒤로. 편집 중 값을 바꿨으면 닫기 전에 "변경 사항을 버릴까요?" 확인창을 먼저 띄운다([screens.md](screens.md#공통-패턴)).
- 같은 시트 안에서 단계가 바뀌는 경우(카테고리 선택 → 신규 카테고리, 비교 끝내기 단계)는 시트를 닫지 않고 내용만 바꾼다. 높이 변화는 `emphasized` 300으로 맞춘다(구현 기본값).
- 확인창(가운데 뜨는 창)은 이 모션을 쓰지 않는다. 뒤 막은 같고, 창은 `opacity 0 → 1`, `scale(.96 → 1)` 200 `fade-in`, 닫을 때 `opacity` 150 `ease-in`(구현 기본값).

플랫폼 메모
- SwiftUI `.sheet`·Compose `ModalBottomSheet`의 시스템 애니메이션은 이 곡선을 따르지 않는다. 시트는 직접 그린 오버레이(SwiftUI `ZStack` + `offset`, Compose `Animatable<Float>` + `offset`)로 만든다.
- 뒤 화면 블러: SwiftUI는 뒤 콘텐츠에 `.blur(radius: 12)`. Compose는 `Modifier.blur(12.dp)`가 Android 12(API 31) 이상에서만 동작하므로 그 아래에서는 어두운 막만 쓴다.

### 2. 화면 이동 (push·pop)

모든 화면 이동은 **누른 요소가 다음 화면으로 커지는 공유 요소 전환**이다. 옆으로 밀리는 플랫폼 기본 전환은 쓰지 않는다.

**사진이 있을 때** — 상품 카드 → 상품 상세 · [strip](reference/strips/push-photo.png) · [뒤로 strip](reference/strips/push-photo-back.png) · [보드](reference/boards/MotionPushB.dc.html)

| 단계 | 대상 | 속성 | 시간 | 곡선 |
| --- | --- | --- | --- | --- |
| 열기 | 카드 사진 | 카드 사진 사각형 → 상세 사진 사각형(위치·크기, 모서리 20 유지) | 420 | `emphasized` |
| 열기 | 상세의 나머지 | `opacity 0 → 1` | 420 | `ease-out` |
| 열기 | 목록의 원래 사진 자리 | 전환 동안 비워 둔다(바탕색) | 420 | — |
| 열기 | 목록 화면 | 움직이지 않고 그대로 둔다. 상세가 위에서 나타나며 가린다 | — | — |
| 뒤로 | 상세 사진 | 상세 사진 사각형 → 카드 사진 사각형 | 360 | `emphasized` |
| 뒤로 | 상세의 나머지 | `opacity 1 → 0` | 250 | `ease-in` |

**사진이 없을 때** — 카테고리 칩 → 목록, 설정 줄, 아카이브 기록 카드, 목적 카드 등 · [strip](reference/strips/push-surface.png) · [뒤로 strip](reference/strips/push-surface-back.png) · [보드](reference/boards/MotionPushPlaceholder.dc.html)

| 단계 | 대상 | 속성 | 시간 | 곡선 |
| --- | --- | --- | --- | --- |
| 떠오름 | 누른 요소의 면(자리 표시) | 사방 3 커짐, 그림자 `0 8 24 rgba(0,0,0,.14)` 생김 | 80 | `ease-out` |
| 커짐 | 자리 표시 면 | 떠오른 사각형 → 화면 전체, 모서리(요소 값) → 0, 색(요소 면 색) → 다음 화면 바탕색, 그림자 → 0 | 420 | `emphasized` |
| 커짐 | 다음 화면 내용 | `opacity 0 → 1`, 커짐 시작 후 190(약 45%)부터 230 동안 | 230 | `ease-out` |
| 뒤로 | 화면 내용 | `opacity 1 → 0` | 180 | `ease-in` |
| 뒤로 | 자리 표시 면 | 화면 전체 → 떠오른 사각형 | 360 | `emphasized` |
| 내려앉음 | 자리 표시 면 | 떠오른 사각형 → 원래 요소, 그림자 0, 끝나면 면을 지움 | 80 | `ease-in` |

- 자리 표시 면은 누른 요소의 면 그대로(색·모서리)에서 시작한다. 안의 글자·아이콘은 싣지 않는다.
- 사진이 없는 상품(정보 보완 필요)의 카드는 사진 자리 표시(사진 칸 면)를 "사진"으로 보고 첫 번째 방식으로 커진다.
- 탭 바가 있는 화면에서 탭 바가 없는 화면으로 갈 때 탭 바는 다음 화면 내용과 함께 사라지고 나타난다(따로 움직이지 않는다).
- 손가락으로 끌어 뒤로 가기: 끈 비율(0~1)만큼 사진·면이 원래 자리 쪽으로 줄어들고 내용이 옅어진다. 손을 떼면 진행 50% 이상이거나 빠르게 놓았으면 나머지 뒤로 모션, 아니면 다시 펼친다(구현 기본값).

플랫폼 메모
- SwiftUI: iOS 18 이상은 `.navigationTransition(.zoom(sourceID:in:))` + `.matchedTransitionSource`가 이 동작(끌어서 뒤로 포함)에 가장 가깝다. 시간·곡선이 다르면 `matchedGeometryEffect`로 직접 만든다.
- Compose: `SharedTransitionLayout`의 `sharedElement`(사진)·`sharedBounds`(자리 표시 면)에 `boundsTransform = { _, _ -> tween(420, easing = CubicBezierEasing(.2f, 0f, 0f, 1f)) }`. 끌어서 뒤로는 `PredictiveBackHandler`의 진행값으로 같은 전환을 되감는다.

### 3. 탭 전환 (페이드 스루)

[strip](reference/strips/tab.png) · [보드](reference/boards/MotionTabC.dc.html)

| 대상 | 속성 | 시작 | 시간 | 곡선 |
| --- | --- | --- | --- | --- |
| 이전 탭 화면 | `opacity 1 → 0` | 0 | 90 | `ease-in` |
| 새 탭 화면 | `opacity 0 → 1`, `scale(.97 → 1)` | 90 | 210 | `fade-in` |
| 탭 바 흰 알약 | `translateX(이전 칸 → 새 칸)` | 0 | 250 | `standard` |
| 탭 글자·아이콘 색 | 흰색 ↔ 먹색, 굵기 400 ↔ 700 | 125 | 즉시 | — |

- 탭 바(먹색 알약 바, 흰 선택 알약)는 화면 전환과 별개로 늘 제자리에 있다.
- 각 탭은 스크롤 위치와 하위 화면 스택을 기억한다. 현재 탭을 다시 누르면 맨 위로 부드럽게 스크롤한다.

플랫폼 메모
- SwiftUI: 시스템 `TabView` 대신 직접 그린 탭 바 + `ZStack`에 세 탭을 모두 두고 `opacity`·`scaleEffect`로 바꾼다(상태 유지).
- Compose: `AnimatedContent`에 `fadeIn(tween(210, 90, fade-in)) + scaleIn(tween(210, 90, fade-in), initialScale = .97f) togetherWith fadeOut(tween(90, easing = ease-in))`. 탭별 상태는 `SaveableStateHolder`로 유지한다.

### 4. 연속 처리 카드 (분류·목적 확인)

[strip](reference/strips/review-card.png) · [보드](reference/boards/MotionCardA.dc.html)

| 대상 | 속성 | 시작 | 시간 | 곡선 |
| --- | --- | --- | --- | --- |
| 현재 카드(확정) | `translateX(0 → +480)`, `rotate(0 → +16°)` | 0 | 340 | `throw` |
| 현재 카드(보류) | `translateX(0 → -480)`, `rotate(0 → -16°)` | 0 | 340 | `throw` |
| 다음 카드 묶음 | `scale(.94 → 1)`, `translateY(14 → 0)` | 40 | 300 | `emphasized` |
| 위치 표시 `n / 전체` | 새 값으로 바꿈 | 0 | 즉시 | — |

- 방향 규칙: **확정 = 오른쪽, 보류 = 왼쪽**(버튼 아래 안내 문구와 같다).
- 회전 중심은 카드 가로 가운데, 세로 90% 지점(아래쪽)이다.
- 뒤에 쌓인 카드는 남은 수만큼 최대 2장이 보인다(회전 -2.5°·3°, 위치 차 8·14). 다음 카드가 올라오면 쌓인 카드도 한 단계씩 앞으로 온다.
- 손가락으로 밀기: 카드는 손가락을 따라 `translateX(dx)`, `rotate(dx / W × 16°)`로 움직인다. 손을 떼면 `|dx| ≥ W × 0.3` 이거나 가로 속도가 800/s 이상이면 그 방향으로 위 모션의 남은 구간을 이어 나가고, 아니면 `emphasized` 250으로 제자리에 돌아온다(구현 기본값).
- 카드가 나가는 동안(340)은 확정·보류 버튼과 밀기를 받지 않는다.
- 마지막 카드가 나가면 요약 화면(`확정 n · 보류 n`)이 다음 카드 자리 모션(`scale .94 → 1`)으로 나타난다(구현 기본값).

### 5. 정보 보완 넘김

[strip](reference/strips/fill-next.png) · [보드](reference/boards/MotionFillA.dc.html)

| 대상 | 속성 | 시간 | 곡선 |
| --- | --- | --- | --- |
| 현재 상품 내용 | `translateX(0 → -W)` | 320 | `standard` |
| 다음 상품 내용 | `translateX(+W → 0)` | 320 | `standard` |
| 머리(닫기·위치 표시)·아래 버튼 | 움직이지 않음. 위치 표시는 전환 시작 때 새 값 | — | — |

- `저장하고 다음`과 `건너뛰기` 모두 같은 방향(왼쪽으로)이다.
- 움직이는 범위는 머리 아래부터 아래 버튼 위까지다(그 밖으로는 잘린다).
- 전환이 시작될 때 키보드가 열려 있으면 먼저 닫는다. 다음 상품 내용은 맨 위로 스크롤된 상태로 들어온다.
- 전환 동안(320)은 두 버튼을 받지 않는다.

### 6. 공유 저장 카드

[strip](reference/strips/share-card.png) · [보드](reference/boards/MotionShareA.dc.html)

| 단계 | 대상 | 속성 | 시간 | 곡선 |
| --- | --- | --- | --- | --- |
| 나타남 | 카드 | `translateY(+140 → 0)` | 340 | `standard` |
| 유지 | 카드 | 그대로 | 1500 | — |
| 사라짐 | 카드 | `translateY(0 → +140)` | 260 | `accelerate` |

- 카드는 화면 아래 40, 좌우 16 여백에 뜨고 버튼이 없다. 사라짐이 끝나면 공유 화면을 닫는다.
- 저장 처리가 1.5초 안에 끝나지 않아도 카드는 정해진 시간에 닫는다. 저장은 앱 쪽에서 이어서 한다(로그인 전·오프라인 문구 규칙은 [screens.md](screens.md) 참고).

플랫폼 메모
- iOS 공유 확장은 시스템 시트 위에 우리 화면을 띄운다. 확장 화면을 투명하게 두고 카드만 이 모션으로 올린 뒤, 사라짐이 끝나면 `completeRequest`를 부른다.
- Android는 `ACTION_SEND`를 받는 투명 테마 Activity에 카드만 그리고, 사라짐 뒤 `finish()`(전환 애니메이션 없음)한다.

## 작은 동작

| 동작 | 대상·속성 | 시간·곡선 | 쓰는 곳 |
| --- | --- | --- | --- |
| 펼치기 화살표 | `rotate(0 → 180°)` | 200 `ease` | 홈 할 일 카드, 로그인 전 분석 대기, 확인창 안 상품 목록 |
| 펼침 내용 | 높이 0 → 내용 높이, `opacity` | 200 `ease`(구현 기본값, 보드는 즉시) | 위와 같음 |
| 그 자리 편집 패널 | 높이 0 ↔ 내용, `opacity`, 위 간격 -16 ↔ 0 | 높이·간격 260 `ease`, `opacity` 200 `ease` | 목적 상세·아카이브 상세 편집 |
| 보기 줄 접힘(편집 시작) | 후보 추가·비교 끝내기 줄 높이 → 0, `opacity` → 0 | 위와 같음 | 목적 상세 |
| 접힌 머리 띠 | `opacity 0 → 1`, `translateY(-8 → 0)` | 220 `ease` | 목적 상세·아카이브 상세 |
| 편집 칸 포커스 | 밑줄 1 회색 → 글자색 | 즉시 | 모든 그 자리 편집 칸 |
| ⋯ 메뉴 | `opacity`, `scale(.96 → 1)`, 버튼 쪽 모서리 기준 | 150 `ease-out`(구현 기본값) | ⋯ 메뉴 |
| 목적 카드 펼침 | 누른 카드 높이 접힘 ↔ 펼침, 다른 카드는 접힘 | 300 `emphasized`(구현 기본값) | 목적 탭 첫 화면 |

### 머리 접기 (목적 상세·아카이브 상세)

- 구조: 위쪽 색 면(머리)과 그 아래 시트(목록)가 한 스크롤 안에 있다. 접힌 띠는 화면 위에 따로 겹쳐 있다(높이 108 = 위 여백 52 + 버튼 44 + 아래 12).
- 접힘 기준: `stop = 시트 윗변 위치 - 108`. 스크롤 값이 `stop - 24` 이상이면 접힘, 아니면 펼침.
- 스냅: 아래로 스크롤 중 `0 < 스크롤 < stop`이면 `stop`까지, 위로 스크롤 중 `0 < 스크롤 < stop`이면 0까지 부드럽게 스크롤한다. 스냅 중 450 동안은 다시 스냅하지 않는다.
- 손잡이를 누르면 접힘 ↔ 펼침을 같은 부드러운 스크롤로 바꾼다.
- 편집 시작: 접혀 있으면 먼저 0으로 스크롤한 뒤 편집 패널을 펼친다. 편집 중에도 스크롤로 접힌다.
- 플랫폼에서 "부드러운 스크롤"은 SwiftUI `ScrollViewReader`/`scrollPosition`의 `withAnimation(.timingCurve(.2,0,0,1, duration: .3))`, Compose `animateScrollTo(target, tween(300, easing = emphasized))`로 맞춘다(구현 기본값).

## 구현 기본값

디자인에서 따로 정하지 않아 위 명세에 **구현 기본값**으로 표시한 값이다. 이 값으로 먼저 구현하고, 실제 기기에서 어색하면 디자인 결정을 추가해 바꾼다.

| 항목 | 기본값 |
| --- | --- |
| 시트 끌어내려 닫기 | 시트 높이의 25% 이상 끌었거나 아래 속도 1000/s 이상이면 닫기 모션, 아니면 `spring-sheet` 300으로 되돌림 |
| 카드 밀기 확정 | `|dx| ≥ W × 0.3` 또는 가로 속도 800/s 이상, 아니면 `emphasized` 250으로 되돌림 |
| 끌어서 뒤로 가기 | 진행 50% 이상 또는 빠르게 놓으면 뒤로, 아니면 되돌림 |
| 확인창 | 나타남 `opacity`·`scale(.96 → 1)` 200 `fade-in`, 사라짐 `opacity` 150 `ease-in` |
| 시트 안 단계 바꿈 높이 | `emphasized` 300 |
| 펼침 내용 높이 | 200 `ease` |
| ⋯ 메뉴 | 150 `ease-out` |
| 목적 카드 펼침 | 300 `emphasized` |
| 전환 중 입력 | 움직이는 대상과 그 전환을 다시 부르는 버튼은 전환이 끝날 때까지 입력을 받지 않는다 |

## 하지 않는 것

- 시스템 "동작 줄이기" 설정에 따른 대체 모션은 이번 범위에서 다루지 않는다(사용자 결정, 2026-10-05).
- 화면 이동에 플랫폼 기본 좌우 밀기 전환을 쓰지 않는다.
- 시트·확인창 자체를 반투명하게 하지 않는다(블러는 뒤 화면에만).
