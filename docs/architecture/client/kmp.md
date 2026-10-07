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

- 클라이언트는 `client/` 독립 Gradle build이며 `:android`, `:shared`, `:localdb` 모듈이다(`:localdb`는 C2 Task 7에서 추가, 아래 "모듈 구성").
- shared target은 Android, `iosArm64`, `iosSimulatorArm64`다. Android는 공식 KMP library plugin, iOS는 static `Shared.framework` direct integration을 사용한다.
- 지원 하한은 Android 8(API 26), iOS 17이다.
- 현재 공통 코드는 두 앱 연결을 확인하는 `AppInfo`와 아래 공통 결과·인증 세션 계약을 포함한다. 상품 모델·상태 정책, Create/Get 저장소 인터페이스와 보드 시드, Fake·Remote(ITEM-01·03)·로컬 캐시와 이를 조립하는 `SharedRuntime`, 그리고 C4가 확장할 상품 상세 Presenter 기반(아래 마지막 두 절)도 포함한다. 화면 비즈니스 기능은 후속 구현 대상이다.

## 구현 구조

> 2026-10-05 결정 — [ADR-027](../../history/architecture/client/ADR-027-client-implementation-strategy.md), [구현 로드맵 설계](../../superpowers/specs/2026-10-05-client-implementation-roadmap-design.md)

| 영역 | 선택 |
| --- | --- |
| HTTP·JSON | Ktor client(OkHttp / Darwin), kotlinx.serialization |
| local DB | SQLDelight |
| DI | Koin(`:shared` 안 internal 조립, runtime별 격리 `koinApplication`. 앱은 `SharedRuntimeFactory`만 호출) |
| Swift 연결 | SKIE(Flow·suspend → AsyncSequence·async). 미지원 시 KMP-NativeCoroutines |
| 인증 | 플랫폼별 Firebase 공식 SDK가 KMP `AuthTokenProvider` 구현 |
| 설정 저장 | multiplatform-settings |
| 테스트 | kotlin.test, Turbine, Ktor MockEngine |

공유 코드는 `:shared`의 패키지로 경계를 나눈다. SQLDelight plugin·schema·생성 코드만 내부 모듈 `:localdb`에 둔다.

```text
core/  model/  data/remote/  data/local/  data/fake/  repository/  domain/  presentation/
```

- 의존 방향은 `presentation → domain → repository(interface) ← data(remote | fake | local)`이다.
- `presentation`의 Presenter(`StateFlow<State>` + intent)가 화면 데이터, 로딩·오류, 편집 초안과 dirty 여부, 연속 처리 진행, 409 복구 상태를 소유한다.
- `repository`는 서버 API ID 단위 인터페이스다. Fake·Remote·UNAVAILABLE을 API ID마다 명시한 `RepositoryBindings`로 고르고(build mode에서 추론하지 않는다), 서버 묶음이 끝나면 API ID 단위로 Remote로 바꾼다. 같은 계약 테스트를 양쪽에 실행한다.
- 서버가 아직 고정하지 않은 필드·오류 코드는 `CONTRACT-PENDING(<API-ID>)`로 표시하고 DTO·매퍼 안에만 둔다.

**모듈 구성(계획과 다른 점):** C2 계획의 Architecture는 `:shared` 한 모듈이었다. SQLDelight 생성 클래스는 `public`만 가능해서 `:shared`에 두면 `Shared.h`(ObjC header)에 노출되므로 internal 모듈 `:localdb`로 분리했다. `:shared`는 `implementation`으로만 의존하고 export하지 않으며, iOS 앱 target은 `-lsqlite3`를 직접 링크한다. 결정 기록은 [C2 `:localdb` 모듈 분리](../../history/architecture/client/c2-localdb-module-split-2026-10-07.md).

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


## 상품 모델·상태 정책 경계

> 2026-10-07 Task 2b 구현 — 서버 기준은 Task 1에서 병합한 B2 `1c6d949081d47ddb28e60c00eda44b4aa0d91fb0`의 `WishlistItemDtos.kt`·`WishlistItemPolicy.kt`·정책 테스트다. 이 절은 공통 snapshot과 Fake 정책 계약이며 HTTP DTO·저장소·formatter 구현을 포함하지 않는다.

