# Client 의사결정

## 초기 결정

- **확정**: iOS UI는 SwiftUI, Android UI는 Jetpack Compose로 각 플랫폼 native UI를 유지한다.
- **확정**: domain model, repository, use case, API client, local cache/sync, URL normalization은 KMP 공유 대상으로 둔다.
- **확정**: iOS Share Extension과 Android `ACTION_SEND` 처리는 플랫폼 native로 구현한다.

## 구현 결정

- [ADR-027: 클라이언트는 계약 우선 fake와 KMP Presenter로 기능 단위 구현한다](ADR-027-client-implementation-strategy.md) — 서버 의존 방식, KMP 스택, 화면 상태 소유, 진행 단위

## 검증 기록

- [iOS 라우터 spike 결과 (2026-10-05)](ios-router-spike-2026-10-05.md) — `NavigationStack` 없는 자체 라우터 진행 판단, 끌어서 뒤로는 window 수준 UIKit pan

추가 결정은 `ADR-번호-제목.md` 형식으로 이 폴더에 기록한다.
