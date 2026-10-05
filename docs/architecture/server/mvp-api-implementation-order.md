# MVP API 구현 순서 계획

> **구현 담당자:** 이 순서에 따라 묶음을 하나씩 구현한다. 세부 구현에는 `superpowers:executing-plans`를 적용한다. 각 묶음의 계약·변경 파일·테스트를 먼저 구체화한 뒤 진행한다.

**목표:** 와이어프레임 43개를 지원하는 제품 API 37개를 의존성 순서로 구현하고, 각 묶음을 독립적으로 검증할 수 있게 한다.

**구조:** 기존 Ktor·JDBC modular monolith를 확장한다. 공개 API, 일반/browser Worker, maintenance의 실행 역할은 분리하고 DB transaction과 owner 범위는 공유한다.

**기술:** 저장소의 Kotlin/JDK 17, Ktor, PostgreSQL/Flyway, Firebase, Cloud Tasks, Testcontainers를 유지한다. 버전 변경을 전제하지 않는다.

**근거:** [API 목록](mvp-api-inventory.md), [구조·데이터 설계](mvp-product-api-design.md), [기존 확정 상태/API 계약](../wishlist-item-state-api.md), [서버 정밀 검토](../../history/architecture/server/technical-design-checkpoint-2026-10-04.md).

> 상태: **구현 순서 결정 · 상세 계약은 묶음별 확정** · 2026-10-04
>
> 이 문서는 전체 구현의 순서와 통과 조건이다. 모든 DTO·오류·SQL이 확정된 작업별 코딩 명세는 아니다. 제품의 미결정 정책을 임의로 확정하지 않는다. 최초 순서 결정 시 서버 코드를 변경하지 않았으며, 현재 B0 기반은 Task 1~9에서 구현했다. 후속 B1~B11의 완료를 의미하지 않는다.

## 기준과 전역 제약

- 제품·디자인 기준은 `design/handoff@51c67e0`, 최초 서버 조사 기준은 `server/initial-setup@9eacba5`이고 B0는 제품 문서 통합 main@4d31e3c에서 시작했다. 구현 시작 시 변경 여부를 확인하고 관련 문서 차이를 반영한다.
- 아래 순서를 기본으로 실행한다. 선행 조건을 생략하려고 임시 성공 응답·가짜 집계·부분 원자성으로 API를 완성 처리하지 않는다.
- 목록의 API ID를 유지한다. 전체 37개 각각에 최초 구현 묶음이 하나만 있다. 이후 확장은 별도 표로 추적한다.
- Firebase owner 격리, 다른 owner 자원 404, 기존 생성 key의 의미, ITEM-05의 version 없는 반복 삭제 계약을 보존한다.
- 사용자 변경·명시적 목적 해제를 AI가 덮어쓰지 않으며, 이전 generation·lease 실행·삭제/아카이브 전 작업은 결과를 반영하지 않는다.
- 후보 이동·종료·복원·중복 선택 삭제는 전체 원자 처리다. 확인 당시 영향이 바뀌면 재확인한다.
- 공용 taxonomy는 수정하지 않는다. 사용자 category는 사용자당 20개·같은 상위 normalized 이름 unique 및 기존 입력 제한을 지킨다.
- 갱신은 진입·foreground·사용자 새로고침이다. polling·push·검색·추천·개별 archive 후보 편집은 추가하지 않는다.
- 기존 V1~V7 SQL은 수정하지 않고 후속 migration을 추가한다. 실제 다음 번호는 구현 시 HEAD를 확인해 배정한다.
- local은 실제 PostgreSQL/Testcontainers와 인증·queue·AI fake로 검증한다. 실제 외부 AI 호출이나 production 쓰기가 단위·DB 테스트의 전제이면 안 된다.

## 순서 선택의 이유

검토한 방법은 화면 순서, 전체 기반 선행, 자원 의존 순서다. 화면 순서는 홈을 빨리 보여주지만 아직 없는 목적·카테고리·이미지를 편집 API가 참조해 재작업이 생긴다. 전체 기반을 먼저 완성하면 피드백 가능한 조회 API가 너무 늦어진다.