- `WishlistItem`은 raw string ID·`clientSubmissionId`·양수 `version`·`sourceUrl`과 `ProductSnapshot`, `ItemCategory`, `ItemPurpose`, `ItemAnalysis`, review/lifecycle, 서버 `requiredAction`·`allowedActions`, 생성·갱신·수동 완료·기기 공유 시각을 보관한다. 상태 축과 수동 완료는 독립적이다. 모델은 서버 행동을 재계산하지 않으며 UI는 `WishlistItem.allowedActions`를 직접 읽는다.
- `ProductSnapshot`은 nullable 이름·이미지 URL·정밀 가격·통화·브랜드·판매처·metadata 확인 시각·이름/이미지 출처를 보존한다. `ItemCategory`는 nullable ID·source·missingReason·name·parentId·raw kind를 보존한다. category ID가 있으면 missingReason은 null이어야 한다. `ItemPurpose(id, source)`는 사용자가 목적을 해제한 `(null, USER)`도 허용한다. 분석의 공개 `failureCode`는 nullable raw string이다.
- `Category(id, name, parentId, kind, version)` 한 목록으로 B2 상위 그룹(`G001`~`G011`, parent/kind/version null), 공용 leaf(`C001`~`C087`, G-ID parent, PUBLIC, version null), custom leaf(UUID, G-ID parent, CUSTOM, 양수 version)를 표현한다. kind는 nullable raw string이므로 새 kind를 보존한다. ID는 UUID 타입으로 제한하지 않으며 UUID 검사는 생성·요청 경계의 책임이다. C1 보드의 사용자 분류 이름은 후속 시드에서 custom UUID leaf로 매핑한다.
- `Purpose`는 raw ID·name·nullable description·raw colorKey/iconKey·version·createdAt/updatedAt의 최소 snapshot이다. 알려진 키는 `PurposeKeys`에 제공하되 새 키도 모델에 그대로 보관한다. `Archive`는 ID·title·originalPurposeId·목적 표시 snapshot·createdAt만 둔다. 이는 과거 표시용 모델이며 아직 구현되지 않은 archive wire·후보 페이지·복원/삭제 명령 계약을 확정하지 않는다.
- `LocalSubmission`은 확정된 로컬 계약인 clientSubmissionId/sourceUrl/createdAt/accountBinding/submissionStatus/serverItemId/lastSubmissionError를 보관한다. 상태는 PENDING/SUBMITTING/ACCEPTED이며 마지막 오류는 nullable `ClientError`다. 기본은 미귀속 PENDING이다. 재전송·계정 격리·영속 transaction 구현은 후속 task의 책임이다.
- Kotlin 모델 시각은 `kotlin.time.Instant`다. framework의 Instant 타입은 `KotlinInstant`로 노출되므로 Swift는 공개 `createdAtIso`, `updatedAtIso`, `manualCompletionAtIso`, `clientCreatedAtIso`, `metadataCheckedAtIso` 문자열 getter를 사용한다(각 모델이 가진 시각만 제공). nullable 시각은 nullable 문자열이다. Kotlin의 `Purpose.description`과 `ArchivePurposeSnapshot.description`은 property-target `@ObjCName`으로 Swift의 `purposeDescription`에 노출해 `NSObject.description()` 충돌을 피한다. 모든 snapshot은 Android/Swift 구체 state가 사용할 수 있도록 public이다.
- Fake만 `evaluateItem`을 호출해 저장할 행동을 채운다. 순서는 비ACTIVE → PROCESSING → 이름 누락 → EXTRACTION_UNRESOLVED → AI_ABSTAINED/AI_RESPONSE_UNUSABLE → CUSTOM_CATEGORY_DELETED → 이유 없는 category 누락 → PENDING 검토다. 수동 완료 후에는 원래 analysis를 보존하고 MANUAL_COMPLETE/REANALYZE/REVIEW를 더 허용하지 않는다. READY의 삭제된 category는 EDIT로 재지정한다. DEFERRED는 검토를 다시 요구하지 않는다. `evaluateItem`은 상태 축만 읽고 이미 저장된 `requiredAction`/`allowedActions`를 입력으로 쓰지 않는다. analysis UNKNOWN이면 서버가 준 행동 목록이 없으므로 `(UNKNOWN, 행동 없음)`이다.
- `sanitizeAllowedActions(analysisStatus, requiredAction, actions)`는 후속 Remote mapper의 매핑 경계에서만 사용한다. analysis 또는 requiredAction이 UNKNOWN이면 제공된 행동 중 DELETE만 남기며 서버가 허용하지 않은 DELETE를 만들지 않는다. 알려진 상태는 입력 행동을 유지한다. review/source/missingReason의 설명용 UNKNOWN은 서버 행동을 변경하지 않는다. unknown wire action 제거와 unknown lifecycle의 INVALID_RESPONSE 처리는 후속 HTTP mapper에서 담당한다.
- `homeActionGroup(requiredAction)`은 서버 requiredAction을 홈 그룹에 투영하는 별도 함수다. INFORMATION_COMPLETION/CATEGORY_ASSIGNMENT/CATEGORY_REASSIGNMENT는 같은 정보 보완 그룹이며 NONE/UNKNOWN은 그룹이 없다. `isListEligible`은 ACTIVE·이름/category 존재를 요구하고 analysis/requiredAction UNKNOWN을 제외한다. PARTIAL도 최소 정보가 있으면 목록에 포함하며 설명용 UNKNOWN으로 제외하지 않는다.
- `DecimalAmount.parseOrNull`은 `[+-]?[0-9]+(\.[0-9]+)?` 형태의 plain decimal만 받아 canonical 문자열을 만든다. 양수 부호·정수 선행 0·소수 끝 0을 제거하고 음수 0을 `0`으로 정규화한다. 일반 음수·큰 정밀 값은 유지하며 Double 변환·지수 확장·자리 수 상한·metadata precision 가정을 넣지 않는다. malformed/nonfinite는 null이다. `ApiId`는 ITEM 8/HOME 2/DUP 2/CAT 6/PUR 8/ARC 9/MEDIA 2의 37개와 각 wire ID를 제공한다.

독립 기대값 표와 별도 홈 투영 표로 Fake 정책을 검증하며, 서버 행동 보존과 UNKNOWN 정제·B2 category snapshot·모델 불변식·decimal 안전 파싱·API ID 누락/중복을 두 공통 테스트 runtime에서 실행한다. simulator framework 링크와 작은 Swift typecheck는 ISO 문자열 getter·목적 설명 이름·non-throwing decimal 파싱 노출을 확인한다. 실제 화면·HTTP·저장소 동작은 이 검증 범위가 아니다.


## 저장소 인터페이스·보드 시드 경계

> 2026-10-07 Task 4 구현 — Create/Get 인터페이스·결정적 시드와 abstract 공통 계약 harness를 준비했다. Fake backend·Remote wire 실행 검증은 Task 5·6b에서 수행한다.

