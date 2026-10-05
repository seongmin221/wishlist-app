# Android 구조

> 상태: **확정** — Kotlin + Jetpack Compose + `ACTION_SEND` 기반 Share Receiver

## 역할

- Jetpack Compose로 화면과 navigation을 구성한다.
- 상품 링크 웹뷰를 Compose navigation 안에서 열고, 공통 웹뷰 탐색·외부 앱·쿠키 정책을 구현한다.
- `ACTION_SEND` intent에서 `text/plain` 형태의 URL을 읽는다.
- intent 수신 후 URL을 검증·정규화하고 KMP 저장 UseCase로 연결한다.

## 주의점

- 앱이 실행 중이 아닐 때도 intent가 들어올 수 있으므로 navigation과 pending URL 복구를 설계한다.
- URL이 아닌 공유 텍스트와 여러 URL이 포함된 텍스트의 MVP 처리 방침을 명확히 한다.
- UI layer가 네트워크·추출 상태를 직접 다루지 않게 한다.
- 시스템 뒤로 가기는 웹페이지 방문 기록을 먼저 처리하고, 기록이 없을 때 웹뷰 화면을 닫는다.

## 탭 셸과 화면 이동 (C1)

- Navigation Compose를 쓰지 않는다. `navigation/WLNavigator`가 탭별 독립 스택과 현재 탭을 가진 순수 상태 기계이고(단위 테스트 대상), `WLNavHost`가 그린다. 모션 원본은 [motion.md](../../../design/handoff/interactions/motion.md) 2·3절이다.
- 전환 중 입력: `push`·`pop`·`selectTab`은 전환을 시작하고, 화면이 모션을 끝내면 `finishTransition()`을 부른다. 그동안 들어온 이동·탭·뒤로는 무시한다. 뒤로는 시스템에 넘기지 않고 받아서 버린다.
- 탭 전환은 `AnimatedContent` 페이드 스루, 탭 상태는 `SaveableStateHolder`(탭 → 스택 칸 순서로 두 겹)로 유지한다. 현재 탭을 다시 누르면 `scrollToTopRequests`로 맨 위 스크롤을 요청한다.
- 화면 이동은 `SharedTransitionLayout` + 탭마다 `SeekableTransitionState`를 쓰는 `AnimatedContent`다. 사진은 `sharedElement`이고, 사진 없는 이동은 누른 요소 뒤에 깐 자리 표시 면을 `sharedElement`로 이어 떠오름 → 화면 전체 → 다음 화면 바탕색으로 키운다. 자리 표시 면은 다음 화면과 키가 맞은(누른) 요소만 그린다(`isMatchFound`). 같은 화면의 다른 칩·카드는 같은 칸 전환을 타도 그리지 않아 그림자·다시 그리기가 없다. 다음 화면 내용은 전환 중 overlay에서 면 위에 그린다. 시간표가 같은 keyframes로 위치·색·모서리·그림자를 맞춘다.
- 뒤로는 `PredictiveBackHandler` 하나로 처리한다. 끄는 동안 pop 전환을 진행값으로 되감고, 놓을 때 50% 이상이거나 빠르게 놓았으면(진행 속도 초당 1.5 이상, 구현 기본값) 확정, 아니면 되돌린다. 진행값 없이 끝나는 뒤로(API 33 미만, 3버튼 내비게이션, 화면의 뒤로 버튼)는 같은 pop 전환을 처음부터 재생한다. overlay가 떠 있는 동안에는 꺼진다(overlay의 `BackHandler`만 받는다).
- `MainActivity`는 `windowSoftInputMode="adjustResize"`(키보드는 `imePadding`으로만 피한다. 기본 pan이면 창이 한 번 더 밀린다)이고 `configChanges="uiMode"`로 테마가 바뀌어도 다시 만들지 않는다. 색은 `isSystemInDarkTheme()`로 바로 바뀌고 탭 스택·열린 시트가 유지된다. 글자 크기·회전 변경은 여전히 다시 만들며 탭 스택은 초기화된다(navigator 저장은 실제 화면이 생길 때 다룬다).
- 탭 바 글자는 글자 크기 배율을 1.3까지만 따른다(칸 폭 안에 "카테고리"를 넣기 위해서).
- 데모 화면(`feature/demo`)은 main source set에 있고 탭 첫 화면에서 `BuildConfig.DEBUG`일 때만 보인다.