**자원 의존 순서**를 선택한다. 최소 안전 기반 뒤 상품 상세를 먼저 연결하고, 카테고리·목적을 준비해 목록·홈을 만든다. 비동기 실행과 이미지 준비 후 사용자 변경을 완성하며, 영향이 큰 삭제·후보 이동·중복 해소·아카이브를 뒤에 둔다. 처음부터 공통 상태·owner·transaction을 사용하되 캐시 최적화와 클라우드 검증은 별도 통과 조건으로 관리한다.

## 전체 실행 순서와 최초 API 배정

API의 method/path·최소 입출력은 [전체 목록](mvp-api-inventory.md)에서 확인한다. 아래 화살표는 **묶음 안의 권장 구현 순서**다. 같은 묶음의 변경을 함께 검증한다.

| 묶음 | 목표 | 최초 구현 API — 내부 권장 순서 | 제품 수 | 선행 |
| --- | --- | --- | ---: | --- |
| B0 | 문서·상태·DB·Worker 최소 안전 기반 | SYS-01 유지·검증, WORK-01/WORK-02 결과 반영 보호 보완 | 0 | 없음 |
| B1 | 실제 상품 생성 응답·상세 | ITEM-03 → ITEM-01 보완 | 2 | B0 |
| B2 | 카테고리 조회·생성·편집 | CAT-01 → CAT-03 → CAT-02 → CAT-04 | 4 | B1 |
| B3 | 목적 조회·생성·편집 | PUR-02 → PUR-03 → PUR-01 → PUR-04 | 4 | B2 |
| B4 | 상품 목록·홈·연속 처리 조회 | ITEM-02 → HOME-02 → HOME-01 | 3 | B3 |
| B5 | 비동기 분석·복구 실행 완성 | WORK-01 → WORK-02 → OPS-01 | 0 | B4 |
| B6 | 사용자 이미지 업로드 | MEDIA-01 → MEDIA-02 | 2 | B5 |
| B7 | 상품 편집·보완·검토·삭제·재분석 | ITEM-04 → ITEM-07 → ITEM-08 → ITEM-05 → ITEM-06 | 5 | B6 |
| B8 | 후보 이동·카테고리/목적 삭제 | PUR-07 → PUR-08 → CAT-05 → CAT-06 → PUR-05 → PUR-06 | 6 | B7 |
| B9 | 중복 비교·선택 해소 | DUP-01 → DUP-02 | 2 | B8 |
| B10 | 전체 아카이브·조회·제목·복원·삭제 | ARC-01 → ARC-02 → ARC-03 → ARC-04 → ARC-05 → ARC-06 → ARC-07 → ARC-08 → ARC-09 | 9 | B9 |
| B11 | 클라우드 연결·회귀·출시 검증 | 새 제품 API 없음. SYS/WORK/OPS 실제 연결 검증 | 0 | B10 |
| **합계** | **제품 API 전체** | **37개. 내부 3개·health 1개는 별도** | **37** | |

B2~B4가 읽기·참조 자원 준비 단계이고, B5~B7 완료 뒤 실제 사용자의 저장→분석→보완/검토 흐름을 끝까지 시험할 수 있다. B4까지의 조회 화면 확인을 전체 제품 완료로 보고하지 않는다.

## 공통 실행 절차와 통과 조건

각 묶음은 다음 순서로 진행하고 체크 결과를 기록한다.