- `CreateItemRepository`는 ITEM-01의 `CreateItemCommand(submissionId, sourceUrl, clientCreatedAt)`를 받아 snapshot을 반환한다. 같은 로컬 공유는 같은 UUID key를 재사용하며, 같은 URL을 다시 공유할 때는 새 key다. `sourceUrl`은 원문 그대로 보관한다. 이 경계에서 trim/정규화 또는 네트워크 URL 검증 정책을 추가하지 않는다. `GetItemRepository`는 owner 범위의 ITEM-03 조회다. 계정 gate와 backend 조립은 후속 task 책임이다.
- `CatalogRepository`는 category/purpose/item 시드 조회 경계이며 nullable categoryId/purposeId로 실제 membership을 제한한다. 목록 paging·count·SELECT/BROWSE·facets 같은 서버 wire 계약을 지정하지 않는다. 시드 조회가 CAT-01/PUR-01/ITEM-02의 Remote 구현 완료를 뜻하지 않는다.
- `BoardSeeds.create(clock, ids)`는 같은 clock 값·같은 UUID 공급 순서로 같은 `BoardSeedData`를 만든다. B2 공용 taxonomy와 registry의 G/C-ID를 그대로 사용한다. 보드 상위는 G001~G007과 G011의 8개이며 전체 SELECT taxonomy의 11개와 구별한다. flat category 목록은 8개 상위와 30개 보드 leaf다. 공용 taxonomy에 없는 오디오 케이블·DAC/백패킹 소품/레고는 각각 G003/G006/G007 아래의 owner custom UUID leaf(version 1)다.
- 시드는 보드 목적 7개와 중복을 합친 헤드폰 8개만 포함한다. 여행 캐리어 목적은 비어 있다. 긴 이름·homeProducts 넘침 예시는 별도 UI fixture여서 여기에 목적/상품을 추가하지 않는다. 목적·상품·clientSubmissionId·custom category ID는 주입한 생성기로 공급한다. 가격은 원래 decimal 문자열을 `DecimalAmount`로 읽고 Float/Double로 변환하지 않는다.
- `WishlistItem`은 category-list snapshot을 기준으로 한다. Marshall MAJOR V는 purpose null·PENDING이며 출퇴근 목적의 실제 membership은 4개다. `BoardDisplayMetadata`에는 보드 category chip 숫자(합계 69), 목적 candidate 숫자(출퇴근 5), l/c 카드별 사진 색·비율·caption을 보관한다. c5 Marshall과 AirPods의 다른 사진은 같은 item ID의 표시 fixture로 남기며 domain 상품을 복제하지 않는다. metadata는 repository query/filter/count에 전달하거나 읽지 않는다.
- test-only `RepositoryContractFixture`는 같은 store의 owner context를 바꾸고 분석 완료·삭제를 제어한다. abstract `RepositoryContractTest`는 생성/replay의 같은 ID·최초 공유 시각·최신 상태, 새 key의 새 상품, 원문 URL 충돌, owner 격리, 없는 항목/삭제 GET 404와 replay tombstone 시나리오를 제공한다. 신규 201/replay 200은 도메인 결과에 넣지 않고 Task 6b transport 테스트가 검사한다. Task 4에는 concrete factory가 없어 이 contract suite는 실행되지 않았다.

시드 테스트 8개와 기존 공통 테스트를 Android host·iOS simulator에서 각각 실제 실행했다(각 55개, 실패/오류/skip 0). API별 서버 B단계·클라이언트 C단계·Fake/Remote/MockEngine/실서버의 진행과 미실행은 [연동 상태](server-integration-status.md)에서 분리한다.


## Fake 상태 저장소와 계정 경쟁

> 2026-10-07 Task 5 구현. Fake 공개 저장소는 ITEM-01 Create·ITEM-03 Get과 시드 조회이며, mutation wire API는 후속 범위다.

`FakeStore(session, clock, ids)`가 owner별 상품·submission key·재분석 attempt·분석 generation을 보관하고 모든 비즈니스 규칙을 적용한다. `FakeItemRepository`, `FakeCatalogRepository`, `FakeControls`는 이를 위임한다. 개발 세션 조립은 먼저 `MutableAuthSession.changeAccount`를 호출한 다음 `store.seed`로 현재 owner namespace를 초기화한다. seed 자체도 같은 session gate를 통과하며, 미인증이면 실패한다. 재초기화로 사용자 변경을 덮어쓰지 않는다.

요청 시작의 `SessionSnapshot`을 보관하고 주입한 delay를 gate 밖에서 실행한다. 이후 `AuthSession.withCurrent` → Store Mutex 순서로 commit하며, 결과 공개 직전 snapshot도 비교한다. A→B와 A→logout→A 모두 이전 generation의 응답과 쓰기를 SESSION_CHANGED로 거절한다. `failNext`/`delayNext`는 다음 한 요청에만 적용하고 coroutine test scheduler로 실제 지연·취소 경계를 검증할 수 있다. 자동 분석·wall-clock 타이머는 없다.

생성은 PROCESSING·version 1·analysisGeneration 1이다. owner와 submission UUID가 같은 재전송은 원본 URL 문자열을 비교하고, 최초 clientCreatedAt 및 최신 snapshot을 반환한다. 삭제는 version과 무관하고 반복 삭제도 성공한다. GET은 삭제 항목을 숨기지만 생성 key 재전송은 tombstone을 반환한다. 최종 분석·사용자 변경·삭제는 version을 증가시키며, 일반 편집·직접 보완·검토는 expectedVersion을 비교한다.

