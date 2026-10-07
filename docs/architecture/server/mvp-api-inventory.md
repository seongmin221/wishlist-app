# MVP 화면·기능별 API 목록

> 상태: 요구사항 추적 및 API 구성 제안 · 최초 조사 2026-10-04 · B1 구현 현황 갱신 2026-10-06
>
> 범위: 현재 MVP 제품 문서와 전달된 와이어프레임의 모든 화면·사용자 행동. URI, JSON 필드명과 신규 오류 코드는 구현 전 계약에서 고정한다. 제품의 미결정 규칙을 확정한 문서나 완성된 OpenAPI 명세는 아니다.

## 확인 기준과 문서 우선순위

- 제품·디자인: `design/handoff@51c67e006ac5c52f3aebeb939fdc9187dc78ab4e`. 해당 워크트리의 변경 사항이 없는 상태에서 읽었다.
- 최초 서버 조사: `server/initial-setup@9eacba51420e07de150b9427b8d2ed39be184939`. B0 Task 1~9는 [완료 기록](../../history/architecture/server/b0-foundation-implementation.md)에서 검증하며, 현재 B1 생성·상세 구현은 develop@59c11cc 기반 Orca workspace의 [B1 조회 계약](wishlist-item-read-api.md)을 따른다.
- 와이어프레임: `design/handoff/wireframes/README.md`, manifest, 보드 HTML 43개와 FlowMap. 첫 화면뿐 아니라 HTML의 조건부 표시·시트·확인창·입력·핸들러를 확인했다. 실제 브라우저의 클릭 검증은 수행하지 않았다.
- 보드 HTML이 실제 API를 호출하지 않더라도 제품 문서가 요구하는 동작이면 목록에 포함했다. 시트 닫기·가짜 목록 초기화처럼 예시 runtime만의 행동을 서버 기능으로 자동 채택하지 않았다.
- 최신 제품 문서 → 최신 디자인 결정 → 와이어프레임 예시 순서로 기능을 해석한다. 이력의 대체된 규칙은 현재 요구사항으로 사용하지 않는다. 기존 확정 API 계약과 충돌하는 새 제품 규칙은 아래에서 별도로 표시한다.
- 아래 제품 근거는 **위 디자인 커밋의 파일 내용**을 뜻한다. B0는 관련 제품 문서가 통합된 main@4d31e3c에서 시작했고 디자인 작업 파일을 별도로 변경하지 않았다.

| 근거 ID | 확인한 문서·기능 |
| --- | --- |
| S1 | `docs/product/overview.md`, INDEX/README — MVP 전체 범위·향후 제외 기능 |
| S2 | `docs/product/save-a-product.md` — 공유·로컬 대기·계정 귀속·생성·재분석·캐시·결과 갱신 |
| S3 | `docs/product/organize-candidates.md` — category/purpose CRUD·연결·AI 검토·중복 해소 |
| S4 | `docs/product/inspect-and-edit-a-product.md` — 홈·목록·상품 상세/편집·보완·이미지·웹뷰·삭제 영향 |
| S5 | `docs/product/finish-a-purchase-decision.md` — 구매 선택·전체 archive·snapshot·제목·전체 복원/삭제 |
| S6 | `docs/product/references/item-states.md`, `product-taxonomy.md`와 `ai/taxonomy/v1.json` — 상태·11개 상위/87개 세부 유형 |
| S7 | `docs/design/decisions.md`, `design/canvas-fresh/README.md` 및 해당 생성기의 동작/데이터 — 최신 목적 아이콘·색·archive 구매 표시 등 |
| S8 | `docs/architecture/wishlist-item-state-api.md`, client 공통/KMP, server/AI 구조 — 인증·idempotency·version·window·실행 경계 |
| S9 | MVP 결정 문서 14개 — 저장, 캐시, 중복, category lifecycle/삭제 변경/입력, 목적 그룹/삭제/AI 연결, 보완, 홈, 웹뷰, archive의 근거와 대체 관계 |
| S10 | 서버 운영/AI 설계 spec 2개, 공유 수신 Q&A — private Worker·maintenance·share extension 경계 |

제품 기능의 현재 상세 문서는 S2~S5다. 별도 `features/` 명세를 전제로 삼지 않는다. S9의 예전 `카테고리 미지정` 영역·선택 아이콘·NEW/UNCONFIRMED 규칙은 최신 제품·디자인에 맞춰 해석했다.

## 집계와 구현 상태

| 종류 | 수 | 현재 상태 |
| --- | ---: | --- |
| 앱 서버 제품 API 동작 | **37** | B1 상품 생성·상세 2개, B2 category 4개, B3 목적 4개 route 연결, 나머지 27개 route 없음. 목적 삭제 영향·표시 metadata 등 후속 확장은 각 묶음에서 완료 |
| 내부 작업 HTTP 동작 | **3** | 일반 Worker 연결, browser는 조건부 route/service만 있고 runtime 연결 없음, maintenance 신규 제안 |
| 공통 health HTTP 동작 | **1** | `/health` 구현 |
| 와이어프레임 | **43** | 아래 W01~W43 모두 API 또는 기기/외부 서비스 책임에 연결 |

동작 수는 `HTTP method + path` 기준이다. query, 시트·확인 상태와 같은 요청의 재사용을 중복 집계하지 않는다. preview·별도 후보 페이지 등 조회 분리는 이 문서의 권장 구성으로, 필요한 데이터를 다른 응답에 합쳐 제공하면 endpoint 수는 줄일 수 있다. **37은 기능을 지원하기 위한 현재 구성안의 수이며 제품 기능의 수나 최소 API 수를 뜻하지 않는다.**

B0의 공통 DTO와 owner-scoped 상태 repository를 B1에서 공개 GET과 생성/replay의 실제 mapper에 연결했다. clientCreatedAt 보관과 신규 outbox event 지정 발행도 B1에 포함한다. [B1 계약](wishlist-item-read-api.md)을 따르며 WORK-02와 OPS-01 runtime은 B5에 남는다.

신규 목록의 ‘필수 데이터’는 구현 명세 작성에 필요한 최소 입출력 범위다. 필드 타입·null/누락·status code·각 오류 응답의 완전한 schema는 후속 계약에서 작성한다. 구현 상태는 설계 문서가 아니라 코드의 route와 runtime 조립을 기준으로 판단했다.

## 앱 서버 API — 상품 8개

모든 `/v1` API는 Firebase ID token의 owner 범위로 처리한다. 아래 경로의 `{id}`는 각 자원의 ID다.