- [ ] 해당 묶음의 미결정 제품 규칙만 해결하고, 요청/응답/오류·version·key·cursor를 계약 문서에 반영한다.
- [ ] 정확한 수정/생성 파일, 서비스 입출력, migration 및 아래 검증 사례를 작업별 계획으로 작성한다. 뒤 묶음의 코드를 미리 완성하려고 범위를 넓히지 않는다.
- [ ] 동작·실패·경합을 확인할 테스트를 먼저 작성하고, 기존 동작 또는 미구현 때문에 실패함을 확인한다.
- [ ] route/service/SQL과 runtime을 구현한다. 입력 타입·transaction·owner 조회 조건은 계약과 일치시킨다.
- [ ] 해당 테스트와 영향받은 기존 테스트를 실행하고, 실제 PostgreSQL의 transaction/경합/upgrade 결과를 확인한다.
- [ ] API 목록의 구현 상태·확정 계약·INDEX와 의미 있는 이력을 갱신하고, 변경을 검토한 뒤 프로젝트 규칙에 맞춰 커밋한다.

실제 DB 테스트를 실행하지 못하면 SQL·원자성 묶음은 통과하지 않는다. 단위 테스트 통과로 대체하지 않는다. 검증용 fake는 허용하지만 서버 응답을 미완성 상태로 숨기는 임시 데이터는 허용하지 않는다.

## B0 — 첫 API 전에 필요한 최소 기반

상세 실행은 [B0 서버 상태·DB·Worker 기반 구현 계획](../../superpowers/plans/2026-10-04-b0-server-foundation.md)의 Task 1~9를 따른다.

**범위:** 최신 제품 문서 차이, 공통 상품 상태·응답 규칙, 확장 migration, 기존 Worker 결과 반영 보호, 테스트 실행 환경.

**기존 변경 지점:** `server/src/main/kotlin/app/wishlist/WishlistItem.kt`, `app/http/WishlistRoutes.kt`, `app/analysis/GeneralWorkerService.kt`, `app/browser/BrowserWorkerService.kt`, `app/extraction/GeneralExtractionProcessor.kt`, `app/browser/BrowserRenderProcessor.kt`, `app/ai/AiClassificationService.kt`, `app/Main.kt`, `server/src/main/resources/db/migration/`. 경로의 `app/`는 `server/src/main/kotlin/` 기준이다.

- [x] 디자인 브랜치의 제품 문서와 서버의 확정 계약을 대조해 필요한 변경만 반영한다. `READY + CUSTOM_CATEGORY_DELETED` 재지정과 PARTIAL/실패 수동 완료를 구분한다.
- [x] lifecycle/review/manual/current generation·값 출처의 기본 모델과 requiredAction/allowedActions의 상태표를 정한다. 아직 없는 목적·이미지는 nullable 참조의 의미를 정하고 해당 테이블/완전한 기능은 소유 묶음에서 추가한다.
- [x] 공통 DTO·오류 응답과 owner 범위 조회 기반을 마련한다. 동기 JDBC/외부 호출의 IO 경계·연결 수 제한·종료 처리를 정하고 적용한다.
- [x] 기존 job generation backfill과 claim execution token/lease를 추가한다. 성공·부분·terminal·retry·복구의 모든 **최종 item 반영**에 현재 generation/lease/lifecycle/수동 변경 보호를 적용한다. processor의 임시 결과 저장과 최종 반영을 구분해 빠진 쓰기 경로를 남기지 않는다.
- [x] Testcontainers PostgreSQL과 기존 테스트가 실행 가능한지 확인한다. migration test의 현재 `7개` 고정 검사는 후속 migration을 반영하도록 수정하고, 빈 DB뿐 아니라 V7 데이터에서의 upgrade도 검증한다.

**통과:** 기존 item/owner/key 유지, 상태표 테스트, 삭제 상태에 Worker 반영 없음, generation N+1 뒤 N 성공·실패 무시, lease 만료 실행 무시, migration upgrade 성공. B5에서는 runtime/복구 전체를 완성하지만 이 보호를 B5까지 미루지 않는다.

**현재 상태:** B0 기반 구현·검증을 완료했다. [감사·회귀·B1 인계 기록](../../history/architecture/server/b0-foundation-implementation.md)을 통과 증거로 사용한다. B5 browser/maintenance runtime과 운영 적용은 완료 범위에 포함하지 않는다.

## B1 — 상품 상세와 실제 생성/replay

