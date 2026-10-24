# C5 카테고리 탭·세부 유형 목록·선택 시트 설계

> 2026-10-14 · **확정(검토 3회 반영: SELECT 공급·사진 캐시·제출 중·편집 복구·code point 입력·확인 시각 누락 / 사진 메모리 상한·전환 중 사진 보유·목록 머리 갱신·필드 오류 전달 / iOS 요청 취소·뒤로 전환 사진 공급원)** · 제품 빈칸 확정(D1~D15) · 기준 `origin/develop` `e2653f6`(C4 PR #15·#16 merge 뒤)

## 목표와 범위

카테고리 탭에서 상위 카테고리 레일과 세부 유형 칩으로 저장한 상품을 찾아가고, 칩을 눌러 세부 유형의 2열 엇갈림 상품 목록을 보며, 카드를 눌러 C4 상품 상세로 간다. 지금 보는 상위 아래에 직접 만든 세부 카테고리를 추가하고, 그 목록 화면에서 이름·설명·포함 예시를 편집한다. 상품 편집(C8)에서 쓸 카테고리 선택 시트와 그 안의 신규 카테고리 폼도 완성한다. 보드는 FCategoryHome·AddSheet·List·ListCustom·ListCustomEmpty·EditSheet, FProductCategoryPicker·Create이고 서버는 B2(CAT-01~04)와 B4(ITEM-02)다. 앱은 DEBUG Fake로 완료하며 실서버는 "인증 연결" 단계다.

**하지 않는 것:** 직접 만든 카테고리 삭제(CAT-05/06, FCategoryDeleteConfirm은 C9), 상품에 카테고리 저장(ITEM-04, C8), 목록의 복귀·새로고침 anchor와 window 캐시(C7), 목적 탭(C6), 웹뷰 순수 로직 shared 이전(이슈 #18).

## 확정한 제품 결정 (2026-10-14 사용자)

| # | 질문 | 결정 |
| --- | --- | --- |
| D1 | 선택 시트 범위 | 레일+칩 시트, 시트 안 신규 카테고리 폼, Presenter를 C5에서 완성한다. 진입은 DEBUG 데모만 두고, 상품에 저장(ITEM-04)과 편집 화면 연결은 C8 |
| D2 | Remote | CAT-01~04와 ITEM-02(categoryId 범위) Remote를 만들고 Fake·MockEngine에 같은 계약 테스트를 돌린다. 앱 DEBUG는 Fake, RELEASE는 UNAVAILABLE 유지 |
| D3 | 목록 페이지 | ITEM-02 cursor로 40개씩, 끝에 닿으면 다음 페이지를 이어 붙인다. 상세에서 돌아오면 화면이 위치를 유지한다. 복귀·새로고침 anchor 갱신은 C7 |
| D4 | PR 분할 | PR A `client/c5-category`: 카테고리 기능 전체(카드 → 상세는 슬라이드). PR B `client/c5-photo-transition`: 카드 사진 → 상세 사진 공유 요소 전환(A 위에서 시작, A merge 뒤 develop으로 rebase) |
| D5 | 로그인 전 탭 | 제목 "카테고리" + 빈 상태 "로그인하면 저장한 상품이 카테고리별로 모여요" + 로그인 버튼(기존 `LoginRoute` push) |
| D6 | 빈 탭(상품·직접 만든 카테고리 없음) | 빈 상태 "아직 상품이 없어요 · 상품을 공유해 저장하면 여기에 모여요" + 보조 버튼 "새 카테고리 만들기"(상위를 고르는 추가 시트). 레일·헤더 개수는 숨긴다 |
| D7 | 직접 만든 목록의 ⋯ | 메뉴에 "편집"만 둔다. "삭제"는 C9에서 더한다 |
| D8 | 카드 마지막 줄 | 공용·직접 만든 목록 모두 목적 색 점 + 목적 이름 · "N일 전 확인". 목적이 없으면 "목적 미지정 · …". 직접 만든 목록 보드의 판매처 표기는 따르지 않는다 |
| D9 | 탭에서 만든 뒤 | 시트를 닫고 만든 카테고리의 상위 페이지로 간다. 칩 목록 끝에 새 칩(0)이 보인다 |
| D10 | 사진 비율 | 페이지 단위 skeleton. 한 페이지의 사진 크기를 모두 알거나(실패·사진 없음 포함) 제한 시간이 지날 때까지 skeleton 카드를 보이고, 그다음 원래 비율로 한 번에 배치한다. **비율 제한 없음.** 배치한 뒤에는 카드 높이가 바뀌지 않는다 |
| D11 | 편집 409 | 최신 상세(CAT-02)를 다시 받아 폼을 교체하고 "다른 곳에서 바뀐 내용을 불러왔어요"를 보인다. 내 입력은 버린다 |
| D12 | 포함 예시 입력 | 키보드 완료(↵)로 칩이 되고 칸은 비운다. 빈 값·이미 있는 예시는 무시하고, 5개면 입력칸을 숨긴다. 저장 때 칸에 남은 글자도 예시로 넣는다 |
| D13 | 미확정 표시 | `reviewStatus`가 PENDING·DEFERRED인 카드는 사진 왼쪽 위에 보드의 ✳ 표시(접근성 "분류·목적 미확정")를 붙인다([구매 후보 정리](../../product/organize-candidates.md#분류목적-확인): 보류 항목은 일반 목록 카드에서 미확정 표시) |
| D14 | 선택 시트에서 만든 뒤 | 만든 카테고리를 선택값으로 돌려주고 시트를 닫는다([디자인 결정](../../design/decisions.md) 2026-10-02 선택 시트, API 목록 "만들고 현재 상품에 선택"). 편집을 취소해도 만든 카테고리는 남는다 |
| D15 | 확인 시점 표현 | 공용 상대 시각에 주 단위를 더한다: 방금·N분·N시간·어제·N일 전(2~6일)·N주 전(7~27일)·M월 d일(28일 이상, 해가 다르면 연도 포함). 목록 카드와 C4 상세 가격 안내가 같은 함수를 쓴다(보드 "1주 전 확인"과 맞춤) |

이미 정한 디자인 결정(레일·세로 페이저·마지막 상위 기억, 칩, 점선 `+ 추가`, 편집 시트, 선택 시트의 먹색 선택 칩과 `+ 카테고리`, 2열 엇갈림, 목록 sticky 머리, 사진 없는 이동의 슬라이드)은 [디자인 결정](../../design/decisions.md)과 [screens.md](../../../design/handoff/interactions/screens.md#카테고리)를 그대로 따른다.

## 접근 방식

1. **채택: 화면별 Presenter + 카테고리·목록 repository를 API 단위로 새로 둠.** `CategoryHomePresenter`, `CategoryListPresenter`, `CategoryFormPresenter`(생성·편집), `CategoryPickerPresenter`가 각자 state를 갖고, repository는 CAT-01~04·ITEM-02 단위 인터페이스다. C2의 seed 전용 `CatalogRepository`는 카테고리·목록 쪽 메서드를 걷어내고 목적 조회만 남긴다(C6에서 정리). C4와 같은 Presenter 계약(단일 lane, 마지막 요청 승리, session 변경 시 초기화, 멱등 close)을 따른다.
2. 카테고리 store 하나(전역 StateFlow)를 두고 화면들이 구독하는 방식은 생성·편집 뒤 갱신이 자동이지만, 계정·오류·로딩을 store 하나에 섞고 C4 Presenter 계약과 다르게 된다. 생성·편집 뒤에는 결과를 받은 화면이 CAT-01을 다시 조회하면 충분하다(서버 계약도 재조회를 요구).
3. 플랫폼 ViewModel마다 상태를 두는 안은 입력 규칙(code point 길이, 금지 문자)과 409 복구가 두 번 생긴다.

## 1. 데이터

**모델.** `CategoryCatalog(scope, groups, customUsedCount, customLimit)`, `CategoryGroup(id, name, itemCount, categories)`, `CategoryEntry(id, name, parentId, kind: PUBLIC|CUSTOM|UNKNOWN, itemCount)`, `CustomCategoryDetail(id, name, parentId, description?, examples, itemCount, version)`, `CategoryCreated(detail, customUsedCount, customLimit)`, `ItemPage(items, totalCount, nextCursor?)`. kind 원문은 보존하고 모르는 값은 UNKNOWN(공용처럼 ⋯ 없음)이다. 기존 flat `Category`는 C2 seed 경계에서만 남기고 새 화면은 쓰지 않는다.

**repository(API 단위).** `CategoryRepository`: `catalog(scope, parentId?)`(CAT-01), `custom(id)`(CAT-02), `create(command, idempotencyKey)`(CAT-03), `update(id, expectedVersion, patch)`(CAT-04). `ItemListRepository.byCategory(categoryId, cursor?, limit = 40)`(ITEM-02). PATCH는 바뀐 필드만 보낸다(`description` 해제는 null, 예시를 모두 지우면 `[]`).

**Remote.** DTO는 [B2 계약](../../architecture/server/category-management-api.md)과 [B4 ITEM-02](../../architecture/server/wishlist-item-read-api.md#item-02-범위와-표시)에서 옮긴다. 카드 `item`은 기존 상세 mapper를 재사용한다. 오류 코드는 `ClientError`로 매핑한다: `CATEGORY_NAME_DUPLICATE`·`CATEGORY_LIMIT_REACHED`·`CATEGORY_VERSION_CONFLICT`(`details.currentVersion`)·`CATEGORY_NOT_FOUND`·`CATEGORY_CREATE_RATE_LIMITED`(`Retry-After`)·`INVALID_CATEGORY_INPUT`(`details.fields`)·`IDEMPOTENCY_KEY_REUSED`·`CATEGORY_NOT_AVAILABLE`. 201과 replay 200은 같은 결과다.

**필드 오류 전달.** 지금 `ClientError`에는 `currentVersion`·`retryAfterSeconds`만 있고 `ApiErrorMapper`는 `details`에서 `currentVersion`만 읽는다. `ClientError`에 `fields: Set<String> = emptySet()`을 더하고, mapper가 `details.fields`(문자열 배열, 서버는 `parentId`·`name`·`description`·`examples`·`expectedVersion`을 정렬해 넣음)를 읽는다. 배열이 아니거나 문자열이 아닌 원소는 무시한다(오류 분류는 그대로). Fake도 `CategoryInputRules` 결과로 같은 이름을 넣는다. 기존 오류 경로는 빈 집합이라 바뀌지 않으며 Swift에는 `Set<String>`으로 보인다.

**Fake.** FakeStore가 서버 규칙을 재현한다: 11개 상위·87개 공용 leaf(서버 `V11__category_management.sql`의 이름·순서·ID), SELECT/BROWSE 노출과 count(ACTIVE·현재 category 일치·비공백 이름, group은 leaf 합), custom 순서(생성 당시 parent의 최대 displayOrder + 1, 첫 값은 공용 leaf 수), normalized 이름 중복(NFC는 common에 없어 trim·공백 collapse·소문자로 근사하고 한계로 적는다), 20개 한도, 60초 5건(주입 Clock), 같은 key 같은 payload replay·다른 payload 409, version CAS와 동일 내용 PATCH 무변경, ITEM-02 정렬(`createdAt DESC, id DESC`)·opaque cursor·limit 1~100, 없는 custom 404.

**보드 seed.** BROWSE count가 실제 membership에서 나오므로 보드 칩 숫자(합계 69)를 맞추려면 그만큼 상품이 있어야 한다. `BoardSeeds`가 각 칩 숫자만큼 READY 상품을 만든다(헤드폰 8개·오디오 케이블·DAC 2개는 보드 이름·가격, 나머지는 카테고리 이름 기반의 단순 이름). `BoardDisplayMetadata.categoryChipCounts`는 실제 count와 같아지므로 지우고 `BoardSeedsTest`가 count를 검증한다. 목적 연결은 지금과 같게 헤드폰만이다. 40개 페이지 경계는 기본 seed로 확인할 수 없으므로 DEBUG fixture hook(`seedLongCategory`)이 한 카테고리에 120개(세로로 아주 긴 사진·느린 사진 포함)를 넣는다.

**입력 규칙(shared 순수 함수 `CategoryInputRules`).** 이름 1~40, 설명 0~200, 예시 0~5개 × 1~60을 **Unicode code point**로 센다(서버 계약). 이름·예시의 줄바꿈, 단독 CR, 그 밖의 제어문자, U+200B·U+FEFF·bidi 제어(U+202A~202E, U+2066~2069), 짝 없는 surrogate를 금지 문자로 판정한다. 설명은 LF·CRLF를 허용한다. 금지 문자는 저장 전에 안내하고 Fake도 같은 함수로 422를 낸다.

**입력칸 길이 제한.** 기존 `WLInput`(두 플랫폼)은 길이를 grapheme으로 세고, IME 조합이 끝난 확정 글자에만 제한을 적용한다(조합 중인 한글은 건드리지 않는다). C5는 `WLInput`에 단위 인자(`Grapheme` 기본, `CodePoint`)를 더하고 카테고리 폼만 `CodePoint`를 쓴다. 조합 보존 방식은 그대로다. 넘는 글자는 **code point 상한 안에 들어가는 마지막 grapheme 경계**에서 자른다. 그래서 surrogate 쌍이나 emoji ZWJ 묶음을 반쪽으로 남기지 않는다(상한 근처 emoji는 통째로 빠질 수 있다). 붙여넣기도 같은 규칙이다. 자르는 함수는 플랫폼별 순수 함수로 두고, 조합 중 입력·확정 직후 자르기·emoji 경계를 단위 테스트한다. 기존 grapheme 사용처의 동작은 바뀌지 않는다.

**카드 마지막 줄(shared 순수 함수 `CardMetaLine`).** 목적 표시(색 점 + 이름 또는 "목적 미지정")와 확인 시점("N일 전 확인")을 " · "로 잇는다. `metadataCheckedAt`이 null이면 목적 표시만 두고 구분점을 붙이지 않는다. 목적 표시는 항상 있으므로 줄이 비지 않는다. 가격이 null이면 가격 줄만 숨긴다. 표를 테스트로 고정한다.

**목록과 상세 캐시.** ITEM-02 카드 응답은 상세 GET과 같은 표현이므로 `item_cache`에 GET 경로 규칙(`version >=`)으로 쓴다. 목록에서 본 상품의 표시 metadata(목적·카테고리 이름)가 상세 캐시에도 들어가 C4 한계 1(같은 version 표시값은 그 항목 GET에서만 갱신)이 줄어든다. 다만 카테고리 편집은 item version을 올리지 않아, 편집 전에 시작한 목록·GET 응답이 늦게 오면 같은 version으로 옛 이름을 덮을 수 있다(C4 한계 2와 같은 마지막 쓰기 승리). 편집 성공 뒤 목록은 `applyEdited`로 이름을 바로 고치고 다음 조회에서 바로잡히므로 한계로 남기고 C7 window 캐시에서 다시 본다. 페이지 자체(window)는 SQLite에 두지 않는다(C7).

**마지막 상위.** 계정별로 `app_state`에 `category.lastGroup.<accountId>` = G-ID를 둔다(C3-D7과 같은 표, 새 의존성 없음). 로드맵·kmp.md의 multiplatform-settings 도입 예정은 이 결정으로 대신하고 문서를 고친다. 저장된 상위가 BROWSE에 없으면 첫 상위를 연다.

## 2. Presenter

모두 `SharedRuntime`의 factory로 만들고 생성자는 `internal`이다. session 변경(계정 떠남·로그인) 때 진행 요청을 취소하고 처음 상태로 돌아간다. 오류 문구 분류는 C4 상세와 같다(연결 계열 "불러오지 못했어요", 그 밖 "잠시 후 다시 시도해 주세요").

**`CategoryHomePresenter`.** state `CategoryHomeState(signedIn, groups, totalCount, currentGroupId?, customUsedCount, customLimit, loading, error)`. `load()`(탭 진입), `refresh()`(당겨서 새로고침·foreground), `showGroup(groupId)`(페이저가 페이지를 바꿀 때, 마지막 상위 저장), `retry()`. BROWSE를 parent 없이 조회한다. 로그인 전은 `signedIn=false`로 요청하지 않는다(D5). 상품·custom이 없으면 빈 groups(D6).

**`CategoryListPresenter`.** `load(categoryId, hint)`: `hint`(칩에서 넘긴 이름·상위 이름·kind)로 머리를 바로 그리고, CAT-01 BROWSE(parent 지정)에서 이름·count·kind를, custom이면 CAT-02에서 상세를, ITEM-02 첫 페이지를 받는다. state `CategoryListState(header, items, totalCount, hasMore, appending, loading, error, gone)`. `loadMore()`는 진행 중이면 무시하고 실패하면 그 자리 아래에 "다시 시도" 줄을 둔다. `refresh()`는 머리와 첫 페이지를 다시 받고 이어 붙인 페이지를 버린다(C7 전까지의 한계). `refreshHeader()`는 상품 목록을 건드리지 않고 머리만 다시 받는다(CAT-01 BROWSE parent 지정, custom이면 CAT-02). `applyEdited(detail)`은 편집 성공 응답(CAT-04 최신 상세)의 이름을 머리에 바로 넣고 `refreshHeader()`로 개수·상위를 맞춘다.
- **부분 실패:** `load`·`refresh`에서 머리 조회와 ITEM-02는 따로 성공·실패한다. 머리만 실패하면 hint(또는 이전 머리)와 ITEM-02 totalCount로 그리고 오류를 보이지 않는다. ITEM-02만 실패하면 머리는 유지하고 본문에 오류 + 다시 시도(`retry()`는 실패한 쪽만 다시 받는다). `refreshHeader()` 실패는 이전 머리를 유지한다(편집한 이름은 `applyEdited`로 이미 반영됨). 당겨서 새로고침에서 머리만 실패하면 짧은 안내("일부 정보를 불러오지 못했어요")를 보이고, 편집 뒤 자동 갱신 실패는 안내 없이 둔다. 머리 개수는 CAT-01 itemCount를 쓰고, CAT-01이 없을 때만 ITEM-02 totalCount를 쓴다(둘은 같은 표시 집합). custom이 CAT-02 또는 ITEM-02에서 404(`CATEGORY_NOT_FOUND`)면 어느 경로든 `gone`이다. `refreshHeader`·`applyEdited`는 items·nextCursor·배치한 카드 높이를 그대로 둔다. 공용 leaf가 비어 BROWSE에서 빠지면 hint 이름과 ITEM-02 totalCount(0)로 빈 목록을 보인다. custom이 404면 `gone`("삭제된 카테고리예요" + 닫기).

**`CategoryFormPresenter`.** 생성(`startCreate(parentId?, source, select: CategoryCatalog?)`, select는 picker가 이미 받은 SELECT)과 편집(`startEdit(customId)`, CAT-02로 초기값)을 한 폼이 맡는다. state `CategoryFormState(mode, phase, parents, parentId, name, description, examples, pendingExample, dirty, canSubmit, submitting, fieldErrors, notice, usage, result?)`. `phase`는 `Loading | Ready | LoadFailed | ConflictUnresolved`다.

- **생성 폼의 데이터:** 탭 첫 화면의 BROWSE에는 상품이나 custom이 있는 상위만 있으므로 추가 시트의 상위 11개와 `customUsedCount/customLimit`은 `startCreate`가 **CAT-01 SELECT**로 받는다(빈 탭 D6도 같다). 받는 동안 상위 칩 자리는 skeleton이고 입력칸은 쓸 수 있으며 만들기는 꺼져 있다. 실패하면 `LoadFailed`("불러오지 못했어요 · 다시 시도", 다시 시도는 SELECT만 재조회, 입력 유지). 선택 시트에서 열 때는 picker가 이미 받은 SELECT를 넘겨 다시 조회하지 않는다.
- **편집 폼 첫 조회:** `startEdit`은 `Loading`(입력칸 skeleton, 저장 꺼짐, 닫기 가능)으로 시작해 CAT-02를 받는다. 실패는 `LoadFailed` + 다시 시도, 404는 `result = Gone`(시트를 닫고 목록도 "삭제된 카테고리예요").

- `canSubmit` = `phase == Ready` && 제출 중 아님 && `CategoryInputRules` 통과(이름 비공백 포함) && 상위 있음 && (편집: dirty) && (생성: `customUsedCount < customLimit` **또는** 지금 payload가 이미 보낸 payload와 같음). 뒤 조건은 응답을 잃은 같은 payload의 replay를 한도 선검사가 막지 않게 한다(서버는 replay를 한도보다 먼저 처리). 한도에 닿았으면 "세부 카테고리는 20개까지 만들 수 있어요"를 미리 보인다.
- **멱등 key:** 제출은 한 번에 하나다. 제출할 때 key와 보낸 payload snapshot을 함께 보관하고, 다음 제출의 payload가 snapshot과 같으면 같은 key, 다르면 새 key다(같은 key 다른 payload는 서버 409). snapshot은 생성 성공 때 지운다.
- 결과: 생성 성공 → `result = Created(id, parentId)`(D9·D14). 편집 성공 → `Saved`. 이름 중복 → 이름 칸 오류. 한도 → 위 안내. 429 → "잠시 후 다시 만들어 주세요". 422 → `fields`로 칸을 고른다. `name`·`description`·`examples`는 그 입력칸 아래 "쓸 수 없는 문자가 들어 있어요"(길이는 입력칸이 막으므로 서버만 아는 규칙 차이도 같은 문구), `parentId`는 상위 칩 아래 "상위 카테고리를 다시 골라 주세요", `expectedVersion`·빈 집합·모르는 이름은 칸 없이 "저장하지 못했어요. 다시 시도해 주세요". 칸 오류는 그 칸을 고치면 지운다. 404(편집) → `result = Gone`. 그 밖 → "저장하지 못했어요. 다시 시도해 주세요"(입력 유지).
- **409 version(D11) 복구:** 최신 CAT-02를 받는 동안 입력칸과 저장은 잠긴다. 성공하면 폼을 교체하고 notice. 단, 최신 값의 name·description·examples가 마지막으로 보낸 PATCH를 적용한 기대 결과와 같으면 원하는 값이 이미 반영된 것이므로(내 응답 유실이든 다른 기기가 같은 값을 저장했든) `Saved`로 끝낸다. 폼 교체와 notice는 최신 상세를 받은 뒤에만 한다. 재조회가 실패하면 `ConflictUnresolved`: 초안은 그대로 두고 "다른 곳에서 바뀌었어요. 최신 내용을 불러오지 못했어요 · 다시 시도"를 보이며, 저장은 재조회가 성공할 때까지 꺼진다(옛 version으로 다시 보내지 않는다). 재조회가 404면 `Gone`.
- **제출 중:** `submitting` 동안 Presenter는 다시 제출을 무시하고, 플랫폼은 버튼을 진행 표시로 바꾸고 입력칸·상위 칩·예시 ×를 잠근다. 닫기 버튼·아래로 끌기·시스템 뒤로도 응답이 올 때까지 막는다(transport 제한 시간으로 끝이 있다). 응답 뒤의 결과가 화면이 할 일을 정하므로, 닫힌 시트에 결과가 도착해 "만들었는데 칩이 안 보임" 같은 어긋남이 없다. 계정 전환·화면 종료로 Presenter가 닫히면 요청을 취소하고 결과는 버린다.
- **응답을 잃었을 때:** 연결 끊김·시간 초과처럼 서버 처리 여부를 모르는 실패는 "저장하지 못했어요. 다시 시도해 주세요"이고 입력을 유지한다. 생성은 입력을 바꾸지 않고 다시 누르면 **같은 key**라서 서버가 이미 만들었으면 replay 200으로 같은 카테고리를 돌려받는다(중복 생성 없음). 입력을 바꾼 뒤 누르면 새 key라 처음 시도가 실제로 성공했다면 카테고리가 하나 더 생길 수 있다. 이름이 같으면 중복 409로 막히고, 다르면 생긴다(알려진 한계, 생성 뒤 SELECT 재조회로 보이게 된다). 편집은 `expectedVersion`이 있어 다시 보내면 409 → 위 복구에서 "이미 반영됨"으로 끝난다.
- dirty는 처음 값과 비교한다(되돌리면 dirty가 아니다). 닫기·아래로 끌기는 dirty면 플랫폼이 "변경 사항을 버릴까요?"를 띄운다.

**`CategoryPickerPresenter`.** `open(selectedId?)`: SELECT를 조회해 11개 상위와 전체 leaf를 보이고, 선택된 칩이 있는 상위를 연다(없으면 마지막 상위). `showGroup`, `select(id)` → `result = Selected(id, name, parentId, kind)`. `+ 카테고리`는 플랫폼이 같은 시트 안에서 폼 모드로 바꾸고 `CategoryFormPresenter.startCreate(현재 상위, Picker)`를 쓴다. 생성 성공이면 그 카테고리를 `Selected`로 돌려준다(D14).

**플랫폼 owner.** C4와 같은 패턴(Android entry `ViewModel`, iOS `WLEntryOwners`)으로 화면 entry마다 Presenter를 소유한다. 시트의 폼 Presenter는 시트를 연 화면 owner가 시트 수명 동안 갖는다.

## 3. 화면과 내비게이션

**Route.** `CategoryListRoute(categoryId, hint)`: 계정 범위, 탭 바를 보이는 목록 화면, 슬라이드 push(두 화면 모두 탭 바가 있어 탭 바는 움직이지 않음). Android codec token `category/<id>/<base64url(hint json)>`. 카드 → 기존 `ItemDetailRoute`(PR A는 슬라이드, PR B에서 사진 공유 요소). 시트는 route가 아니라 화면 overlay(C1 `WLBottomSheet`)다.

**카테고리 첫 화면(FCategoryHome).** 머리 "카테고리"(도현 28) + "상품 N개". 왼쪽 레일 124pt(상위 이름, 가운뎃점·띄어쓰기 뒤에서만 줄바꿈, 현재 페이지 3px 막대). 오른쪽은 상위 하나가 한 페이지인 세로 페이저: 상위 이름 레이블, 칩(이름 + 고정폭 개수, 14px 알약), 점선 `+ 추가`. 놓으면 가까운 페이지로 스냅(300 `emphasized`), 레일을 누르면 같은 모션으로 이동. 한 페이지가 화면보다 길면(칩이 많을 때) 그 페이지 안에서 먼저 스크롤하고 끝에서 다음 페이지로 넘어간다. 당겨서 새로고침은 첫 페이지 위에서만 동작한다. 로그인 전·빈 탭은 D5·D6.

**추가 시트(FCategoryAddSheet).** 신규 카테고리: 상위 11개 칩(CAT-01 SELECT, 지금 페이지의 상위를 미리 고름, 빈 탭에서는 첫 상위), 이름·설명·포함 예시, 안내 "공용 카테고리는 바뀌지 않고, 나만 쓰는 세부 카테고리로 추가돼요.", 취소·만들기. 만들면 D9.

**세부 유형 목록(FCategoryList·ListCustom·ListCustomEmpty).** sticky 머리: 뒤로, 이름 + 개수(IBM Plex 700 고정폭), 보조 줄 상위 이름(직접 만든 것은 " · 직접 만든 카테고리"), custom만 ⋯(편집만, D7). 2열 엇갈림 카드: 사진(없으면 C4 기본 placeholder), D13 표시, 이름, 가격(없으면 숨김), D8 줄. 빈 custom: "아직 상품이 없어요 / 상품 편집에서 이 카테고리를 고르면 여기에 모여요.". 현재 탭을 다시 누르면 맨 위로(기존 `scrollToTopRequests`). 상세에서 돌아오면 위치 유지.

**사진 배치(D10).** 페이지가 오면 그 페이지 카드들의 사진을 **열 폭 기준(높이 제한 없음)**으로 받아 결과 픽셀 크기에서 비율을 얻는다. 크기만 따로 묻는 요청은 없고, 받은 사진이 곧 카드 사진이다(Android Coil은 원본 크기를 결과에서 얻고 아래 상한 크기로 디코드, iOS 새 `RemoteImage.image(for:width:scale:maxPixels:)`가 이미지와 원본 픽셀 크기를 함께 돌려줌). 모두 알거나 **3초**가 지나면 한 번에 배치한다.
- **메모리 상한:** 표시 비율은 EXIF 회전을 적용한 원본 픽셀 크기(디코드 전 헤더 값)로 정하고, 디코드 결과가 줄어도 그 비율을 유지한다. Android는 Coil 결과의 bitmap 크기가 아니라 디코드 전 원본 크기(`BitmapFactory` bounds를 읽는 decoder 단계에서 기록)를 쓴다. 디코더가 상한 때문에 정수 배로 줄이면 반올림으로 비율이 조금 달라지기 때문이다. 디코드 크기는 따로 제한한다. 디코드 폭은 열 폭 픽셀이고, 그 폭에서 높이가 열 폭의 3배를 넘으면 전체를 균일하게 줄여 **한 장의 디코드 픽셀이 열 폭 × 열 폭 × 3 이하**가 되게 한다. 비율은 그대로이고 아주 긴 사진만 해상도가 낮아진다. 동시 사진 작업은 **최대 6개**이고, 화면 밖으로 멀리 나간 카드의 요청은 취소한다. Android는 화면 쪽 semaphore로 Coil 요청을 감싸며, composable이 사라지면 Coil이 요청 coroutine을 취소한다. iOS는 아래 로더 변경으로 제한과 취소를 **실제 작업 기준**으로 건다.
- **iOS 로더 취소와 동시 제한(PR A):** 지금 `RemoteImageLoader`는 key마다 비구조 `Task`를 만들어 기다리는 쪽들이 공유하므로, 화면(`.task`)이 취소돼도 다운로드·디코드가 끝까지 돈다. 그래서 진행 중 작업마다 기다리는 수를 세고, 기다리는 쪽은 `withTaskCancellationHandler`로 빠질 때 수를 줄인다. 수가 0이 되면 내부 `Task`를 취소한다. `URLSession.data(from:)`는 Swift 취소를 받아 연결을 끊는다. 다른 화면이 같은 key를 기다리면 계속한다. 동시 제한은 로더 전체의 비동기 semaphore(허가 6개)로 둔다. 다운로드와 디코드를 하는 동안 허가를 잡고, 허가를 기다리는 동안 취소되면 대기열에서 빠져 작업을 시작하지 않는다. 메모리 상한도 실제 디코드 수 기준으로 지켜진다. 테스트(XCTest, stub `URLProtocol`): 기다리는 쪽 모두 취소 → 내부 요청 취소, 둘 중 하나만 취소 → 계속, protocol 기준 동시 실행 최대 6, 대기 중 취소된 요청은 시작되지 않음, 취소 뒤 같은 key 재요청은 새 작업. Presenter·배치 상태는 비율 숫자만 갖고 이미지 객체를 들지 않는다(이미지는 로더 cache와 화면에 그려진 카드만 보유). 메모리 cache 크기는 C4 설정을 유지하고 성능 기록(120개 목록 스크롤 중 최대 메모리, 긴 사진 여러 장과 느린 요청이 섞인 페이지)으로 확인한다. 사진이 없거나 실패·시간 초과면 1:1 placeholder이고, 늦게 온 사진은 그 1:1 칸을 채워 자른다(높이 불변). 이어 붙일 페이지는 목록 끝에 skeleton 카드를 두고 같은 규칙으로 바꾼다. skeleton은 카드색 면 + 이름·가격 자리 막대이며 움직임은 없다(동작 줄이기 대응은 범위 밖).

**편집 시트(FCategoryEditSheet).** "세부 카테고리 편집", "‘{상위}’ 아래 · 상품 n개", 이름·설명·포함 예시(칩 + ×, "n / 5"), 상품이 있으면 "이름을 바꾸면 상품 n개에 바로 반영돼요", 취소·저장. 저장하면 시트를 닫고 목록은 `applyEdited` + `refreshHeader()`, 첫 화면은 `refresh()`로 칩 이름을 맞춘다.

**선택 시트(FProductCategoryPicker·Create).** "카테고리 선택", 레일 120pt + 칩(선택은 먹색 면, 체크 없음), 끝에 `+ 카테고리`. 칩을 누르면 바로 선택·닫힘. `+ 카테고리`는 같은 시트가 "신규 카테고리"(뒤로 + 닫기, "‘{상위}’ 아래에 나만 쓰는 세부 카테고리를 만들어요. 공용 카테고리는 바뀌지 않아요.", 이름·설명·예시, "내 세부 카테고리 n / 20", 취소·만들기)로 바뀌고 높이는 300 `emphasized`. 뒤로·취소는 dirty면 버리기 확인 뒤 선택 화면으로. **진입은 DEBUG 데모(D1):** 기존 debug Demo 경로 자리에 "카테고리 선택 시트" 데모를 두고 선택 결과를 데모 화면에 표시한다.

**갱신.** 첫 화면: 탭 진입·당겨서 새로고침·foreground·생성/편집 성공 뒤. 목록: 진입·당겨서 새로고침(`refresh`), 편집 성공 뒤(`applyEdited` + `refreshHeader`, 상품 목록 유지). 목록의 foreground 갱신은 하지 않는다(이어 붙인 페이지를 버리게 되므로 C7 anchor 갱신에서). polling 없음.

**계정.** `CategoryListRoute`는 C4 규칙대로 계정을 떠날 때 닫힌다. 탭 첫 화면은 남고 Presenter가 처음 상태로 돌아간다. 로그인(null → A)이면 다시 조회한다. 열린 시트는 계정을 떠날 때 닫는다.

**기존 데모.** Android `feature/demo`의 `DemoCategoryScreen`·`DemoCategoryListScreen`과 iOS `DemoCategoryScreen`은 실제 화면으로 바꾸고 지운다. 목적 탭 데모는 C6까지 둔다. Android 모듈은 나누지 않는다(C3-D10 유지, 재검토 결과를 android.md에 적는다). iOS 이미지 로더는 C4 자체 로더를 유지하고 목록 메모리를 성능 기록에 남긴다(D5 재검토, Nuke는 새 의존성이라 문제가 보일 때만 제안).

## 4. 사진 공유 요소 전환 (PR B)

카드 사진이 상세 사진 자리로 420ms(`cubic-bezier(.2,0,0,1)`)에 커지고, 뒤로는 360ms에 줄어들며, 끌어서 뒤로는 손가락을 따라 줄어든다([디자인 결정](../../design/decisions.md) 2026-10-05·2026-10-07). Android `SharedTransitionLayout` sharedElement, iOS 기존 `WLSharedElement`(C1 spike)를 쓴다.

**사진 캐시 계약.** 지금 두 로더의 메모리 key에는 표시 크기가 들어 있다(iOS `RemoteImage`는 주소·칸 픽셀 크기·채우기 방식, Coil은 요청 size). 카드 크기와 상세 크기가 달라서 그대로 두면 상세가 전환 중에 다시 디코드하고, 그동안 커지는 면이 비거나 깜빡인다. 그래서 PR B에 로더 변경을 넣는다.

- **iOS:** `RemoteImage`에 주소별 색인을 두어 `bestCached(for: url)`가 그 주소로 메모리에 있는 가장 큰 사진을 돌려준다. 상세 사진 자리는 이것을 먼저 그리고(전환 시작 프레임부터), 상세 크기 사진이 오면 바꾼다. key와 다운샘플 규칙은 그대로다. 색인은 NSCache에서 빠진 항목을 따라 지운다. 상세 크기 사진은 따로 준비하며, URLCache(디스크)에 없으면 다시 받을 수 있다(허용).
- **Android:** 상세 요청에 `placeholderMemoryCacheKey(카드 요청의 memoryCacheKey)`를 주어(보조 경로) 카드 사진을 자리표시로 쓰고, 공유 요소 전환 중에는 그 자리표시가 보인다. 카드의 key는 카드가 그릴 때 받은 결과에서 얻어 route 인자가 아니라 화면 사이 전환 상태로 넘긴다.
- **전환 중 사진 보유:** cache key만으로는 사진이 남는다는 보장이 없다(메모리 cache는 축출될 수 있고, Coil 가이드의 `placeholderMemoryCacheKey`도 cache에 있을 때만 동작한다). 그래서 카드를 누르는 순간 카드가 지금 그리고 있는 이미지 객체(Android `coil3.Image`, iOS `UIImage`)를 셸의 `PhotoHandoff`(entry id → 이미지, 메모리 전용)에 넣는다. 상세 사진 자리는 handoff 이미지 → cache 검색(`bestCached`·`placeholderMemoryCacheKey`) → 기본 placeholder 순으로 쓴다. 카드 이미지를 확보하지 못하면(아직 로딩 중) 기본 placeholder가 같은 공유 요소 모션으로 커진다(slide로 바꾸지 않는다). handoff는 상세 크기 사진이 그려지고 전환 모션이 끝나면 놓고, 전환이 취소되거나 entry가 사라질 때도 놓는다. 뒤로 갈 때 줄어드는 사진은 상세가 그리고 있는 이미지를 쓴다. 지금 iOS 전환층(`WLPhotoFlight`)은 push·pop 모두 목록 카드 쪽(`WLSharedPhotoSource`가 registry에 등록한 사진)을 그리고, `WLSharedPhotoTarget`은 위치 탐침만 등록한다. 그래서 PR B에서 다음을 바꾼다.
  - **iOS:** `WLSharedPhotoTarget`도 지금 그리는 사진을 target key로 registry에 등록한다. 전환층은 방향에 따라 공급원을 고른다. push는 source(카드 handoff 이미지), pop·끌어서 뒤로는 target(상세 현재 사진)이고, 고른 쪽이 없으면 다른 쪽, 그다음 placeholder다. 끌기를 취소해 상세로 되돌아가는 동안에도 target 사진을 유지한다. 테스트: 방향별 공급원 선택(순수 함수)과 target 사진 등록·해제.
  - **Android:** Compose 공유 요소는 전환 동안 overlay에 한쪽 내용을 그린다. 뒤로 갈 때 그려지는 카드 쪽 사진 칸이 상세의 현재 이미지를 쓰도록, 상세가 pop을 시작할 때 자기 이미지를 `PhotoHandoff`(역방향 칸)에 넣고 카드 사진 칸은 전환 중이면 그것을 먼저 그린다. 전환이 끝나면 놓는다. 테스트: 역방향 handoff 넣기·놓기 수명. 프로세스 복원에는 handoff가 없으므로 일반 로드다. 사진이 아직 없는 카드(skeleton·placeholder)는 handoff 없이 placeholder가 커진다.
- 테스트: iOS 색인(큰 것 우선·빠진 항목 정리)과 handoff 수명(넣기·상세 사진 뒤 놓기·entry 제거 때 놓기)은 XCTest, Android key 전달과 handoff 수명은 단위 테스트. 기기에서 전환 첫 프레임에 사진이 비지 않음을 확인하고, 메모리 cache를 비운 상태(DEBUG hook)에서 push·pop·끌어서 뒤로 취소를 확인한다. 로더 호출 수와 실제 네트워크 다운로드 수는 따로 센다. 뒤로 갈 때 첫 프레임이 상세 사진(카드보다 선명한 사진)인지 확인한다. 사진이 없는 카드는 placeholder가 같은 방식으로 커진다. 목록이 아닌 진입(홈 "분류 중" 줄)은 슬라이드 그대로다. 상세 쪽 변경은 사진 자리의 공유 key와 진입 방식 인자뿐이다.

## 5. 문구

새 키는 ko·en 리소스를 함께 두고 문구 선택 함수를 순수 함수로 두어 키를 전수 검증한다. 보드 문구가 기준이고, 보드에 없는 문구(오류·빈 탭·로그인 전·skeleton 접근성)는 이 spec에서 정한다. 상세 표는 plan에 둔다.

| 자리 | 한국어 |
| --- | --- |
| 로그인 전 | "로그인하면 저장한 상품이 카테고리별로 모여요" · "로그인" |
| 빈 탭 | "아직 상품이 없어요" · "상품을 공유해 저장하면 여기에 모여요" · "새 카테고리 만들기" |
| 이름 중복 | "같은 상위 카테고리에 이미 있는 이름이에요" |
| 한도 | "세부 카테고리는 20개까지 만들 수 있어요" |
| 생성 rate limit | "잠시 후 다시 만들어 주세요" |
| 금지 문자 | "쓸 수 없는 문자가 들어 있어요" |
| 저장 실패 | "저장하지 못했어요. 다시 시도해 주세요" |
| 편집 409 | "다른 곳에서 바뀐 내용을 불러왔어요" |
| 409 재조회 실패 | "다른 곳에서 바뀌었어요. 최신 내용을 불러오지 못했어요" · "다시 시도" |
| 상위 필드 오류 | "상위 카테고리를 다시 골라 주세요" |
| 폼 데이터 조회 실패 | "불러오지 못했어요" · "다시 시도" |
| 없어진 custom | "삭제된 카테고리예요" · "닫기" |
| 다음 페이지 실패 | "더 불러오지 못했어요" · "다시 시도" |
| skeleton 접근성 | "불러오는 중" |

## 6. 오류·테스트·검증

| 경로 | 동작 |
| --- | --- |
| CAT-01 실패 | 항목 없으면 "불러오지 못했어요 · 다시 시도", 있으면 유지 + 짧은 안내 |
| ITEM-02 첫 페이지 실패 | 머리는 hint로 유지, 본문 오류 + 다시 시도 |
| 다음 페이지 실패 | 목록 끝 "더 불러오지 못했어요 · 다시 시도" |
| 생성·편집 실패 | §2 결과 목록, 입력 유지(409 version만 교체) |
| 폼 첫 조회 실패(SELECT·CAT-02) | `LoadFailed` + 다시 시도, 편집 404는 `Gone` |
| 409 뒤 재조회 실패 | `ConflictUnresolved`, 초안 유지·저장 꺼짐·다시 시도 |
| 응답 유실 | 입력 유지, 같은 payload는 같은 key로 재시도 |
| 이미지 실패 | 1:1 placeholder |

**TDD.** 새 테스트마다 해당 수정만 되돌려 실패를 확인하고 기록한다. `commonTest`는 Android host와 iOS simulator 모두 실제 실행한다.

- **범위 주의:** MockEngine 공통 테스트는 클라이언트의 wire 처리 확인이고 서버 구현 검증이 아니다(실서버는 인증 연결 단계).
- **commonTest:** `CategoryInputRulesTest`(code point·surrogate·금지 문자·설명 줄바꿈 경계), `CardMetaLineTest`(확인 시각 null·목적 없음), `ApiErrorMapperTest`(`details.fields` 읽기·잘못된 모양 무시·기존 경로 빈 집합), `CategoryMapperTest`·`ItemListMapperTest`(DTO·모르는 kind), `CategoryRepositoryContractTest`와 `ItemListRepositoryContractTest`(Fake·MockEngine 공통: SELECT/BROWSE·count·빈 custom·순서, 생성 201/replay·다른 payload 409·중복·한도·rate limit + Retry-After, CAT-02 404, PATCH version·no-op·null 해제, ITEM-02 정렬·cursor·이름 없는 상품 제외·없는 custom 404), `BoardSeedsTest`(count = 보드 칩), `LocalStoreContractTest`(목록 카드 cache 쓰기 `>=`, 마지막 상위 계정별), Presenter 넷(마지막 요청 승리·session 초기화·로그인 전·빈 탭·loadMore 중복 무시·실패 줄·refresh·`refreshHeader`가 목록 유지·`applyEdited`·머리만 실패/ITEM-02만 실패·실패한 쪽만 retry·hint·gone, 폼의 SELECT 공급(빈 계정·BROWSE에 상위 하나뿐인 계정·SELECT 실패)·첫 조회 실패/재시도·dirty·canSubmit·제출 중 재제출 무시·key 재사용/교체·응답 유실 뒤 같은 key·오류별 결과·`fields`별 칸 오류와 고치면 지움·409 교체·409 재조회 실패/404·초안과 같은 최신 값이면 Saved·남은 예시 포함, picker 선택·생성 후 선택), `SharedModulesTest`(binding·factory).
- **Android unit:** codec 왕복(`category/` hint의 `/ ? # %`·한글), 문구 키 전수, `WLInput` code point 자르기(조합 중 유지·확정 뒤 자름·emoji 경계·grapheme 기본값 불변), (PR B) 상세 자리표시 key 전달, owner 수명, 페이지 배치(크기 수집·3초 시간 초과·늦은 사진 1:1 유지·디코드 크기 상한 계산·동시 요청 6개) 순수 로직, 레일 줄바꿈 함수.
- **iOS XCTest:** route, 문구 키(ko·en), owner close, 같은 배치 로직, `RemoteImage.image(for:width:scale:)` 크기 반환, `WLInput` code point 자르기, (PR B) `bestCached` 색인, `SharedInteropTests` 새 Presenter.
- **화면 확인:** Android는 USB 실기기 Galaxy Z Flip6(`adb -s R3CX70ABZHP`, screencap에 `-d <display id>`), iOS는 iPhone 17 Pro 시뮬레이터. 보드 L/D 비교(FCategoryHome·AddSheet·List·ListCustom·ListCustomEmpty·EditSheet·FProductCategoryPicker·Create), 세로 페이저 스냅·레일 이동·마지막 상위 기억(앱 재시작 포함), 생성 → 새 칩, 편집 → 이름 반영, 한도·중복 오류(DEBUG Fake), 목록 이어 붙이기·skeleton·상세 왕복 위치 유지, 로그인 전·빈 탭·계정 떠날 때 목록 닫힘, 입력·IME 성능(C3 인계).
- **성능 기록:** [C3 성능 확인 목록](../../architecture/client/c3-performance-checks.md)에 목록 120개(3페이지, 세로로 아주 긴 사진 포함) 스크롤·최대 메모리를 더한다. 통과 기준이 아니라 기록이다.
- **마무리:** KMP_TEST·ANDROID_CHECK·IOS_TEST·iOS Release simulator build·`gen_tokens --check`, PR마다 push 전 독립 리뷰, draft PR에 로컬 검증 결과. PR A base `develop`, PR B는 A 위에서 시작해 base A로 열고 A merge 뒤 develop으로 rebase한다(D4). 리뷰 결과는 PR 코멘트로 올리지 않는다.

**작업 묶음.** 묶음 1~4는 PR A, 묶음 5는 PR B다. 묶음마다 커밋·검증·중간 확인 지점을 둔다.

1. **데이터:** 모델·DTO·mapper, `ClientError.fields`, `CategoryInputRules`·`CardMetaLine`, Fake 규칙·taxonomy·seed, Remote CAT-01~04·ITEM-02, 계약 테스트, binding, 목록 cache 쓰기·마지막 상위.
2. **Presenter:** 넷과 runtime 연결, Swift interop.
3. **Android 화면:** `WLInput` 단위 인자, route·codec, 첫 화면 페이저, 목록·배치, 추가·편집 시트, 선택 시트와 DEBUG 진입, 데모 제거.
4. **iOS 화면:** 같은 범위 + `RemoteImage` 폭 기준 요청·요청 취소·실제 작업 기준 동시 제한.
5. **사진 공유 요소 전환(PR B):** 로더 캐시 계약(iOS `bestCached`, Android 자리표시 key), 전환 중 사진 보유(`PhotoHandoff`), 방향별 전환 사진 공급원(iOS target 사진 등록·전환층 선택, Android 역방향 handoff) 포함.

## 문서

계획 `docs/superpowers/plans/2026-10-14-client-c5-category.md`, `kmp.md`·`android.md`·`ios.md` C5 절과 한계 표(NFC 근사, 목록 refresh가 이어 붙인 페이지를 버림, foreground 목록 갱신 없음, skeleton 3초), `server-integration-status.md`(CAT-01~04·ITEM-02), 로드맵 C5 행, `docs/design/decisions.md`(D5~D15), C5 구현·화면 확인 history 기록, 한계(응답 유실 뒤 입력을 바꿔 재제출하면 생길 수 있는 중복 생성, 상한 근처 emoji가 통째로 빠짐), 관련 INDEX. 새로 설명한 개념(멱등 key 교체 규칙, code point 길이)은 learning Q&A로 남긴다.