| API ID | Method·path | 지원 동작·근거 | 요청의 핵심 | 응답·결과의 필수 데이터 | 구현 |
| --- | --- | --- | --- | --- | --- |
| ITEM-01 | `POST /v1/wishlist-items` | 공유 URL 서버 저장, 로컬 대기 자동/수동 전송, 응답 유실 재전송 · S2/S8 | sourceUrl, 선택 clientCreatedAt, Idempotency-Key=clientSubmissionId | id·실제 item 표현·상태·version, Location, 재전송 표시. URL이 같아도 다른 key면 새 item | **구현(B1)**: 공유 시각 보관·실제 공통 mapper·신규 event 지정 발행. nullable 표시 metadata 및 후속 참조 확장은 B2/B3/B5/B6 |
| ITEM-02 | `GET /v1/wishlist-items` | 카테고리/목적 상품 목록, 스크롤 추가 로딩·복귀 anchor 갱신 · S4/S8 | categoryId 또는 purposeId 또는 purposeUnassigned=true, cursor/limit 또는 anchor/before/after, 명시적 목적 미지정 filter | 카드용 metadata·브랜드·가격/통화·확인 시각·purpose 색/아이콘·review 표시·version, 공용 카드 wrapper·totalCount·앞뒤 cursor·requested/resolved anchor ID·anchorResolved | **구현(B4)**: 단일 scope·page/anchor·공통 상세 mapper, 저장하지 않는 metadata는 B5까지 nullable |
| ITEM-03 | `GET /v1/wishlist-items/{id}` | 정상·보완·분석 중 상세, 409 뒤 최신 값, 삭제 확인·도움말 · S4/S8 | item ID | 전체 item·값 출처·실패/누락 이유·allowedActions·version·원본 URL. deletionImpact에 현재 목적명·후보 수·삭제 후 잔여 수·빈 목적 유지 안내 | **구현(B1 기본 조회)**: owner 격리·DELETED 404·실제 저장값·안전한 실패 code. 목적 deletionImpact는 ITEM-05와 함께 B7 확장 |
| ITEM-04 | `PATCH /v1/wishlist-items/{id}` | 일반 편집 한 번에 저장, 브랜드 수정·category 재지정·purpose 선택/해제 · S3/S4 | expectedVersion, 변경된 이름/brand/mediaId/categoryId/purposeId. 생략=유지, optional null=해제 | 갱신된 item·출처·review·version. 사용자 값만 수정, price/currency/sourceUrl 변경 제외 | 없음 |
| ITEM-05 | `DELETE /v1/wishlist-items/{id}` | 일반·분석 중 삭제, 목적에서 항목 제거 · S2/S4 | item ID. 기존 계약상 expectedVersion 없음 | 204, 늦은 Worker 반영 차단. owner의 이미 삭제한 item 반복 삭제도 204 | 없음 |
| ITEM-06 | `POST /v1/wishlist-items/{id}/analysis-attempts` | 재시도 가능한 실패를 다시 분석 · S2/S8 | attemptRequestId를 Idempotency-Key로 전달 | 새 generation·PROCESSING item, job/outbox 원자 생성. 수동 완료·terminal·PROCESSING이면 거절 | 없음 |
| ITEM-07 | `PUT /v1/wishlist-items/{id}/manual-completion` | 실패/PARTIAL에서 직접 보완 저장·저장하고 다음 · S4/S8 | 이름+category 필수, expectedVersion, 선택 brand/mediaId/purposeId | 수동 완료 시각·CONFIRMED·갱신 item. 진단용 원래 분석 결과 유지, 재분석 제외 | 없음 |
| ITEM-08 | `PUT /v1/wishlist-items/{id}/review-decision` | 분류·목적 확정/보류 버튼·스와이프, 검토 중 연결 수정 · S3/S8 | CONFIRM/DEFER, expectedVersion, 선택 categoryId/purposeId | 갱신 item·CONFIRMED/DEFERRED·version. 보류 자동 재노출 없음 | 없음 |

ITEM-03의 삭제 영향은 상세에 포함해 별도 item deletion-impact API를 만들지 않는다. 열어 둔 확인창 이후 다른 기기 변경으로 영향이 달라질 수 있다는 점은 ITEM-05의 version 없는 기존 계약과 함께 기록한다. 정확히 확인 당시 영향으로만 삭제해야 한다는 새 요구가 생기면 그 계약을 별도 변경한다.

READY 항목에서 사용자 category 삭제 때문에 category가 빈 경우에는 ITEM-04로 재지정한다. ITEM-07은 PARTIAL/실패의 수동 완료이며 두 흐름을 같은 명령으로 강제하지 않는다. 상세의 allowedActions로 클라이언트가 명령을 선택한다.

## 앱 서버 API — 홈 2개·중복 2개

| API ID | Method·path | 지원 동작·근거 | 요청의 핵심 | 응답·결과의 필수 데이터 | 구현 |
| --- | --- | --- | --- | --- | --- |
| HOME-01 | `GET /v1/home` | 로그인 후 홈·foreground 복귀·사용자 새로고침 · S4/S8 | 인증 owner | 분석 중·정보 보완·분류 검토 count/미리보기, 최근 활동순 ACTIVE 목적 최대 3개(빈 목적 포함): 이름·색·아이콘·후보 수·최근 활동·최근 저장 후보 최대 4개(이미지 null도 포함·placeholder 표시). 별도 서버 할 일 합계 필드 없음(그룹 count 제공). 기기 로컬 대기는 서버 count에 포함하지 않음 | **구현(B4)**: 같은 snapshot의 count/preview·B3 최근 목적 summary |
| HOME-02 | `GET /v1/home/action-items` | 영역 펼침, 연속 처리, 캐러셀 특정 상품부터 진입 · S4/S7/S8 | group 필수(세 홈 그룹, action query 없음), cursor/limit 또는 anchor={cursor}+before/after | 같은 홈 그룹 predicate의 item 목록·totalCount·cursor·anchorResolved. 정보 보완 그룹은 INFORMATION_COMPLETION·CATEGORY_ASSIGNMENT·CATEGORY_REASSIGNMENT를 함께 포함하고 item별 requiredAction·허용 행동은 유지 | **구현(B4)**: group·page/anchor, 카드별 requiredAction 유지 |
| DUP-01 | `GET /v1/wishlist-items/{id}/duplicate-candidates` | 새/기존 항목 비교 시트, 기존 실패/처리 상태 안내 · S3 | 새 item ID, cursor/limit | 중복 candidate ID·판단 근거와 MATCH/동일URL안내/판단대기 구분, 양쪽 metadata·저장일·category/purpose·version·기존 항목 삭제 영향 | 없음 |
| DUP-02 | `PUT /v1/wishlist-items/{id}/duplicate-decisions` | 둘 다 두기/새 항목 지우기/기존 항목 지우기 확정 · S3 | candidate ID, KEEP_BOTH/DELETE_NEW/DELETE_EXISTING, decision/version 정보, 재전송 식별 key | 판단 기록과 선택 삭제를 같은 transaction에 반영, 남는/삭제 item IDs·갱신 상태. review 확정/보류는 별도 ITEM-08 | 없음 |

