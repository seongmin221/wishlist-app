# 와이어프레임 기준 서버 정밀 검토

> 상태: 검토 완료 · 설계 제안 작성 · 2026-10-04

## 요청과 검토 범위

사용자는 디자인을 진행하는 동안 시작할 수 있는 서버 작업을 찾고, 전체를 정밀 검사해 서버 설계를 구체화하도록 요청했다. 제품 목적은 저장한 상품을 비교·정리하고 구매 결정을 기록하는 기존 MVP다.

- 서버 기준: `server/initial-setup`, `9eacba5`.
- 디자인 기준: `design/handoff`, `c59741d`(origin과 동일, 해당 워크트리 변경 없음).
- 디자인 자료: README·manifest·보드 HTML 43개·FlowMap·support.js·capture.py. 전체 보드의 문구와 입력 요소를 정적으로 조사하고 주요 상태 처리 코드를 읽었다. PNG는 주요 흐름을 시각 확인했다. 실제 브라우저에서 전체 버튼을 실행한 검증은 아니다.
- 서버 자료: main Kotlin 소스, V1~V7 SQL, build 설정, 테스트 구조, 상품·서버·AI 문서. 배포 관련 파일은 저장소에서 검색했으나 실행 가능한 배포 구성은 확인하지 못했다.
- 디자인 브랜치를 병합하거나 제품 코드를 변경하지 않았다.

## 화면별 구현 대조

| 화면 묶음 | 보드 수 | 서버가 해야 할 일 | 현재 구현 |
| --- | ---: | --- | --- |
| 홈·검토·보완·중복 | 7 | 영역별 건수·미리보기·페이지 조회, 검토·보완·중복 결정 | 조회·사용자 결정 API 없음 |
| 카테고리 탐색·관리 | 7 | 공용 분류와 사용자 분류, 상품 수, 생성·수정·삭제 영향 | 공용 taxonomy 리소스만 존재 |
| 목적 탐색·관리·후보 추가 | 8 | 목적 CRUD, 활동순, 후보 조회·일괄 이동·비교 종료 | 목적 테이블·API·사용자 목적 후보 공급 없음 |
| 상품 상세·편집 | 3 | 상세 조회·편집·삭제·재분석, 상태별 허용 행동 | 생성 API만 존재 |
| 카테고리 선택·생성 | 2 | 전체 taxonomy와 사용자 분류, 한도·중복 검증 | taxonomy 데이터 존재, 사용자 CRUD 없음 |
| 원본 웹뷰 | 3 | 원본 URL 제공, 웹뷰 탐색·쿠키는 클라이언트 | 원본 URL 저장·생성 응답에 포함 |
| 아카이브 | 5 | 종료 snapshot·조회·제목 편집·원자적 복원·전체 삭제 | 테이블·API 없음 |
| 로그인·설정·공유 수신 | 8 | Firebase 소유자 확인·계정 범위 생성. 로컬 저장·로그아웃·웹뷰 데이터 삭제는 클라이언트 | UID 검증·소유자 변환·생성 idempotency 존재 |

## 코드에서 확인한 우선 과제

아래 우선순위는 현재 코드를 화면과 연결할 때의 영향이다. 모든 항목이 이미 운영 중 발생한 장애라는 뜻은 아니다.