Fake 전용 `Patch.Unchanged`/`Patch.Set(null)`은 생략과 명시적 삭제를 구별한다. 직접 보완은 이름·카테고리를 요구하고 원래 analysis status/failureCode를 유지하며 CONFIRMED·manualCompletionAt을 기록한다. 검토 CONFIRM/DEFER 이후 검토를 다시 열지 않는다. 같은 재분석 attempt는 generation을 한 번만 증가시키며 최신 상태를 재전송한다. 이전 generation·완료된 generation·삭제 후 분석 결과는 무시한다.

최종 분석 반영은 서버 `AnalysisResultRepository`의 병합 규칙을 따른다. 출처가 USER인 이름·카테고리와 CONFIRMED/DEFERRED 검토는 유지한다. READY는 AI 이름을 새 값으로 바꾸고, 그 밖의 결과는 비어 있는 이름만 채운다. READY에는 반영 후 category가 있어야 하며, category가 없으면 이유가 없을 때 EXTRACTION_UNRESOLVED를 넣는다. 검토 PENDING은 이름과 category가 모두 있고 category가 확인 전 AI 값일 때만 설정한다. 목적·이미지·user override 목록은 Fake 분석 결과에 없어 반영하지 않는다.

상품 ID와 submission key는 UUID 정규 소문자 문자열로 저장·조회한다. 시드와 모든 상품 연산이 같은 정규화를 거치므로 플랫폼 생성기가 대문자 UUID(NSUUID)를 만들어도 같은 상품을 가리킨다. `BoardSeeds`도 생성 ID를 정규화한다. UUID가 아닌 상품 ID는 조회와 개발용 제어 모두 `VALIDATION INVALID_WISHLIST_ITEM_ID`다.

host/Native에서 공통 계약 7개와 Fake 집중 테스트 19개를 실제 실행했다(기존 55개 포함 각 81개, 실패·오류·skipped 0). static simulator framework 링크도 확인했다. Remote HTTP 상태·서버 SSRF/URL 정규화·새 Fake API의 Swift 호출 동작은 이 검증 범위에 포함하지 않는다. 구현과 wire 공개 상태는 [서버 연동 상태](server-integration-status.md)에서 구분한다.

## transport·인증 재시도·오류 envelope

> 2026-10-07 Task 6a 구현. B1 Remote repository(ITEM-01·03)는 이 transport 위에 Task 6b에서 만든다. 아래 타입은 모두 Kotlin `internal`이라 Swift framework header에 Ktor 타입이 노출되지 않는다(`Shared.h`에서 확인).

- **구성:** `CallbackAuthTokenProvider(session, source)`가 `PlatformTokenSource` callback을 `AuthTokenProvider.getToken(snapshot, forceRefresh)` suspend 호출로 바꾼다. 첫 callback만 채택하고 늦은·중복 callback은 무시한다. 호출 취소 시 `TokenRequest.cancel()`을 정확히 한 번 호출하고 `CancellationException`을 전파한다. token은 캐시하지 않으며 token 대기는 session gate 밖에서 한다. `AuthenticatedTransport(session, tokens, client, baseUrl).execute(snapshot, apiId, buildRequest)`는 Kotlin 전용이며 성공은 2xx `HttpResponse`, 그 외는 `ClientResult.Failure`다. Ktor Auth/Bearer plugin은 token cache가 session guard를 우회하므로 쓰지 않는다.
- **인증 대상:** 요청 URL이 주입한 base URL과 scheme·host·port가 같고 경로가 `/v1` 또는 `/v1/...`일 때만 `Authorization: Bearer`를 붙이고 token을 요청한다. 그 밖의 URL(다른 origin, `/v10`, `/healthz` 등)은 token 요청 없이 익명으로 보낸다.
- **재시도·stale 검사:** 401은 `forceRefresh=true`로 token을 한 번만 다시 받아 같은 URL·body·`Idempotency-Key`로 한 번 재전송한다(요청 builder는 한 번만 실행하고 시도마다 `takeFrom`으로 복사하므로 caller가 key를 생성해도 두 시도가 byte 단위로 같다. body는 text/bytes처럼 재전송 가능해야 한다). 두 번째 401, token 없음, refresh 실패는 `UNAUTHENTICATED`(플랫폼 errorCode는 `code`에 보존)다. 시작 전·token 완료 후·첫 전송 전·401 재전송 전·반환 직전에 `session.state.value == snapshot`을 비교해 다르면 `SESSION_CHANGED`로 끝내고 옛 body를 새 계정 token으로 보내지 않는다. 네트워크 실패·5xx는 application 차원에서 재전송하지 않는다. OkHttp native `retryOnConnectionFailure`(끊긴 keep-alive 복구)는 true로 유지한다.
- **오류 매핑(`ApiErrorMapper`):** 401 UNAUTHENTICATED, 404 NOT_FOUND, 409 CONFLICT, 400·422 VALIDATION, 429 RATE_LIMITED(`Retry-After` 정수 초만, HTTP-date·음수는 null), 5xx SERVER, 3xx와 그 밖의 예상 밖 상태(403 포함) INVALID_RESPONSE. Ktor timeout(request/connect/socket)은 TIMEOUT, 그 밖의 연결·IO 예외는 NETWORK. `CancellationException`은 항상 전파한다. `error.code`·`error.requestId`를 읽고 requestId가 없거나 비어 있으면 `X-Request-ID`를 쓴다. JSON이 아니거나 모양이 틀린 body(error가 문자열, code가 숫자, details가 배열 등)는 예외 없이 HTTP 상태 kind와 `code=null`로 안전하게 처리한다. 알 수 없는 code는 상태 kind에 code를 그대로 싣는다.
- **currentVersion:** 위치는 `error.details.currentVersion`(JSON number)로 확정이다. 문자열·소수는 무시하고, B1 `IDEMPOTENCY_KEY_REUSED`는 항상 null이다. 어떤 후속 mutation code가 이 값을 싣는지는 매퍼에 `CONTRACT-PENDING(ITEM-04/ITEM-07/ITEM-08)`로 표시했고 지금은 그 code를 가리지 않고 있으면 읽는다.
- **엔진·redirect·timeout:** `platformHttpEngine()`은 Android OkHttp(`followRedirects(false)`, `followSslRedirects(false)`를 명시), iOS Darwin이다. Ktor client는 `followRedirects=false`, `expectSuccess=false`이며 3xx는 따라가지 않고 `INVALID_RESPONSE`다. timeout 시작 기본값은 request 30초·connect 10초·socket 30초이고 모든 요청에 적용됨을 MockEngine 요청의 capability로 확인했다. 실제 효과는 engine별이다. request timeout은 Ktor `HttpTimeout`이 engine과 무관하게 적용하고, OkHttp는 connect·socket도 지원한다. Darwin(3.4.3 소스 `TimeoutUtils.kt`)은 socket 값만 `NSURLRequest.timeoutInterval`로 쓰며 connect timeout은 별도로 적용하지 않는다.
- **redirect 검증 범위:** 실제 OkHttp는 Android host test(`OkHttpRedirectTest`)에서 MockWebServer 두 대(동적 port; 302·307·https로의 301)로 확인했다. redirect target 요청 수 0, bearer는 원 서버에만 도달한다. Darwin은 simulator 실행 없이 소스 검토만 했다. 3.4.3 기본 `KtorNSURLSessionDelegate`의 redirect callback이 `completionHandler(null)`로 따라가지 않으며, custom delegate를 추가하지 않는다. localhost 접근은 테스트 fixture 전용이고 production base URL 정책과 무관하다.

