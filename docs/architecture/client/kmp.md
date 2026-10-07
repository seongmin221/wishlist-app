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
- 현재 공통 코드는 두 앱 연결을 확인하는 `AppInfo`·interop probe와 아래 공통 결과·인증 세션 계약을 포함한다. 상품 모델·상태 정책, Create/Get 저장소 인터페이스와 보드 시드도 포함한다. 실제 저장소 backend·화면 비즈니스 기능은 후속 구현 대상이다.

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


## 상품 모델·상태 정책 경계

> 2026-10-07 Task 2b 구현 — 서버 기준은 Task 1에서 병합한 B2 `1c6d949081d47ddb28e60c00eda44b4aa0d91fb0`의 `WishlistItemDtos.kt`·`WishlistItemPolicy.kt`·정책 테스트다. 이 절은 공통 snapshot과 Fake 정책 계약이며 HTTP DTO·저장소·formatter 구현을 포함하지 않는다.

- `WishlistItem`은 raw string ID·`clientSubmissionId`·양수 `version`·`sourceUrl`과 `ProductSnapshot`, `ItemCategory`, `ItemPurpose`, `ItemAnalysis`, review/lifecycle, 서버 `requiredAction`·`allowedActions`, 생성·갱신·수동 완료·기기 공유 시각을 보관한다. 상태 축과 수동 완료는 독립적이다. 모델은 서버 행동을 재계산하지 않으며 UI는 `WishlistItem.allowedActions`를 직접 읽는다.
- `ProductSnapshot`은 nullable 이름·이미지 URL·정밀 가격·통화·브랜드·판매처·metadata 확인 시각·이름/이미지 출처를 보존한다. `ItemCategory`는 nullable ID·source·missingReason·name·parentId·raw kind를 보존한다. category ID가 있으면 missingReason은 null이어야 한다. `ItemPurpose(id, source)`는 사용자가 목적을 해제한 `(null, USER)`도 허용한다. 분석의 공개 `failureCode`는 nullable raw string이다.
- `Category(id, name, parentId, kind, version)` 한 목록으로 B2 상위 그룹(`G001`~`G011`, parent/kind/version null), 공용 leaf(`C001`~`C087`, G-ID parent, PUBLIC, version null), custom leaf(UUID, G-ID parent, CUSTOM, 양수 version)를 표현한다. kind는 nullable raw string이므로 새 kind를 보존한다. ID는 UUID 타입으로 제한하지 않으며 UUID 검사는 생성·요청 경계의 책임이다. C1 보드의 사용자 분류 이름은 후속 시드에서 custom UUID leaf로 매핑한다.
- `Purpose`는 raw ID·name·nullable description·raw colorKey/iconKey·version·createdAt/updatedAt의 최소 snapshot이다. 알려진 키는 `PurposeKeys`에 제공하되 새 키도 모델에 그대로 보관한다. `Archive`는 ID·title·originalPurposeId·목적 표시 snapshot·createdAt만 둔다. 이는 과거 표시용 모델이며 아직 구현되지 않은 archive wire·후보 페이지·복원/삭제 명령 계약을 확정하지 않는다.
- `LocalSubmission`은 확정된 로컬 계약인 clientSubmissionId/sourceUrl/createdAt/accountBinding/submissionStatus/serverItemId/lastSubmissionError를 보관한다. 상태는 PENDING/SUBMITTING/ACCEPTED이며 마지막 오류는 nullable `ClientError`다. 기본은 미귀속 PENDING이다. 재전송·계정 격리·영속 transaction 구현은 후속 task의 책임이다.
- Kotlin 모델 시각은 `kotlin.time.Instant`다. framework의 Instant 타입은 `KotlinInstant`로 노출되므로 Swift는 공개 `createdAtIso`, `updatedAtIso`, `manualCompletionAtIso`, `clientCreatedAtIso`, `metadataCheckedAtIso` 문자열 getter를 사용한다(각 모델이 가진 시각만 제공). nullable 시각은 nullable 문자열이다. Kotlin의 `Purpose.description`과 `ArchivePurposeSnapshot.description`은 property-target `@ObjCName`으로 Swift의 `purposeDescription`에 노출해 `NSObject.description()` 충돌을 피한다. 모든 snapshot은 Android/Swift 구체 state가 사용할 수 있도록 public이다.
- Fake만 `evaluateItem`을 호출해 저장할 행동을 채운다. 순서는 비ACTIVE → PROCESSING → 이름 누락 → EXTRACTION_UNRESOLVED → AI_ABSTAINED/AI_RESPONSE_UNUSABLE → CUSTOM_CATEGORY_DELETED → 이유 없는 category 누락 → PENDING 검토다. 수동 완료 후에는 원래 analysis를 보존하고 MANUAL_COMPLETE/REANALYZE/REVIEW를 더 허용하지 않는다. READY의 삭제된 category는 EDIT로 재지정한다. DEFERRED는 검토를 다시 요구하지 않는다.
- `sanitizeAllowedActions(analysisStatus, requiredAction, actions)`는 후속 Remote mapper/Fake의 매핑 경계에서 사용한다. analysis 또는 requiredAction이 UNKNOWN이면 제공된 행동 중 DELETE만 남기며 서버가 허용하지 않은 DELETE를 만들지 않는다. 알려진 상태는 입력 행동을 유지한다. review/source/missingReason의 설명용 UNKNOWN은 서버 행동을 변경하지 않는다. unknown wire action 제거와 unknown lifecycle의 INVALID_RESPONSE 처리는 후속 HTTP mapper에서 담당한다.
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