중복 선택은 generic DELETE를 호출한 뒤 별도로 판단을 저장하는 두 요청으로 구성하지 않는다. standalone 상품 삭제는 ITEM-05, 비교 시트의 결정은 DUP-02가 담당한다. 후보 여러 개일 때의 선택 단위는 미결정 목록에 있다.

`처음부터 다시 보기`는 완료된 review 재개 API로 만들지 않는다. 정보 보완의 건너뛰기는 기기 세션에서 다음 항목으로 이동하며 server review DEFER로 기록하지 않는다.

## 앱 서버 API — 카테고리 6개

B2 CAT-01~04와 owner별 AI 후보·stale 보호를 구현·검증했다. [확정 계약](category-management-api.md)을 따른다.
후속 리뷰 보완 후 최종 전체 실행은 252개 중251 통과·실패/오류0·RealUrlPilot1 skip이다.
생성 key는 계정 데이터 유지 동안 보존, 미확정 stale 실행은 예산을 승계해 재예약, 신규 생성은 owner별 60초 5건으로 사용자 확인을 완료했다.

| API ID | Method·path | 지원 동작·근거 | 요청의 핵심 | 응답·결과의 필수 데이터 | 구현 |
| --- | --- | --- | --- | --- | --- |
| CAT-01 | `GET /v1/categories` | category 탭, 전체 category 선택, 생성 상위 선택 · S3/S6/S7 | scope=BROWSE/SELECT, 선택 parentId | stable ID·이름·상위·공용/사용자 구분·순서·활성 item count, customUsedCount/limit. BROWSE는 상품/빈 custom이 있는 상위, SELECT는 전체 taxonomy | **구현(B2)**: SELECT/BROWSE·count·빈 custom·안정 순서 |
| CAT-02 | `GET /v1/custom-categories/{id}` | custom 목록 헤더·편집 폼 초기값 · S3 | custom category ID | 이름·고정 parent·설명·예시·itemCount·version. AI 후보 제외 내부 사유는 노출하지 않음 | **구현(B2)**: owner 상세·빈 custom·내부 AI 정보 제외 |
| CAT-03 | `POST /v1/custom-categories` | 탭 + 추가, 선택 시트 안 새 category 만들기 · S3 | parentId, 이름, 선택 설명/예시, Idempotency-Key | 생성 category ID·표시값·사용 개수. 사용자당20·40/200/5×60 제한·같은 상위 normalized 이름 unique | **구현(B2)**: owner 잠금·receipt/replay·20개·60초5건 |
| CAT-04 | `PATCH /v1/custom-categories/{id}` | 이름·설명·예시 저장 · S3 | expectedVersion, 변경 필드 | 새 category·version, 활성 표시명 반영. 부모 이동은 현재 문서 요구에 없으므로 받지 않음 | **구현(B2)**: version·no-op·null·parent 고정·현재 표시명 |
| CAT-05 | `GET /v1/custom-categories/{id}/deletion-impact` | 삭제 확인 count·영향 목록 펼침 · S3/S4 | cursor/limit | 영향 ACTIVE 전체 item 수(이름 누락 포함, CAT-01/02 표시용 count와 별도)·이름/이미지·cursor·category version·impactToken. 상품 유지·홈 보완·archive 비영향 | 없음 |
| CAT-06 | `DELETE /v1/custom-categories/{id}` | 삭제 확정 · S3/S4 | category version·impactToken | category 삭제·참조 해제·CUSTOM_CATEGORY_DELETED 원자 반영. 영향이 바뀌면 재확인 가능한 409 | 없음 |

CAT-03의 ‘만들고 현재 상품에 선택’은 category 생성 후 반환 ID를 편집 초안에 넣고 ITEM-04/ITEM-07/ITEM-08에서 연결한다. 상품 편집을 취소해도 이미 생성한 category를 삭제하지 않는다. 생성·연결을 하나의 API로 묶거나 취소 시 자동 삭제하려는 요구는 현재 근거에 없으며 후속 제품 확인 대상이다.

## 앱 서버 API — 목적 8개

B3 PUR-01~04와 owner별 AI 목적 후보·반영 보호를 구현했다. [확정 계약](purpose-management-api.md)과 [AI 목적 후보](purpose-ai-candidates.md)를 따르며, 정책 경위는 [제품 결정](../../history/product-planning/mvp/decisions/b3-purpose-api-policy-2026-10-07.md)에 있다.