## 상품 DTO·mapper·Remote 계약

> 2026-10-07 Task 6b 구현. fixture 기준은 서버 develop `1c6d949`(`WishlistItemDtos.kt`·`WishlistItemViewMapper.kt`·`DecimalJsonSerializer.kt`·`CategoryDtos.kt`)이며 손으로 옮겼다. Fake·evaluator로 정답을 만들지 않는다. 모두 Kotlin `internal`이다.

- **DTO:** `WishlistItemDto`의 enum은 전부 raw 문자열이고 가격은 `JsonElement`다. 누락된 필수 필드·잘못된 JSON 타입(version이 `"2"` 문자열인 경우 포함)은 디코딩 실패이며 추가 필드는 무시한다.
- **mapper 규칙:** `mapItem`/`parseItem`은 예외를 던지지 않는다(취소 제외). 모델 `require`(version>0, category id와 missingReason 동시 존재 금지)와 모든 Instant·가격 파싱을 모델 생성 전에 검사하고 실패는 `INVALID_RESPONSE`다. `requiredAction`/`allowedActions`는 서버 값을 쓰고 `evaluateItem`은 Remote에서 쓰지 않는다. 필드별 해석: lifecycleStatus가 모르는 값이면 단건 `INVALID_RESPONSE`, review/source/missingReason/analysis.status/requiredAction은 `UNKNOWN`. 모르는 allowedActions는 버린다.
- **UNKNOWN 처리:** 설명용 UNKNOWN(review·source·missingReason)은 서버가 준 known action을 유지한다. 분기용 UNKNOWN(analysis.status 또는 requiredAction)은 `sanitizeAllowedActions`로 서버 목록에 있는 DELETE만 남기고, 서버가 DELETE를 주지 않았으면 빈 set이다. `failureCode`는 알 수 없는 값도 원문 그대로 보관한다(일반 안내 문구 매핑은 표시 계층의 후속 일이다).
- **가격 reader:** `readPrice`는 읽기 전용이다. JSON number 원문을 `DecimalAmount.parseOrNull`로 바로 파싱해 정밀도를 잃지 않는다. 없음·null은 가격 없음, JSON 문자열·boolean·배열/객체·지수 표기는 `INVALID_RESPONSE`다. 금액 serialize는 하지 않는다.
- **Remote:** `RemoteItemRepository(session, transport)`가 ITEM-01(`POST /v1/wishlist-items`, `Idempotency-Key`=submission UUID, 원본 URL 문자열과 선택 clientCreatedAt body; 201·replay 200 모두 성공)과 ITEM-03(`GET /v1/wishlist-items/{id}`, id는 path segment 하나로 인코딩)을 구현한다. 호출 시작에 snapshot을 잡아 transport에 넘기고 반환 직전 다시 비교해 SESSION_CHANGED를 낸다. 계정이 없으면 요청 없이 `UNAUTHENTICATED`다.
- **공통 계약:** `RemoteItemRepositoryContractTest`가 Task 4의 `RepositoryContractTest` 7개 시나리오를 그대로 실행한다. fixture는 MockEngine 위의 테스트 전용 `ScriptedItemServer`(Bearer token으로 owner 구분, owner별 submission key 맵, 손으로 쓴 서버 JSON)이며 `completeAnalysis`/`deleteItem`은 저장 JSON 상태만 바꾼다. A→B와 A→로그아웃→A의 지연 응답 거절은 Fake(`FakeItemRepositoryTest`)와 Remote(`RemoteItemRepositoryTest`) 양쪽에서 검증한다. 후속 mutation의 검증은 Fake 규칙 테스트 수준이며 실서버는 실행하지 않았다.
- **transport 보완(6a 리뷰):** 요청 builder를 한 번만 실행해 재전송 key·body를 동일하게 보장, 요청 취소가 IO 오류로 나타나도 NETWORK가 아니라 취소로 전파(`ensureActive`), token provider가 session을 검사하지 않아도 transport 자체의 stale 검사가 SESSION_CHANGED를 낸다.