**산출물:** 클라이언트가 저장 후 같은 ID를 조회해 실제 분석 상태·metadata를 표시할 수 있다.

**파일 경계:** B0의 `WishlistItemStateRepository`, `WishlistItemPolicy`, `app/http/WishlistItemDtos.kt`, `ApiHttpSupport`와 IO/pool을 사용한다. 기존 `WishlistItem.kt`, `CreateWishlistItemService.kt`, `WishlistRoutes.kt`, DTO를 확장하고 `app/wishlist/GetWishlistItemService.kt`와 `app/wishlist/WishlistItemViewMapper.kt`를 추가한다. 현재 state repository에 없는 표시 metadata·시각·원본 URL은 owner 조건을 유지한 조회 projection으로 확장한다. 생성·상세·replay는 동일 mapper를 사용한다.

**내부 순서:** owner-scoped 상세 조회 → ITEM-03 route → 생성 DTO·clientCreatedAt 보관 → ITEM-01/replay mapper 교체. metadata를 상수 null로 만드는 현재 문자열 응답을 제거한다. 공유 시각은 서버 정렬 시각과 분리한다.

**통과:** 생성 201/Location, replay 200/표시 header, 같은 key 다른 URL 409, 다른 owner GET 404, READY/PARTIAL/실패 표현, DELETED 일반 GET 404·생성 replay에는 기존 tombstone. 중복 key 재전송은 item/job/outbox를 늘리지 않는다. deletionImpact의 목적 정보는 B3/B8에서 실제 연결 데이터와 함께 확장한다.

**주요 테스트:** 기존 `CreateWishlistItemServiceTest`, `WishlistRoutesTest`, `DatabaseMigrationTest`; 신규 `GetWishlistItemServiceTest`와 mapper 상태표 테스트.

## B2 — 카테고리 기본 관리

**산출물:** 공용/사용자 category 선택, 사용자 category 생성·상세·편집.

**내부 순서:** taxonomy SELECT/BROWSE와 count 조회 → custom 테이블·생성 → 상세 → 편집. 사용자 category owner·고정 parent·normalized 이름·20개 제한을 DB transaction에서 검증한다. 아직 삭제 API는 공개하지 않는다.

**AI 연결:** 사용자 category를 owner별 후보 공급에 연결하고, category 수정 전 snapshot으로 분석한 작업의 후보 유효성/버전을 반영 시 재검증한다. 필요한 재판단은 제품 정책에 맞게 예약한다. 표시명만 바뀌었다고 사용자 확정 item의 연결을 초기화하지 않는다.

**통과:** 19개에서 동시 생성 두 건 중 한 건만 성공, 같은 parent 공백/대소문자 정규화 중복 방지, 다른 parent의 이름 규칙 준수, parent 변경 거절, 입력 제한, 빈 custom 표시, 다른 owner 차단. archive 독립성은 B10에서 후속 검증한다.

## B3 — 목적 기본 관리

**산출물:** 목적 생성·상세·목록·편집, 상품/검토 화면의 선택 목록.

**내부 순서:** stable colorKey/iconKey 리소스 → 목적 생성 → 상세 → 목록/미리보기 → 편집. 활동순 정의와 이름/설명 제한을 먼저 확정한다. 빈 목적 생성은 허용하고 빈 목적 archive는 허용하지 않는다.

**AI 연결:** owner의 최근 활성 목적 후보를 공급하고 purpose version·사용자 확정/해제 출처를 보호한다. 신규 목적 생성 때문에 기존 상품 전체를 다시 분석하지 않는다. 편집에 따른 재판단은 미확정 대상에만 제품 정책을 적용한다.

**통과:** 선택 key 검증·빈 목적 유지·목적 0~1개 모델·정렬 tie-break·owner 격리, 목적 수정과 분석 결과 반영 경합. 아직 목적 삭제·후보 이동은 공개하지 않는다.

## B4 — 상품 목록·홈·연속 처리 조회

**산출물:** category/purpose 목록과 홈에서 같은 상태·건수·허용 행동을 표시한다.

