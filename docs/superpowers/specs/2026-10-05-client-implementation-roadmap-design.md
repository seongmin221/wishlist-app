# KMP 클라이언트 MVP 구현 로드맵 설계

> 상태: **확정** · 날짜: 2026-10-05

## 목표와 범위

iOS(SwiftUI)·Android(Compose) 앱에서 MVP 화면 52개(라이트·다크 보드 104개)와 4개 사용자 흐름을 핸드오프 디자인·모션대로 구현한다. LocalSubmission, 상태 계산, API, 동기화 같은 비즈니스 규칙은 KMP에 한 번만 둔다. 서버(`server/*` 워크스페이스)가 B1~B11 묶음을 끝낼 때마다 해당 기능을 실제 API로 전환해 종단 동작을 확인하는 것이 성공 기준이다.

이 문서는 전체 진행 구조와 단계별 경계를 고정한다. 단계별 파일·테스트 수준의 작업은 각 단계 시작 시 별도 plan으로 작성한다.

## 기준 자료

우선순위는 제품 문서 → [디자인 결정](../../design/decisions.md) → 완성 화면 → 와이어프레임 순이다.

- 제품: [제품 INDEX](../../product/INDEX.md), [상품 상태 용어](../../product/references/item-states.md)
- 디자인: `design/handoff/screens/`(구현 기준 보드·PNG), `design/handoff/interactions/`(`motion.md`, `tokens.json`, `screens.md`), `design/handoff/wireframes/FlowMap`
- 클라이언트 구조: [Client 구조](../../architecture/client/README.md), [KMP](../../architecture/client/kmp.md), [초기 셋업](../../architecture/client/initial-setup.md)
- 서버 계약: [WishlistItem 상태·API 계약](../../architecture/wishlist-item-state-api.md)(확정), 서버 워크스페이스의 `docs/architecture/server/mvp-api-inventory.md`(API ID 37개)·`mvp-api-implementation-order.md`(B0~B11)

