# Kotlin Multiplatform 구조

> 상태: **확정** — UI가 아닌 domain/data/sync 계층을 공유한다.

## 공유 대상

- `WishlistItem`, `Product`, `Category`, 처리 상태 같은 domain model
- Repository interface와 구현
- URL normalization 및 입력 검증 규칙
- API client, 인증 헤더 연결 지점
- local database/cache 및 sync 정책
- Create/Fetch/Update category 같은 UseCase

## 플랫폼별 구현이 필요한 대상

- HTTP engine과 secure storage
- local database driver
- 앱 생명주기, push notification, 네트워크 상태 감지
- Share Extension/Receiver 및 UI

## 선택 이유

동일한 동기화·상태 규칙을 두 번 구현하는 비용을 줄이면서도, iOS와 Android의 native UI 품질과 플랫폼 관례를 유지한다.
