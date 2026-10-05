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

## 초기 구현

- 클라이언트는 `client/` 독립 Gradle build이며 `:android`, `:shared` 모듈로 시작한다.
- shared target은 Android, `iosArm64`, `iosSimulatorArm64`다. Android는 공식 KMP library plugin, iOS는 static `Shared.framework` direct integration을 사용한다.
- 지원 하한은 Android 8(API 26), iOS 17이다.
- 현재 공통 코드는 두 앱 연결을 확인하는 `AppInfo`뿐이다. 위 공유 대상의 비즈니스 기능은 아직 구현하지 않았다.
- DB·HTTP·인증·DI와 Swift 비동기 연결 방식은 기능 도입 시 결정한다.

빌드 명령과 검증 제한은 [client 실행 가이드](../../../client/README.md), 상세 근거와 핸드오프 검토는 [초기 셋업](initial-setup.md)에 정리한다.
