# Client C2 KMP Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** C3 이후 두 플랫폼이 같은 모델·상태 규칙·repository·저장소·Presenter를 사용하도록, 화면 없이 검증 가능한 KMP 핵심을 만든다.

**Architecture:** `:shared` 한 모듈 안에서 `presentation → domain → repository(interface) ← data(remote | fake | local)` 경계를 둔다. 서버 응답의 상태·허용 행동은 Remote의 권위이며, 같은 정책을 domain 테스트와 Fake에서 재현한다. 플랫폼은 engine·DB driver·token 공급·Presenter 수명만 연결한다.

**Tech Stack:** Kotlin 2.3.21, AGP 9.0.0, Gradle 9.3.0, Ktor client(OkHttp/Darwin), kotlinx.serialization, SQLDelight, Koin DSL, SKIE, kotlin.test/coroutines-test/Turbine/MockEngine.

**Spec:** [확정 로드맵](../specs/2026-10-05-client-implementation-roadmap-design.md), [KMP 구조](../../architecture/client/kmp.md), [상품 상태·API 계약](../../architecture/wishlist-item-state-api.md), [B1 조회 계약](../../architecture/server/wishlist-item-read-api.md).

## Global Constraints

- 상태: **승인 v4 · Task 1~5 검증 완료, 중간 확인 대기 (2026-10-07)**. 아래 제안 인터페이스와 기본값은 구현 계획이며 서버 신규 계약을 확정하지 않는다. 사용자 승인에 따라 Task 1부터 실행하며 Task 5 뒤 중간 확인을 받는다.
- 브랜치 `client/c2-kmp-core`, PR base `develop`. 시작 HEAD: `e884d14fcd59be026886b2056f3e0e377a5c627f`.
- Kotlin **2.3.21**, Android **API 26+**, iOS **17+**, JDK **17**. `iosArm64`/`iosSimulatorArm64`, static `Shared.framework` direct integration 유지. 공유 코드에 Compose/SwiftUI 의존성 없음.
- 서버 최신 확인(2026-10-07 13:48 KST): B1 PR [#8](https://github.com/seongmin221/wishlist-app/pull/8)와 B2 PR [#9](https://github.com/seongmin221/wishlist-app/pull/9) merged. B2 브랜치 `origin/seongmin221/server-b2-category-management` tip `3df880e`, 최신 `origin/develop`은 `1c6d949081d47ddb28e60c00eda44b4aa0d91fb0`이며 열린 PR은 없다. B2 PR #9 mergedAt은 13:31:42 KST. 과거 시작 HEAD는 위 기록을 유지하며, Task 1 실행 전에 `origin/develop` `1c6d949081d47ddb28e60c00eda44b4aa0d91fb0` 위로 승인된 rebase를 완료했다(실행 기준 계획 commit `dd62150`). 이 commit의 서버 계약을 기준으로 실행한다.
- 서버 category 기준: B2 [PublicCategoryRegistry](https://github.com/seongmin221/wishlist-app/blob/3df880e/server/src/main/kotlin/app/category/PublicCategoryRegistry.kt)와 [registry test](https://github.com/seongmin221/wishlist-app/blob/3df880e/server/src/test/kotlin/app/category/PublicCategoryRegistryTest.kt)에서 상위 `G001`~`G011`, leaf `C001`~`C087`를 확인했다. custom은 UUID. B2 [category 계약](https://github.com/seongmin221/wishlist-app/blob/3df880e/docs/architecture/server/category-management-api.md)의 상품 mapper와 `CategoryDtos.kt`에서 nullable `name/parentId/kind`도 모델에 보존한다. CAT-01~04 서버 완료와 C2의 ITEM-01·03 Remote 범위를 구별한다.
- C2 첫 구현 작업은 **SKIE Kotlin 2.3.21 연결 검증**. 실패 원인을 분리한 뒤 미지원이면 KMP-NativeCoroutines 대안을 비교·보고하고 전환안을 확인한다.
- 모델 축: analysis/review/lifecycle/manualCompletion/categoryMissingReason/value source를 분리한다. 서버 내부 AnalysisJob·lease·예산은 클라이언트 모델로 옮기지 않는다.
- 같은 로컬 공유 재전송은 같은 UUID key. 같은 URL의 새로운 공유는 새 key. accountBinding이 다른 로컬 대기는 숨긴다.
- 갱신은 신규 실행·foreground·사용자 새로고침. polling·오프라인 편집 큐·push·환율 환산 없음.
- SQL 캐시는 상품 snapshot 저장에 사용한다. viewport·anchor 범위는 후속 인계 표를 따른다.
- 미확정 wire 필드·오류는 `CONTRACT-PENDING(<API-ID>)`를 **DTO/매퍼**에만 둔다. domain은 JSON·HTTP를 참조하지 않는다.
- domain·Presenter 상태 전이·Fake/Remote 계약은 TDD. `commonTest`를 Android host와 iOS simulator에서 실제 실행하며 `NO-SOURCE`/`SKIPPED`를 통과로 기록하지 않는다.
- KMP 커밋은 `feature(kmp): 한글 설명` + 빈 줄 + 짧은 본문. 연결된 GitHub 이슈가 생기면 `[#번호]` 접두사. 문서만 바꾸면 `docs`. PR 번호를 이슈 번호로 대신 넣지 않는다.
- **C1 가격 인계로 C2에 포함:** Android/iOS PriceText·가격 demo 입력을 공유 formatter로 이관한다. C1 토큰·모션·탭 셸은 유지하고 신규 화면은 후속 인계 표를 따른다.

## Review Focus

1. **계정 전환 중 응답:** 공통 AuthSession의 snapshot을 사용하고, 같은 계정 재로그인도 generation이 다르면 이전 응답을 버린다. 이전 데이터는 새 캐시/Presenter에 섞이지 않는다(Task 2a·5·6a·7·9).
2. **정밀 금액·반올림 경계:** JSON number를 Double로 거치지 않고 KRW/JPY 0, USD 2, KWD 3, CLF 4자리와 HALF_UP·정수 소수 생략을 두 플랫폼에서 같게 표시한다. null/blank 가격·통화의 숨김 규칙을 검증한다(Task 3·6b).
3. **응답 유실·프로세스 중단:** key 재전송과 로컬 transaction rollback에서 URL을 잃거나 서버 항목을 중복 생성하지 않는다(Task 5·7).
4. **서버 schema 진화·잘못된 오류 body:** 추가 필드 허용, unknown allowedActions 제거, unknown analysis/requiredAction은 UNKNOWN과 서버가 허용한 DELETE만 유지, unknown lifecycle만 INVALID_RESPONSE. 잘못된 오류 body도 crash하지 않는다(Task 6a·6b).
5. **취소·동시 refresh:** close된 Presenter와 늦은 이전 요청은 새 상태를 덮어쓰지 않고 CancellationException을 일반 실패로 바꾸지 않는다(Task 1·6a·9).

## 선택한 결정과 실행 확인

| 번호 | 결정 사항 | 선택한 안 | 대안·영향 |
| --- | --- | --- | --- |
| D1 | 가격 domain 표현을 정확한 decimal 문자열 값 객체로 둘까? | `DecimalAmount`가 정규화된 십진 문자열을 보관; wire는 JSON number. formatter는 문자열/정수 자리 계산으로 HALF_UP. ISO minor-unit 표를 출처와 함께 공유 코드에 고정. 가격 null/통화 null·blank면 표시하지 않음(사용자 동의, 기존 앞 공백 버그 수정). 상한·극단값 정책은 서버 metadata precision 확정 때 정함 | 외부 decimal 라이브러리: 연산 확장에는 좋지만 Native·ObjC framework 노출 의존성 추가. 현재는 가격 계산/환산이 없어 값 객체가 작음 |
| D2 | C2 fake의 완성 범위를 어디까지 둘까? | 생성·상세·상품 상태 전이·version CAS·idempotency·계정 격리와 시드 조회까지. 확장 범위는 후속 인계 표 참조 | 전체 37개 Fake 선행: C2가 후속 계약·제품 결정을 앞당기고 커짐. 목적/아카이브 규칙 선행은 미확정 계약을 고정할 위험이 있어 제외 |
| D3 | C2에서 B1 Remote를 얼마나 연결할까? | ITEM-01·03의 실제 DTO/mapper/Remote + MockEngine 계약 테스트까지. 앱 debug 기본은 Fake; 인증·실서버 연결은 후속 인계 표 참조 | 실서버 연결까지: 인증 환경·공유 화면 없는 C2가 환경 준비에 묶임 |
| D4 | API별 Fake/Remote를 어떻게 선택할까? | `RepositoryBindings(buildMode, backends: Map<ApiId,Backend>)`; FAKE/REMOTE/UNAVAILABLE을 API마다 명시. RELEASE의 FAKE는 구성 오류. C2 release 앱은 Firebase provider 연결 전이므로 37개 API 모두 UNAVAILABLE | repository 전체 단위 전환은 ITEM-01·03만 먼저 전환하기 어려움 |
| D5 | 모델의 ID·목적 key는 어떤 타입으로 공개할까? | ID는 Swift에 바로 전달 가능한 `String`, UUID는 생성·요청 경계에서 검사. key는 raw string 보존 + 알려진 목록; 색 `coral,mustard,periwinkle,cyan,mint,pink`, 아이콘 `heart,home,plane,gift,tent,music,star,book` | value class/닫힌 enum은 강한 타입 대신 Swift 노출·서버 새 key 처리가 복잡. rendering fallback은 UI 단계에서 지정 |
| D6 | 구현 실행 방식은? | 로드맵 기본인 subagent-driven; task별 구현·리뷰, 공유 인터페이스 변경은 순서대로 | native: 현재 세션에서 직접 구현하고 마지막 독립 리뷰. 비용이 작지만 task별 독립 검토 없음 |
| D7 | C2 LocalStore 범위를 어디까지 확정할까? | 앱 단일 프로세스 schema 초안·기본 transaction·계정 격리·version 비교 item upsert·조건부 캐시 제거·accept 원자성. 확장 범위는 후속 인계 표 참조 | C2에서 공유·전송·window 저장 API까지 고정하면 미결정 계약을 앞당김 |
| D8 | 서버의 모르는 enum 값을 어떻게 처리할까? | unknown action만 버림; 설명용 review/source/missingReason은 서버 행동을 유지. analysis/requiredAction UNKNOWN이면 서버가 허용한 DELETE만 유지. unknown lifecycle은 단건 실패; 목록 정책은 후속 인계 표 참조 | 핵심 enum 모두 실패는 상태 추가만으로 상세를 막음. 모두 허용은 잘못된 변경 위험 |

D1~D8은 사용자 의견을 반영한 선택이다. 전체 계획 확인 뒤 subagent 방식으로 실행하고 Task 5 뒤 중간 확인을 받는다. 실행 기본값 D9는 아래에 표시하며, 확장 범위는 문서 끝 인계 표에서 관리한다.

**D9 — 실행 기본값:** 사용자 실행 승인에 따라 권장안을 적용한다. 통화는 trim·대문자화 후 `[A-Z]{3}` 형식만 표시하며 `???`는 숨긴다. 미등록 `XYZ`는 소수 2자리 fallback을 유지한다.

## 파일 구조와 책임

아래 `S`는 `client/shared/src/commonMain/kotlin/app/wishlist/shared`, `T`는 `client/shared/src/commonTest/kotlin/app/wishlist/shared`다. 이하 task의 파일 약어도 이 경로를 뜻한다.

| 경로 | 책임 |
| --- | --- |
| `S/core/ClientResult.kt`, `ClientError.kt`, `RuntimeDependencies.kt`, `AuthSession.kt`, `ApiId.kt` | typed 결과/오류, 공통 계정·세대 snapshot, API ID enum, Clock·UUID·dispatcher |
| `S/model/ItemState.kt`, `WishlistItem.kt`, `Category.kt`, `Purpose.kt`, `Archive.kt`, `LocalSubmission.kt`, `DecimalAmount.kt` | HTTP/플랫폼 독립 데이터와 snapshot |
| `S/domain/ItemPolicy.kt`, `PriceFormatter.kt`, `CurrencyMinorUnits.kt` | 상태 projection·허용 행동, 정확한 가격 표기 |
| `S/repository/ItemRepository.kt`, `CatalogRepository.kt`, `LocalStore.kt` | 현재 C2의 API ID별 경계와 로컬 원자 저장 계약 |
| `S/data/fake/FakeStore.kt`, `FakeItemRepository.kt`, `FakeCatalogRepository.kt`, `FakeControls.kt`, `BoardSeeds.kt` | 계정별 원자 메모리 저장, 제어 가능한 분석·지연·실패, 보드 시드 |
| `S/core/PlatformTokenSource.kt`, `S/data/remote/AuthTokenProvider.kt`, `CallbackAuthTokenProvider.kt`, `WishlistHttpClient.kt`, `ApiErrorMapper.kt`, `PriceJsonReader.kt`, `ItemDtos.kt`, `ItemMapper.kt`, `RemoteItemRepository.kt` | token·Ktor·공개 오류·B1 실제 wire 계약 |
| `client/shared/src/commonMain/sqldelight/app/wishlist/shared/db/Wishlist.sq` | LocalSubmission·계정별 item 캐시 schema 초안과 query |
| `S/data/local/SqlLocalStore.kt`, `CachedGetItemRepository.kt`, `DriverFactory.kt` | SQLDelight transaction·플랫폼 driver·GET 캐시 동기화 |
| `S/di/SharedModules.kt`, `RepositoryBindings.kt`, `SharedRuntime.kt` | API별 DI 선택·공개 Swift 진입점·자원 수명 |
| `S/presentation/Presenter.kt`, `ItemDetailPresenter.kt`, `ItemDetailState.kt` | C4 상품 상세로 확장할 조회/retry/세션/수명 기반 |
| Android/iOS main, iosMain, androidHostTest, iosTest | engine·driver·시계·UUID 구현과 플랫폼 통합 테스트 |

`ProductSnapshot`은 `WishlistItem.kt`에 정의하며 서버 공용 Product cache repository는 만들지 않는다. 시드 이미지 견본 색/비율은 `BoardSeeds.kt`의 별도 fixture metadata; domain 모델·UI 색 토큰에 넣지 않는다.

## 검증 명령 약어

모든 Gradle 명령은 `client/`에서 다음 환경을 적용한다. Xcode build phase는 부모 JAVA_HOME을 물려받는다. `build-shared.sh`는 이미 JAVA_HOME이 없을 때 `java_home -v 17`을 사용한다. 아래 JAVA_HOME은 이 맥에서 사용한 **환경 예시**다. 실행 기기의 JDK 17 경로로 바꾼다. 올바른 환경을 전달해도 script 문제가 재현될 때만 수정하며, 기기 절대 경로를 repository 기본값으로 하드코딩하지 않는다.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew -Porg.gradle.java.installations.paths="$JAVA_HOME" :shared:testAndroidHostTest :shared:iosSimulatorArm64Test
```

위 두 task를 **KMP_TEST**라 부른다. 각 TDD task에서 먼저 대상 test를 실패시키고 RED 원인을 확인한 뒤 GREEN을 얻는다. 계획의 test helper(`fixture`, `successValue`, `failureKind`, `enqueueSuccess` 등)는 commonTest harness에서 정의한다. host 필터를 쓰는 RED 명령 예: `:shared:testAndroidHostTest --tests "*ItemPolicyTest*"`. Native 필터 호환성에 의존하지 않고 GREEN 때 `:shared:iosSimulatorArm64Test`로 전체 commonTest를 실행한다. task별 테스트 이름과 전체 Native 결과를 모두 기록한다. `:shared:allTests`만 보고 두 runtime 실행을 추정하지 않는다.

**기기 예시:** 현재 이 맥의 iPhone 17 Pro / iOS 26.5 UDID는 `AFBA9C17-206B-4EA6-A508-EF6E0CE2D7B0`. 실행 시 다시 목록을 확인하고 대상 UDID를 선택한다. 새 기기를 쓸 경우 boot 완료 후 앱 접근성을 켠다.

```sh
xcrun simctl spawn <udid> defaults write com.apple.Accessibility ApplicationAccessibilityEnabled -bool true
xcodebuild test -project client/ios/Wishlist.xcodeproj -scheme Wishlist \
  -destination 'platform=iOS Simulator,id=<udid>' \
  -derivedDataPath client/ios/DerivedData CODE_SIGNING_ALLOWED=NO
```

## Task 1: SKIE와 후보 의존성 전체 호환성 관문

**Files:** Modify `client/gradle/libs.versions.toml`, `client/build.gradle.kts`, `client/shared/build.gradle.kts`, `client/ios/Wishlist.xcodeproj/project.pbxproj`; Create `S/core/PlatformTokenSource.kt`, 임시 `S/presentation/InteropProbe.kt`; Test `client/ios/WishlistTests/SharedInteropTests.swift`; Record `docs/history/architecture/client/c2-dependency-compatibility-2026-10-07.md`와 INDEX.

| 만들 것 | 검증할 것 | 이 task에서 하지 않을 것 |
| --- | --- | --- |
| SKIE 양방향 ABI probe, 모든 후보의 임시 compile/link spike, 고정 버전·근거·dependency graph | Swift Flow/suspend 수집·취소, Swift 구현→Kotlin callback 성공 1건/오류 1건, 모든 후보 host/Native compile·framework link | Firebase SDK·token callback의 늦은 완료/중복/취소 의미론(Task 6a 소유) |

```kotlin
interface TokenCallback { fun complete(token: String?, errorCode: String?) }
interface TokenRequest { fun cancel() }
interface PlatformTokenSource {
    fun fetchToken(forceRefresh: Boolean, completion: TokenCallback): TokenRequest
}
// InteropProbe: val state: StateFlow<Int>; suspend fun increment(): Int; fun close()
// ABI 방향 확인용 non-suspend 진입점:
// fun invokeToken(source: PlatformTokenSource, completion: TokenCallback): TokenRequest
```

```swift
// 정확한 protocol spellings는 생성 framework header로 확인한다.
final class TestTokenSource: NSObject, PlatformTokenSource {
    func fetchToken(forceRefresh: Bool, completion: any TokenCallback) -> any TokenRequest {
        completion.complete(token: "test-token", errorCode: nil)
        return TestTokenRequest()
    }
}
final class TestTokenRequest: NSObject, TokenRequest { func cancel() {} }
// XCTest: Kotlin invokeToken이 Swift source를 호출하고 전달한 observer가 token을 받음.
// 별도 source는 complete(token: nil, errorCode: "TOKEN_FAILED")를 호출해 오류 ABI 확인.
```

- [x] **Step 1: 기준 갱신·공식 근거.** 최신 develop을 작업 브랜치에 병합한다. 충돌이 있으면 문서·C1 기준을 유지해 해결한다. [SKIE 0.10.12](https://skie.touchlab.co/changelog/0.10.12)의 Kotlin 2.3.21 지원을 기록하고 이 버전부터 검증한다. 다른 후보의 공식 Kotlin/Gradle/Native 지원과 선택 이유도 기록한다.
- [x] **Step 2: ABI RED.** Swift Flow 초기0/increment 후1·suspend·수집 취소·close와 callback 성공/오류 XCTest를 먼저 작성해 미정의 API로 compile FAIL 확인. callback 방향 검증은 `invokeToken`의 직접 위임으로 충분하며 suspend adapter를 probe에 구현하지 않는다.
- [x] **Step 3: shared coroutines 설정.** commonMain의 coroutines-core와 commonTest의 coroutines-test를 **1.10.2**로 추가하고 Android test도 통일한다. Android debug/releaseRuntimeClasspath `:android:dependencies`와 debug `:android:dependencyInsight --dependency kotlinx-coroutines --configuration debugRuntimeClasspath` 결과를 기록하고 catalog 주석을 갱신한다. Android Debug/Release 단위 테스트로 변경 회귀를 확인한다.
- [x] **Step 4: 전체 후보 해석·spike.** Ktor **3.6.0**(core/content-negotiation/serialization-json/auth 없이 MockEngine·OkHttp·Darwin), serialization **1.10.0**, SQLDelight **2.4.1**(plugin/JDBC/Android/Native driver), Koin DSL **4.2.2**, Turbine **1.2.1**, coroutines **1.10.2**를 후보로 같은 baseline에서 해석한다. MockWebServer는 resolved OkHttp와 같은 버전. 임시 별도 checkout에 작은 `@Serializable` DTO, Ktor engine factory, SQL schema·query, Koin module, commonTest `runTest`/Turbine 호출을 두고 host/Native compile·KMP_TEST·simulator/device framework link를 실행한다. Android driver는 앱 compile까지 확인한다. 성공 버전은 catalog·근거 표로 고정하고 임시 spike 소스는 제거한다.
- [x] **Step 5: 실제 interop 실행.** 선택한 의존성과 최소 probe로 simulator XCTest, `:shared:linkDebugFrameworkIosSimulatorArm64`, `:shared:linkReleaseFrameworkIosArm64` PASS 확인. SQLDelight/HTTP/DI 의존성은 공유 구현 내부에 두고 Swift에는 필요한 facade만 export한다.
- [x] **Step 6: 실패 분류·대안.** 의존성 artifact/compiler/Gradle/engine 문제와 JDK/Xcode 환경 문제를 분리한다. SKIE 실제 미지원이면 [KMP-NativeCoroutines](https://github.com/rickclephas/KMP-NativeCoroutines)의 Kotlin 2.3.21용 Kotlin/Swift 버전을 맞춰 동일 Flow/suspend probe로 비교하고 전환안을 보고한다. 선택 대기 중 **Task 8의 Swift ready 수집과 Task 9의 Swift Flow/suspend 연결만 보류**한다. Task 3 가격 일반 함수와 비suspend callback ABI는 ObjC framework로 검증하며 계속 진행한다. 순수 Kotlin 후보 전체는 SKIE를 비활성화한 spike에서도 확인할 수 있다. Kotlin/AGP baseline 변경은 별도 결정으로 보고한다.
- [x] **Step 7: 기록·커밋.** 버전별 해석/host/Native/framework 실행 결과와 남은 interop 문제를 기록한다. Task 5 확인 전에 모든 Kotlin 후보 호환성 결과가 있어야 한다. `feature(kmp): 공통 의존성과 Swift 연결 호환성 검증`.

**Task 1 완료 근거:** [호환성 기록](../../history/architecture/client/c2-dependency-compatibility-2026-10-07.md). Ktor 3.6.0 후보는 실제 compile/link 통과 후 coroutines 1.10.2 유지 조건으로 배제했고 3.4.3을 채택했다. SKIE 0.10.12, serialization 1.10.0, SQLDelight 2.4.1, Koin 4.2.2, Turbine 1.2.1, OkHttp/MockWebServer 5.3.2 고정. Swift XCTest 71건(interop 5건), Android 59/59건, shared host/simulator 2/2건, 선택 spike 4/4건 통과. 이미 develop `1c6d949` 반영된 작업 tree에서 실행했으며 추가 rebase는 하지 않았다. SKIE 실패가 없어 Step 6의 대안 전환·후속 task 보류는 적용하지 않는다.

## Task 2a: 공통 결과·인증 세션과 commit gate

**Files:** Create `S/core/ClientResult.kt`, `ClientError.kt`, `RuntimeDependencies.kt`, `AuthSession.kt`; Test `T/core/AuthSessionTest.kt`.

| 만들 것 | 검증할 것 | 이 task에서 하지 않을 것 |
| --- | --- | --- |
| ClientResult/ClientError, Clock·UUID·dispatcher 계약, MutableAuthSession과 단일 commit gate | 세대 증가·refresh 유지, stale operation 미실행, 성공/실패 결과 단일 래핑, commit과 계정 변경 순서 | 모델·상태 정책(Task 2b), gate 안의 network/token fetch |

```kotlin
sealed interface ClientResult<out T> {
    data class Success<T>(val value: T): ClientResult<T>
    data class Failure(val error: ClientError): ClientResult<Nothing>
}
data class ClientError(val kind: ErrorKind, val code: String?, val requestId: String?,
    val currentVersion: Int?, val retryAfterSeconds: Long?)
data class SessionSnapshot(val accountId: String?, val generation: Long)
interface AuthSession {
    val state: StateFlow<SessionSnapshot>
    suspend fun <T> withCurrent(snapshot: SessionSnapshot,
        operation: suspend () -> ClientResult<T>): ClientResult<T>
}
// MutableAuthSession: suspend fun changeAccount(accountId: String?)
```

ErrorKind: UNAUTHENTICATED/SESSION_CHANGED/NOT_FOUND/CONFLICT/VALIDATION/RATE_LIMITED/NETWORK/TIMEOUT/SERVER/INVALID_RESPONSE/UNAVAILABLE. accountId는 플랫폼 인증 SDK의 안정 ID다. 서버 owner UUID는 별도 식별자다. 모든 소비자는 **동일 AuthSession**을 주입받는다. `withCurrent`는 stale이면 operation 미실행·SESSION_CHANGED, current이면 operation 결과를 한 겹으로 반환한다. lock 순서는 session gate→FakeStore/DB이며 짧은 commit만 직렬화한다. login/logout/같은 계정 재로그인은 세대 증가, token refresh는 유지한다. `withCurrent`와 changeAccount는 Kotlin 내부 계약이며 Swift에는 non-suspend runtime facade를 노출한다.

| 비교 지점 | 소유자 | 정확한 조건·검증 |
| --- | --- | --- |
| 쓰기 commit | FakeStore·LocalStore | `withCurrent(snapshot)` 안에서 session gate→store/DB 잠금. snapshot 불일치면 쓰기 operation 미실행. callback/network/delay는 gate 밖 |
| 요청 전송 | CallbackAuthTokenProvider·AuthenticatedTransport | token callback 완료 시 Kotlin adapter가 snapshot 확인. 최초 전송과 401 재전송 직전에 transport가 동일 snapshot 확인. 이전 body에 새 세션 token 사용 금지; 이미 전송된 이전 요청은 그 계정 작업이며 되돌리지 않음 |
| 상태 발행 | Presenter·repository 결과 반환 | repository는 결과 반환 직전 snapshot 확인, Presenter는 state 발행 직전 snapshot+request ID 확인. session 변경 시 이전 state 제거·진행 작업 취소. 같은 계정 재로그인도 세대가 달라 폐기 |

```kotlin
@Test fun current_gate_returns_operation_result_without_nesting() = runTest {
    val result: ClientResult<Int> = session.withCurrent(session.state.value) {
        ClientResult.Success(7)
    }
    assertEquals(ClientResult.Success(7), result)
}
```

- [x] **Step 1: 실패 테스트.** 초기 null 계정, A→B/A→logout→A 세대 증가, refresh 유지, stale operation 미실행. operation Success와 Failure를 그대로 반환하고 CancellationException을 전파한다. gate 동안 changeAccount와 commit의 직렬 순서 검증.
- [x] **Step 2: RED.** KMP_TEST에서 AuthSessionTest 미정의 API 실패 확인.
- [x] **Step 3: 구현.** 공통 결과·오류·의존성 계약과 Mutex 기반 세션 gate 구현. Swift UI는 후속 구체 state를 사용한다.
- [x] **Step 4: GREEN.** KMP_TEST에서 성공·실패 한 겹 결과와 세션 경계 통과.
- [x] **Step 5: 문서·커밋.** kmp.md에 확정 세션 경계를 기록. `feature(kmp): 공통 인증 세션과 결과 계약 구현`.

## Task 2b: 모델·API ID·상태 정책

**Files:** Create `S/core/ApiId.kt`, `S/model/` 파일, `S/domain/ItemPolicy.kt`; Test `T/domain/ItemPolicyTest.kt`, `T/model/ModelInvariantTest.kt`, `T/core/ApiIdTest.kt`.

| 만들 것 | 검증할 것 | 이 task에서 하지 않을 것 |
| --- | --- | --- |
| WishlistItem 상태 축·서버 행동, Category/Purpose/Archive/LocalSubmission snapshot, DecimalAmount.parseOrNull, ApiId 37개 | 독립 상태표·UNKNOWN 정책·API ID 누락/중복·B2 category snapshot | 가격 formatter(Task 3), HTTP DTO(Task 6b) |

```kotlin
data class ItemPolicy(val requiredAction: RequiredAction, val allowedActions: Set<ItemAction>)
// fun evaluateItem(item: WishlistItem): ItemPolicy
// fun homeActionGroup(action: RequiredAction): HomeActionGroup?
// fun isListEligible(item: WishlistItem): Boolean
// DecimalAmount companion: fun parseOrNull(value: String): DecimalAmount?
```

WishlistItem은 서버 requiredAction/allowedActions를 직접 보관한다. Fake만 evaluateItem으로 값을 채우며 Remote는 mapper의 서버 값을 사용한다. homeActionGroup은 requiredAction의 INFORMATION_COMPLETION/CATEGORY_ASSIGNMENT/CATEGORY_REASSIGNMENT를 같은 그룹으로 projection한다. 목록 자격은 ACTIVE·이름/category 존재, 분기용 UNKNOWN이면 false. 시각은 kotlin.time.Instant, wire/ObjC 경계는 ISO 문자열. Category는 raw ID·name·parentId·kind와 종류별 nullable version을 보관한다(공용 ID는 UUID가 아니다). item.category도 B2의 nullable name/parentId/kind를 포함한다. Archive는 identity·표시 snapshot 모델만, LocalSubmission은 확정 계약 필드를 보관한다.

ApiId는 ITEM_01..08/HOME_01..02/DUP_01..02/CAT_01..06/PUR_01..08/ARC_01..09/MEDIA_01..02 총37개와 wireId. AnalysisStatus는 PROCESSING/READY/PARTIAL/FAILED_RETRYABLE/FAILED_TERMINAL/UNKNOWN, ReviewStatus는 NOT_REQUIRED/PENDING/CONFIRMED/DEFERRED/UNKNOWN, lifecycle은 ACTIVE/ARCHIVED/DELETED. RequiredAction/ValueSource/CategoryMissingReason은 알려진 계약+UNKNOWN. 양수 item/custom version·category가 있으면 missingReason=null·USER purpose=null 허용. 설명용 UNKNOWN은 서버 known action 유지, 분기용 UNKNOWN은 서버 목록의 DELETE만 유지한다.

```kotlin
@Test fun extraction_unresolved_precedes_review() {
    val item = fixture(analysis = PARTIAL, name = "상품", categoryId = null,
        missingReason = EXTRACTION_UNRESOLVED, review = PENDING)
    assertEquals(INFORMATION_COMPLETION, evaluateItem(item).requiredAction)
}
```

- [x] **Step 1: 실패 테스트.** 독립 기대값 출처는 `server/src/test/kotlin/app/wishlist/WishlistItemPolicyTest.kt`와 `server/src/main/kotlin/app/wishlist/WishlistItemPolicy.kt`; 실행 시 Task 1의 병합 기준 commit 기록. 비ACTIVE→NONE/행동없음; PROCESSING→ANALYSIS_IN_PROGRESS/DELETE만; blank 이름 및 EXTRACTION_UNRESOLVED→INFORMATION_COMPLETION; AI_ABSTAINED/AI_RESPONSE_UNUSABLE→CATEGORY_ASSIGNMENT; CUSTOM_CATEGORY_DELETED→CATEGORY_REASSIGNMENT; category/reason null legacy→INFORMATION_COMPLETION; 이후 PENDING→CLASSIFICATION_REVIEW, DEFERRED 미재노출. PARTIAL+이름/category 목록 포함, 수동 완료의 analysis 보존·추가 행동 없음, READY 재지정 EDIT. home projection은 별도 입력표.
- [x] **Step 2: RED.** KMP_TEST에서 ModelInvariantTest/ItemPolicyTest/ApiIdTest 실패.
- [x] **Step 3: 구현.** 모델·enum·ApiId와 Fake policy/홈 projection. DecimalAmount plain decimal parseOrNull은 malformed/nonfinite에 null 반환. 테스트 fixture는 대응 필드에 직접 값을 넣고 production evaluator를 기대값 생성에 사용하지 않는다.
- [x] **Step 4: GREEN.** KMP_TEST에서 서버 행동과 Fake evaluator·홈 projection 역할 검증.
- [x] **Step 5: 문서·커밋.** kmp.md 확정 모델 경계 갱신. `feature(kmp): 상품 모델과 상태 정책 구현`.

## Task 3: 가격 domain과 정확한 문자열 플랫폼 입력

**Files:** Modify `S/model/DecimalAmount.kt`; Create `S/domain/PriceFormatter.kt`, `CurrencyMinorUnits.kt`; Test `T/domain/PriceFormatterTest.kt`; Modify 양 플랫폼 PriceText·가격 tests·DemoContent 가격 호출부.

| 만들 것 | 검증할 것 | 이 task에서 하지 않을 것 |
| --- | --- | --- |
| 정확한 plain decimal formatter·ISO minor-unit 표와 두 플랫폼 PriceText 문자열 입력 | HALF_UP·정수 소수 생략·null/blank·미등록 코드 fallback·D9 형식 검사 | Double/Swift Decimal 변환·서버 가격 쓰기 |

PriceFormatter는 nullable 문자열을 받아 nullable 표시 문자열을 반환한다. DecimalAmount.parseOrNull은 plain decimal 값을 보존하며 malformed/nonfinite에 null을 반환한다.

```kotlin
// Task 2b 모델 API를 사용. parsing/formatting은 예외를 Swift에 던지지 않는다.
fun formatPrice(amount: DecimalAmount?, currency: String?): String?
class PriceFormatter {
    fun formatOrNull(amount: String?, currency: String?): String?
}
```

가격 일반 함수는 SKIE 선택과 독립적으로 ObjC framework를 통해 Swift에서 호출한다.

- [x] **Step 1: 실패 테스트.** `549000/KRW→KRW 549,000`, `299/USD→USD 299`, `19.995/USD→USD 20`, `19.9/USD→USD 19.90`, `19.5/JPY→JPY 20`, `1.2345/KWD→KWD 1.235`, `1.23456/CLF→CLF 1.2346`. price null/currency null/blank→null; malformed decimal→null. 미등록 `" xyz "`는 `XYZ`·2자리로 표시. `" ??? "`는 D9 권장안에 따라 null. Swift wrapper는 일반 문자열 fixture와 malformed 입력을 확인한다.

```kotlin
@Test fun whole_amount_omits_fraction() {
    assertEquals("USD 299", formatPrice(DecimalAmount.parseOrNull("299.00"), "USD"))
}
@Test fun blank_currency_hides_price() {
    assertNull(formatPrice(DecimalAmount.parseOrNull("1.20"), "  "))
}
```

- [x] **Step 2: RED.** KMP_TEST에서 PriceFormatterTest 실패 확인.
- [x] **Step 3: 구현.** ISO minor-unit 표를 [SIX](https://www.six-group.com/en/products-services/financial-information/data-standards.html) 출처·조회 날짜와 함께 공유 코드에 고정한다. 통화는 trim/uppercase 후 `[A-Z]{3}` 검사(D9), unknown/미정의 unit은 2, 문자열 기반 HALF_UP/grouping, 정수면 소수 생략. 서버 B1은 price/currency를 아직 저장하지 않아 실제 응답은 null이며 nonnull 테스트는 formatter/미래 metadata 계약용 fixture임을 기록한다.
- [x] **Step 4: 플랫폼 입력 이관.** Android PriceText와 iOS PriceText는 `amountText:String?`, `currency:String?`를 직접 받는다. shared DecimalAmount의 canonical을 넘기고 기존 demo 가격도 문자열로 바꾼다. 플랫폼 formatter/ISO cache 삭제, nullable 공통 결과가 없으면 가격 view를 표시하지 않는다. 한 줄/두 줄 레이아웃 유지.
- [x] **Step 5: GREEN.** KMP_TEST·Android 가격 tests·iOS PriceFormatTests. Swift 잘못된 문자열에서 null과 앱 생존 확인.
- [x] **Step 6: 문서·커밋.** null/blank 규칙·가격 책임을 design-system.md에 기록, `feature(kmp): 가격 표기를 공통 도메인으로 통합`.

## Task 4: repository 경계와 검증 가능한 보드 시드

**Files:** Create `S/repository/ItemRepository.kt`, `CatalogRepository.kt`, `S/data/fake/BoardSeeds.kt`; Test `T/data/fake/BoardSeedsTest.kt`, `T/repository/RepositoryContractTest.kt`; Create `docs/architecture/client/server-integration-status.md`.

| 만들 것 | 검증할 것 | 이 task에서 하지 않을 것 |
| --- | --- | --- |
| Create/Get/Catalog seed 인터페이스·BoardSeeds·BoardDisplayMetadata·공통 계약 harness | 8개 보드 상위/7개 목적·B2 안정 category ID·참조 유효성·metadata와 membership 분리 | 후속 wire 목록·count를 맞추기 위한 더미 상품 |

```kotlin
data class CreateItemCommand(
    val submissionId: String, val sourceUrl: String, val clientCreatedAt: Instant?
)
interface CreateItemRepository {
    suspend fun create(command: CreateItemCommand): ClientResult<WishlistItem>
}
interface GetItemRepository {
    suspend fun get(id: String): ClientResult<WishlistItem>
}
interface CatalogRepository {
    suspend fun categories(): ClientResult<List<Category>>
    suspend fun purposes(): ClientResult<List<Purpose>>
    suspend fun items(categoryId: String?, purposeId: String?): ClientResult<List<WishlistItem>>
}
data class BoardDisplayMetadata(
    val categoryChipCounts: Map<String, Int>,
    val purposeCandidateCounts: Map<String, Int>
)
```

```kotlin
@Test fun board_counts_are_separate_from_membership() {
    val seeds = BoardSeeds.create(fixedClock, fixedIds)
    assertEquals(7, seeds.purposes.size)
    assertEquals(0, seeds.items.count { it.purpose.id == seeds.carrierId })
    assertTrue(seeds.displayMetadata.purposeCandidateCounts.containsKey(seeds.commuteId))
}
```

BoardSeeds.create의 반환 `BoardSeedData`는 categories/purposes/items/displayMetadata/carrierId/commuteId를 제공한다. displayMetadata는 Fake query 입력으로 사용하지 않는다.

- [x] **Step 1: 실패 테스트.** 시드에 보드 상위 카테고리 8개·목적 7개(빈 carrier 유지); 모든 category/purpose 참조 유효, 안정된 UUID item/purpose·공용 taxonomy category ID; 같은 now/ID 공급자면 동일 결과. B2 taxonomy `G001`~`G011`/`C001`~`C087`와 BROWSE 보드의 8개를 구분한다. 기준은 원격 `3df880e`의 PublicCategoryRegistryTest와 category-management-api.md이며 실행 시 병합 기준으로 재확인한다.
- [x] **Step 2: RED.** KMP_TEST.
- [x] **Step 3: 구현.** Android/iOS DemoContent·FCategoryHome/FCategoryList/FPurposeHome/FPurposeDetail을 대조해 중복 l/c 상품을 stable ID로 통합한다. 카드별 다른 사진 색/비율은 presentation fixture로 분리. 보드의 category chip count와 purpose candidate count는 `BoardDisplayMetadata`에 그대로 보관하는 **이미지 비교용 fixture 값**이다. 실제 Fake query의 count는 저장된 membership만 집계하며 이 metadata를 읽지 않는다. 보드 숫자를 맞추기 위한 더미 상품은 만들지 않는다. 두 count의 일치는 C2 완료 조건이 아니다.
- [x] **Step 4: 계약 harness 준비.** `RepositoryContractTest`는 factory가 제공한 같은 시나리오를 두 backend에 실행한다. fixture 생성/분석 제어는 test harness에만 있고 production repository 메서드에 노출하지 않는다. contract input에 owner context·동일 key·201/200 의미·404/tombstone을 넣는다.
- [x] **Step 5: GREEN·추적.** KMP_TEST. integration-status에 API 37개를 빠짐없이 등록, C단계·서버 B단계·Fake/Remote/MockEngine/실서버 검증을 별도 열로 기록. C2 실제 대상과 미구현을 구분한다.
- [x] **Step 6: 커밋.** `feature(kmp): 저장소 계약과 보드 기반 시드 추가`.

## Task 5: Fake 상태 전이와 경쟁·원자성 규칙

**Files:** Create `S/data/fake/FakeStore.kt`, `FakeItemRepository.kt`, `FakeCatalogRepository.kt`, `FakeControls.kt`; Test `T/data/fake/FakeItemRepositoryTest.kt`, `FakeItemStateTransitionTest.kt`와 Task 4 계약 harness.

| 만들 것 | 검증할 것 | 이 task에서 하지 않을 것 |
| --- | --- | --- |
| FakeStore가 단독 소유하는 상태 전이·CAS·idempotency와 위임하는 FakeControls/repository | 동일/새 key·owner 격리·generation 경합·삭제 tombstone·version CAS | 목적 삭제·archive/restore (인계 표) |

FakeStore(session:AuthSession)의 비즈니스 연산을 repository와 FakeControls가 위임한다. Fake delay 후 Task 2a의 withCurrent→Store commit 순서를 따른다. patch는 Unchanged/Set(value)로 누락/null을 구분한다.

```kotlin
data class AnalysisOutcome(
    val status: AnalysisStatus, val name: String?, val categoryId: String?,
    val missingReason: CategoryMissingReason?, val failureCode: String?
)
interface FakeControls {
    suspend fun completeAnalysis(
        id: String, analysisGeneration: Int, result: AnalysisOutcome
    ): ClientResult<Unit>
    fun failNext(apiId: ApiId, error: ClientError)
    fun delayNext(apiId: ApiId, millis: Long)
}
// FakeStore의 상품 연산은 item+expectedVersion을 입력받고 ClientResult<WishlistItem> 반환.
// delete는 ClientResult<Unit>, 재분석은 attemptRequestId를 별도 key로 받는다.
```

```kotlin
@Test fun new_key_creates_new_item_for_same_url() = runTest {
    val first = repository.create(command(key1, url)).successValue()
    val replay = repository.create(command(key1, url)).successValue()
    val other = repository.create(command(key2, url)).successValue()
    assertEquals(first.id, replay.id)
    assertNotEquals(first.id, other.id)
}
```

`successValue()`는 commonTest helper로만 정의하며 실패면 test assertion을 발생시킨다.

- [x] **Step 1: 실패 테스트.** 같은 owner/key/URL→같은 ID, 다른 URL→409 IDEMPOTENCY_KEY_REUSED, 같은 URL/새 key→새 ID; replay는 최초 clientCreatedAt 보존·최신 상태 반환; 다른 owner GET→404; A→B 및 A→logout→A 도중 지연된 Fake 응답/쓰기→SESSION_CHANGED; 삭제 tombstone은 replay되지만 GET은404. PROCESSING 변경 거절/삭제만, version CAS 실패·동시 edit 한 건만 성공, 수동 완료 후 retry 거절/분석상태 보존, DEFERRED 미재노출.
- [x] **Step 2: RED.** KMP_TEST.
- [x] **Step 3: 핵심 구현.** UUID·Clock 주입, 최초 analysisGeneration 1, 최종 분석/사용자 변경은 version 증가; analysisGeneration N+1 후 N 결과·삭제 후 늦은 결과 무시. 자동 분석 진행·wall-clock sleep 대신 명시적 제어와 test scheduler.
- [x] **Step 4: 상태 전이 GREEN.** KMP_TEST와 Fake contract suite.
- [x] **Step 5: 문서·커밋.** integration-status에 fake 규칙과 API 공개 구현을 구별해서 적고 `feature(kmp): 가짜 저장소의 상태와 재전송 규칙 구현`.

## Task 5 이후 사용자 확인 지점

**실행 결과 (2026-10-07):** Task 1·2a·2b·3·4·5는 구현과 task별 독립 리뷰(spec PASS / quality Approved)를 완료했다. SKIE 0.10.12의 Kotlin 2.3.21 Flow/suspend 및 Swift 구현 → Kotlin callback 호출을 실제 검증했고, 선택 의존성은 [호환성 기록](../../history/architecture/client/c2-dependency-compatibility-2026-10-07.md)에 고정했다. 최종 commonTest는 Android host·iOS simulator 각각 81개(공통 Fake 계약 7개 포함)가 실패·오류·skip 없이 실행됐다. 가격 플랫폼 이관 시 Android Debug/Release 각각 62개와 Swift 전체 73개도 통과했으며, Task 5 simulator framework link를 확인했다. Task 4~5 이후 플랫폼 전체 테스트를 다시 실행한 결과로 해석하지 않는다. 기존 Gradle configuration/deprecation 경고는 남아 있다.

시드는 상위 8개/세부 30개 분류·목적 7개·중복 통합 상품 8개이며 보드 count와 실제 membership은 분리한다. 공용 taxonomy에 없는 세 분류는 custom UUID다. Fake는 현재 세션을 통한 명시적 seed 투입, 생성·상세·상태 전이·CAS·idempotency·계정 격리를 검증했다. 통합 범위는 [서버 연동 현황](../../architecture/client/server-integration-status.md)에 기록했다. 새 Fake API의 Swift 호출·Remote·실서버 연결은 아직 검증하지 않았고, Task 6a~10은 중간 확인 후 진행한다.

Task 1~5의 Kotlin 모델·가격·시드·Fake 산출물, 실제 테스트 결과, 미검증 Swift 경계를 제시한다. 사용자 확인 전 Task 6a~10은 시작하지 않는다. 이 중간 확인은 큰 PR의 후반 설계 재작업을 줄이기 위해 사용자가 요청한 절차다. 브랜치/PR은 C2 한 개를 유지한다. interop 대안이 미결정이어도 Task 2a~5의 Kotlin 부분까지 진행할 수 있다.

## Task 6a: transport·세션 인증 재시도·오류 envelope

**Files:** Uses Task 1의 `S/core/PlatformTokenSource.kt`; Create `S/data/remote/AuthTokenProvider.kt`, `CallbackAuthTokenProvider.kt`, `WishlistHttpClient.kt`, `ApiErrorMapper.kt`; 플랫폼 `PlatformHttpEngine.kt`; Test `T/data/remote/HttpClientTest.kt`, `ApiErrorMapperTest.kt`, `client/shared/src/androidHostTest/kotlin/app/wishlist/shared/data/remote/OkHttpRedirectTest.kt`.

| 만들 것 | 검증할 것 | 이 task에서 하지 않을 것 |
| --- | --- | --- |
| Kotlin callback token adapter·명시적 401 retry·오류 mapper·Ktor/engine 설정 | 늦은/중복 callback·취소·세션 전환·401 한 번·오류 envelope·실제 OkHttp redirect | token 캐시·Swift suspend interface 구현·network/5xx POST application retry |

CallbackAuthTokenProvider(session,source)는 suspendCancellableCoroutine으로 callback을 감싸며 취소 시 TokenRequest.cancel, 늦은/중복 callback은 무시한다. Swift는 token/error callback만 공급한다. AuthenticatedTransport는 session/tokens/client를 주입받고 Task 2a의 전송/반환 지점을 사용한다.

```kotlin
interface AuthTokenProvider {
    suspend fun getToken(
        snapshot: SessionSnapshot, forceRefresh: Boolean
    ): ClientResult<String>
}
// CallbackAuthTokenProvider(session: AuthSession, source: PlatformTokenSource)
// AuthenticatedTransport(session: AuthSession, tokens: AuthTokenProvider, client: HttpClient)
// AuthenticatedTransport의 Kotlin 내부 메서드 (Ktor 타입은 Swift에 export하지 않음):
// suspend fun execute(snapshot: SessionSnapshot, apiId: ApiId,
//     buildRequest: HttpRequestBuilder.() -> Unit): ClientResult<HttpResponse>
// 최초 전송/401 retry에 같은 snapshot과 동일하게 재구성한 body/key를 사용.
```

```kotlin
@Test fun session_change_during_refresh_does_not_resend_old_body() = runTest {
    val request = async { transport.create(commandForAccountA) }
    tokenSource.awaitRefreshStarted()
    session.changeAccount("account-b")
    tokenSource.completeRefresh("token-b")
    assertEquals(SESSION_CHANGED, request.await().failureKind())
    assertEquals(1, mockEngine.requestHistory.size) // 최초401만, 두 번째 POST 없음
}
```

이 테스트의 `transport.create`는 request harness가 ITEM-01 requestBuilder를 구성하는 test helper다. 실제 repository는 Task 6b에서 연결한다.

- [x] **Step 0: 고정 결과 대조.** Task 1의 Ktor/serialization/OkHttp·MockWebServer 선택 결과·compile/link 로그와 catalog를 대조한다. 이 task에서 새 호환성 조사를 시작하지 않는다.
- [x] **Step 1: 실패 테스트.** Kotlin fake PlatformTokenSource의 성공/오류·동기 callback·늦은/중복 완료·취소 시 TokenRequest.cancel 정확히 한 번을 commonTest에서 검증. Bearer 부착,401→forceRefresh 한 번→같은 URL/body/key 재시도, 두 번째401/token 없음/refresh 실패 종료. A의 token fetch·refresh·response 중 B로 전환 또는 A 재로그인→SESSION_CHANGED, 새 계정 token으로 옛 body 재전송 없음. 요청 취소는 전파. 오류 code/requestId·429 Retry-After·timeout/연결실패/500/unknown code/nonJSON 오류 body 안전 매핑. **B1 IDEMPOTENCY_KEY_REUSED는 currentVersion=null**.
- [x] **Step 2: RED.** KMP_TEST.
- [x] **Step 3: 구현.** Ktor Auth/Bearer plugin의 token cache를 사용하지 않고 AuthenticatedTransport의 명시적 request/retry 함수로 구현한다. 동일 origin `/v1` 요청에만 인증; 네트워크/5xx POST 자동 retry 없음. OkHttp의 native `retryOnConnectionFailure`는 기본값 true를 유지한다. 끊긴 keep-alive 등의 연결 복구와 Ktor의 응답 이후 application retry를 구별하며, GET/Idempotency-Key가 있는 ITEM-01은 native 복구에도 같은 요청·key를 유지한다. request 30초/connect 10초/socket 30초는 시작 기본값이며 engine별 지원 옵션·실제 적용을 기록한다. Error envelope `error.code/requestId/details`와 X-Request-ID를 매핑. `error.details.currentVersion`의 **위치는 상품 상태 API 계약에서 확정**되어 있다. 값은 nullable이며 B1 IDEMPOTENCY_KEY_REUSED에는 없다. **어떤 후속 mutation 오류 code가 이 필드를 포함하는지**만 오류 DTO/매퍼에 `CONTRACT-PENDING(ITEM-04/ITEM-07/ITEM-08)`로 표시한다.
- [x] **Step 4: redirect 설정과 실제 engine 검증.** Ktor `followRedirects=false`, OkHttp native client의 `followRedirects=false` 및 `followSslRedirects=false`를 고정한다. 확인한 **Ktor 3.4.3 소스에서는 이미** [OkHttp 기본 설정](https://github.com/ktorio/ktor/blob/3.4.3/ktor-client/ktor-client-okhttp/jvm/src/io/ktor/client/engine/okhttp/OkHttpConfig.kt)이 두 값을 false로 두고, [Darwin 기본 delegate](https://github.com/ktorio/ktor/blob/3.4.3/ktor-client/ktor-client-darwin/darwin/src/io/ktor/client/engine/darwin/KtorNSURLSessionDelegate.kt)는 redirect callback에 `completionHandler(null)`을 호출한다. Darwin에는 별도 followRedirects Boolean이 없으므로 기본 delegate의 차단을 유지하고 불필요한 custom delegate를 추가하지 않는다. 실제 채택 버전이 다르면 그 tag 소스를 다시 확인한다. C2는 **선택(a)**: Android host의 OkHttp MockWebServer 두 대가 302와 redirect target을 담당하고 실제 OkHttp에서 target 요청 수0을 확인한다. 포트는 각 MockWebServer의 동적port URL을 사용하며 별도 외부 프로세스 없음. Darwin은 채택 tag의 delegate 소스 근거를 기록한다. 실제 검증 범위는 인계 표를 따른다. MockEngine은 application 오류/401 테스트만 담당한다. 3xx는 typed INVALID_RESPONSE로 처리. 테스트 fixture에서만 localhost 접근을 사용하며 production base URL 정책과 구분한다.
- [x] **Step 5: GREEN·커밋.** KMP_TEST와 실제 OkHttp host redirect 테스트. Darwin은 소스 검토만 기록. token refresh 취소·session 변경·종료 자원 검증. `feature(kmp): 세션 인증과 HTTP 오류 처리 구현`.

## Task 6b: B1 DTO·mapper·Remote와 공통 계약

**Files:** Create `S/data/remote/ItemDtos.kt`, `ItemMapper.kt`, `PriceJsonReader.kt`, `RemoteItemRepository.kt`; Test `T/data/remote/ItemMapperTest.kt`, `RemoteItemRepositoryTest.kt` 및 Task 4 계약 harness.

| 만들 것 | 검증할 것 | 이 task에서 하지 않을 것 |
| --- | --- | --- |
| B1+B2 상품 DTO·읽기 전용 price mapper·Remote Create/Get | 서버 행동 보존·필드별 UNKNOWN·공통 Fake/Remote 계약·B2 category snapshot | category Remote API·금액 serialize |

```kotlin
// ItemMapper.kt
// fun mapItem(dto: WishlistItemDto): ClientResult<WishlistItem>
// PriceJsonReader.kt
// fun readPrice(value: JsonElement?): ClientResult<DecimalAmount?>
// RemoteItemRepository(session: AuthSession, transport: AuthenticatedTransport)
//     : CreateItemRepository, GetItemRepository
```

```kotlin
@Test fun descriptive_unknown_keeps_server_actions() {
    val item = mapItem(dtoFixture(reviewStatus = "NEW_REVIEW_VALUE",
        allowedActions = listOf("EDIT", "DELETE", "NEW_ACTION"))).successValue()
    assertEquals(setOf(EDIT, DELETE), item.allowedActions)
}
@Test fun branching_unknown_preserves_server_delete_only() {
    val item = mapItem(dtoFixture(analysisStatus = "NEW_ANALYSIS_VALUE",
        allowedActions = listOf("EDIT", "DELETE"))).successValue()
    assertEquals(setOf(DELETE), item.allowedActions)
}
```

추가로 서버 action 목록에 DELETE가 없으면 빈 set을 기대한다.

- [x] **Step 0: 선행 산출물 확인.** Task6a의 고정 dependency·transport·세션 계약 사용. DTO fixture의 기준 commit과 API ID를 기록한다.
- [x] **Step 1: 실패 테스트.** Task 1 병합 기준의 B1+B2 fixture(상품 category.name/parentId/kind 포함)의201/replay200/GET/404/ARCHIVED/삭제 tombstone·nullable metadata·clientCreatedAt·공개 failure code. raw decimal 정확성/JSON string number 거절. 서버 requiredAction/allowedActions가 유지되고 evaluator로 덮어쓰지 않음. 추가 필드는 허용. unknown allowedActions 제거; unknown analysis.status 또는 requiredAction→UNKNOWN과 서버 목록에 존재하는 DELETE만 유지, 나머지 상세 데이터 유지; unknown lifecycleStatus→단건 INVALID_RESPONSE. unknown review/source/missingReason은 UNKNOWN 보존·서버가 준 알려진 행동 유지, unknown failureCode는 일반 실패 안내로 매핑. required 필드 누락/잘못된 JSON 타입도 INVALID_RESPONSE.
- [x] **Step 2: RED.** KMP_TEST.
- [x] **Step 3: 구현.** `server/src/main/kotlin/app/http/WishlistItemDtos.kt`, `WishlistItemViewMapper.kt`, `WishlistRoutes.kt`, `DecimalJsonSerializer.kt`와 B2 `CategoryDtos.kt`의 독립 JSON fixture를 사용. raw enum 문자열은 mapper에서 필드별로 해석한다. 요청 ownerId 없음, 데이터 source를 보존. normal/설명용 UNKNOWN 응답의 행동은 서버 권위다. 분기용 UNKNOWN은 DELETE만 남기되 서버가 허용하지 않은 DELETE를 새로 만들지 않는다. Swift UI에는 구체 model/state만 노출.
- [x] **Step 4: 공통 계약 실행.** Fake와 Remote(MockEngine)에 같은 생성/replay/상세/404/tombstone 시나리오 실행. Fake를 호출해 Remote fixture 정답을 만들지 않는다. session 전환/동일 계정 재로그인 stale 응답 거절도 양쪽 검증. 후속 mutation의 검증 수준은 Fake 규칙 테스트로 표시한다.
- [x] **Step 5: GREEN·커밋.** KMP_TEST, 실서버 검증 수준 표시. `feature(kmp): 상품 API 모델과 저장소 계약 연결`.

## Task 7: SQLDelight 최소 schema·계정 격리·accept 원자성

**Files:** Create 위 `Wishlist.sq`·`S/repository/LocalStore.kt`·`S/data/local/` 파일(포함 `CachedGetItemRepository.kt`); 플랫폼 driver; Test `T/data/local/LocalStoreContractTest.kt`, `CachedGetItemRepositoryTest.kt`, Android host/iosTest driver harness; Modify catalog/shared build.

| 만들 것 | 검증할 것 | 이 task에서 하지 않을 것 |
| --- | --- | --- |
| SQL schema·계정별 LocalStore·CachedGetItemRepository·accept transaction | reopen/rollback·계정 격리·version skip/replace·조건부 제거·GET cache 동기화 | 다중 프로세스·pending 전송·window/settings (인계 표) |

SqlLocalStore(session:AuthSession,driver:SqlDriver)의 accept는 현재 세션에 이미 binding된 pending만 받는 transaction primitive다. 호출자의 임의 accountId 대신 AuthSession을 사용한다.

```kotlin
interface LocalStore {
    suspend fun saveSubmission(submission: LocalSubmission): ClientResult<Unit>
    suspend fun pending(): ClientResult<List<LocalSubmission>>
    suspend fun upsertItem(snapshot: SessionSnapshot, item: WishlistItem): ClientResult<Unit>
    suspend fun cachedItem(snapshot: SessionSnapshot, id: String): ClientResult<WishlistItem?>
    suspend fun accept(snapshot: SessionSnapshot, submissionId: String,
        item: WishlistItem): ClientResult<Unit>
    suspend fun removeCachedItem(snapshot: SessionSnapshot, id: String,
        throughVersion: Int): ClientResult<Unit>
    suspend fun clearCurrentCache(): ClientResult<Unit>
}
```

```kotlin
@Test fun late_not_found_does_not_remove_newer_cache() = runTest {
    store.upsertItem(sessionA, item(version = 7))
    store.upsertItem(sessionA, item(version = 8))
    store.removeCachedItem(sessionA, itemId, throughVersion = 7)
    assertEquals(8, store.cachedItem(sessionA, itemId).successValue()!!.version)
}
```

GET 캐시 동기화는 이 task의 CachedGetItemRepository가 소유한다. Task 9 Presenter는 GetItemRepository만 소비한다.

```kotlin
// S/data/local/CachedGetItemRepository.kt
// CachedGetItemRepository(delegate: GetItemRepository, localStore: LocalStore,
//     session: AuthSession) : GetItemRepository
// override suspend fun get(id: String): ClientResult<WishlistItem>
```

CachedGetItemRepository는 요청 시작 snapshot을 캡처하고 `cachedItem(snapshot,id)`에서 version을 읽은 뒤 delegate GET을 실행한다. 성공은 upsert, NOT_FOUND는 관찰한 version 이하만 제거, 캐시가 없었으면 no-op. 일반 오류는 기존 캐시를 유지한다. 조회 결과는 delegate 응답을 사용한다. B2의 category 표시명은 item version 증가 없이 바뀔 수 있으므로 같은 version 캐시를 건너뛰어도 호출자는 최신 응답의 표시명을 받는다. 처리 순서는 snapshot→cache read→snapshot 확인→delegate→cache write→동일 snapshot 확인→반환. 캐시 read/write 실패는 ClientError로 반환하고 취소는 전파한다. delegate는 같은 AuthSession을 주입받으며 세션이 바뀌면 wrapper가 결과와 cache commit을 거절한다.

```kotlin
@Test fun cached_repository_owns_success_and_not_found_sync() = runTest {
    delegate.enqueueSuccess(item(version = 7))
    cachedRepository.get(itemId)
    assertEquals(7, store.cachedItem(sessionA, itemId).successValue()!!.version)
    delegate.enqueueNotFound()
    cachedRepository.get(itemId)
    assertNull(store.cachedItem(sessionA, itemId).successValue())
}
```

- [x] **Step 0: 고정 결과 대조.** Task 1의 SQLDelight plugin·JDBC/Android/Native driver 버전과 schema 생성·compile/link 결과를 사용한다.
- [x] **Step 1: 실패 테스트.** 파일 DB close/reopen 후 pending 유지, 다른 accountBinding의 pending/cache 숨김, 비로그인에서는 미귀속 pending만 노출. `accept`의 upsert 후 오류 주입→pending/cache 모두 rollback; 성공→cache upsert+pending 삭제. stale snapshot·같은 account의 다른 generation·미귀속/다른 binding의 accept는 거절. 낮은 version은 skip, 같은 version도 C2 cache 정책에 따라 skip, 더 높은 version만 replace. 404 관찰 당시 version 또는 tombstone version 이하 cache만 제거하고 그 사이 도착한 최신 version은 보존한다. saveSubmission도 현재 snapshot과 일치하는 binding만 허용한다. decorator의 성공/404/일반 오류·늦은404와 새 version 경합·계정 변경·캐시 실패 계약을 별도 suite로 검증한다.
- [x] **Step 2: RED.** JDBC SQLite host와 Native SQLite simulator에서 같은 suite. Android Context driver는 별도 플랫폼 smoke test.
- [x] **Step 3: 구현.** LocalSubmission UUID primary key, item cache는 account+item key, decimal/Instant는 정확한 text. session gate→DB transaction으로 commit과 세션 변경을 직렬화한다. cache clear/remove는 pending 보존, token 저장 없음. CachedGetItemRepository의 단일 동기화 흐름을 함께 구현한다. GET 404는 조회 시작 때 캡처한 cached version을 throughVersion으로 넘기고, DELETED replay는 tombstone version을 넘긴다. throughVersion 이하만 삭제하므로 늦은404가 최신cache를 지우지 않는다. accept에서 DELETED replay는 캐시 삭제+pending 삭제를 한 transaction으로 처리한다. C2 driver는 주입 가능한 앱 sandbox의 단일 프로세스로 검증한다.
- [x] **Step 4: GREEN.** reopen/rollback/계정 격리/version/404·tombstone 제거 suite, driver close, 생성 schema 검증. 첫 client schema 생성 결과를 기록한다.
- [x] **Step 5: 문서·커밋.** D7 범위와 transaction/decorator 계약을 기록한다. `feature(kmp): 계정별 로컬 캐시와 원자 저장 기반 구현`.

## Task 8: Koin·명시적 API backend·앱 조립

**Files:** Uses `S/core/ApiId.kt`(Task 2b); Create `S/di/SharedModules.kt`, `RepositoryBindings.kt`, `SharedRuntime.kt`; Test `T/di/SharedModulesTest.kt`; 앱 초기화·iOS `WishlistApp.swift`/project 수정; Create Android debug `app/wishlist/android/di/DebugSessionBootstrap.kt`, iOS `Wishlist/Debug/DebugSessionBootstrap.swift`(`#if DEBUG`).

| 만들 것 | 검증할 것 | 이 task에서 하지 않을 것 |
| --- | --- | --- |
| ApiId→Backend 전수 map, isolated KoinApplication·SharedRuntime facade, 앱별 buildMode/debug bootstrap | 37개 전수 지정, RELEASE의 FAKE 거절/전부 UNAVAILABLE 허용, cached get 조립·자원 close | API backend를 build mode에서 암묵적으로 추론, RELEASE Fake binding |

```kotlin
enum class ClientBuildMode { DEBUG, RELEASE }
enum class Backend { FAKE, REMOTE, UNAVAILABLE }
data class RepositoryBindings(
    val buildMode: ClientBuildMode, val backends: Map<ApiId, Backend>
)
// SharedRuntime: fun startDebugSession(); val ready: StateFlow<Boolean>; fun close()
// createItemRepository()/getItemRepository()/catalogRepository()/localStore() concrete facade
```

```kotlin
@Test fun release_rejects_any_fake_binding() {
    val bindings = ApiId.entries.associateWith { Backend.UNAVAILABLE } +
        (ApiId.ITEM_03 to Backend.FAKE)
    assertFailsWith<IllegalArgumentException> {
        createRuntime(RepositoryBindings(RELEASE, bindings))
    }
}
@Test fun release_all_unavailable_is_valid() {
    val bindings = ApiId.entries.associateWith { Backend.UNAVAILABLE }
    val runtime = createRuntime(RepositoryBindings(RELEASE, bindings))
    assertEquals(Backend.UNAVAILABLE, runtime.resolvedBackend(ApiId.ITEM_03))
    runtime.close()
}
```

`createRuntime`/`resolvedBackend`는 DI test helper다. ApiId 전수 key 누락은 오류, RELEASE+FAKE는 하나라도 오류다. REMOTE를 지정한 미구현 API도 구성 오류로 처리해 명시적 UNAVAILABLE로 선택하게 한다. C2 release 앱은 **37개 모두 UNAVAILABLE**이며 runtime ready=true 이후 요청도 UNAVAILABLE을 반환한다. DEBUG는 C2 구현 API만 FAKE, 나머지는 UNAVAILABLE로 전수 지정하고 Remote 계약 테스트에서는 ITEM_01/03만 REMOTE로 바꾼다. catalog seed 조회는 별도 debug facade이며 미구현 CAT/PUR wire API의 Fake 완료로 세지 않는다.

- [x] **Step 0: 고정 결과 대조.** Task 1의 Koin DSL 버전·host/Native/framework 검증 결과를 사용한다.
- [x] **Step 1: 실패 테스트.** 37개 전수 map·누락 오류, backend 개별 선택, RELEASE FAKE 거절/전부 UNAVAILABLE 성공, 미구현 REMOTE 거절. runtime별 격리·동일 AuthSession·client/driver 한 번 close. Get facade가 CachedGetItemRepository를 사용해 UI 없이 cache 동기화 검증.
- [x] **Step 2: RED.** KMP_TEST에서 DI suite 실패.
- [x] **Step 3: 구현.** Android BuildConfig.DEBUG/iOS #if DEBUG가 mode와 map을 전달한다. SharedRuntime는 같은 session/token source/driver/engine으로 Kotlin token adapter와 repository를 생성한다. Get facade는 선택한 delegate를 CachedGetItemRepository로 한 번 감싼다. unavailable delegate도 오류로 cache를 유지한다. RELEASE graph는 Remote(명시 선택 시)/unavailable만 연결하며 검증 대상은 resolved backend다. commonMain의 Fake 코드 포함 여부와는 별개다.
- [x] **Step 4: debug bootstrap.** 양 앱 DebugSessionBootstrap이 runtime.startDebugSession을 호출하고 Kotlin scope가 changeAccount("debug-board-owner")→같은 namespace seed 주입→ready=true 순서를 소유한다. 준비 중 요청은 UNAVAILABLE. RELEASE는 조립 후 ready=true, debug bootstrap 호출 없음. Swift ready 수집은 Task 1의 interop stack 선택 이후 실행한다.
- [x] **Step 5: GREEN·커밋.** KMP_TEST·두 플랫폼 Debug/Release build와 mode 전달·ready·close 테스트. `feature(kmp): API별 backend와 공통 DI 조립 구현`.

## Task 9: 상품 상세 Presenter의 조회·세션·수명 기반

**Files:** Create `S/presentation/Presenter.kt`, `ItemDetailState.kt`, `ItemDetailPresenter.kt`; Test `T/presentation/ItemDetailPresenterTest.kt`; SharedInteropTests.swift를 실제 Presenter로 전환하고 InteropProbe 삭제; Android `ItemDetailPresenterOwner`와 iOS `ItemDetailPresenterOwner.swift` 수명 소유자 및 테스트.

| 만들 것 | 검증할 것 | 이 task에서 하지 않을 것 |
| --- | --- | --- |
| C4가 확장할 ItemDetailPresenter/State의 load·retry·close·계정 전환 기반 | 마지막 요청 승리·취소·세션 초기화·Swift 구체 state와 owner 종료 | Presenter의 캐시 접근, 화면 rendering |

```kotlin
data class ItemDetailState(
    val item: WishlistItem?, val loading: Boolean, val error: ClientError?
)
// ItemDetailPresenter(repository: GetItemRepository, session: AuthSession,
//     dispatcher: CoroutineDispatcher)
// val state: StateFlow<ItemDetailState>
// fun load(id: String); fun retry(); fun close()
```

Presenter의 유일한 상품 조회 의존성은 GetItemRepository다. 캐시 동기화는 Task 7 decorator에 맡긴다. C4는 이 state/Presenter에 상세 intent를 추가한다. Android ViewModel.onCleared와 iOS MainActor @Observable owner가 close를 호출하는 소유 관계도 C4에서 유지한다.

```kotlin
@Test fun account_change_clears_previous_item() = runTest {
    presenter.load(itemId)
    advanceUntilIdle()
    assertNotNull(presenter.state.value.item)
    session.changeAccount("account-b")
    advanceUntilIdle()
    assertNull(presenter.state.value.item)
    assertNull(presenter.state.value.error)
}
```

- [ ] **Step 0: 고정 결과 대조.** Task 1의 coroutines-test/Turbine 버전·host/Native 결과를 사용한다.
- [ ] **Step 1: 실패 테스트.** initial→loading→item, error→retry→item, refresh 일반 오류는 기존 item 유지, NOT_FOUND는 item 제거, B가 먼저 완료하면 A 무시, close 취소/이후 intent 무시. session 계정/세대 변경 즉시 item/error 제거·요청 취소. 늦은 repository 응답도 snapshot/request ID 비교로 폐기. cache 검증은 Task 7 suite로 유지한다.
- [ ] **Step 2: RED.** KMP_TEST에서 Presenter suite 실패.
- [ ] **Step 3: 구현.** SupervisorJob·주입 dispatcher·read-only StateFlow, close idempotent. CancellationException은 전파. state 발행은 Task 2a 표를 따른다. UI/navigation 람다는 state 밖에 둔다.
- [ ] **Step 4: 플랫폼 검증.** Swift에서 구체 item/error 사용 compile, for await 수집/retry/collector 취소/close/계정 전환 XCTest, Android owner 종료 테스트. Task 1 Flow/suspend 검증을 실제 Presenter 또는 repository harness로 유지하고 callback ABI 성공/오류는 독립 harness로 유지한다. Task 6a token 취소 의미론은 commonTest가 담당한다.
- [ ] **Step 5: GREEN·커밋.** KMP_TEST·SharedInteropTests·owner tests. `feature(kmp): 상품 상세 Presenter 기반과 수명 연결 구현`.

## Task 10: 전체 검증·문서·C2 PR

**Files:** Modify `client/README.md`, `docs/architecture/client/{INDEX,kmp,design-system,android,ios}.md`, `server-integration-status.md`, `docs/superpowers/plans/INDEX.md`, 로드맵 C2 행; 의미 있는 결정만 `docs/history/architecture/client/`와 INDEX에 기록. 새로운 기술 질문에 답한 경우만 `docs/learning/client/q-and-a/` 기록·INDEX 갱신.

| 만들 것 | 검증할 것 | 이 task에서 하지 않을 것 |
| --- | --- | --- |
| 최종 로컬 검증·architecture/INDEX/로드맵·develop 대상 draft PR | Task 1 의존성 결과 대조·실행 건수/fail/skip·문서 링크·diff | merge·최종 단계에서 최초 호환성 조사 |

- [ ] **Step 1: 최종 의존성 대조.** Task 1에서 검증하고 후속 Step 0에서 대조한 catalog/dependency graph·Kotlin/AGP/Gradle baseline·공식 근거 기록만 대조. 새로운 호환성 조사를 마지막까지 미루지 않는다.
- [ ] **Step 2: 전체 로컬 검증.** 아래 명령 모두 성공, shared host/Native의 실행 건수·fail/skip·로그 경로 기록. 플랫폼 자동 CI 비활성화 유지. UI 변경은 가격 입력·null/blank 표시 규칙 이관이므로 기존 가격 fixture의 라이트·다크 데모 표시를 확인하고 기존 fixture를 검증한다.

```sh
# repository root
python3 -m unittest client/tools/test_gen_tokens.py
python3 client/tools/gen_tokens.py --check
# client/; 위 JAVA_HOME 및 DEVELOPER_DIR 적용
./gradlew -Porg.gradle.java.installations.paths="$JAVA_HOME" \
  :shared:testAndroidHostTest :shared:iosSimulatorArm64Test :shared:allTests \
  :android:testDebugUnitTest :android:testReleaseUnitTest \
  :android:assembleDebug :android:assembleRelease :android:lintDebug
./gradlew -Porg.gradle.java.installations.paths="$JAVA_HOME" :shared:linkReleaseFrameworkIosArm64
# repository root; 선택한 simulator id와 ApplicationAccessibilityEnabled 적용
xcodebuild test -project client/ios/Wishlist.xcodeproj -scheme Wishlist \
  -destination 'platform=iOS Simulator,id=<udid>' \
  -derivedDataPath client/ios/DerivedData CODE_SIGNING_ALLOWED=NO
xcodebuild -project client/ios/Wishlist.xcodeproj -scheme Wishlist \
  -configuration Release -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath client/ios/DerivedData CODE_SIGNING_ALLOWED=NO build
```

- [ ] **Step 3: 문서 정합성.** 37개 API ID의 중복/누락과 실제 backend 상태 대조; Fake 규칙 테스트·MockEngine Remote·실서버 검증 분리. 알려진 C1 한계·C3 성능/모듈 검토 인계 유지. 날짜·서버 commit·채택 의존성·metadata precision 후속 검증·미결정 정책을 정확히 기록. 문서 링크 검사와 `git diff --check`.
- [ ] **Step 4: 리뷰·커밋.** 선택 실행 방식의 독립 리뷰와 중요 결함 수정·재검증 후 완료 표시. `docs: C2 공통 핵심 구조와 검증 결과 기록` + 본문. 실패/미실행이 있으면 완료로 승격하지 않는다.
- [ ] **Step 5: PR.** `client/c2-kmp-core` push, base develop의 draft PR 생성. 최종 설명에 모델·fake 범위·SQL transaction·B1 Remote·Swift 수명, 로컬 검증 건수/환경/미실행·C3 인계를 적는다. merge는 별도 요청 범위.

## C2 완료 기준

- [ ] SKIE Kotlin2.3.21 static framework 연결, Swift Flow/suspend/취소 및 Kotlin→Swift 비suspend token callback 실행 증거 있음(또는 승인받은 대안의 동일 증거).
- [ ] 축소한 C2 모델·state policy·가격·상품 Fake 규칙·Presenter 전이가 Android host/iOS simulator commonTest에서 통과.
- [ ] ITEM-01·03 Fake/Remote 공통 계약 통과; 후속 API는 단계·잠정 계약이 추적됨.
- [ ] SQLDelight 재개방·계정 격리·accept 원자성 실제 SQLite 테스트와 CachedGetItemRepository 성공/404/경합 계약 통과.
- [ ] 두 플랫폼이 같은 가격 함수 사용, Android/iOS build·기존 회귀 통과, release 전수 UNAVAILABLE backend·DI graph 검증.
- [ ] Koin API별 선택·debug bootstrap과 플랫폼 runtime/Presenter 자원 수명이 검증됨.
- [ ] OkHttp redirect host 실행 검증, Darwin은 소스 검토와 C12 인계로 명확히 구분됨.
- [ ] 전체 후보 의존성 호환성은 Task 1에서 검증하고, Task 5 중간 산출물을 사용자에게 확인받음.
- [ ] architecture/INDEX/integration-status·의미 있는 결정 이력·PR 검증 결과 최신화.

## 초안 자체 검토

로드맵 C2의 세션(Task2a)·모델/상태(Task2b), repository/시드(Task4), Fake(Task5), SQLDelight(Task7), transport/오류/AuthTokenProvider(Task6a), B1 DTO/Remote 계약(Task6b), Koin(Task8), SKIE·전체 후보 호환성(Task1), Presenter(Task9), C1 가격 인계(Task3)를 대응했다. 후속 범위는 문서 끝 인계 표에 모았다. 사용자 의견을 반영한 D1~D8·D9와 task 계약을 전체 확인한 뒤 Task 1부터 실행하고 Task 5 뒤 중간 확인을 받는다.

## 후속 단계 인계

| 단계 | 인계할 범위·조건 |
| --- | --- |
| C3 | 공유 수신/자동 전송·Share Extension 직접 전송 여부·app group/다중 프로세스·bind/recoverSubmitting. **최초 POST 전에 미귀속 pending의 accountBinding을 영속 commit**하고, 계정 전환으로 응답을 버려도 binding을 유지한다. 다른 owner가 같은 key를 보내는 계정 간 중복 생성을 막는다. iOS navigation 저장·Android 모듈·목록 성능 검토와 데모 seed 소비도 연결한다. |
| C4 | ItemDetailPresenter/State와 두 플랫폼 owner를 상세 화면·intent로 확장한다. CachedGetItemRepository의 단일 cache 동기화 계약을 유지한다. Firebase SDK/실서버 연결은 해당 인증 단계와 함께 확정한다. |
| C5 | CategoryPreferences/multiplatform-settings의 계정별 last parent·인터페이스·의존성·복원 테스트. B2 공용/custom category 규칙과 code point 입력 검증 반영. |
| C6/C7/C8 | 홈 노출 수·window cache/membership·dirty/409 복구·입력 제한·사진 압축/업로드. unknown lifecycle은 목록 **항목 단위 skip**, 원래 cursor 보존·빈 결과+다음 cursor 검증. viewport는 화면 수명 메모리 상태이며 anchor 앞뒤20개 연결. |
| C9/C11 | 목적 삭제·archive/restore는 B8/B10 확정 계약으로 같은 FakeStore 확장. 복원 예외(삭제 category/종료 당시 PROCESSING)도 그때 확정. |
| metadata 연결 단계 | DB precision·부호·scale/exponent·상한·nullable currency를 확정하고 price reader/formatter 테스트 확장. 가격 쓰기는 실제 계약이 필요한 단계에서 추가. |
| C12 | 호스트 로컬 redirect 서버 실행·simulator 포트 전달과 Darwin 실제 target 요청수0 검증을 구체화. C2의 Darwin 증거는 채택 버전 delegate 소스 검토. |