| 우선순위 | 근거 파일·관찰 | 영향과 후속 작업 |
| --- | --- | --- |
| P0 | `http/WishlistRoutes.kt`의 `itemJson`이 metadata·category·purpose를 null, review와 requiredAction을 상수로 생성 | 분석 뒤 생성 재전송도 실제 결과와 다른 표현을 반환. 모든 조회·생성 응답을 동일 DTO mapper로 구성해야 함 |
| P0 | V1~V7에 사용자 변경 category/purpose, review, manual completion, 현재 generation 없음 | 디자인의 사용자 선택을 영속화할 수 없음. 상태 축과 현재 값·출처·현재 generation을 확장 |
| P0 | `Main.kt`의 후보 공급은 공용 taxonomy만 사용, `TaxonomyCatalog.snapshot`의 목적은 empty | 자동 목적 연결과 사용자 카테고리 분류가 동작하지 않음. owner별 후보 공급·현재 유효성 검증 필요 |
| P0 | `GeneralWorkerService`·`BrowserWorkerService`는 전달 generation과 job을 비교하지만 item의 현재 generation과 비교할 필드는 없음 | 새 재분석 API를 추가하기 전에 늦은 이전 작업의 결과·실패를 막는 조건 필요 |
| P0 | Worker의 일부 실패·retry update에는 현재 실행 claim을 재확인하는 조건이 없고 claim token도 없음 | 복구 후 같은 job을 다시 실행할 때 이전 실행의 결과가 새 실행과 섞일 가능성. 성공뿐 아니라 실패·재시도에도 동일 실행 fence 적용 |
| P0 | `Main.kt`는 browser 역할을 조립하지 않고 `RuntimeConfig`는 api/general-worker만 허용 | browser 서비스 코드는 있으나 runtime으로 배포해 실행할 수 없음. 공개 API 경로와 private worker 경로 분리 후 연결 |
| P0 | 분석 reconciler·outbox dispatcher는 있지만 Main의 주기 실행 연결 없음. budget maintenance는 별도 단발 entry point | 생성 직후 발행 실패·Worker 중단·browser 전환을 복구할 운영 실행 경로가 필요 |
| P1 | `Metadata`·SQL은 제목·설명·이미지·최종 fetch URL 위주 | 화면의 가격·통화·브랜드·판매처·확인 시점을 공급할 수 없음. parser부터 staged 결과·최종 DTO까지 함께 확장 |
| P1 | `canonicalUrl`은 현재 fetch 완료 URL이고 Product cache·중복 후보 테이블/서비스 없음 | 정규화·canonical 검증·중복 해소를 별도 설계. 다른 판매처의 동일 상품 판단은 canonical 비교만으로 구현할 수 없음 |
| P1 | API가 commit 후 동기적으로 Cloud Tasks client 생성·발행을 수행 | 분석은 비동기지만 생성 응답이 task 등록 latency를 기다림. 생성 SLA 내 bounded 발행 또는 주기 dispatcher로 분리 |
| P1 | `PGSimpleDataSource` 사용, API·Worker에서 동기 JDBC/HTTP 실행 | 연결 pool·종료 처리와 blocking IO dispatcher 경계를 명시해야 함 |
| P1 | 오류 JSON에 requestId 없음, 명시적 공통 exception 처리 없음, clientCreatedAt은 읽지 않음 | 오류 계약·요청 DTO validation·추적 ID와 저장순의 기준을 맞춰야 함 |
| 출시 전 | browser URL 검사는 존재하지만 일반 fetch처럼 실제 DNS 연결을 pin하지 않음. 현재 browser 테스트는 canRequest만 검사 | 배포 egress 차단·redirect/subresource·service worker 경계를 검증하기 전 안전성을 확정하면 안 됨 |
| 출시 전 | fetch 단계 MIME 부적합·접근 거부는 Partial, UnsafeUrl 실패에는 pending failure code 기록 없음 | 제품의 terminal/retryable/partial 오류 분류와 사용자 버튼 노출을 일치시켜야 함 |
| 출시 전 | token 계산 20초·Responses 70초와 fetch 최대 15초×redirect가 Worker 90초 예산과 별개 | 단계 timeout 합이 전체 예산을 넘을 수 있음. 남은 deadline을 단계에 전달하고 호출 취소·claim 복구를 연결 |
| 출시 전 | 일반/browser가 서로 다른 attempt count·최초 시각으로 각각 3회·30분을 검사 | 문서의 generation 전체 상한과 차이. fallback과 인프라 재시도를 합친 허용 실행 횟수·deadline 정의를 통일 |

P0는 UI용 신규 API를 본격 연결하기 전에 해결할 경계다. 출시 전 항목은 기존 기능을 production에서 안전하고 지속적으로 실행하기 위한 검증이다.

## 제품 문서와의 차이

