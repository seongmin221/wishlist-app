# 인터랙션 핸드오프

클라이언트(iOS SwiftUI, Android Compose)에서 화면의 움직임과 동작을 구현할 때 보는 자료다. 정적 화면은 [완성 화면](../screens/README.md), 색·서체·결정 근거는 [디자인 결정](../../../docs/design/decisions.md)에 있다.

## 파일

| 파일 | 내용 | 이럴 때 본다 |
| --- | --- | --- |
| [motion.md](motion.md) | 화면 전환 6가지와 작은 동작의 대상·속성·시간·곡선, 제스처, 플랫폼 구현 메모, 구현 기본값 | 애니메이션 코드를 쓸 때 |
| [tokens.json](tokens.json) | motion.md의 값을 기계가 읽는 형태로 둔 모션 토큰 | 상수·테마 파일을 만들 때 |
| [screens.md](screens.md) | 화면별 상태 변수, 누르면 바뀌는 것, 공통 패턴(편집 중 닫기 확인, ⋯ 메뉴 등) | 화면 상태와 이벤트를 설계할 때 |
| [reference/strips/](reference/strips/) | 모션별 주요 시점 프레임을 한 줄로 이어 붙인 PNG | 시점별 모습을 빠르게 확인할 때 |
| [reference/boards/](reference/boards/) | 고른 모션 보드(반복 재생 HTML)와 쓰는 화면 캡처 | 실제 움직임과 속도를 볼 때 |

## 읽는 순서

1. [screens.md](screens.md)에서 구현할 화면의 상태와 동작을 본다.
2. 그 화면이 쓰는 전환을 [motion.md](motion.md)에서 찾아 값을 옮긴다. 값은 [tokens.json](tokens.json)과 같다.
3. 모양이 헷갈리면 [strips](reference/strips/)의 프레임을 보고, 속도감은 [boards](reference/boards/)를 브라우저로 열어 본다.

## 원칙

- **같은 값을 두 플랫폼에 그대로 쓴다.** 곡선은 cubic-bezier 4개 값, 시간은 ms다. SwiftUI는 `Animation.timingCurve`, Compose는 `CubicBezierEasing` + `tween`으로 옮긴다. 플랫폼 기본 전환(좌우 밀기 push, 시스템 시트 애니메이션)으로 바꾸지 않는다.
- **결정과 구현 기본값을 구분한다.** motion.md에서 "구현 기본값"으로 표시한 값(제스처 임계값, 확인창 모션 등)은 디자인에서 정하지 않은 값이다. 먼저 이 값으로 구현하고, 바꾸려면 디자인 결정을 추가한다.
- **보드가 원본이다.** 문서와 보드가 다르면 보드와 [디자인 결정](../../../docs/design/decisions.md)을 따르고 문서를 고친다.
- 시스템 "동작 줄이기" 설정 대응은 이번 범위에 없다.

## 보드를 로컬에서 보기

```bash
python3 -m http.server --directory design/handoff/interactions/reference/boards 8000
# http://localhost:8000/MotionPushB.dc.html
```

보드는 화면 캡처(`motion/*.png`)를 겹쳐 CSS 애니메이션으로 반복 재생한다. 회색 원은 손가락이 누른 자리다. 후보 비교와 고르지 않은 안은 디자인 캔버스의 `검토 · 화면 전환 모션` 페이지에 있다.

## 갱신

1. 모션 보드: [`design/canvas-fresh/gen_motion.py`](../../canvas-fresh/gen_motion.py)로 만들고, 화면 캡처는 [`capture_motion_states.py`](../../canvas-fresh/capture_motion_states.py)로 찍는다. 고른 보드만 `reference/boards/`에 둔다.
2. 스트립: [`capture_motion_strips.py`](../../canvas-fresh/capture_motion_strips.py)를 실행하면 `reference/strips/`를 다시 만든다.
3. 값이 바뀌면 디자인 결정 → motion.md → tokens.json 순으로 함께 고친다.