| API ID | Method·path | 지원 동작·근거 | 요청의 핵심 | 응답·결과의 필수 데이터 | 구현 |
| --- | --- | --- | --- | --- | --- |
| PUR-01 | `GET /v1/purposes` | 목적 탭, 상품/검토의 목적 선택 시트, 빈 목적 표시 · S3/S7 | cursor/limit, 요약/선택용 projection | ID·이름·설명·colorKey/iconKey·후보 수·최근 활동 시각·미리보기·version, archive 입구 count/요약 | **구현(B3)**: SUMMARY/SELECT·활동순 keyset cursor·미리보기4·archiveSummary(현재 0) |
| PUR-02 | `POST /v1/purposes` | 목적 탭·상품·검토에서 새 목적 만들기 · S3 | 필수 이름/colorKey/iconKey, 선택 설명, Idempotency-Key | 빈 ACTIVE purpose와 ID·version. 기존 상품 전체 자동 재판단 없음 | **구현(B3)**: owner 잠금·receipt replay·60초10건·ACTIVE30·job 없음 |
| PUR-03 | `GET /v1/purposes/{id}` | 목적 상세·빈 목적·편집 초기값 · S3/S7 | purpose ID | 목적 정보·후보 count·membershipVersion·allowedActions·version. 후보 0이면 archive 불가 | **구현(B3)**: owner 상세·후보 수·membershipVersion·allowedActions(ARCHIVE는 후보≥1) |
| PUR-04 | `PATCH /v1/purposes/{id}` | 이름·설명·색·아이콘을 한 번에 저장 · S3/S7 | expectedVersion, 변경 필드 | 새 purpose·version·표시값. 관련 미확정 AI 결과의 재판단은 서버 내부 정책으로 처리 | **구현(B3)**: expectedVersion·no-op·optional null·AI 재판단 없음 |
| PUR-05 | `GET /v1/purposes/{id}/deletion-impact` | 목적 삭제 확인·목적 미지정이 될 후보 펼침 · S3 | cursor/limit | 영향 count·item 요약·cursor·purpose version·impactToken, 상품 유지·archive 비영향 | 없음 |
| PUR-06 | `DELETE /v1/purposes/{id}` | 목적만 삭제·상품 purpose 해제 · S3 | purpose version·impactToken | 상품 유지·사용자가 확정한 목적 미지정, 관련 version 갱신. 빈 목적도 허용 | 없음 |
| PUR-07 | `GET /v1/purposes/{id}/candidate-items` | 후보 추가 시트, 전체/상위/세부 category 필터 · S3/S7 | categoryId 또는 parentId, cursor/limit, includeCategoryFacets | 현재 목적 소속 제외한 선택 가능 item·현재 다른 목적·이동 안내·version·cursor. facets는 **전체 추가 가능 pool**의 category별 count, 현재 필터/page로 제한하지 않음 | 없음 |
| PUR-08 | `POST /v1/purposes/{id}/candidate-moves` | 여러 후보 ‘n개 추가’, 다른 목적에서 옮기기 · S3/S7 | item IDs와 각 expectedVersion/기존 목적, 대상 purpose version, Idempotency-Key | 전체 이동의 원자적 결과·새 소속/versions·이전/대상 목적 count. 하나라도 충돌하면 전체 거절 | 없음 |

현재 목적 후보 목록은 ITEM-02의 purposeId 필터로 조회한다. 별도의 목적별 상품 GET을 중복 추가하지 않는다. 추가 후보 category 레일도 PUR-07의 facets를 사용해 별도 category facet API를 만들지 않는다.

PUR-02의 ‘새로 만들고 이 상품에 연결’은 반환 ID를 해당 검토/편집의 초안에 적용한 후 ITEM-08/ITEM-04/ITEM-07로 저장한다. 새 목적의 후보 추가 화면은 PUR-07/PUR-08을 사용한다. 생성 후 편집을 취소해도 목적을 자동 삭제하지 않는다(B3 확정).

## 앱 서버 API — 아카이브 9개

| API ID | Method·path | 지원 동작·근거 | 요청의 핵심 | 응답·결과의 필수 데이터 | 구현 |
| --- | --- | --- | --- | --- | --- |
| ARC-01 | `GET /v1/purposes/{id}/archive-preview` | 비교 끝내기·구매 후보 선택·아카이브 확인·전체 후보 펼침 · S5 | purpose ID, cursor/limit, 선택 purchasedItemId | 목적/후보 count·선택 구매 요약·페이지 후보·허용 여부·purpose/membership version·전체 후보 상태를 나타내는 previewToken | 없음 |
| ARC-02 | `POST /v1/purposes/{id}/archives` | 아카이브 최종 확정, 구매 없음을 포함 · S5 | purchasedItemId 또는 null, previewToken, expectedVersion, Idempotency-Key | archive ID·snapshot·종료 시각, 목적+**전체** 후보 ARCHIVED. 확인 후 변화는 409, 빈 목적 거절 | 없음 |
| ARC-03 | `GET /v1/archives` | 끝난 비교 목록 · S5/S7 | cursor/limit | 기록 ID·제목·후보 수·종료 시각·사진 최대3개·목적 아이콘 snapshot·선택 구매 요약·cursor | 없음 |
| ARC-04 | `GET /v1/archives/{id}` | 구매 있음/없음 상세, 제목 편집 초기값, 복원/삭제 메뉴·확인 요약 · S5/S7 | archive ID | 제목·원래 목적 snapshot/iconKey·후보 수·구매 요약·version·allowedActions·복원/삭제 영향 요약. 활성 목적/category join 없이 과거 표시 | 없음 |
| ARC-05 | `GET /v1/archives/{id}/items` | 상세·삭제 확인에서 많은 후보 조회·펼침 · S5/S7 | cursor/limit | 당시 상품명·이미지·category명·구매 표시·원본 item ID·snapshot cursor. 구매 상품 있으면 맨 앞 정렬 | 없음 |
| ARC-06 | `PATCH /v1/archives/{id}` | 기록 제목 저장 · S5 | title, expectedVersion | 갱신 archive·version. 목적의 원래 이름·다른 목적을 수정하지 않음 | 없음 |
| ARC-07 | `GET /v1/archives/{id}/restoration-preview` | 비교 다시 열기 전 현재 복원 가능 여부·영향 확인 · S5 | archive ID | 목적/후보 전체 복원·구매 해제·기록 제거 요약, 사라진 category 등 현재 참조 문제·version·previewToken | 없음 |
| ARC-08 | `POST /v1/archives/{id}/restorations` | 다시 열기 확정 · S5 | expectedVersion·previewToken·Idempotency-Key | 원래 목적+전체 item 복원·구매 지정 제거·archive 목록 제거를 한 transaction으로 처리 | 없음 |
| ARC-09 | `DELETE /v1/archives/{id}` | 기록 묶음 전체 삭제 · S5 | archive version | 전체 기록 삭제. 개별 후보 삭제/부분 복원/실행 취소는 제공하지 않음 | 없음 |

archive 삭제 count와 펼침 목록은 ARC-04/ARC-05를 재사용한다. immutable 후보 snapshot만 보이는 삭제 확인용 별도 GET은 만들지 않는다. ARC-07은 **현재** category 등 참조의 상태를 평가하는 읽기이므로 immutable 기록 조회와 역할이 다르다. 복원 예외의 구체적인 처리 규칙은 아직 결정이 필요하다.

ARC-01 previewToken은 전체 후보 상태를 나타내며 해당 page의 item IDs만 나타내면 안 된다. ARC-02는 확인한 전체 후보를 대상으로 한다. 구매 상품 지정은 별도의 ‘구매 확정 API’가 아니라 ARC-02의 optional 입력이다. 웹뷰 결제 결과로 자동 호출하지 않는다.

## 앱 서버 API — 이미지 2개

