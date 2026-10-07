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
- 현재 공통 코드는 두 앱 연결을 확인하는 `AppInfo`·interop probe와 아래 공통 결과·인증 세션 계약을 포함한다. 상품 모델·저장소·화면 비즈니스 기능은 후속 구현 대상이다.

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

## 공통 결과·인증 세션 경계

> 2026-10-07 Task 2a 구현 — 이 절은 결과·세션 gate의 확정 계약이다. token adapter·transport·repository·Presenter 연동은 후속 task에서 검증한다.

- `ClientResult<T>`는 `Success(value)` 또는 `Failure(ClientError)` 한 겹이다. 공통 오류 종류는 `UNAUTHENTICATED`, `SESSION_CHANGED`, `NOT_FOUND`, `CONFLICT`, `VALIDATION`, `RATE_LIMITED`, `NETWORK`, `TIMEOUT`, `SERVER`, `INVALID_RESPONSE`, `UNAVAILABLE`이다. `ClientError`의 `code`, `requestId`, `currentVersion`, `retryAfterSeconds`는 nullable이며 기본값은 null이다.
- `AuthSession.state`는 읽기 전용 `StateFlow<SessionSnapshot>`다. 초기값은 `(accountId=null, generation=0)`이며 account ID는 플랫폼 인증 SDK의 안정 ID다. 서버 owner UUID와 구별한다. 한 runtime의 모든 소비자에는 같은 `AuthSession`을 주입한다.
- `MutableAuthSession.changeAccount` 호출마다 generation을 증가시킨다. 로그인·로그아웃·같은 계정 재로그인·이미 로그아웃한 상태의 세션 변경도 새 세대다. token refresh는 이 메서드를 호출하지 않으므로 세션을 유지한다.
- `withCurrent(snapshot)`는 단일 Mutex 안에서 현재 account ID와 generation을 모두 비교한다. 다르면 operation을 실행하지 않고 `SESSION_CHANGED`를 반환한다. 같으면 operation의 성공·실패 결과를 그대로 반환하며 `CancellationException`을 전파한다. gate를 기다리는 동안 계정이 변경되면 잠금을 획득한 뒤 최신 snapshot으로 다시 비교한다.
- 계정 변경과 짧은 commit은 같은 gate로 직렬화한다. 잠금 순서는 **session gate → FakeStore/DB**다. token callback, network, 지연은 gate 밖에서 수행하고 store/DB commit만 안에서 수행한다. gate 내부에서 `changeAccount`나 `withCurrent`를 다시 호출하지 않는다. store를 잠근 채 session gate를 기다리는 역순도 금지한다.
- 이 계약은 Android 모듈과 후속 Kotlin 조립 코드가 사용할 수 있도록 Kotlin `public`이다. generic suspend `withCurrent`만 `@HiddenFromObjC`로 숨긴다. Swift UI는 후속 구체 model/state와 non-suspend runtime facade를 사용하며 `AuthSession`을 구현하거나 generic 결과를 UI 계약으로 사용하지 않는다. `ClientError`는 후속 구체 state의 오류 필드에도 사용할 수 있다.
- 실행 의존성 계약은 `Clock.now(): kotlin.time.Instant`, `IdGenerator.newId(): String`, `RuntimeDispatchers(default, io)`다. 실제 플랫폼 Clock·UUID·dispatcher 조립은 후속 단계에서 주입한다.

공통 테스트는 초기 상태·계정/세대 변경·읽기 전용 상태 발행·결과 단일 래핑·stale operation 미실행·취소 전파/대기 취소·commit과 계정 변경 순서를 Android host와 iOS simulator에서 같은 suite로 검증한다. 동시성 테스트는 coroutine barrier를 사용하고 실제 시간 대기를 하지 않는다.