**내부 순서:** ITEM-02의 category/purpose/미지정 filter·cursor/anchor → HOME-02의 requiredAction window → HOME-01의 count/미리보기·목적 요약. 홈과 연속 조회는 공통 predicate를 사용한다.

**통과:** owner/filter가 다른 cursor 거절, 같은 createdAt의 안정된 페이지 순서, anchor 삭제/이동 시 복구, limit 상한, 홈 count/미리보기와 action 목록 일치, DEFERRED 자동 재노출 없음. 로컬 pending은 서버 건수에 합치지 않는다. B7의 mutation 후 같은 검증을 실제 변경 흐름으로 반복한다.

## B5 — 비동기 분석과 운영 복구

**산출물:** 일반→browser fallback→결과 반영·중단 복구가 실제 runtime으로 조립된다.

**내부 순서:** WORK-01 후보/metadata 공급 → WORK-02 browser runtime → OPS-01 maintenance runtime·outbox/reconciler/budget 연결. B0의 보호 조건을 새 경로에도 적용한다.

- API의 commit 후 task 발행은 저장 응답을 무제한 지연시키지 않는다. 기존 즉시 발행+Scheduler 복구 정책 안에서 제한 시간·재발행을 검증한다.
- 일반/browser stage 전환이 generation 전체 최대 3회·30분 예산을 초기화하지 않도록 횟수·deadline 계약과 테스트를 고정한다.
- 브랜드·가격/통화·판매처·metadata 확인 시각의 추출/저장을 연결한다. 신뢰 가능한 값이 없으면 null이며 필수인 것처럼 꾸미지 않는다.
- URL/canonical 안전 정책과 browser egress 제약을 구현·검증한다. 일시적 DNS 장애와 사설 주소 차단의 실패 의미를 구분한다.
- B2/B3의 owner별 category/purpose 후보 공급과 후보 stale 검증을 일반/browser 양쪽에서 확인한다.

**통과:** 새 역할 config·API의 internal route 비노출, outbox 중복 발행·process 중단·lease 만료 회복, fallback deadline·budget 보존, 이전 실행 ACK, 사용자 정보 보호. 클라우드 IAM·Scheduler 실호출은 B11에서 확인하되 local runtime과 fake queue의 전체 흐름은 여기서 통과한다.

**주요 테스트:** 기존 `GeneralWorkerServiceTest`, `BrowserWorkerServiceTest`, `WorkerRoutesTest`, `RuntimeConfigTest`, `OutboxDispatcherTest`, `AiClassificationServiceTest`, budget 테스트. maintenance의 통합 실행 테스트를 추가한다.

## B6 — 사용자 이미지

**산출물:** 파일을 업로드해 owner의 검증된 READY mediaId를 받는다.

**내부 순서:** media 테이블·storage adapter → MEDIA-01 → 직접 byte 업로드 → MEDIA-02 객체 확인. 상품 연결은 B7에서 수행한다.

**통과:** MIME/size/실제 이미지 검증, 다른 owner completion 거절, 완료 재전송 idempotency, 만료/실패 재업로드, 미참조 정리와 참조 보존. OPS-01에 media 정리를 추가한다. 실제 Cloud Storage 권한·접근 만료는 B11에서 확인한다.

## B7 — 상품 사용자 변경

**산출물:** 일반 편집, 정보 보완, 확정/보류, 삭제, 재분석의 전체 사용자 흐름.

**내부 순서:** ITEM-04(category/purpose/READY media 연결) → ITEM-07 → ITEM-08 → ITEM-05(job 취소) → ITEM-06(새 generation/job/outbox). 공통 참조 검증·CAS·값 출처·activity/membership 갱신을 먼저 만든다.

