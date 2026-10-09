# Client·Server 연동 정밀검사 (2026-10-09)

## 검사 기준과 판정

검사 기준 HEAD는 `419be18`(client C3 PR #12·server B4 PR #13 병합)이다. 실제 코드·서버 계약·클라이언트 Fake/MockEngine·런타임 조립·최근 변경 문서를 함께 대조했다. 최초 검사에서는 제품 코드를 수정하지 않았다. 이후 사용자 승인으로 진행한 수정과 재검증은 마지막 절에 구분했다.

**현재 앱을 그대로 실행해서 서버와 연동할 수는 없다.** DEBUG는 ITEM-01/03 Fake만 사용하고 RELEASE는 37개 API 모두 Unavailable이다. 이는 C2/C3의 의도된 범위다. Remote ITEM-01/03의 기본 wire 계약은 호환되지만, 실제 인증 조립이 없고 아래 재현된 결함·후속 계약 차이가 있으므로 앱 단위 연동 완료로 판정하지 않는다.

서버가 완료한 제품 API는 ITEM-01/02/03, HOME-01/02, CAT-01~04, PUR-01~04의 **13개**다. 클라이언트 Remote는 그중 ITEM-01/03의 **2개**뿐이다. allowedActions가 반환되는 것과 해당 mutation API의 구현 완료는 별개다.

## 검사 당시 코드의 결함과 테스트 계약 차이

### 1. 대문자 scheme 공유 링크가 서버에서 영구 실패 — P2

- 클라이언트 [ShareTextParser](../../../../client/shared/src/commonMain/kotlin/app/wishlist/shared/domain/ShareTextParser.kt)는 case-insensitive 정규식으로 링크를 받고 원문을 보존한다. 기존 테스트에도 `HTTPS://A.EXAMPLE/Path`를 정상 링크로 받는 사례가 있다.
- 서버 [CreateWishlistItemService](../../../../server/src/main/kotlin/app/wishlist/CreateWishlistItemService.kt)의 `isPublicHttpUrl`은 `URI.scheme`을 `http`, `https`와 대소문자 구분해서 비교한다. extraction의 `UrlSafetyPolicy`는 이미 소문자화해서 검사하므로 서버 내부에도 기준 차이가 있다.
- 실제 HTTP에서 같은 대문자 링크가 `422 INVALID_URL` → Remote `VALIDATION`으로 반환된다. `SubmissionErrorPolicy`는 이를 영구 `FAILED`로 분류한다. Fake에서는 URL 검증을 하지 않아 성공한다.
- 후속 수정: 서버의 scheme 판정만 대소문자 무관하게 처리한다. 저장할 URL 원문과 멱등 replay의 원문 비교는 유지한다. 이 링크를 client→server 계약 회귀 사례에 넣는다.

### 2. 계정 전환과 전송 실패가 겹치면 오류 계약 위반 — P2

- [WishlistHttpClient](../../../../client/shared/src/commonMain/kotlin/app/wishlist/shared/data/remote/WishlistHttpClient.kt)의 `AuthenticatedTransport.execute`는 `send` 실패를 즉시 반환한다(첫 전송과 401 후 재전송 둘 다). 실패 뒤 session 비교가 없다.
- [RemoteItemRepository](../../../../client/shared/src/commonMain/kotlin/app/wishlist/shared/data/remote/RemoteItemRepository.kt)도 transport 실패를 바로 반환하여 성공 응답 뒤에 있는 session 검사를 우회한다.
- 임시 집중 테스트에서 HttpSend interceptor가 A→B 계정 변경 후 전송 예외를 던지도록 했다. 기대는 `SESSION_CHANGED`, 실제는 **`NETWORK`**였다. 재현 테스트 1개가 이 assertion으로 실패했다.
- 영향은 repository 오류 의미의 불일치다. coordinator의 `markSubmission(snapshot, …)`와 LocalStore의 session gate가 이전 계정에 대한 stale 쓰기를 차단하므로 **교차 계정 저장·유출로 확인된 문제는 아니다**.
- 후속 수정: 실패 결과를 반환하기 전에도 snapshot 검사를 적용하고, 첫 전송·401 재전송·token 실패의 모든 종료 경로를 회귀 검증한다. 오류 body를 읽는 동안의 계정 변경은 별도 재현하지 않았으므로 확정 결함으로 확대하지 않는다.

### 3. Fake·MockEngine의 일부 계약이 서버보다 느슨함 — 검증 공백

| 차이 | 실제 서버 | Fake / MockEngine | 영향 |
| --- | --- | --- | --- |
| 생성 URL | URI·길이·UTF-8·localhost 등 검사 | Fake와 ScriptedItemServer는 URL 정책을 재현하지 않음 | Fake 정상 공유가 실서버에서 실패할 수 있음 |
| 생성 시각 | year 1..9999·UTC 변환·microsecond 절삭 | Fake는 원래 Instant 보존 | 서버 반환 시각과 원본 시각의 완전 일치를 가정하면 안 됨 |
| 비정규 상품 ID GET | 400 `INVALID_WISHLIST_ITEM_ID` → VALIDATION | ScriptedItemServer 404, RemoteItemRepositoryTest는 NOT_FOUND 기대 | 잘못된 오류 계약을 정상으로 검증하고 있음 |
| 멱등 key | canonical UUID 검사·대소문자 동일성 | ScriptedItemServer는 존재 여부만 검사·문자열 key 사용 | 잘못된 key와 UUID case replay를 기존 MockEngine 테스트가 놓침 |

근거: [FakeStore](../../../../client/shared/src/commonMain/kotlin/app/wishlist/shared/data/fake/FakeStore.kt), [ScriptedItemServer](../../../../client/shared/src/commonTest/kotlin/app/wishlist/shared/data/remote/ScriptedItemServer.kt), [Remote 테스트](../../../../client/shared/src/commonTest/kotlin/app/wishlist/shared/data/remote/RemoteItemRepositoryTest.kt), [서버 parser](../../../../server/src/main/kotlin/app/http/CreateWishlistItemRequestParser.kt), [서버 route](../../../../server/src/main/kotlin/app/http/WishlistRoutes.kt).

이번 실제 HTTP 대조에서도 `get("a b/c?d")`는 VALIDATION으로 반환됐다. production error mapper는 이 경우 올바르게 동작한다. 잘못된 Mock 기대값을 production 오류로 분류하지 않는다.

## B3/B4 완료 후 인계해야 할 계약

이 항목들은 아직 구현하지 않은 C4~C7에 대한 차이다. 현재 Fake 전용 C3 앱의 출시 회귀로 분류하지 않는다.

| 영역 | 현재 client 가정·구현 | 확정 서버 계약과 필요한 변경 |
| --- | --- | --- |
| 목적 표시 key | PurposeKeys는 `coral`, `heart` 등 소문자 | B3 wire key는 `CORAL`, `HEART` 등 exact-match 대문자. C6 요청에 UI token key를 그대로 보내면 422. 명시적 변환 필요 |
| 상품의 목적 표시 | PurposeDto·ItemPurpose는 id/source만 보존 | B3 추가 `name/colorKey/iconKey`를 현재 decoder가 무시함. C4/C6에서 DTO→domain→SQLite 표시 경로 확장 |
| 목적 표시 캐시 | item version이 높을 때만 SQLite upsert | 목적·카테고리 편집은 item version을 올리지 않음. 같은 version의 표시 metadata를 위한 별도 갱신 정책 필요. GET 호출자에게는 최신 delegate 응답을 반환하므로 현재 상세 GET 결과 자체가 오래된다는 뜻은 아님 |
| 목적 projection | 현재 Purpose는 createdAt/updatedAt 필수이며 집계 없음 | SELECT/SUMMARY에는 해당 시각 필드가 없음. 상세·SELECT·SUMMARY를 projection별 모델로 구성하고 없는 값을 꾸며 넣지 않기 |
| 목적 정렬 | 로드맵은 최근 수정순 Fake를 기본값으로 제시 | 생성·후보 유입만 activity를 바꿈. 편집·제거·조회는 순서 유지. 최대 30개이므로 limit 생략, 진입·foreground·refresh에 전체 재조회 |
| 목록 eligibility | Fake Catalog는 category/purpose 모두 `isListEligible` 적용 | category scope는 ACTIVE+이름 존재, purpose scope는 ACTIVE+purpose 일치만. purpose 목록은 PROCESSING·이름/category 없는 후보도 포함하여 candidateCount와 일치해야 함 |
| category projection | seed용 평면 Category 목록 | SELECT/BROWSE·G-ID 그룹 집계·displayOrder·빈 public/custom 규칙 구분. G-ID는 ITEM-02 filter가 아니며 생성/편집 뒤 CAT-01 재조회 필요 |
| 목록 window | ITEM-02 Remote 미구현 | scope-bound opaque anchor/page cursor, anchor 조회와 cursor/limit 혼용 금지, anchorMatched=false의 정상 복구, 빈 page의 반대방향 cursor 처리 필요 |
| 홈 | 현재 C3는 로컬 대기·processing 표시 | HOME-01은 로컬 대기를 포함하지 않음. 정보 보완은 requiredAction 3종을 합친 group. 최근 목적 최대 3개, null preview 이미지도 슬롯을 차지함 |
| 후속 mutation | FakeControls가 일부 상태를 바꿈 | 공개 ITEM-04~08, 목적 삭제·후보 추가·archive wire 구현을 뜻하지 않음. B7/B8/B10 전에는 allowedActions만 보고 API를 호출하지 않기 |

근거: [B3 목적 계약](../../../architecture/server/purpose-management-api.md), [B2 카테고리 계약](../../../architecture/server/category-management-api.md), [B4 상품·홈 조회](../../../architecture/server/wishlist-item-read-api.md), [window 상태 계약](../../../architecture/wishlist-item-state-api.md), [KMP 알려진 한계](../../../architecture/client/kmp.md).

Window 갱신은 응답을 단순 append하는 것으로 끝나지 않는다. 처리·이동으로 scope에서 빠진 카드의 membership를 해당 갱신 범위에서 반영하고 `resolvedAnchorItemId` 기준으로 위치를 복원한다. 불러오지 않은 범위까지 동기화 완료로 간주하지 않는다. 새 프로세스는 저장된 viewport가 아니라 최신 첫 window로 시작한다.

## 실제 인증·native 앱 연결의 선행 작업

1. `SharedRuntime`은 DEBUG `FakeAuthFacade`, RELEASE `UnavailableAuthFacade`를 고정해서 만들고 MutableAuthSession은 internal이다. RemoteConfig에 URL·tokenSource를 넣는 것만으로 실제 계정을 session에 전달할 수 없다. SDK 인증·계정 변경을 연결하는 공용 bridge/주입 경계를 함께 구현해야 한다.
2. SDK account ID와 서버 owner UUID는 의도적으로 다르다. 서버는 projectId+uid로 owner UUID를 도출하고, client는 SDK UID를 로컬 account binding에 사용한다. client binding을 서버 owner UUID로 임의 변환하지 않는다.
3. 두 플랫폼 AppRuntimeConfig를 REMOTE로 바꾸고 실제 endpoint를 제공해야 한다. 지금 iOS 앱은 remote=nil이다. 로컬 HTTP를 쓰면 Android 기본 cleartext 정책에 맞는 개발 설정이 필요하고 iOS ATS도 실제 실행으로 확인한다. 임시 JVM HTTP 검증은 모바일 OS 정책을 검증하지 않는다.
4. **Android INTERNET 누락이라는 초기 의심은 기각했다.** `:android:processDebugMainManifest`·`:android:processReleaseMainManifest` 결과에서 INTERNET을 확인했고 Debug merger report로 OkHttp Android dependency가 추가했음을 확인했다. 앱 source manifest만 읽고 네트워크 권한 결함으로 판정하면 안 된다.
5. local 서버는 DB/Firebase 설정이 없으면 health-only로 시작하며, DB/Firebase가 있어도 TASKS dispatcher 설정이 없으면 outbox가 자동 worker 전송되지 않는다. API 실행·인증 emulator·queue/worker·분석 완료를 각각 확인한다.
6. iOS 확장은 C3에서 app group inbox만 쓴다. 본 앱 import 전에는 서버 저장·분석을 보장하지 않는다. 직접 background 전송과 shared Keychain, 실제 Firebase token, 실기기 signing은 후속 인증 연결 범위다.

근거: [SharedRuntime](../../../../client/shared/src/commonMain/kotlin/app/wishlist/shared/di/SharedRuntime.kt), [RemoteConfig](../../../../client/shared/src/commonMain/kotlin/app/wishlist/shared/di/RepositoryBindings.kt), [AuthSession](../../../../client/shared/src/commonMain/kotlin/app/wishlist/shared/core/AuthSession.kt), [server Main](../../../../server/src/main/kotlin/app/Main.kt), [Firebase owner](../../../../server/src/main/kotlin/app/http/FirebaseOwnerResolver.kt), [ADR-030](ADR-030-share-receipt-mode.md).

## 문서 수정

- [서버 연동 상태](../../../architecture/client/server-integration-status.md): B2 snapshot을 최신 B3/B4 상태로 갱신. 서버 완료와 client Remote 미구현을 구분하고 인증 런타임 작업을 명시했다.
- [클라이언트 로드맵](../../../superpowers/specs/2026-10-05-client-implementation-roadmap-design.md): 이미 확정된 목적 wire key·activity 정렬·홈 최대 3개를 반영했다.
- [API inventory](../../../architecture/server/mvp-api-inventory.md): Share Extension 수신 방식은 C3에서 확정됐음을 반영했다.

## 실행 검증

검증 결과는 전체 회귀, 정상 계약 대조, 결함 재현을 구분한다. 임시 재현 코드와 HTTP bridge는 검증 후 제거한다.

| 검증 | 이번 실행 결과 |
| --- | --- |
| server `./gradlew test --rerun-tasks` | 345건 중 344 통과·1 skip, 실패/오류 0. `RUN_REAL_URL_PILOT=0`으로 외부 URL pilot만 제외 |
| client `:shared:testAndroidHostTest --rerun-tasks` | 임시 코드 제거 후 400건 통과, 실패/오류/skip 0 |
| client `:shared:iosSimulatorArm64Test --rerun-tasks` | 임시 코드 제거 후 397건 통과, 실패/오류/skip 0 |
| 임시 실제 HTTP client/server bridge | 양쪽 집중 테스트 각 1건 통과. 생성·replay·GET·409·422·404·malformed ID·READY/PARTIAL/FAILED_RETRYABLE/FAILED_TERMINAL·ARCHIVED·DELETED replay 대조. 대문자 scheme 거절도 실제 응답으로 확인 |
| 임시 계정 전환+전송 예외 재현 | 1건 실패: expected SESSION_CHANGED, actual NETWORK. 결함 재현 결과이며 기존 suite 회귀 실패와 구분 |
| Android Debug/Release manifest 병합 | 둘 다 INTERNET 포함. cleartext 허용 설정 없음 |
| 문서 | 상대 경로 링크·`git diff --check` 확인 |

서버는 JDK 17, `DOCKER_HOST=unix:///var/run/docker.sock`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`, `TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1`, `RUN_REAL_URL_PILOT=0`으로 실행했다. client도 JDK 17과 로컬 Android SDK를 사용했다. 실제 HTTP bridge의 서버는 전체 회귀와 출력 경로가 충돌하지 않도록 임시 source 복사본에서 실행했다. 별도 영구 테스트나 제품 설정을 남기지 않았다.

실제 HTTP bridge는 OkHttp client → Netty의 실제 ITEM-01/03 route → 실제 PostgreSQL migration/repository/service → 실제 view mapper → client decoder 경로를 사용했다. owner resolver와 ID token은 테스트 대역이다. 분석 완료 상태는 DB fixture로 만들었고 extraction/AI/worker는 실행하지 않았다. 따라서 **실제 Firebase 인증·native 앱·SubmissionCoordinator·worker까지의 E2E 통과를 뜻하지 않는다.**

후속 순서는 scheme·session 실패 경로·Mock 계약 보완 → 실제 인증 런타임 연결 → native ITEM-01/03+worker 검증 → C4~C7 모델·projection·window 연결이다.

## 후속 수정: bugfix/client-server-contract

사용자가 위 세 가지 수정 범위를 승인한 뒤 develop에서 별도 브랜치를 만들었다.

- 서버는 scheme만 소문자화해서 판정한다. 원본 URL 저장·동일 key의 원문 비교·localhost 차단은 유지한다. 서비스·실제 HTTP route 회귀 2건은 수정 전 InvalidUrl/422로 실패했고 수정 후 통과했다.
- transport의 token 실패·첫 전송 실패·401 재전송 실패·HTTP 오류 body 처리 뒤에도 전체 SessionSnapshot(accountId+generation)을 확인한다. repository도 transport 실패와 성공 body 읽기 실패를 확인한다. 13개 집중 테스트 중 stale 경로 8개가 수정 전 실패했고, 수정 후 전체 통과했다. 같은 계정 재로그인도 거절하고 정상 계정의 오류 정보·CancellationException 전파는 유지한다.
- Fake 생성과 ScriptedItemServer는 URL 입력 검사·정규 UUID case 동일성·공유 시각 범위/정밀도를 반영한다. 공통 계약은 기존 7개에서 17개로 늘었다. malformed GET ID는 400/VALIDATION이며, 원문 URL을 trim하지 않는다. Core UUID helper도 36자 canonical spelling만 허용한다.
- UTF-8로 인코딩할 수 없는 원문 URL은 Remote에서 TextContent 생성 전 VALIDATION/INVALID_URL로 반환한다. 새 corpus가 기존 MalformedInputException 노출을 재현했다. 이 오류를 NETWORK로 재시도하지 않는다.
- iOS Foundation이 잘못된 userinfo·C1 제어문자·숫자 host의 끝 점을 보정하는 차이는 원문 검사와 공통 회귀로 막았다. scoped IPv6 숫자 주소도 DNS 없이 파싱한다. 모든 Foundation/Java URI 문법의 동등성까지 보장하지는 않는다.
- LocalStore용 whitespace fixture를 coordinator 생성 테스트에 그대로 쓰던 네 경로가 실제 생성 검증에 실패했다. coordinator 전용 fixture만 유효 URL로 바꾸고 LocalStore의 원문 저장 테스트는 유지했다.

최종 검증: server 347건 중 346 통과·외부 URL pilot 1 skip, shared Android host 434건·iOS simulator 431건 통과(실패/오류 0). `:shared:allTests --rerun-tasks`와 server 전체 회귀를 실행했다. 코드 리뷰에서 발견한 iOS parser 차이는 별도 Native red→green으로 확인했다. Android Debug/Release 앱 build·unit 각 94건·Debug lint(오류 0, warning 24)와 `:shared:compileKotlinIosArm64`도 통과했다. 기존 Kotlin opt-in warning은 남아 있으며 이번 수정 실패로 집계하지 않는다.

인증 facade 조립·REMOTE 앱 전환·목적 표시 모델·scope별 목록·같은 version 표시 캐시는 후속 단계로 유지한다. 이번 수정의 완료를 앱 전체 E2E 완료로 해석하지 않는다.
