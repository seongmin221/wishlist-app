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

## 구현 구조

> 2026-10-05 결정 — [ADR-027](../../history/architecture/client/ADR-027-client-implementation-strategy.md), [구현 로드맵 설계](../../superpowers/specs/2026-10-05-client-implementation-roadmap-design.md)

| 영역 | 선택 |
| --- | --- |
| HTTP·JSON | Ktor client(OkHttp / Darwin), kotlinx.serialization |
| local DB | SQLDelight |
| DI | Koin(shared 모듈 + 플랫폼 모듈) |
| Swift 연결 | SKIE(Flow·suspend → AsyncSequence·async). 미지원 시 KMP-NativeCoroutines |
| 인증 | 플랫폼별 Firebase 공식 SDK가 KMP `AuthTokenProvider` 구현 |
| 설정 저장 | multiplatform-settings |
| 테스트 | kotlin.test, Turbine, Ktor MockEngine |

`shared`는 Gradle 모듈 하나로 두고 패키지로 경계를 나눈다.

```text
core/  model/  data/remote/  data/local/  data/fake/  repository/  domain/  presentation/
```

- 의존 방향은 `presentation → domain → repository(interface) ← data(remote | fake | local)`이다.
- `presentation`의 Presenter(`StateFlow<State>` + intent)가 화면 데이터, 로딩·오류, 편집 초안과 dirty 여부, 연속 처리 진행, 409 복구 상태를 소유한다.
- `repository`는 서버 API ID 단위 인터페이스다. Fake와 Remote 구현을 Koin과 debug flavor/scheme으로 고르고, 서버 묶음이 끝나면 API ID 단위로 Remote로 바꾼다. 같은 계약 테스트를 양쪽에 실행한다.
- 서버가 아직 고정하지 않은 필드·오류 코드는 `CONTRACT-PENDING(<API-ID>)`로 표시하고 DTO·매퍼 안에만 둔다.

빌드 명령과 검증 제한은 [client 실행 가이드](../../../client/README.md), 상세 근거와 핸드오프 검토는 [초기 셋업](initial-setup.md)에 정리한다.