## 로컬 저장소(SQLDelight)·캐시 decorator

> 2026-10-07 Task 7 구현. D7 범위는 최소 schema·계정별 `LocalStore`·accept 원자성·GET 캐시 동기화다. 다중 프로세스, pending 전송, window/settings는 제외한다. token은 저장하지 않는다.

- **모듈:** SQLDelight 2.4.1 plugin은 별도 `:localdb` 모듈에만 적용한다. 생성 코드(`WishlistDatabase`·query·row 타입)는 public만 가능해서 `:shared`에 두면 ObjC header에 노출되기 때문이다. `:shared`는 이를 `implementation`으로만 쓰고 `SqlLocalStore`·`CachedGetItemRepository`·`DriverFactory`는 `internal`이다. 링크한 `Shared.h`에서 SQLDelight/SqlLocalStore 계열 심볼 0건을 확인했다. plugin이 `:localdb`에 있으므로 native binary의 `-lsqlite3`는 `:shared` iOS binary에서 직접 지정한다. 공개 면은 `repository/LocalStore`뿐이다.
- **첫 생성 schema(`Wishlist.sq`):** `local_submission`(UUID `client_submission_id` PK, `source_url`, `created_at` 정확한 ISO text, nullable `account_binding`, `status`, `server_item_id`, ClientError 분해 컬럼)과 `item_cache`(PK `(account_id, item_id)`, `version`, 상품·카테고리·목적·분석 상태 컬럼 전부 평탄화, 가격은 `DecimalAmount.canonical` text, Instant는 ISO text, `allowed_actions`는 정렬된 쉼표 text). 사용 query는 insert/select/delete 위주이며 조건은 `version <= ?` 삭제뿐이다. 첫 schema 버전은 1이고 migration은 아직 없다.
- **계정 규칙:** 캐시 key는 (accountId, itemId)다. 같은 계정 재로그인은 캐시 행을 유지하되 이전 snapshot의 쓰기는 `SESSION_CHANGED`로 거절한다. `pending()`은 현재 계정 귀속분+미귀속분(로그아웃 상태는 미귀속만), `saveSubmission`은 binding이 null이거나 현재 계정일 때만 허용하고 나머지는 `VALIDATION/ACCOUNT_BINDING_MISMATCH`다. 이미 귀속된 기존 행은 같은 binding으로만 다시 저장할 수 있고(재귀속·귀속 해제 거절), 미귀속→현재 계정 귀속만 허용한다. `accept`는 pending이 snapshot 계정에 귀속된 경우에만 받는다(미귀속·타 계정은 같은 code).
- **transaction 계약:** 모든 연산은 session gate(`withCurrent`) → DB transaction 순서이며 gate 안에는 짧은 DB commit만 둔다(네트워크·delay 없음). `accept`는 한 transaction에서 캐시 upsert(또는 DELETED replay면 tombstone version 이하 캐시 삭제)와 pending 삭제를 수행하고, 중간 오류는 둘 다 rollback한다. 낮은·같은 version의 upsert는 건너뛰고 더 높은 version만 교체한다. `removeCachedItem`은 `throughVersion` 이하만 지우고 `clearCurrentCache`는 현재 계정 캐시만 지우며 pending은 보존한다. DB/driver 예외는 `UNAVAILABLE/LOCAL_STORE_FAILURE`로 바꾸고 취소는 항상 전파한다. rollback 오류 주입은 `SqlLocalStore`의 internal 생성자 hook(테스트 전용)이다.
- **decorator 계약:** `CachedGetItemRepository(delegate, localStore, session)`는 snapshot→cache read(version 관찰)→snapshot 확인→delegate→cache write→동일 snapshot 확인→반환 순서다. 성공은 upsert, `NOT_FOUND`는 관찰한 version 이하만 제거(캐시가 없었으면 no-op)해 늦은 404가 새 version을 지우지 않는다. 일반 오류는 캐시를 유지한다. 반환은 항상 delegate 응답이라 같은 version 캐시를 건너뛰어도 최신 표시명을 받는다. 캐시 read/write 실패는 ClientError로 반환하고, 계정/세대가 바뀌면 결과와 commit을 거절한다. 로그아웃 상태에서는 캐시 없이 delegate 결과를 그대로 반환한다. Presenter는 `GetItemRepository`만 소비한다.
- **driver·검증 범위:** Android는 `AndroidSqliteDriver`(앱 sandbox, `DriverFactory(context)`), iOS는 `NativeSqliteDriver`다. 동작 suite는 같은 commonTest를 JDBC SQLite(androidHostTest, 임시 파일)와 Native SQLite(iosSimulatorArm64Test, 임시 파일)에서 `expect` 테스트 driver factory로 실행하며 close/reopen을 검증한다. Robolectric/에뮬레이터가 없어 Android Context driver는 컴파일만 확인했고 실기기 runtime smoke는 C3로 넘긴다.

## API별 backend·SharedRuntime 조립

> 2026-10-07 Task 8 구현. Koin 4.2.2(`koin-core`, Task 1 고정 버전)를 runtime마다 격리된 `koinApplication { }`으로 쓰고 전역 `startKoin`은 쓰지 않는다. 모든 DI·SQLDelight·Ktor 타입은 Kotlin `internal`이다.

