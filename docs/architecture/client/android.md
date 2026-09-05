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