**통과:** 하나의 저장 transaction, 필드 누락/optional null 구분, price/sourceUrl 편집 거절, category 삭제 후 READY의 재지정은 PATCH, PARTIAL/실패는 수동 완료, PROCESSING 편집 거절·삭제 허용. 수동 완료 뒤 재분석 거절, DEFERRED 미재노출, 사용자 purpose 해제를 AI가 덮어쓰지 않음. 동시 edit/review는 stale version 409, 삭제 반복 204, 재분석 key replay에는 job 하나, 이전 Worker 성공·실패 모두 무시.

**후속 확인:** ITEM-03 삭제 영향, ITEM-02/HOME-01/HOME-02 건수·순서와 PUR-01/PUR-03 후보 요약이 변경 후 실제 데이터와 일치한다. 비로그인/오프라인 local pending 전송을 인증 fake로 끝까지 검증한다.

## B8 — 후보 이동과 영향 확인 삭제

하위 묶음마다 별도 통과·커밋한다. 재사용하는 연결 변경 transaction은 item·purpose 잠금 순서를 고정한다.

### B8a: 목적 후보 추가

PUR-07 → PUR-08. 현재 목적 item을 제외하고, 전체 추가 가능 pool의 category facets를 제공한다. 다른 목적 소속·이동 영향을 표시하고 선택한 item 전체를 한 transaction으로 옮긴다.

**통과:** 현재 목적 제외·filter/cursor/facets, 요청 크기 상한, 하나의 version 충돌 시 전부 rollback, 이전/대상 count와 membershipVersion 갱신, key replay 중복 이동 없음. 선택 유지/초기 filter는 클라이언트와 맞추되 서버 filter 의미와 혼동하지 않는다.

### B8b: 사용자 category 삭제

CAT-05 → CAT-06. 영향 확인 fingerprint/token 뒤 최종 재검증한다. 활성 item의 category를 비우고 누락 사유·version을 바꾼다.

**통과:** 확인 뒤 새 연결/편집 발생 시 409, archived item 비영향, 삭제 후 홈 보완·PATCH 재지정, 삭제와 AI 반영 경합 시 사라진 category 재연결 없음. archive snapshot 독립성은 B10 통합 테스트로 다시 확인한다.

### B8c: 목적만 삭제

PUR-05 → PUR-06. 상품은 유지하고 목적 연결만 사용자 해제로 기록한다.

**통과:** 빈 목적 삭제 가능, 영향 변경 409, 상품 category/review 임의 확정 없음, AI가 삭제 목적을 되살리거나 다른 목적을 자동 덮어쓰지 않음, 다른 owner 영향 목록 비노출.

## B9 — 중복 비교와 판단

**산출물:** 중복 후보를 비교하고 둘 다 유지/선택 삭제를 영속 처리한다.

**내부 순서:** canonical·식별 기준 확정 → Product cache와 item snapshot 복사 → 중복 candidate/decision 테이블 → DUP-01 → DUP-02. 캐시는 공개 API 수에는 포함하지 않지만 이번 묶음에서 구현·검증한다.

**통과:** 동일 URL의 새 key는 여전히 새 item, cache 재사용 후 owner별 category/purpose 재판단, 기존 item snapshot 불변, 기존 실패/처리 상태의 안내, KEEP_BOTH 판단 기록 유지, 선택 삭제+판단 한 transaction, review 결정과 분리, 동시 삭제·다중 후보·key 재전송 검증.

**조건:** 다른 판매처 동일 상품·여러 후보 처리 정책을 먼저 정한다. 동일 canonical만 구현한 상태를 전체 중복 기능 완료로 기록하지 않는다. 캐시 지연이 상품 조회·변경 구현을 막지는 않지만 출시 평가에서는 기획 범위 누락을 허용하지 않는다.

## B10 — 아카이브 전체 생명주기

B8의 연결/삭제 보호와 B9의 중복 판단 모델을 재사용한다. 공개 단위는 하위 묶음별로 나누며 생성만 배포해 복원/삭제를 약속한 상태로 제품 완료 처리하지 않는다.

### B10a: 종료 확인·전체 snapshot·조회

