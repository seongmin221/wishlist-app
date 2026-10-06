# ADR-029: 디자인 토큰은 Python 표준 라이브러리 생성기로 두 플랫폼 상수를 만든다

> 상태: **확정** · 날짜: 2026-10-06 · 영역: **client** · 관련: [ADR-027](ADR-027-client-implementation-strategy.md)

## 맥락

ADR-027은 토큰을 `tokens.json`에서 두 플랫폼 상수로 생성하기로 했다. 그런데 핸드오프 `design/handoff/interactions/tokens.json`에는 모션(시간·곡선·비율)만 있다. 색·모서리·간격·목적 색·상태 타일은 [디자인 결정](../../../design/decisions.md) 문장과 보드에 흩어져 있다. 클라이언트 빌드에는 Node·Style Dictionary 같은 도구가 없다.

## 결정

- 생성기 `client/tools/gen_tokens.py`는 Python 3.9 표준 라이브러리만 쓴다.
- 입력은 두 개다. 모션은 핸드오프 `tokens.json`을 그대로 읽는다. 색·모서리·간격·목적 색·상태 색은 `client/tools/design-tokens.json`에 디자인 결정 값을 옮겨 적고, 행마다 `source`에 결정 날짜를 남긴다.
- 출력 `WishlistTokens.kt`·`WishlistTokens.swift`는 커밋한다. 손으로 고치지 않으며(`GENERATED` 머리), `--check`가 생성물과 원본이 다르면 실패한다. 이름은 두 플랫폼 공통 camelCase다.
- 생성기가 내보내는 이름은 생성기 안의 표(`THEME_FIELDS`, `MOTION_MS`, `MOTION_RATIO`)로 고른다. 화면에 새 값이 필요하면 코드에 리터럴을 쓰지 않고 이 표나 원본 JSON에 더한다.
- 색 입력은 `#RRGGBB`로 검증한다. 짧은 RGB와 alpha 포함 hex는 두 플랫폼 의미가 달라질 수 있어 받지 않는다. CI가 생성기 테스트와 `--check`를 실행한다.

## 이유와 trade-off

- 추가 설치 없이 macOS 기본 Python으로 돌고, Gradle·Xcode 빌드에 끼우지 않아 빌드가 느려지지 않는다. 대신 생성을 잊으면 빌드는 오래된 값으로 성공하므로 `--check`를 검증 명령과 PR 검증에 넣는다.
- 생성물을 커밋하면 Xcode·Android Studio에서 바로 열리고 리뷰에서 값 변화가 보인다. 원본과 생성물이 함께 바뀌어야 하는 비용이 있다.
- 디자인 결정의 사본(`design-tokens.json`)이 생겨 두 곳이 어긋날 수 있다. `source` 날짜로 어느 결정에서 왔는지 추적하고, 결정이 바뀌면 사본을 같이 고친다.
- Style Dictionary 같은 범용 도구는 형식이 정해져 있지만, 이 프로젝트에 필요한 출력(Compose `Color`·`CubicBezierEasing`, SwiftUI `Animation`)은 작고 고정적이라 직접 쓰는 편이 단순하다.

## 재검토 조건

- 핸드오프가 색·간격까지 담은 토큰 파일을 주면 `design-tokens.json`을 없애고 그 파일을 읽는다.
- 토큰 종류가 크게 늘어 생성기 유지 비용이 커지면 범용 도구를 다시 검토한다.

## 관련 문서

- [디자인 시스템과 앱 뼈대](../../../architecture/client/design-system.md)
- [client/README.md 디자인 토큰](../../../../client/README.md)
