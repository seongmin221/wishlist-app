# Client 의사결정

## 초기 결정

- **확정**: iOS UI는 SwiftUI, Android UI는 Jetpack Compose로 각 플랫폼 native UI를 유지한다.
- **확정**: domain model, repository, use case, API client, local cache/sync, URL normalization은 KMP 공유 대상으로 둔다.
- **확정**: iOS Share Extension과 Android `ACTION_SEND` 처리는 플랫폼 native로 구현한다.

추가 결정은 `ADR-번호-제목.md` 형식으로 이 폴더에 기록한다.