ARC-01 → ARC-02 → ARC-03 → ARC-04 → ARC-05. 먼저 snapshot/참조 보존·구매 optional·후보 상태·정렬을 확정한다. 확인 token은 전체 후보를 나타내며 읽은 page만 대상으로 삼지 않는다.

**통과:** 빈 목적 거절, 선택 구매가 후보인지 검증, 확인 뒤 추가/이동/편집 409, 목적+전체 item 원자 ARCHIVED·job 취소, owner 격리·cursor·구매 item 맨앞. 활성 category/purpose 수정·삭제 뒤에도 당시 표시 유지, archive media 참조 보존. archived item의 B7/B8/B9 변경과 Worker 반영 차단을 여기서 종합 검증한다.

### B10b: 기록 제목 편집

ARC-06. version CAS로 기록 제목만 바꾸고 원래 목적 snapshot과 구분한다.

**통과:** 동시 제목 수정 409, 원래 목적 이름·다른 기록 비영향, 수정 제목을 원래 목적 이름으로 잘못 복원하지 않음.

### B10c: 전체 복원·전체 삭제

ARC-07 → ARC-08 → ARC-09. 현재 참조 상태와 복원 정책을 preview/실행에 동일 적용한다.

**통과:** 사라진 custom category·종료 당시 미완료 분석 처리 정책, 목적+전체 후보 복원·구매 해제·archive 목록 제거 원자성, 취소된 job 재활성화 없음, replay 중복 없음, restore/delete/title 경합. 전체 삭제 뒤 참조 media 정리와 원본 item 보존/삭제 범위는 확정 계약대로 검증한다. 부분 복원·개별 후보 삭제는 만들지 않는다.

## B11 — 출시 연결과 종단 검증

**산출물:** 구현된 모든 API·Worker·복구가 production 구성과 출시 기준에 맞게 동작한다.

- 공개 API의 Firebase 인증, private Worker/Scheduler OIDC·invoker·실제 queue, 역할별 secret·DB 연결 제한·migration 실행을 검증한다.
- 즉시 발행 실패→maintenance 복구, general→browser, 제한 시간·예산·중단 실행 회복을 실제 배포 구성에서 확인한다.
- 이미지 실제 upload/접근/만료, 삭제되지 말아야 할 archive 참조, 공개 로그의 token/개인정보/서명 URL 누출 방지를 확인한다.
- 저장→분석→보완/검토→중복 해소→목적 이동→archive→복원의 종단 흐름, 다중 owner·동시 기기·네트워크 응답 유실을 검증한다.
- 37개 route와 43개 화면 대응표를 최종 대조하고 현재 AI 출시 평가와 기존 운영 기준을 만족한다.

배포·외부 쓰기는 당시 사용자 승인 범위를 따르며, 읽기·local 준비·리뷰 가능한 변경은 먼저 완료한다. 배포 설정을 마지막에 처음 발견하지 않도록 역할 config는 B0/B5, storage는 B6에서 설계·local 검증한다.

## 이전 API의 후속 확장 — 완료 상태를 숨기지 않는 기준

| 최초 묶음 | 후속 묶음 | 반드시 연결할 부분 |
| --- | --- | --- |
| B1 상품 표현·생성 | B2/B3/B5/B6/B7/B8/B10 | category/purpose 실참조, metadata 확장·업로드 접근, 사용자 값·삭제 영향, archive lifecycle. 공통 mapper에서 제공 |
| B2 category 조회·count | B7/B8/B10 | 편집·이동·삭제·archive/restore 뒤 활성 count 및 AI 후보 유효성 |
| B3 purpose 목록·상세·활동 | B7/B8/B10 | membershipVersion/activityAt·미리보기·archive count·전체 복원 |
| B4 목록·홈·action | B7/B8/B9/B10 | 사용자 결정·재지정·중복 삭제·archive/restore의 공통 predicate 갱신 |
| B0/B5 Worker·maintenance | B6/B7/B8/B9/B10 | media 정리, retry generation, 삭제 참조 차단, cache 사용, archive job 취소·미참조 정리 |
| B6 media | B7/B10/B11 | 실제 item 연결·archive 참조 보존·실제 storage 권한 |