1. 디자인 제품 문서는 category 누락을 홈 `정보 보완 필요`로 묶지만 서버 계약은 CATEGORY_ASSIGNMENT·CATEGORY_REASSIGNMENT를 분리한다. 공개 홈 동작을 통일하면서 내부 누락 사유를 보존한다.
2. READY 항목의 사용자 category 삭제는 일반 PATCH 재지정 경로가 필요하다. 기존 manual-completion은 PARTIAL·실패 항목만 허용하므로 홈 보완 UI가 한 endpoint만 호출하면 상태 충돌이 난다.
3. 브랜드 편집이 제품에 추가됐지만 기존 PATCH 계약에는 없다.
4. 목적 색·아이콘은 필수로 바뀌었지만 일부 보드에 선택 문구가 남아 있다.
5. 보드의 처음부터 다시 보기는 완료 검토 재개 금지·보류 자동 재노출 금지와 그대로 양립하지 않는다. 예시 동작을 서버 재개 API로 해석하지 않는다.
6. 다른 판매처의 동일 상품을 비교하는 예시는 있지만 상품 동일성 기준은 구현 가능한 수준으로 정해져 있지 않다.

목적 정렬·필터·입력 제한·복원 예외 등 제품 확인 목록은 [디자인 전달 검토](../../product-planning/mvp/checkpoints/design-server-handoff.md)에 보존한다.

## 검증 결과와 한계

- manifest의 43개 보드와 FlowMap의 HTML·PNG, 이동 대상 파일 존재 여부: 누락 0건.
- JDK 17에서 서버 main/test 소스 컴파일 완료.
- Docker 없이 실행 가능한 10개 테스트 클래스 24건: 실패 0건, skip 0건.
- 실행 명령: `server/`에서 `./gradlew test --tests app.HealthRouteTest --tests app.RuntimeConfigTest --tests app.ai.ClassificationGatewayTest --tests app.ai.TaxonomyCatalogTest --tests app.extraction.HttpMetadataExtractorTest --tests app.extraction.SafeHttpTransportTest --tests app.extraction.UrlSafetyPolicyTest --tests app.browser.PlaywrightGatewayTest --tests app.tasks.CloudTasksGatewayTest --tests app.http.FirebaseOwnerResolverTest`.
- Docker daemon이 실행되어 있지 않아 PostgreSQL/Testcontainers migration·repository·Worker 통합 테스트는 이번에 실행하지 않았다. 기존 테스트 코드가 있다는 사실과 이번 테스트 통과를 구분한다.
- 실제 Firebase·Cloud Tasks·OpenAI 호출, browser 렌더링과 production 배포·부하 시험은 이번 검토에 포함하지 않았다.

## 결과

권장 구조·데이터 모델·API와 첫 구현 범위는 [MVP 제품 API 설계 제안](../../../architecture/server/mvp-product-api-design.md)에 구체화했다. 이번 결과는 제안이며, 미결정 제품 규칙은 명시적으로 분리했다. 다음 구현은 상품 조회·표현과 상태 기반부터 시작하는 것이 적절하다.


## 후속 화면·기능 API 목록 대조

같은 날짜에 사용자 요청에 따라 `design/handoff@51c67e006ac5c52f3aebeb939fdc9187dc78ab4e`의 최신 제품 문서·14개 MVP 결정·디자인 결정·43개 보드 HTML을 다시 대조했다. 최초 감사의 기준 커밋과 테스트 결과는 위 기록을 유지한다.

결과는 [MVP 화면·기능별 API 목록](../../../architecture/server/mvp-api-inventory.md)에 기록했다. 제품 HTTP 동작 37개(생성 1개 부분 구현, 나머지 36개 route 없음), 내부 작업 3개와 health 1개를 구분했다. 각 화면의 사용자 행동을 서버 또는 기기/외부 서비스 책임에 연결하고 최소 입출력·예외·미결정 제품 규칙을 기록했다. 이는 신규 route 구현이나 완성된 OpenAPI 계약을 뜻하지 않는다.

custom category 상세와 archive 종료/복원 미리보기·snapshot 후보 페이지를 보강했다. 상품 삭제의 목적 영향은 상세 응답, 추가 후보 category facets는 후보 조회에 합쳐 중복 endpoint를 피했다. 최초 구조 제안의 API 요약도 이 목록을 참조하도록 갱신했다.