| API ID | Method·path | 지원 동작·근거 | 요청의 핵심 | 응답·결과의 필수 데이터 | 구현 |
| --- | --- | --- | --- | --- | --- |
| MEDIA-01 | `POST /v1/media/upload-intents` | 사진 보관함 선택 후 실제 업로드 준비 · S4 | 파일 MIME/size·이미지 정보, 재전송 key | owner의 mediaId, 만료 upload URL·방법/필수 headers·유효기간·제한 | 없음 |
| MEDIA-02 | `POST /v1/media/{id}/completion` | 객체 업로드 성공 확인 후 상품에 연결할 자원 확정 · S4 | media ID | 검증된 READY mediaId·치수·표시 접근 정보. 같은 객체 재확인은 idempotent | 없음 |

실제 이미지 byte 업로드는 intent의 저장소 URL로 한다. ITEM-04/ITEM-07에서 mediaId를 연결하고 server는 해당 owner의 READY 자원인지 확인한다. private 이미지 접근 URL은 item/archive 응답에서 갱신해 제공하며 최초 구성에는 별도 media 조회/URL 갱신 API를 추가하지 않는다. 편집 취소 때 상품에 연결되지 않은 자원은 server 정리 작업이 처리한다. 업로드 완료만으로 item 정보가 바뀌지는 않는다.

## 공통·내부 HTTP — 제품 API 수에 포함하지 않음

| ID | Method·path | 호출자·역할 | 입력/결과 | 구현 |
| --- | --- | --- | --- | --- |
| SYS-01 | `GET /health` | 실행 상태 확인 | 현재 `ok`. DB readiness 검사는 별도 요구 시 검토 | 구현 |
| WORK-01 | `POST /internal/worker/general` | Cloud Tasks → private 일반 Worker | jobId/generation, 완료·stale ACK 204 또는 인프라 retry 503 | route·general-worker runtime 연결. owner/최신 generation fence 보완 필요 |
| WORK-02 | `POST /internal/worker/browser` | Cloud Tasks → private browser Worker | jobId/generation, 제한된 rendering·최종 분류/보완 상태 | 조건부 route/service 있음. browser runtime 미연결 |
| OPS-01 | `POST /internal/maintenance` | Scheduler → private maintenance 서비스 | outbox 발행·멈춘 job 복구·예산 정산/경고·미사용 media 정리를 제한 batch로 실행 | 신규 구성 제안. 관련 서비스와 budget 단발 CLI는 있음 |

private 경로를 공개 API 서비스에 같이 등록하지 않는다. Firebase 사용자 token과 service OIDC를 혼용하지 않는다. parser·분류·캐시 조회·AI 품질 평가·DB migration은 공개 endpoint가 아니라 서버 내부 함수/작업/CLI다. 현재 사용자 UI에는 별도의 public AI 호출·outbox 제어 API가 필요하지 않다.

## 와이어프레임 43개 → 행동 → API

W ID는 manifest 순서다. 각 행의 API는 화면 진입·그 상태에서 실행하는 데이터 변경을 포함한다. 공통 navigation·닫기·펼침·초안 선택은 다음 절의 기기 책임을 따른다.