2026-10-05 기준으로 `design/handoff`는 `origin/main`에 병합됐다(PR #4). `client/initial-setup` 브랜치는 main보다 5커밋 뒤처져 있어 핸드오프 사본이 없으므로 C0에서 리베이스한다.

## 확정한 결정

| 결정 | 선택 | 대안과 이유 |
| --- | --- | --- |
| 서버 의존 | 계약 우선 + KMP fake | 서버 순서만 따르면 대기 시간이 길다. 로컬 우선 데모는 서버가 계산하는 상태(`requiredAction`, version 충돌)와 맞지 않는다. |
| 플랫폼 순서 | 기능마다 KMP → Android → iOS를 같은 단계 안에서 끝냄 | 한 플랫폼을 먼저 하면 Swift 연결 문제를 늦게 발견한다. |
| KMP 스택 | Ktor client, kotlinx.serialization, SQLDelight, Koin, SKIE, multiplatform-settings, kotlin.test·Turbine·Ktor MockEngine | Room KMP + KMP-NativeCoroutines는 Swift 쪽 연결 코드가 더 길다. |
| 인증 | 플랫폼별 Firebase 공식 SDK → KMP `AuthTokenProvider` 인터페이스 | 공식 SDK를 그대로 쓰고 KMP는 token만 받는다. |
| 이미지 | Android Coil 3, iOS는 C4에서 자체 로더와 Nuke 중 선택 | — |
| 화면 상태 | KMP Presenter가 비즈니스 상태를 소유하고 플랫폼은 얇은 래퍼 | 플랫폼 ViewModel마다 구현하면 제품 규칙이 두 번 생긴다. |
| 진행 구조 | 기반(C0~C2) → 서버 묶음 순서에 맞춘 기능 단계(C3~C12) | 탭 화면 순서는 홈이 모든 기능에 의존해 재작업이 생긴다. 계층 순서는 화면 요구를 늦게 발견한다. |
| 브랜치 | 단계마다 `client/cN-<이름>` 브랜치 하나와 PR 하나, main 기준 | — |

## 구조

```text
client/shared/   (Gradle 모듈 1개, 패키지로 경계 구분)
  core/          결과·오류 코드, Clock, ID 생성, dispatcher
  model/         WishlistItem, Category, Purpose, Archive, LocalSubmission, 상태 축 enum
  data/remote/   Ktor API, DTO ↔ model 매퍼, 오류 계약(code·requestId)
  data/local/    SQLDelight: LocalSubmission, 화면 window 캐시, 마지막 상위 카테고리
  data/fake/     API ID 기준 메모리 fake와 보드 기반 시드
  repository/    API ID 단위 인터페이스 + Remote/Fake 구현
  domain/        홈 조치 합성, 허용 행동, 편집 초안 dirty 판단, 연속 처리 큐, 가격 표기, 수신 URL 검증
  presentation/  화면별 Presenter: StateFlow<State> + intent 함수
client/android/  designsystem/, navigation/, feature/<화면>/, share/, webview/
client/ios/      DesignSystem/, Navigation/, Features/<화면>/, ShareExtension/, WebView/
```

- 의존 방향은 `presentation → domain → repository(interface) ← data(remote | fake | local)`이고, 플랫폼 UI는 `presentation`만 참조한다.
- Presenter는 화면 데이터, 로딩·오류, 편집 초안과 dirty 여부, 연속 처리 진행(`n / 전체`, `확정 n · 보류 n`), 409 복구 상태를 소유한다. 플랫폼 래퍼(Android `ViewModel`, iOS `@Observable`)는 Presenter의 수명과 시트 열림·애니메이션·스크롤 anchor 측정 같은 순수 UI 상태만 다룬다.
- 내비게이션 스택과 탭별 상태 유지는 플랫폼이 구현한다. 공유 요소 전환이 플랫폼 API에 묶여 있기 때문이다.
- Fake와 Remote는 Koin 모듈과 debug flavor/scheme으로 선택한다. Remote는 API ID 단위로 하나씩 교체한다.
- 플랫폼 고유 책임은 공유 수신(iOS Share Extension + app group, Android `ACTION_SEND`), Firebase 로그인, 웹뷰, 사진 선택·업로드, 네트워크·foreground 감지다. LocalSubmission 기록은 KMP가 한다.

## 서버 계약과 fake

- DTO는 확정 계약과 서버 API 목록에서 옮긴다. 서버가 아직 고정하지 않은 필드명·오류 코드는 `CONTRACT-PENDING(<API-ID>)` 표시와 함께 DTO·매퍼 안에만 둔다.
- fake는 서버 상태 규칙을 재현한다. version 증가와 `expectedVersion` 불일치 409, `PROCESSING` 항목의 삭제 전용, idempotency key 재사용, 목적 삭제 시 확정된 목적 미지정 전환, 아카이브 전체 복원을 포함한다. 지연·실패 주입과 분석 진행(`PROCESSING → READY/PARTIAL/FAILED_*`) 제어를 제공한다.
- 같은 `RepositoryContractTest`를 Fake와 Remote(MockEngine)에 모두 실행한다.
- 시드 데이터는 완성 화면 보드의 상품·목적·카테고리를 옮겨 화면 비교에 바로 쓴다.
- `docs/architecture/client/server-integration-status.md`가 API ID별로 fake / remote / 실서버 검증 완료 상태를 추적한다.

서버 묶음이 끝나면 해당 단계에서 다음을 수행한다. 서버가 늦으면 fake로 단계를 완료하고 C12에서 몰아서 전환한다.

1. 서버의 확정 계약을 읽고 DTO와 `CONTRACT-PENDING`을 정리한다.
2. Remote 구현으로 교체하고 계약 테스트를 통과시킨다.
3. local 서버(Docker PostgreSQL + Firebase Auth Emulator)에서 시나리오를 수동 검증한다.

갱신과 동기화는 [확정 계약](../../architecture/wishlist-item-state-api.md)을 따른다.

- 갱신은 신규 실행, foreground 복귀, 당겨서 새로고침 때만 한다. polling은 하지 않는다.
- 신규 실행은 최신 첫 window를 조회하고, 복귀·새로고침은 anchor 앞뒤 20개를 갱신한다.
- LocalSubmission 전송 응답이 오면 서버 항목 upsert와 LocalSubmission 삭제를 한 로컬 transaction으로 처리한다.
- 네트워크가 복구되면 자동 재전송한다. 다른 계정에 묶인 대기 항목은 숨긴다.
- 오프라인 편집 큐는 두지 않는다. 저장 실패 시 입력 초안을 유지한다.

인증은 Ktor가 Bearer token을 붙이고 401이면 token을 한 번 갱신해 재시도한다. Firebase 프로젝트가 준비되기 전에는 fake 인증으로 진행한다.

## 디자인 시스템과 모션 기반

- `client/tools/gen_tokens`가 `tokens.json`과 디자인 결정의 값으로 `WishlistTokens.kt`·`WishlistTokens.swift`를 생성한다. 범위는 라이트·다크 색, 목적 6색(면·라이트 표시 테두리), 상태 아이콘 타일 5종, 위험 색 `#C62828`, 모서리 xs 10 ~ xl 36, 간격 4 단위, 곡선 9개와 모션 값이다.
- 목적 색·아이콘의 UI token key(`coral`, `mustard` …)와 wire key를 구분한다. 서버 B3는 `CORAL` 등 6색·`HEART` 등 8아이콘의 대문자 key를 확정했으며 요청은 exact match다. C6에서 명시적인 UI↔wire 변환을 둔다([목적 계약](../../architecture/server/purpose-management-api.md#표시-key-리소스)).
- 서체는 도현(목적 이름 제목·앱의 목소리 전용)과 IBM Plex Sans KR(400·500·700, 숫자는 tabular)을 번들한다. 텍스트 스타일 이름(`display28`, `display20`, `title`, `body`, `label`, `price` 등)은 두 플랫폼에서 같다. 한글 기준 밑줄 3px 같은 글꼴별 보정값은 스타일에 넣는다.
- 공통 컴포넌트는 두 플랫폼에서 이름이 같다: `WLButton`, `WLCard`, `WLIconTile`, `WLChip`, `WLInput`, `WLUnderlineField`, `PriceText`, `PurposeDot`, `EmptyState`, `WLBottomSheet`, `WLConfirmDialog`, `WLMenu`, `Scrim`, `ExpandableGroup`, `Masonry2Col`.
- 시스템 시트·탭·push 전환은 쓰지 않는다([모션 명세](../../../design/handoff/interactions/motion.md)).

| 부품 | iOS 17+ | Android API 26+ |
| --- | --- | --- |
| 바텀시트(`spring-sheet`) | ZStack 오버레이 + `timingCurve` | `Animatable` + offset |
| 뒤 블러 | `.blur(12)` | API 31+ `Modifier.blur`, 미만은 어두운 막만 |
| 탭 페이드 스루·알약 | 직접 그린 탭 바 + 세 탭 ZStack | `AnimatedContent` + `SaveableStateHolder` |
| 공유 요소 push(사진·면) | `matchedGeometryEffect` 직접 구현 | `SharedTransitionLayout` sharedElement·sharedBounds |
| 끌어서 뒤로 | 가장자리 드래그 진행값 | `PredictiveBackHandler` |

- iOS 18의 zoom 전환은 곡선이 다르고 최소 버전이 17이라 기본 수단으로 쓰지 않는다.
- iOS 자체 라우터(스택 상태 + 오버레이 전환)는 C1에서 spike로 먼저 검증한다. 시스템 스와이프 뒤로와 VoiceOver 동작을 포함한다. 실패하면 `NavigationStack` + 커스텀 전환으로 내려가고, 그 차이를 디자인 결정에 기록한다.
- 카드 던지기와 정보 보완 넘김은 C7·C8에서 같은 부품 위에 구현한다.
- 화면 충실도는 단계마다 시뮬레이터·에뮬레이터 390×844 라이트·다크 스크린샷을 핸드오프 `shots/*.png`와 나란히 놓고 비교해 PR에 첨부한다. 픽셀 자동 비교는 하지 않는다.

## 단계

| 단계 | 내용 | 주요 보드 | 서버 |
| --- | --- | --- | --- |
| C0 | 셋업 미커밋 변경 커밋, `origin/main` 리베이스, 핸드오프 확보 확인, "핸드오프 사본 없음" 문구 정리, 이 spec과 C1 plan 커밋 | — | — |
| C1 | 완료(2026-10-06, 목적 아이콘 key 초안은 C1 계획에 없어 남김). 토큰 생성기·서체·공통 컴포넌트, 탭 셸, 시트·확인창·push 전환 데모, iOS 라우터 spike, 목적 색·아이콘 key 초안 | 모션 보드 4종, 탭 바 | — |
| C2 | 구현·로컬 검증 완료(2026-10-07, draft PR 2026-10-09; 실서버·Android Context SQLite 실기기·Darwin redirect는 C3/C12 인계). KMP 핵심: 모델·상태 축, repository 인터페이스, fake·시드, SQLDelight, Ktor client 뼈대·오류 매핑, `AuthTokenProvider`, Koin, SKIE 연결 확인, Presenter 기반 | — | — |
| C3 | 구현·로컬 검증 완료(2026-10-07, PR 대기; fake 인증만, 공유 확장 C안 중 확장 전송은 끔([ADR-030](../../history/architecture/client/ADR-030-share-receipt-mode.md)). 인계: 가입 뒤 "인증 연결"(Firebase·Apple/Google을 `AuthFacade` 뒤에, iOS 확장 background 전송, ITEM-01 실서버, 실기기·팀 서명), C4 원본 웹뷰·대기 항목 삭제 UI, C5 Android 모듈 분리 재검토, C7 FHome 나머지 카드, C12 라이선스 화면). 저장: 공유 수신, LocalSubmission, 로그인 안내·건너뛰기, 로그인 전 홈·분석 대기, 전송·재전송, 설정(로그아웃·웹뷰 데이터 삭제) | FLogin, FHomeLoggedOut, FShareSaved*, FSettings* | ITEM-01 |
| C4 | 상품 상세(보기)·분석 중·원본 링크 웹뷰 | FProductDetail, FProductProcessing, FWebView* | B1 |
| C5 | 카테고리 탭·세부 유형 목록(fake 목록)·선택 시트·생성·편집 | FCategoryHome/AddSheet/List/ListCustom/ListCustomEmpty/EditSheet, FProductCategoryPicker/Create | B2 |
| C6 | 목적 탭·상세(머리 접기)·생성·편집 | FPurposeHome/Create/Detail/DetailEmpty/EditInPlace | B3 |
| C7 | 카테고리·목적 목록의 window·anchor 복원, 홈 할 일·목적 요약, 연속 처리 조회와 카드 모션 | FHome, FHomeReviewFlow, FHomeFillFlow | B4 |
| C8 | 상품 편집·직접 보완·검토 결정·삭제·재분석·사진 업로드 | FProductEdit/PurposeSheet/Fill/FillEdit/DeleteConfirm | B6~B7 |
| C9 | 후보 추가·이동, 사용자 카테고리·목적 삭제 | FPurposeAddCandidates/AddCategoryFilter/DeleteConfirm, FCategoryDeleteConfirm | B8 |
| C10 | 중복 후보 비교와 선택 | FDuplicate* | B9 |
| C11 | 비교 끝내기·아카이브 목록·상세·제목 수정·복원·삭제 | FPurposeFinish*, FArchive* | B10 |
| C12 | 남은 Remote 전환, 실서버 종단 시나리오, 실제 기기·서명 검증 | — | B11 |

C1과 C2는 서로 의존하지 않아 병렬로 진행할 수 있다. C3 이후는 순서대로 진행하되, 서버가 늦은 단계는 fake로 완료한다.

## 단계 진행 절차와 완료 기준

각 단계(C1~C11)는 다음 순서로 진행한다.

1. `docs/superpowers/plans/YYYY-MM-DD-client-cN-<이름>.md`를 writing-plans 형식으로 작성한다. 시작 전에 서버 최신 계약·진행 상황, 해당 보드와 `interactions/screens.md`, 그 단계에 걸린 미결정 사항을 다시 확인한다.
2. KMP의 domain 규칙, Presenter 상태 전이, Fake/Remote 계약 테스트를 TDD로 작성한다. `commonTest`는 Android host와 iOS simulator에서 실행한다.
3. Android 화면과 iOS 화면을 같은 Presenter에 연결해 같은 단계 안에서 완성한다. 보드에 있는 시트·확인창·펼침 상태를 모두 구현한다.
4. `./gradlew :shared:allTests :android:assembleDebug :android:lintDebug`, `xcodebuild` simulator build, 두 플랫폼 스크린샷 비교, 예외 경로(409·실패·오프라인·빈 상태) 수동 확인을 수행한다.
5. `docs/architecture/client/`, 의미 있는 결정은 `docs/history/architecture/client/`, 새로 설명한 개념은 `docs/learning/client/q-and-a/`에 기록한다. 커밋은 `feature(kmp|ios|android): 한글 설명` 형식을 따른다.

완료 기준:

- 해당 보드의 모든 상태가 두 플랫폼에서 동작한다.
- 모든 경로를 fake로 끝까지 시연할 수 있다.
- 계약 테스트가 통과한다.
- `server-integration-status.md`에 fake/remote 상태와 `CONTRACT-PENDING`이 정리돼 있다.

기본 실행 방식은 subagent-driven이다. KMP 작업이 끝난 뒤 Android·iOS 화면 작업은 같은 Presenter 인터페이스만 보면 되므로 병렬로 진행할 수 있다. 단계 plan마다 실행 방식을 다시 고른다.

## 미결정 사항과 결정 시점

| 항목 | 결정 시점 | 진행용 기본값 |
| --- | --- | --- |
| 공유 확장의 서버 직접 전송 여부(공유 카드 문구에 영향, [QA-CLI-002](../../learning/client/q-and-a/QA-CLI-002-share-receipt-feedback.md)) | C3 시작 전 | **C3에서 C안으로 결정([ADR-030](../../history/architecture/client/ADR-030-share-receipt-mode.md)).** iOS 확장은 app group inbox에 기록, 앱이 같은 key로 전송. 확장 background 전송은 "인증 연결" 단계에서 켬 |
| 목적 색·아이콘 key 목록 | **B3 확정**, C6 반영 | 대문자 고정 key 6색·8아이콘. UI 소문자 token과 변환 경계를 둔다([계약](../../architecture/server/purpose-management-api.md#표시-key-리소스)) |
| 연속 처리 "처음부터 다시 보기" 범위 | C7 | 이번 세션에서 건너뛴 항목만. 보류 항목을 다시 여는 서버 API는 없다 |
| 목적 활동순 정렬·홈 노출 개수 | **B3/B4 확정**, C6/C7 반영 | 생성·후보 유입만 활동순을 바꾸며 편집·제거·조회는 유지. 목적 목록은 limit 생략 후 전체 재조회, 홈은 최대 3개([목적](../../architecture/server/purpose-management-api.md#pur-01-목록), [홈](../../architecture/server/wishlist-item-read-api.md)) |
| 사진 업로드 MIME·크기·압축 | C8 / 서버 B6 | 긴 변 2048px JPEG로 압축 |
| Firebase 프로젝트·Apple 로그인 설정 | Apple Developer 가입 뒤 "인증 연결"(C3에서 미룸, C3-D2) | fake 인증(`AuthFacade` 뒤 `FakeAuthFacade`) |
| 실제 앱 ID·서명 | C12 | 임시 ID 유지 |

## 위험

- **SKIE의 Kotlin 2.3.21 지원:** C2 첫 작업에서 확인하고, 안 되면 KMP-NativeCoroutines로 바꾼다.
- **iOS 자체 라우터:** C1 spike에서 실패하면 `NavigationStack` + 커스텀 전환으로 내려간다.
- **서버 계약 변동:** DTO·매퍼 한 곳과 계약 테스트로 영향 범위를 제한한다.
- **화면 수:** 단계별 대상 보드를 위 표로 고정해 범위가 불어나지 않게 한다.
- **Compose blur:** API 26~30은 어두운 막만 쓴다. 디자인과의 차이로 받아들인다.

## 범위 밖

시스템 "동작 줄이기" 대응, iPad 전용 레이아웃, 오프라인 편집, push 알림, 검색.
