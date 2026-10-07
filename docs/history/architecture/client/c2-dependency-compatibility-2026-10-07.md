# C2 공통 의존성과 Swift ABI 호환성 관문

2026-10-07 Task 1. develop `1c6d949`를 반영한 `client/c2-kmp-core`에서 Kotlin 2.3.21, AGP 9.0.0, Gradle 9.3.0, JDK 17, iOS 17+, static `Shared.framework` direct integration을 유지했다.

## 선택한 버전과 공식 근거

| 의존성 | 최초 후보 → 선택 | 근거와 판단 |
| --- | --- | --- |
| SKIE | 0.10.12 → 동일 | [공식 changelog](https://skie.touchlab.co/changelog/0.10.12)가 Kotlin 2.3.21 지원을 명시. 실제 Swift async/Flow와 Swift 구현 protocol 왕복을 검증했다. |
| Ktor | 3.6.0 → **3.4.3** | [3.6.0 catalog](https://github.com/ktorio/ktor/blob/3.6.0/gradle/libs.versions.toml)와 [3.5.0 catalog](https://github.com/ktorio/ktor/blob/3.5.0/gradle/libs.versions.toml)는 coroutines/serialization 1.11.0. 최초 실제 graph도 1.10.2/1.10.0을 각각 1.11.0으로 올렸다. [3.4.3 catalog](https://github.com/ktorio/ktor/blob/3.4.3/gradle/libs.versions.toml)는 Kotlin 2.3.0, coroutines 1.10.2, serialization 1.9.0으로, 고정된 coroutines를 유지하는 최신 이전 안정 버전을 채택했다. |
| serialization | 1.10.0 → 동일 | [공식 release](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.10.0)는 Kotlin 2.3.0 기반. plugin은 프로젝트 Kotlin 2.3.21과 맞추고 `@Serializable` 생성 코드를 실제 사용했다. |
| SQLDelight | 2.4.1 → 동일 | [공식 catalog](https://github.com/sqldelight/sqldelight/blob/2.4.1/gradle/libs.versions.toml)는 Kotlin 2.3.10 기반. plugin과 JDBC/Android/Native driver, 생성 SQL query를 이 프로젝트 baseline에서 검증했다. upstream 빌드 AGP와 consumer 지원 범위를 동일시하지 않는다. |
| Koin DSL | 4.2.2 → 동일 | [공식 catalog](https://github.com/InsertKoinIO/koin/blob/4.2.2/projects/gradle/libs.versions.toml)는 Kotlin 2.3.20, coroutines 1.10.2. module 생성·실제 resolve를 host/Native에서 실행했다. |
| coroutines | 1.10.2 → 동일 | [공식 release](https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.10.2). commonMain core, commonTest test, Android test를 통일했다. Compose가 요청하던 1.9.0도 최종 graph에서 1.10.2로 해석됐다. |
| Turbine | 1.2.1 → 동일 | [공식 catalog](https://github.com/cashapp/turbine/blob/1.2.1/gradle/libs.versions.toml)의 coroutines 1.10.2와 일치. `runTest` 안의 `test`/`awaitItem`을 실행했다. |
| OkHttp / MockWebServer | 5.5.0 → **5.3.2** | 최종 Ktor OkHttp engine의 resolved OkHttp 5.3.2에 테스트 서버 버전을 맞췄다. |

Ktor 3.6.0 자체의 compile/link 실패는 없었다. 배제 이유는 coroutines 1.10.2 유지 조건이다. 3.5.0은 공식 dependency 선언만 확인했고 별도 compile 성공을 주장하지 않는다. 서버 Ktor 버전과 클라이언트 버전 일치를 요구하지 않는다.

## 실제 사용 관문

임시 독립 프로젝트에서 DTO encode, Ktor content negotiation/JSON 및 MockEngine 요청, OkHttp/Darwin factory, SQL schema 생성·query·세 driver, Koin module/resolve, coroutines `runTest`와 Turbine을 사용했다. 선언만 해석한 결과와 구분해 두 버전 조합의 Android host/Native compile, KMP test, simulator Debug framework와 device Release framework, Android 앱 compile을 실행했다. 임시 소스는 제품 tree에 포함하지 않는다.

영구 `InteropProbe`와 `PlatformTokenSource`를 남겨 후속 Task의 ABI 기준으로 사용한다. (Task 9에서 `InteropProbe`를 삭제했다. Flow/suspend/close 보장은 실제 `ItemDetailPresenter`·runtime으로, callback ABI는 REMOTE runtime + Swift token source로 `SharedInteropTests`가 유지한다.) `invokeToken`은 Swift provider를 직접 호출하며 suspend token adapter를 만들지 않는다. token adapter의 늦은 완료/중복/취소 의미론은 Task 6a 범위다. 실제 Firebase SDK는 C3/C4 인증 연결에서 다룬다. Flow/suspend는 SKIE를 사용하고 Ktor·SQLDelight·Koin은 `implementation`으로 두어 framework에 의존성 전체를 export하지 않는다.

Swift 테스트를 먼저 추가해 미정의 protocol로 compile RED를 확인한 뒤 probe를 구현했다. 생성 header에서 `fetchToken(forceRefresh:completion:)`, `complete(token:errorCode:)`, `invokeToken(source:completion:)`을 확인했다. Flow는 0→1, Swift 수집 task 취소 후 값 변경 미수집, suspend 반환, close 후 `CancellationError`, Swift token 성공과 `TOKEN_FAILED` 오류를 검증한다. 후속 Presenter도 이 경계를 유지해야 한다.

Framework bundle ID는 `app.wishlist.shared`로 명시한다. [SKIE의 공식 analytics 설정](https://skie.touchlab.co/Analytics)에 따라 `analytics.enabled=false`로 수집/업로드를 비활성화했다. SQLDelight plugin은 root에서 버전을 고정하고, 실제 database가 생기는 Task 7에서 shared에 적용한다.

## 검증 결과와 로그

실행 명령·테스트 건수·RED/GREEN 원문·dependency graph·spike source snapshot은 로컬 `.superpowers/sdd/2026-10-07-client-c2-kmp-core/task-1-report.md`와 같은 폴더의 `task-1-evidence/`에 보존한다. 이 경로는 gitignore 대상이며 아래 기록은 커밋에 남는 요약이다.

- ABI RED: XCTest build exit 65, 미정의 PlatformTokenSource/TokenCallback/TokenRequest.
- ABI GREEN: 전체 XCTest 71건, interop 5건 포함, 실패 0.
- 최초 후보 Ktor 3.6.0 조합: host 2건/Native 1건과 Android 앱 compile, 양 target compile, simulator Debug/device Release framework 통과. 이때 serialization/coroutines의 실제 해석은 1.11.0이다.
- 선택 버전 actual-use spike: Android host **4건**, iOS simulator **4건**, 실패/오류/skip **0**. 양 Native compile, simulator Debug/device Release framework, Android 앱 compile 통과.
- 제품 tree 최종 회귀: Android Debug **59건**/Release **59건**, shared Android host **2건**/iOS simulator **2건**, 실패/오류/skip **0**. 두 framework link 통과. 테스트 task의 `NO-SOURCE`/`SKIPPED`를 통과 건수로 세지 않았다.

최초 spike의 SQL column `value`는 생성 Kotlin에서 `value_`로 노출됐다. 테스트의 잘못된 참조를 수정한 뒤 통과했으며 의존성 실패로 분류하지 않았다. 기존 Gradle 10 deprecation 경고와 SKIE의 configuration-time dependency resolution 경고는 기능 실패가 아니다. 초기 bundle ID 추론 경고는 명시 설정으로 해결했다. SLF4J NOP 경고는 Gradle에서 Android 단위 테스트 실행 중 발생하며, 경고를 숨기기 위한 제품 logger 의존성은 추가하지 않았다.