| W ID | 보드 | 화면·행동과 API 연결 | 기기 처리·주의 |
| --- | --- | --- | --- |
| W01 | `Home.dc.html` | 요약 HOME-01, 영역/특정 미리보기 진입 HOME-02, 목적 PUR-03/ITEM-02, 분석 중 삭제 ITEM-05 | 로컬 pending 합성·펼침·탭 이동 |
| W02 | `HomeReviewFlow.dc.html` | HOME-02, 확정/보류 ITEM-08, category CAT-01/CAT-03, purpose PUR-01/PUR-02, 중복 DUP-01/DUP-02 | 연결 변경은 review 초안. restart는 기기 skip 초기화 후 현재 미완료 대상 재조회; CONFIRMED/DEFERRED를 검토 대상으로 되돌리지 않음 |
| W03 | `HomeFillFlow.dc.html` | HOME-02/ITEM-03, retry ITEM-06, 보완 ITEM-07 또는 재지정 ITEM-04, CAT-01/CAT-03, MEDIA-01/MEDIA-02 | skip·다음은 session, 실패 유형별 retry 조건 |
| W04 | `DuplicateCompare.dc.html` | DUP-01, category/purpose 변경 ITEM-08 및 CAT-01/CAT-03·PUR-01/PUR-02, 결정 DUP-02 | 시트만 닫으면 두 item 유지·판단 미완료 |
| W05 | `DuplicateConfirmBoth.dc.html` | DUP-01/DUP-02 KEEP_BOTH | confirm 취소는 무변경 |
| W06 | `DuplicateConfirmNew.dc.html` | DUP-01/DUP-02 DELETE_NEW | 삭제 성공 뒤 현재 카드 제거·다음 이동 |
| W07 | `DuplicateConfirmOld.dc.html` | DUP-01의 삭제 영향, DUP-02 DELETE_EXISTING | 새 item review는 독립적으로 남음 |
| W08 | `CategoryHome.dc.html` | CAT-01, 세부 목록 ITEM-02, +추가 CAT-03 | 상위 레일 선택·최근 상위 기억. 예전 long-press 관리 runtime은 최신 규칙에서 제외 |
| W09 | `CategoryList.dc.html` | CAT-01 metadata, ITEM-02 category window, 카드 ITEM-03 | masonry·scroll anchor·탭 재누름 맨위 |
| W10 | `CategoryListCustom.dc.html` | CAT-02, ITEM-02, edit CAT-04, delete CAT-05/CAT-06 | ⋯·시트·확인창 열기 |
| W11 | `CategoryListCustomEmpty.dc.html` | CAT-02, ITEM-02 empty, CAT-04/CAT-05/CAT-06 | 빈 custom 유지·안내 |
| W12 | `CategoryEditSheet.dc.html` | CAT-02 초기값, CAT-04 저장 | 예시 추가/삭제·입력은 초안, 취소/버리기 무호출 |
| W13 | `CategoryDeleteConfirm.dc.html` | CAT-05 count/목록, CAT-06 확정 | 삭제 후 HOME-01/HOME-02로 재지정, 즉시 재지정 API 없음 |
| W14 | `CategoryAddSheet.dc.html` | CAT-01 parent/사용 개수, CAT-03 생성 | 40/200/5×60 검증·초안 |
| W15 | `PurposeHome.dc.html` | PUR-01, 목적 진입 PUR-03/ITEM-02, 생성 PUR-02, archive ARC-03 | 카드 한 개만 펼치기·색/icon 선택 |
| W16 | `PurposeDetail.dc.html` | PUR-03/ITEM-02, 후보 PUR-07/PUR-08, edit PUR-04, delete PUR-05/PUR-06, 비교 종료 ARC-01/ARC-02, 상품 ITEM-03 | 헤더 접힘·구매 선택·확인 펼침 |
| W17 | `PurposeCreate.dc.html` | PUR-02 → 빈 상세 PUR-03 | color/icon 필수는 최신 문서 우선 |
| W18 | `PurposeDetailEmpty.dc.html` | PUR-03/ITEM-02 empty, PUR-07/PUR-08, PUR-04/PUR-05/PUR-06 | archive 버튼 숨김·요청도 거절 |
| W19 | `PurposeEditInPlace.dc.html` | PUR-03 초기값, PUR-04 저장 | 이름·설명·color/icon 미리보기, cancel 무호출 |
| W20 | `PurposeAddCandidates.dc.html` | PUR-07 후보, PUR-08 n개 추가/이동 | 체크·필터 전환 시 선택 상태 유지 정책 미결정 |
| W21 | `PurposeDeleteConfirm.dc.html` | PUR-05 count/목록, PUR-06 확정 | 상품 삭제·archive 명령과 구분 |
| W22 | `PurposeAddCategoryFilter.dc.html` | PUR-07 includeCategoryFacets·parent/category filter | 레일·chip 선택. 초기 filter 규칙 미결정 |
| W23 | `ProductDetail.dc.html` | ITEM-03, ITEM-04 저장, ITEM-05 삭제, CAT-01/CAT-03, PUR-01/PUR-02, MEDIA-01/MEDIA-02 | 원본 URL 웹뷰, 편집 초안·취소 확인·삭제 ? |
| W24 | `ProductDetailFill.dc.html` | ITEM-03, ITEM-07/ITEM-04, retry ITEM-06, CAT-01/CAT-03, MEDIA-01/MEDIA-02 | 수동 완료 후 retry 제외·원본 보기 |
| W25 | `ProductDetailProcessing.dc.html` | ITEM-03, ITEM-05만 변경 가능 | 원본 보기·로딩, edit/retry 없음 |
| W26 | `CategoryPicker.dc.html` | CAT-01 SELECT → ITEM-04/ITEM-07/ITEM-08의 draft category | chip 클릭만으로 item 저장하지 않음 |
| W27 | `CategoryCreate.dc.html` | CAT-01 parent/사용 개수, CAT-03 → draft에 ID | item 저장은 ITEM-04/ITEM-07/ITEM-08, 취소 시 생성 category 수명 정책 확인 |
| W28 | `WebView.dc.html` | 진입 item sourceUrl은 ITEM-03/기존 cache | 이후 쇼핑몰 직접 통신·history·reload·progress·닫기 |
| W29 | `WebViewShare.dc.html` | 별도 앱 서버 API 없음 | 외부 브라우저·복사·OS 공유 |
| W30 | `WebViewExternal.dc.html` | 별도 앱 서버 API 없음 | 외부 intent 허용·확인·취소 |
| W31 | `ArchiveList.dc.html` | ARC-03 목록·preview 사진·구매 요약 | 카드 진입 ARC-04/ARC-05 |
| W32 | `ArchiveDetail.dc.html` | ARC-04/ARC-05 구매 있음, ARC-06 제목, ARC-07/ARC-08 복원, ARC-09 삭제 | 구매 item 맨앞·목적 아이콘·헤더 펼침 |
| W33 | `ArchiveDetailNoPurchase.dc.html` | ARC-04/ARC-05 구매 없음, ARC-06, ARC-07/ARC-08, ARC-09 | 구매 표시 생략 |
| W34 | `ArchiveRestoreConfirm.dc.html` | ARC-07 영향, ARC-08 확정 | 전체 복원, cancel 무호출 |
| W35 | `ArchiveDeleteConfirm.dc.html` | ARC-04/ARC-05 count/후보, ARC-09 확정 | 후보 개별 삭제 없음 |
| W36 | `Login.dc.html` | Firebase/Apple/Google 인증, 성공 뒤 ITEM-01 대기 전송·HOME-01 등 | ‘나중에 하기’는 로컬, 별도 앱 로그인 endpoint 없음 |
| W37 | `HomeLoggedOut.dc.html` | 로그인 전 앱 서버 API 없음. 로그인 뒤 ITEM-01 | 로컬 pending 목록/원본·영역 펼침·계정별 대기 격리 |
| W38 | `Settings.dc.html` | 계정 정보 Firebase client, 로그인 뒤 초기 조회 재사용 | signOut·웹뷰 데이터 삭제·앱 버전/라이선스 |
| W39 | `SettingsLogout.dc.html` | 별도 앱 서버 로그아웃 API 없음 | Firebase signOut, local cache/accountBinding 분리, 서버 계정 데이터 유지 |
| W40 | `SettingsLoggedOut.dc.html` | Firebase 인증 전 앱 서버 API 없음 | 웹뷰/앱 정보는 기기에서 처리 |
| W41 | `ShareSaved.dc.html` | 서버에 전송하는 경우 ITEM-01 | 먼저 로컬 보관. share extension 직접 전송 여부 미결정, 서버 수락 없이 분석 시작 확정 문구 금지 |
| W42 | `ShareSavedLocal.dc.html` | 즉시 호출 없음, 로그인 후 ITEM-01 | 비로그인 URL 영속 보관·확인 자동 닫힘 |
| W43 | `ShareSavedOffline.dc.html` | 즉시 호출 없음, 복구/다음 실행 때 ITEM-01 | 저장 당시 계정에 귀속·확인 자동 닫힘 |

FlowMap은 위 화면 사이 navigation의 근거이며 별도 endpoint를 요구하지 않는다. 상품상세·목적상세·category 선택의 동일 행동은 어느 탭에서 열어도 같은 API를 사용한다.

## 화면마다 보이는 기기/외부 서비스 행동