후속 요구가 반영되기 전에는 해당 기능까지 완료됐다고 보고하지 않는다. 예를 들어 B1은 실제 현재 데이터를 표현하지만 아직 없는 목적 CRUD·archive를 구현했다는 의미는 아니다.

## 미결정 정책의 해결 시점

| 결정 항목 | 늦어도 해결할 묶음 | 확인 범위 |
| --- | --- | --- |
| 홈 보완 projection·기존 requiredAction 충돌 | B0 | category 재지정과 수동 완료 차이, 최신 계약 반영 |
| clientCreatedAt·metadata optional 규칙 | B1 | 보관 필드·검증, 서버 createdAt 정렬 유지 |
| 목적 입력 제한·색/icon stable key·활동순 | B3 | 이름 중복·길이, 목록/AI 최근 목적·홈 정렬 동일 기준 |
| 홈 목적 개수·빈 목적 노출·연속 restart | B4 | 2~3개 확정, 현재 대상 재조회와 완료 검토 재개 구분 |
| retry 예산·deadline·즉시 발행 제한 | B5 | 일반/browser 합산과 timeout·복구, 기존 운영 계약 유지 |
| 이미지 형식/크기·저장소·외부 이미지 보존 | B6 | upload·completion·정리·archive 장기 보존 방향 |
| 상품명/brand 제한·생성 후 편집 취소 | B7 | CAT/PUR POST 성공 뒤 draft 취소 시 새 자원 수명 |
| 후보 filter·선택 유지·분석 상태·bulk 상한 | B8a | 추가 가능 item 상태, 이동 원자성, 최대 요청 크기 |
| impact token·DELETE 전달 형식·신규 key retention | B2 생성 및 B8 삭제 전, B9/B10 재확인 | 생성 key 정책은 B2부터, 최종 삭제 검증은 B8부터. 기존 ITEM-05는 별도 계약 유지 |
| 다중 중복 후보·다른 판매처 식별 | B9 | 정확한 지원 범위와 사용자 판단 단위 |
| archive 입력/정렬·snapshot·분석 상태 | B10a | 제목 제한·후보 순서·원래 목적 정보·전체 후보 |
| 복원 예외·삭제 데이터 보존 범위 | B10c | 삭제 category·취소 작업·원래 목적명·원본 item 처리 |
| Share Extension 직접 전송 | client 연동 전, 늦어도 B11 | ITEM-01 계약은 B1에서 구현. local-only/직접 전송 문구·시점은 별도 |

결정이 막히면 영향 없는 선행 작업을 진행할 수 있지만, 해당 결정을 사용하는 묶음은 완료로 넘기지 않는다. 정책 변경 때문에 순서를 바꿀 때는 의존성·API 배정·후속 검증 표를 함께 수정한다.

## 검증 명령과 기록

작업별 Gradle 명령은 `server/`에서 실행한다. 기본 회귀는 `./gradlew test`이며 PostgreSQL/Testcontainers 준비가 필요하다. 첫 묶음은 기존 `DatabaseMigrationTest`, `CreateWishlistItemServiceTest`, `WishlistRoutesTest`, `GeneralWorkerServiceTest`, `BrowserWorkerServiceTest`에 upgrade·상태·replay·stale 실행 사례를 추가해 대상 실행 후 전체 회귀를 수행한다.

실제 외부 호출·production pilot은 일반 회귀와 분리하고 환경·비용·승인 범위를 확인한다. 테스트 이름/명령/결과·미실행 이유를 묶음 기록에 남긴다. 새 API 테스트는 구현 과정에서 작성하며 이 순서 문서 작성만으로 통과했다고 주장하지 않는다.

이번 계획 검증은 제품 API 37개 최초 배정 누락/중복 없음, 내부/health 별도 포함, 선행 관계 역전 없음, 미결정 해결 시점·후속 확장·상대 링크·INDEX·diff 점검이다.