- **명시적 binding:** `RepositoryBindings(buildMode, backends: Map<ApiId, Backend>)`는 생성 시 검증한다. 37개 API 전부를 지정해야 하고(누락은 `IllegalArgumentException`), RELEASE의 FAKE는 하나라도 오류다. FAKE·REMOTE는 구현이 있는 ITEM-01·03에만 허용하고, 나머지는 명시적 UNAVAILABLE이어야 한다(미구현 REMOTE뿐 아니라 미구현 FAKE도 구성 오류). `RemoteConfig(baseUrl, PlatformTokenSource)`는 REMOTE binding이 있을 때만, 그리고 그때는 반드시 넘긴다(어느 방향이든 어긋나면 조립 오류).
- **앱이 넘기는 값(C2):** DEBUG는 ITEM-01·03만 FAKE, 나머지 35개 UNAVAILABLE. RELEASE는 37개 모두 UNAVAILABLE. 두 앱 모두 remote config가 없다. REMOTE(ITEM-01·03)는 MockEngine 테스트에서만 조립한다.
- **graph:** runtime 하나에 `MutableAuthSession` 하나를 두고 Fake store·SQL 저장소·token adapter·transport·Remote가 모두 그 session을 받는다. Fake 부품은 DEBUG에만 있고(RELEASE graph에는 `FakeStore` 정의가 없다), HTTP 부품은 REMOTE가 있을 때만 있다. ITEM-01/03 delegate는 binding대로 Fake·Remote·`UnavailableItemRepository` 중 하나이며, 테스트 helper `resolvedBackend`는 binding이 아니라 graph에 실제 연결된 delegate를 확인한다.
- **facade:** `createItemRepository()`(ITEM-01), `getItemRepository()`(ITEM-03), `catalogRepository()`, `localStore()`는 구체 accessor다. Get facade는 선택한 delegate를 `CachedGetItemRepository`로 정확히 한 번 감싼다. UNAVAILABLE delegate의 오류(`UNAVAILABLE/API_UNAVAILABLE`)는 일반 오류이므로 캐시를 유지한다. `catalogRepository()`는 DEBUG에서만 seed 조회(Fake)이고 RELEASE에서는 UNAVAILABLE이다. 이는 CAT/PUR/ITEM-02 wire API의 Fake 완료가 아니다.
- **ready·debug bootstrap:** 모든 facade 요청은 `ready`가 false면 `UNAVAILABLE/RUNTIME_NOT_READY`다. RELEASE는 조립 직후 ready=true이고 `startDebugSession()` 호출은 오류다. DEBUG는 앱의 `DebugSessionBootstrap`이 `startDebugSession()`을 부르면 runtime scope가 `changeAccount("debug-board-owner")` → 같은 namespace에 `BoardSeeds` 주입 → ready=true 순서를 소유한다(중복 호출은 무시). seed 항목의 `createdAt`은 보드 순서대로 1분씩 앞서므로 seed 목록은 무작위 UUID와 무관하게 l1..l8 순서다.
- **자원 수명:** SQL driver와 HTTP engine은 처음 필요한 facade를 얻을 때 연다. graph 조회와 debug ready 게시는 lock 없는 close guard(`CloseGuard`, 사용 중 수 + closing 상태를 한 StateFlow에서 CAS) 안에서 실행한다. `close()`는 어느 스레드에서든 한 번만 동작하며 새 조회를 즉시 거절하고 ready=false·bootstrap scope 취소를 한다. 실제 정리(만든 자원만 생성 역순으로 닫기, HttpClient는 넘겨받은 engine을 닫지 않으므로 둘 다 닫음 → Koin 종료 → ready=false)는 진행 중인 조회·게시가 모두 끝난 뒤 정확히 한 번 실행되므로, 닫힌 Koin을 조회하거나 close 뒤 ready가 true로 남지 않는다. close 이후 facade는 graph를 다시 열지 않고 `RUNTIME_NOT_READY`를 반환한다. 닫기 횟수와 경합은 internal test seam(`PlatformResources`, bootstrap 게시 직전 hook)으로 검증한다.
- **공개 면:** 진입점은 `SharedRuntimeFactory.create(context, bindings, remote)`(androidMain)와 `SharedRuntimeFactory.shared.create(bindings:remote:)`(iosMain)이고, 공개 타입은 `SharedRuntime`·`RepositoryBindings`·`RemoteConfig`·`Backend`·`ClientBuildMode`와 기존 repository interface다. 링크한 debug simulator·release device `Shared.h`에서 Koin/Ktor/SQLDelight·HttpClient·SqlDriver·내부 DI 타입 0건을 확인했다. Swift에서는 SKIE가 `RepositoryBindings(buildMode:backends:)`의 map을 `[ApiId: Backend]`로, `ready`를 `for await` 가능한 Flow로 노출한다(RELEASE 상수는 Swift에서 `.theRelease`).
- **static framework 링크:** runtime factory가 Native SQLite driver를 참조하므로 iOS 앱 target은 `-lsqlite3`를 직접 링크한다(static `Shared.framework`의 linker 옵션은 소비자에게 전달되지 않는다).
- **남은 점:** `SqlLocalStore`는 DB I/O를 호출자 dispatcher에서 실행한다. Presenter 경로는 Task 9에서 runtime이 `RuntimeDispatchers.io`를 주입해 UI 스레드 밖에서 실행한다(아래 절). facade를 직접 부르는 다른 호출자는 호출 측 dispatcher를 따른다.

## 상품 상세 Presenter 기반

> 2026-10-07 Task 9 구현. C4는 이 Presenter/State에 상세 intent를 추가하며 아래 규칙과 소유 관계를 유지한다.