| 행동 | 담당과 API 처리 |
| --- | --- |
| Apple/Google 로그인·token 갱신 | 플랫폼 provider와 Firebase client. 앱 서버는 Bearer ID token 검증; 자체 OAuth/session endpoint 추가 없음 |
| 로그인 건너뛰기·로그아웃 확인·실행 | 기기와 Firebase signOut. 서버 상품·분석 작업 삭제·계정 전환 endpoint 없음 |
| 계정 표시·버전·라이선스 | Firebase client profile·앱 bundle에서 표시. `/me`, `/app-version`, `/licenses`는 현재 요구에 없음 |
| 로컬 URL 저장·삭제·다른 계정 pending 숨김 | KMP local storage. 서버 생성 전 item에 서버 DELETE를 호출하지 않음 |
| share 확인 카드·자동 닫힘 | 플랫폼 share extension/Activity. 생성 수락과 로컬 보관 성공을 구분 |
| 웹뷰 이동·새로고침·외부 앱·복사·OS 공유·쿠키 삭제 | 플랫폼 웹뷰/OS·쇼핑몰. 앱 서버가 쇼핑몰 세션을 보관하거나 proxy하지 않음 |
| 편집 입력·color/icon 미리보기·category/purpose 선택 | 초안만 변경. 저장 시 각 PATCH/PUT, 새 자원 ‘만들기’ 시 POST |
| 편집 취소·계속 편집·변경 버리기 | 서버 저장 전 기기 상태. rollback API 없음 |
| 보완 건너뛰기·다음·처리 완료 안내 | 기기 session. 보완 저장 성공/검토 결정 성공 이후만 다음 이동 |
| review 보류·스와이프 확정 | 서버 review 변경 ITEM-08. 보완 skip과 다름 |
| 구매 item 선택·구매하지 않음·아카이브 이전/취소 | 초안. 확정 ARC-02에 구매 ID/null 포함, 선택마다 서버 구매 상태 변경 없음 |
| accordion·겹친 목적 카드·menu·sheet·blur·헤더 접힘 | 화면 상태. 필요한 데이터가 이미 있으면 추가 API 호출 없음 |
| 상위 category 기억·스크롤 anchor·tab 재누름 | 기기 상태, 범위 갱신 때 ITEM-02/HOME-02 사용 |
| 사진 선택·압축·업로드 진행 | OS picker/클라이언트. MEDIA-01 → 저장소 upload → MEDIA-02 → item 저장 |
| 네트워크 단절·저장 실패·409 복구 | 초안·현재 카드 유지. GET 최신 값 후 재시도. 실패 자체를 별도 저장 API로 보고하지 않음 |

색·아이콘 allow-list는 버전 관리한 공통 리소스로 client/server에서 맞춘다. B3에서 색 6개(기본 CORAL)·아이콘 8개(기본 HEART)의 stable key를 확정했다. 목적 만들기 화면의 6색·icon 선택만을 이유로 별도 색/아이콘 HTTP API를 추가하지 않는다. 동적 remote config 요구가 생기면 재검토한다.

## 반드시 계약에 포함할 예외·복구

| 상황 | 관련 API·응답 요구 |
| --- | --- |
| 모든 조회/변경의 인증 실패·다른 owner ID | 401/404, 안전한 code·requestId. client ownerId를 신뢰하지 않음 |
| 생성 key 재사용·응답 유실·동시 생성 | ITEM-01: 같은 owner+key 재조회, 다른 sourceUrl이면409, 다른 key면 새 item |
| 저장 후 삭제된 항목의 생성 재전송 | ITEM-01: 동일 ID/lifecycle 표현, 새 item 생성 금지. 일반 ITEM-03은404 |
| 편집·보완·review의 오래된 version | ITEM-04/ITEM-07/ITEM-08:409 currentVersion/복구 code, 입력 초안 유지·ITEM-03 재조회 |
| PROCESSING 수정·retry, 수동 완료 뒤 retry | ITEM-04/ITEM-06/ITEM-07/ITEM-08: 상태별 거절. UI allowedActions와 server 조건 일치 |
| 사라진 category/purpose를 선택·AI stale 결과 | 선택 저장409 CATEGORY_NOT_AVAILABLE 등, 초안 유지. Worker는 현재 참조·generation 검증 |
| custom 20개·필드 한도·공백 이름 중복·과도한 생성 | CAT-03/CAT-04: 필드별validation, 한도/중복 code,429 Retry-After. 동시 요청에도 한도/unique 적용 |
| category/purpose 삭제 영향이 확인 뒤 바뀜 | CAT-06/PUR-06: impactToken/참조 재검증,409 뒤 CAT-05/PUR-05 재조회 |
| 후보 이동 중 한 item을 다른 기기가 변경 | PUR-08: 전체 거절, 충돌 item 식별·최신 조회 정보. 부분 성공 없음 |
| archive 확인 이후 후보 추가/이동/편집 | ARC-02: 전체 previewToken 불일치409, ARC-01 재조회. 페이지에서 읽은 후보만 archive하지 않음 |
| 빈 목적·구매 item이 현재 후보 아님 | ARC-01/ARC-02: 아카이브 불가/validation, 구매 선택 제거·재확인 |
| archive 제목 수정·복원·삭제 경합 | ARC-06/ARC-08/ARC-09: expectedVersion·lifecycle 재검증, 충돌409. restore/retry는 idempotency |
| 복원 때 custom category 삭제 등 | ARC-07/ARC-08: 현재 영향과 복원 정책을 동일하게 적용. 구체 정책은 아래 미결정 |
| 후보가 많음·anchor item 삭제/이동 | ITEM-02/HOME-02·각 후보/영향 GET:cursor·상한, anchorResolved=false와 가까운 item |
| FAILED_RETRYABLE/TERMINAL·부분 결과 | ITEM-03/HOME-02: 안전한 failureCode·누락 사유, retry 또는 직접 보완/삭제의 조건 구분 |
| image upload 실패·취소·다른 owner의 mediaId | MEDIA-01/MEDIA-02와 ITEM-04/ITEM-07: 미완료 자원 연결 거절·초안 유지·재업로드. cancel 뒤 미참조 정리 |
| 원본 URL/가격/이미지 없음·외부 image 만료 | item/archive GET:optional null·placeholder·확인 시각, 이미지 접근 재조회. 원본 URL은 공유 snapshot에서 유지 |
| Worker/task 중복·중단·이전 generation | WORK-01/WORK-02·OPS-01: ACK/retry, execution/generation fence·outbox 복구. 제품 API 재노출/삭제 복원 금지 |

미리보기 token·DELETE version의 header/query/body 전달 형식과 신규 명령의 key retention 기간은 상세 API 계약에서 정한다. 기존 ITEM-05의 무version 삭제와 신규 자원 삭제의 영향 확인 검증을 동일한 요구라고 가정하지 않는다.

## 남은 제품 결정 — API 목록에서 누락으로 숨기지 않음