- **공개 면:** `Presenter`(수명 계약, `close()`만), `ItemDetailState(item, loading, error)`와 `ItemDetailState.Initial`, `ItemDetailPresenter(repository: GetItemRepository, session: AuthSession, dispatcher: CoroutineDispatcher)`의 `state: StateFlow<ItemDetailState>`·`load(id)`·`retry()`·`close()`. 앱은 생성자 대신 `SharedRuntime.itemDetailPresenter()`를 쓴다. runtime의 gated Get facade(캐시 decorator 포함)·runtime 단일 session·`RuntimeDispatchers.io`로 만든다. 공개 생성자는 commonTest가 test dispatcher를 주입하는 경로다. UI/navigation 람다는 state에 두지 않는다.
- **의존성:** 상품 조회는 `GetItemRepository` 하나뿐이다. 캐시 읽기·쓰기는 하지 않는다(Task 7 decorator 소유).
- **실행 모델:** intent는 어느 스레드에서 불러도 즉시 반환한다. intent 처리, session 관찰, state 발행은 주입 dispatcher의 단일 lane(`limitedParallelism(1)`)에서 순서대로 실행되고, repository 호출만 주입 dispatcher 자체에서 실행한다. scope는 `SupervisorJob`이고 `state`는 읽기 전용 StateFlow다.
- **조회·재시도:** `load(id)`는 loading=true·error=null로 시작한다. 같은 id의 항목이 보이는 중이면(refresh) 항목을 유지하고, 다른 id면 바로 비운다. 성공은 항목 표시, 일반 오류는 기존 항목을 유지한 채 error, `NOT_FOUND`는 항목 제거 + error다. `retry()`는 마지막 id를 다시 조회하며 첫 load 전에는 아무것도 하지 않는다.
- **마지막 요청 승리:** 새 load/retry는 이전 요청을 취소하고 request ID를 올린다. 취소를 무시하고 늦게 도착한 응답도 request ID가 현재가 아니면 버린다.
- **session:** 계정 변경·같은 계정 재로그인(세대 증가)을 관찰하면 진행 요청을 취소하고 `Initial`로 되돌린다. 마지막 id는 남기므로 이후 `retry()`는 새 session으로 다시 조회한다. 응답 발행은 요청 시점 snapshot으로 `AuthSession.withCurrent` 안에서 하므로 계정 변경과 직렬화되고, 관찰자가 아직 변경을 처리하지 못한 경합에서도 이전 session의 응답은 게시되지 않는다. 관찰보다 먼저 들어온 load는 처리 시작 시 session을 먼저 동기화해 새 계정 요청이 뒤늦은 관찰에 취소되지 않는다.
- **close:** `close()`는 scope를 취소한다. 멱등이며 이후 intent·session 변경·늦은 응답은 대체로 state를 바꾸지 않는다(state는 마지막 값에 멈춘다). 다만 다른 스레드의 in-flight 발행과 close의 순서는 보장하지 않고, close 시점에 진행 중이던 요청의 `loading=true`가 그대로 남는다(현재 테스트가 이를 고정하며 C4에서 다룬다).
- **취소:** `CancellationException`은 전파하고 오류 state로 바꾸지 않는다.
- **플랫폼 소유자(C4 유지 계약):** Android `ItemDetailPresenterOwner`(`ViewModel`)는 `onCleared()`에서, iOS `ItemDetailPresenterOwner`(`@MainActor @Observable`)는 `close()`/`deinit`에서 Presenter를 닫는다. 두 owner 모두 화면을 그리지 않는다. 자세한 내용은 [Android](android.md)·[iOS](ios.md) 문서의 C2 절에 있다.
- **검증:** commonTest `ItemDetailPresenterTest`(조회·재시도·refresh 오류·NOT_FOUND·마지막 요청 승리·늦은 응답·close·계정/세대 변경·관찰 지연 경합·취소 전파·주입 dispatcher)와 `SharedModulesTest`의 runtime Presenter 연결을 Android host와 iOS simulator에서 실행한다. Task 1 interop probe는 삭제했고, Swift Flow 수집·collector 취소·suspend·close·계정 전환은 `SharedInteropTests`가 실제 Presenter·runtime으로, Swift `PlatformTokenSource` callback 성공/오류는 REMOTE ITEM-03 runtime + 도달 불가 base URL로 공개 API만 써서 검증한다(성공은 token이 전달되어 NETWORK, 오류는 `UNAUTHENTICATED`와 Swift `errorCode`).

## C2 최종 검증 요약

> 2026-10-07 Task 10. 명령·건수·로그 경로는 [C2 최종 검증 기록](../../history/architecture/client/c2-final-verification-2026-10-07.md)에 있다.

- 의존성 baseline(Kotlin 2.3.21·AGP 9.0.0·Gradle 9.3.0·catalog)은 Task 1 이후 변경이 없다([호환성 기록](../../history/architecture/client/c2-dependency-compatibility-2026-10-07.md)).
- shared commonTest는 Android host 246개·iOS simulator 243개(차이 3개는 Android host 전용 `OkHttpRedirectTest`), Android 단위 테스트는 debug/release 각 66개, iOS XCTest 81개를 실행했고 실패·오류·skip은 0이다.
- Android Context SQLite driver는 compile/assemble만 확인했고 기기 runtime smoke는 C3로 넘긴다. Darwin redirect는 Ktor 3.4.3 delegate 소스 검토만 했고 실제 검증은 C12다. 실서버 호출은 어디에서도 실행하지 않았다.
- 각 Task 리뷰에서 남긴 경미한 결함(deferred minor)은 수정하지 않았다. 위 절들은 해당 한계(예: Presenter close 이후의 경합, DB I/O dispatcher, bootstrap 실패 시 DEBUG never-ready)를 숨기지 않는 범위로만 서술한다.