| 항목 | 현재 확인한 근거·추가 확인 | 영향 API |
| --- | --- | --- |
| 목적 활동순·홈 노출 | **B3/B4 해결**: 생성 또는 후보 유입이 활동, `activityAt DESC, id DESC`, AI 후보 10개. 홈은 최대 3개·빈 목적 포함, 이름·색·아이콘·후보 수·최근 활동·최근 저장 후보 최대 4개(이미지 null도 포함·placeholder 표시) 제공 | HOME-01 |
| category count와 목록 표시 집합 | **B4 해결**: category count는 일반 목록과 같은 owner·ACTIVE·category 일치·비공백 제품명 집합. 공용 BROWSE 노출과 상위 합계도 동일 | CAT-01/CAT-02, ITEM-02 |
| 서버 할 일 합계 | **B4 해결**: 별도 합계 필드 없음(C안). 그룹별 count만 반환하고 필요하면 client에서 합산 | HOME-01 |
| 후보 추가 초기 filter·선택 유지 | 한 category 목적·빈 목적·혼합 category의 초기값과 filter 변경 시 선택 유지 | PUR-07/PUR-08 |
| 연속 처리 재진입/restart | **B4 해결**: 살아 있는 화면은 현재 anchor 재조회, 앱 신규 실행은 최신 첫 구간. restart는 기기 skip 초기화와 현재 미완료 재조회이며 CONFIRMED/DEFERRED 검토 재노출 없음. 별도 restart API 없음 | HOME-02/ITEM-08 |
| 상품명·브랜드·archive 제목 입력 제한 | 목적은 **B3 해결**(이름40/설명200 code point·중복 허용). 상품명·브랜드·archive 제목은 추가 확인 | ITEM-04/ITEM-07, ARC-06 |
| 상품 후보 수 상한·bulk 최대 크기 | 목적 수는 **B3 해결**(ACTIVE 30개·60초 10건). 상품 window 규칙은 있음. bulk 요청 크기는 별도 확인 | PUR-07/PUR-08, ARC-01/ARC-02 |
| 중복 여러 후보·다른 판매처 동일 상품 | 현재 보드는 한 쌍. 여러 후보의 처리 단위·상품 식별 기준과 실패 URL 안내 필요 | DUP-01/DUP-02 |
| 새 자원 만들기와 편집 취소 | purpose는 **B3 해결**: 취소 뒤에도 생성한 목적 유지(빈 목적 자동 삭제 금지). category의 수명은 별도 확인 | CAT-03 + ITEM-04/ITEM-07/ITEM-08 |
| archive 목적 snapshot·복원 예외 | 최신 상세는 목적 아이콘 필요. 설명/color/icon의 복원 범위·삭제 custom 참조·archive 제목 수정 후 목적 이름 복원 기준 확인 | ARC-02/ARC-04/ARC-07/ARC-08 |
| 아카이브 후보의 분석 상태 | 일반적으로 비교 가능 item을 다루지만 PROCESSING/보완 필요 후보를 목적에 추가·archive할 수 있는지 명확히 필요 | PUR-07/PUR-08, ARC-01/ARC-02/ARC-08 |
| archive 정렬·snapshot 후보 순서 | 최근 종료순은 디자인의 가정, 구매 item 맨앞은 최신 결정. 나머지 후보 sort 고정 필요 | ARC-03/ARC-05 |
| Share Extension 직접 전송 | 직접 ITEM-01 호출 또는 local-only 뒤 본 앱 전송 미결정. ‘분석 중’ 문구의 사실성에 영향 | ITEM-01, client share 수신 |
| image 저장 제한·외부 이미지 영속 보관 | 사용자 사진 선택은 확정, MIME/size·관리 저장소·archive 외부 이미지 보존 방식은 기술 설계 필요 | MEDIA-01/MEDIA-02, ITEM/ARC GET |

미결정은 해당 필터·validation·정렬·snapshot 구현의 선행 조건이며, 상품 생성 응답 수정·상세 조회·공통 상태·기본 목록 구현을 모두 막지는 않는다.

## 이번 범위에서 추가하지 않는 API

- 공용 taxonomy 수정/삭제, AI category/purpose 생성, 새 목적 생성 시 기존 상품 전체 자동 재분석.
- 가격·재고 polling, 환율 변환, 전문 검색·추천·유사 상품, 공개 wishlist 공유.
- archive 개별 후보 편집/삭제·부분 복원, 구매 item 단독 확정, 웹뷰 결제 자동 구매 처리.
- 완료 review 재개, 앱 서버 OAuth/login/logout, 쇼핑몰 cookie/session 저장, 전체 로컬 대기 서버 sync.
- 별도 상태 polling, WebSocket/SSE/push 등록. 갱신은 최초 진입/foreground/사용자 새로고침에 기존 조회 API 재사용.

이 제외는 현재 기획 기준이다. 향후 범위가 달라지면 source→행동→API 추적 표를 함께 갱신한다.

## 다음 산출물과 검증

이번 산출물은 **현재 전달 자료의 기능 범위를 모두 대응시킨 API 목록**이다. 각 목록 동작에는 지원 화면 또는 기능 근거, 최소 입출력, 구현 상태를 기록했다. 모든 제품 정책이 확정되거나 API가 구현됐다는 뜻은 아니다.

다음 상세 계약은 이 목록의 ID를 유지해 요청·응답 schema/예시·status/오류·권한·version/idempotency·cursor 규칙을 작성한다. 구현은 [MVP API 구현 순서 계획](mvp-api-implementation-order.md)의 최초 API 배정과 선행 조건에 따른다. 각 묶음 시작 전에 해당 계약을 상세화한다.

검증은 manifest의 W01~W43 전부 대응, API ID 중복/미참조 없음, method+path 중복 없음, core route/runtime 존재 여부 대조, 문서 링크/INDEX와 Markdown diff 점검으로 수행한다. 문서 작업이므로 이번 목록의 신규 API 실행 테스트를 통과했다고 주장하지 않는다.

## 관련 문서

- [MVP API 구현 순서 계획](mvp-api-implementation-order.md) — API별 최초 묶음·선행 조건·통과 기준
- [MVP 제품 API 설계](mvp-product-api-design.md) — 구조·데이터·동시성·구현 순서
- [기존 상품 상태/API 계약](../wishlist-item-state-api.md) — 기존 확정 상품 API와 갱신 정책
- [서버 정밀 검토](../../history/architecture/server/technical-design-checkpoint-2026-10-04.md) — 현재 코드의 차이와 이전 실행 검증
- [디자인 핸드오프 검토](../../history/product-planning/mvp/checkpoints/design-server-handoff.md) — 전달 과정과 추가 확인 사항
