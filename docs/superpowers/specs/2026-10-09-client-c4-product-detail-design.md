# C4 상품 상세·분석 중·원본 링크 웹뷰 설계

> 2026-10-09 · **확정(spec 리뷰 3회 반영)** · 제품 빈칸 확정 · 기준 `origin/develop` `abae37d`(PR #14 merge. 로컬 `develop`은 아직 `419be18`)

## 목표와 범위

홈의 "분류 중" 줄과 로컬 대기 줄에서 상품 상세로 들어가고, 분석 중 상품이 새로고침·foreground 때 완료되면 같은 화면이 상품 상세로 바뀌며, 어떤 상태의 상품이든 하단 "원본 보기"로 앱 안 웹뷰를 연다. 아직 서버에 없는 로컬 대기·FAILED 공유는 이 기기에서 지울 수 있다. 보드는 FProductDetail, FProductProcessing, FWebView·FWebViewShare·FWebViewExternal이고 서버는 B1(ITEM-03)이다. 앱은 DEBUG Fake로 완료하며 실서버는 "인증 연결" 단계다.

**하지 않는 것:** 상품 편집·서버 상품 삭제·다시 분석·직접 보완(ITEM-04~08, C8. 서버에 아직 없다), 카테고리·목적 목록에서의 상세 진입(C5/C6), 카드 사진 공유 요소 전환(사진 카드가 생기는 C5), Swift `.theRelease` 이름·`RemoteConfig` https/path 검사(C12), 계정별 웹뷰 쿠키 분리(스펙에 없음).

## 확정한 제품 결정 (2026-10-09 사용자)

| # | 질문 | 결정 |
| --- | --- | --- |
| D1 | C4에 READY 상품 목록이 없을 때 상세 진입 | 홈 "분류 중" 줄 → 분석 중 상세. 새로고침·foreground로 완료되면 같은 화면이 상세로 바뀐다. 상세 화면은 C4에서 완성하고 C5/C6은 진입점만 붙인다 |
| D2 | 삭제 범위 | 로컬 대기·FAILED만 이 기기에서 삭제. 분석 중·완료 상세의 ⋯ 메뉴는 C4에서 숨기고 C8(ITEM-05)에서 붙인다 |
| D3 | 계정 전환 뒤 상세 `retry()` | session 변경 시 마지막 ID를 버린다(`retry()`는 아무것도 하지 않음). 셸은 계정을 **떠날 때만**(이전 계정이 있던 로그아웃·다른 계정·같은 계정 재로그인) 계정 범위 화면(상세·로컬 대기·웹뷰)을 닫는다. 로그인(null → A)에는 닫지 않는다(2026-10-10): 미귀속 대기 행은 그 계정에 묶여 계속 보이고 전송되면 `MovedTo`로 이어지며, 웹뷰에는 계정 정보가 없다 |
| D4 | 로컬 대기 줄 탭 | 분석 중 상세 틀을 재사용한 로컬 대기 화면. 제목 = host, 대기 이유 문구, 저장 시점, 원본 보기, ⋯에 삭제만 |
| D5 | 이미지 로더 | Android Coil 3, iOS 자체 로더(URLSession + URLCache + NSCache, 다운샘플링). C5 목록에서 재검토 |
| D6 | 웹뷰 공유 URL | 지금 보는 페이지 URL. 링크 복사 뒤 "링크를 복사했어요"(Android 13+는 시스템 표시) |
| D7 | 분석 완료 반영 | 상세의 당겨서 새로고침 + foreground 복귀 + 진입 |
| D8 | 로컬 삭제 확인창 | "링크를 삭제할까요?" / 대상 "{host} · 이 기기에만 있어요" / "아직 보내지 않은 링크예요" · "되돌릴 수 없어요" / 취소 · 삭제(빨강). 삭제 뒤 홈 |
| D9 | 선택 정보가 없을 때 | 사진은 기본 placeholder(카드색 면 + 중립 상품 아이콘). 브랜드·가격이 없으면 그 줄을 숨긴다. 가격이 있으면 확인 시점 안내 |
| D10 | 정보 보완이 필요한 상품(`requiredAction`이 정보 보완 그룹) | 안내 한 줄(아래 `DetailKind` 표의 action별 문구), 빈 이름은 host + "제품명 · 입력해 주세요", 빈 카테고리 "골라 주세요"(누를 수 없음). 보완은 C8 |
| D11 | 목적 표시 | B3 `name/colorKey/iconKey`를 DTO→domain→SQLite로 받아 색 점 + 이름 |
| D12 | 상세 상태 화면 | 첫 로딩은 틀만, 항목 없는 오류 "불러오지 못했어요 · 다시 시도", 항목 있는 오류는 유지 + 짧은 안내, NOT_FOUND "삭제된 상품이에요" + 닫기 |
| D13 | 웹뷰 로딩·오류 | 진행 선은 로드 완료 때 사라짐, 로딩 중 새로고침 → 중지, main frame 실패 "페이지를 열 수 없어요 · 다시 시도", 제목 없으면 도메인만 |
| D14 | 웹뷰 도메인 | `www.`을 뗀 host(홈과 같음), 자물쇠는 https일 때만 |
| D15 | 웹뷰 열기·복원 | 슬라이드 push, 탭 바 숨김. 외부 앱 복귀 때 웹뷰·기록 유지, 프로세스 종료 뒤에는 원래 URL만 다시 연다 |
| D16 | 외부 앱 판정 | 사용자 탭(Android `hasGesture()`, iOS `.linkActivated`)은 바로 열고 그 밖은 확인창. 처리할 앱이 없으면 아무 일도 하지 않는다. 확인창 문구는 보드의 "결제 앱이 열려요"를 일반화한 "다른 앱이 열려요". 국내 결제(ISP·카드사 앱)는 탭 뒤 JS로 다시 이동해 gesture로 인정되지 않아 결제마다 확인창이 뜰 수 있다. 의도한 동작으로 두고 실제 결제 흐름 확인 항목으로 남긴다(실기기·인증 연결 단계) |
| D17 | SUBMITTING 줄 삭제 | 비활성. store도 삭제 때 상태를 다시 확인해 그 사이 시작된 전송을 지우지 않는다 |
| D18 | 인계 결함 | UUID 대소문자·새는 취소·해독 불가 cache row·close 뒤 DB 미접촉 단언을 C4에서 정리. `.theRelease`·`RemoteConfig`는 C12 |
| D19 | 로컬 대기 화면에서 전송 응답이 DELETED | accept가 cache를 지워 상품을 찾을 수 없으면 조용히 닫지 않고 상세 NOT_FOUND와 같은 "삭제된 상품이에요" + 닫기를 보인다(2026-10-10) |
| D20 | PR 분할 | PR A `client/c4-product-detail`(묶음 1~3, "원본 보기"는 지금처럼 시스템 브라우저), PR B `client/c4-webview`(묶음 4, A 위에서 시작하고 A merge 뒤 develop 기준으로 rebase. 웹뷰와 원본 보기·홈 "원본" 교체)(2026-10-10) |

## 접근 방식

1. **채택: 화면별 Presenter + 웹뷰는 플랫폼.** 서버 상품은 기존 `ItemDetailPresenter`를 확장(분석 중·완료·정보 부족을 한 Presenter가 맡음)하고, 로컬 대기는 새 `LocalSubmissionDetailPresenter`가 coordinator view를 관찰한다. 웹뷰는 탐색 기록·진행·외부 앱 판정이 플랫폼 API에 묶여 있어 native로 둔다. C2 Presenter 계약과 테스트를 유지하고 두 Presenter를 따로 검증할 수 있다.
2. 통합 DetailPresenter(target = Local | Item)는 화면 교체가 없지만 C2 계약(load·session·retry)을 다시 짜고 로컬·서버 실패 모델을 한 state에 섞는다.
3. 웹뷰 탐색 상태의 KMP화는 판정 입력이 결국 플랫폼에서 오므로 중계층만 늘린다.

## 1. 데이터

**목적 표시.** 클라이언트 `PurposeDto`에 nullable `name`, `colorKey`, `iconKey`를 더한다(서버 `WishlistItemDtos.PurposeDto`와 같은 모양). domain `ItemPurpose(id, source, name?, colorKey?, iconKey?)`는 wire 값을 원문(B3 대문자 `CORAL`)으로 보존한다. 화면 token(`coral`) 변환은 플랫폼 표시 계층의 함수 하나가 맡고, 모르는 key는 중립색 점이다. C6 요청의 반대 변환과 같은 표를 쓴다.

**SQLite v3.** `2.sqm`이 `item_cache`에 `purpose_name`, `purpose_color_key`, `purpose_icon_key`를 더한다. 기존 행은 NULL이고 다음 GET 때 채워진다. `SchemaMigrationTest`는 v1→v3, v2→v3을 검증한다.

**같은 version 갱신(GET 경로만).** ITEM-03 GET의 cache 쓰기는 `version >=`일 때 쓴다. 목적·카테고리 자체를 편집해도 item version은 그대로라서, 같은 version이라도 GET 응답이 더 최신이다. 더 작은 version은 버린다. `accept`(ITEM-01 응답)는 지금처럼 `>`만 쓴다. 멱등 재전송의 응답이 그 순간 cache에 있는 GET 결과보다 오래된 상태일 수 있어 `>=`면 더 새 값을 덮기 때문이다. `LocalStore.upsertItem`을 쓰는 곳은 GET decorator(`CachedGetItemRepository`) 하나뿐이므로 새 메서드를 만들지 않고 계약만 "`upsertItem`은 GET 전용이고 `>=`로 쓴다"로 바꾼다. `accept` 안의 쓰기는 지금의 `>` 비교를 유지한다. 한계 1: 같은 상품의 GET 둘이 겹치면(foreground 때 coordinator `refreshOnce`와 상세 `refresh()`) 같은 version이라 먼저 받은 응답이 나중에 commit되어 옛 목적 이름으로 덮일 수 있다. 다음 GET에서 바로잡히며 문서에 한계로 남긴다. 한계 2: 이 갱신은 그 상품을 다시 GET할 때만 일어나므로, 목적·카테고리 이름이나 색을 바꾼 뒤 다른 상품의 cache 행(C5 목록 cache 포함)은 다음 GET 전까지 옛 표시값을 가진다. C5/C6 인계에 적는다(목록·목적 조회 응답으로 표시 metadata를 갱신하는 정책).

**해독할 수 없는 cache row.** `SqlLocalStore.cachedItem`이 행 해독(enum·시각·필수값)에 실패하면 같은 transaction에서 그 행을 지우고 miss(null)를 돌려준다. decorator가 네트워크에서 다시 받아 쓴다. 행을 먼저 지우므로 깨진 행의 더 높은 version이 새 응답을 막지 않는다. DB 자체 실패(닫힘·I/O)는 지금처럼 `LOCAL_STORE_FAILURE`다.

**UUID 정규화.** `ItemDetailPresenter.load(id)`와 `CachedGetItemRepository.get(id)`는 `canonicalUuidOrNull(id)`이 있으면 그 소문자 값을, 없으면 원문을 쓴다(원문은 서버·Fake가 `VALIDATION`으로 거절). 캐시 조회·삭제와 Presenter의 같은 항목 비교는 정규화된 id로 한다.

**로컬 삭제.** `LocalStore.deleteSubmission(snapshot, submissionId)`는 한 transaction에서 행이 `PENDING`/`FAILED`이고 지울 수 있는 binding일 때만 지운다. 로그인 상태면 현재 계정 또는 미귀속 행, **비로그인이면 미귀속 행만**이다. 로그인 전에도 로컬 대기 화면에서 지울 수 있어야 하므로 `gatedForAccount`(accountId가 null이면 UNAUTHENTICATED)를 쓰지 않고, `saveSubmission`·`pending`처럼 계정 없이 동작하는 `gated(snapshot)`에서 binding을 직접 비교한다. `SUBMITTING`은 `CONFLICT/SUBMISSION_IN_FLIGHT`, 없으면 `NOT_FOUND`. Gated·Closed store에도 넣고 Closed는 DB를 건드리지 않는다. `SubmissionCoordinator.deleteLocal(submissionId)`가 지운 뒤 바로 게시한다.

`LocalStore.cachedItemBySubmission(snapshot, submissionId)`도 더한다(현재 계정 cache에서 `client_submission_id`를 정규 UUID로 비교, §2 `MovedTo`). cache는 계정 범위라 `gatedForAccount`를 쓰며 비로그인이면 `UNAUTHENTICATED`다. 반환은 `Success(item)`·`Success(null)`(찾지 못함)·`Failure`(조회 실패)를 구분한다.

전송과의 경합은 store transaction이 막는다(`viewLock`이 아니다. `prepareFlush`·`send`는 `viewLock` 밖에서 돈다). `send`의 `markSubmission(SUBMITTING)`은 transaction 안에서 행을 다시 읽고 없으면 `NOT_FOUND`를 돌려주므로, flush가 queue를 읽은 뒤 삭제된 행은 POST되지 않는다. 반대로 SUBMITTING이 먼저 commit되면 삭제가 `SUBMISSION_IN_FLIGHT`로 거절된다. 지금 `send()`가 false면 flush 전체가 멈추므로(`SubmissionCoordinator.kt` flush 루프) 줄 하나를 지우면 뒤의 대기 줄이 다음 계기까지 밀린다. **`markSubmission`이 `NOT_FOUND`이면 그 행만 건너뛰고 계속한다**(그 밖의 실패는 지금처럼 멈춤). 테스트: queue를 읽은 직후 삭제 → 그 행 POST 없음 + 뒤 행은 같은 flush에서 전송.

## 2. Presenter

**`ItemDetailPresenter` 확장.** 단일 lane, 마지막 요청 승리, `withCurrent` 발행, close 규칙은 유지한다.

- **계정 전환(D3):** session 변경 시 진행 요청 취소, `lastId` 삭제, `Initial`. 이후 `retry()`·`refresh()`는 아무것도 하지 않는다. 기존 고정 테스트(`SharedModulesTest` runtime Presenter, iOS `ItemDetailPresenterOwnerTests`·`SharedInteropTests`)를 이 정책으로 바꾼다.
- **`refresh()`:** 당겨서 새로고침·foreground용. `retry()`와 같은 경로이고 항목을 유지한다. "항목 있음 + loading"이 새로고침 중이다. 첫 load 전·계정 전환 뒤에는 무시한다.
- **홈 정합:** 상세 GET이 성공하면 조건 없이 runtime이 주입한 internal hook으로 coordinator에 view 재게시를 요청한다. 순서는 cache decorator가 cache에 쓴 다음(repository가 반환한 뒤)이다. channel이 conflated라 매번 보내도 비용이 거의 없고 이전 값을 들고 있을 필요가 없다. 추가 GET은 없다. 상세에서 완료를 본 뒤 홈의 "분류 중" 줄이 사라져 있다.
- **새는 `CancellationException`:** coroutine이 active인데 repository가 던지면 `UNAVAILABLE/DETAIL_STEP_FAILURE` + `loading=false`(coordinator와 같은 규칙, 화면 문구는 서버 계열 "잠시 후 다시 시도해 주세요"). 실제 취소는 전파한다. close 뒤 state는 마지막 값에 멈춘다(소유자가 사라진 뒤).

**`DetailKind`(shared 순수 함수).** 필드 유무로 다시 추론하지 않고 서버 상태 계약([홈 조치 상태](../../architecture/wishlist-item-state-api.md#홈-조치-상태))의 `lifecycleStatus`와 `requiredAction`을 기준으로 enum 전체를 매핑한다. **확인 순서는 `lifecycleStatus`가 먼저**이고 ACTIVE일 때만 `requiredAction`을 본다(서버 계약상 ACTIVE가 아니면 `requiredAction = NONE`). 표 전체를 테스트로 고정한다(`RequiredAction.entries` × `LifecycleStatus.entries`).

| 입력 | DetailKind | 안내 |
| --- | --- | --- |
| `lifecycle = DELETED`(방어용) | NOT_FOUND 화면과 같음 | "삭제된 상품이에요". 실제로는 나오지 않는다: 서버 GET은 DELETED를 404로 처리하고(`GetWishlistItemService`), accept는 DELETED면 cache를 지운다 |
| `lifecycle = ARCHIVED` | READY(보기만) | 없음. 아카이브 표시는 C10 |
| `ANALYSIS_IN_PROGRESS` | PROCESSING | 보드 안내 |
| `INFORMATION_COMPLETION` | INCOMPLETE | "상품 정보를 다 가져오지 못했어요" |
| `CATEGORY_ASSIGNMENT` | INCOMPLETE | "카테고리를 정하지 못했어요" |
| `CATEGORY_REASSIGNMENT` | INCOMPLETE | "카테고리가 삭제되어 다시 골라야 해요" |
| `CLASSIFICATION_REVIEW` | READY | 없음. 검토 흐름은 C7 |
| `NONE` | READY | 없음(수동 완료된 실패 상품 포함) |
| `UNKNOWN`(미래 값) | 보수적 대체: analysis `PROCESSING` → PROCESSING, 이름 또는 카테고리 없음 → INCOMPLETE("상품 정보를 다 가져오지 못했어요"), 그 밖 → READY | |

`AnalysisStatus`(PARTIAL·FAILED_*·UNKNOWN)와 `ReviewStatus`는 서버가 이미 `requiredAction`에 반영하므로 화면 종류를 직접 정하지 않는다. 안내 문구 둘(`CATEGORY_ASSIGNMENT`·`CATEGORY_REASSIGNMENT`)은 보드에 없어 이 spec에서 정한다.

**표시 값(shared 순수 함수).** `DisplayFormat`의 host(`www.` 제거), 가격(`KRW 549,000`, 통화별 소수 자릿수), 저장 시점 분류(방금·오늘·M월 d일), 가격 확인 시점 분류(N일 전 등). 문구는 플랫폼 리소스에 둔다(C3 `RelativeTime` 방식).

**`LocalSubmissionDetailPresenter`(새, `SharedRuntime.localSubmissionDetailPresenter()`).**

- `load(submissionId)`는 coordinator view를 관찰한다. state `LocalDetailState(row?, canDelete, deleting, error, outcome?)`. `row`는 host·sourceUrl·savedAt·`RowStatus`(홈과 같은 대기 이유 매핑). `canDelete`는 `PENDING`/`FAILED`만.
- `outcome`: 행이 view에서 사라지면 store의 `cachedItemBySubmission(snapshot, submissionId)`(item_cache의 `client_submission_id`, 정규 UUID로 비교)로 찾는다. `accept`는 큐 행 삭제와 cache 쓰기를 한 commit으로 하므로 행이 사라진 뒤의 조회는 항상 결과를 본다. 찾으면 `MovedTo(itemId)` → 플랫폼이 route를 `ItemDetailRoute`로 교체(분석 상태와 무관하게 같은 경로. ITEM-01이 멱등 재전송으로 이미 READY·FAILED 상품을 돌려줘도 `DetailKind`가 화면을 정한다). 결과별 처리:
  - `Success(item)` → `MovedTo(itemId)`.
  - `Success(null)`(찾지 못함)만 `RemovedOnServer` → "삭제된 상품이에요" + 닫기(D19, accept가 DELETED 응답으로 cache를 지운 경우).
  - `Failure`(store 실패) → `Gone`(안내 없이 닫기). 삭제로 보이지 않게 한다.
  - 비로그인 view에서 행이 사라짐 → 조회하지 않고 `Gone`. 비로그인 상태에서는 이 화면의 삭제 말고 행이 사라질 일이 없다.
  - 판정을 건너뛰는 것은 **이전 view의 `accountId`가 null이 아니고 값이 달라졌을 때뿐**이다(계정을 떠남, 셸이 화면을 닫음, D3). null → A(로그인)는 건너뛰지 않는다. 로그인하면 flush가 미귀속 행을 묶고 바로 POST·accept할 수 있고 view 게시는 200ms 간격으로 합쳐지므로, accountId = A인 첫 view에 행이 이미 없을 수 있다. 이때도 평소처럼 `cachedItemBySubmission`으로 판정해 `MovedTo`가 온다. `Deleted`(이 화면에서 삭제 성공) → 홈. view.processing에는 기대지 않는다.
- `delete()`는 플랫폼 확인 뒤 부른다. IN_FLIGHT는 오류 없이 무시(곧 `MovedTo`), 그 밖 실패는 `error`("지우지 못했어요").
- `tick()`은 홈처럼 1분마다 상대 시각을 다시 계산한다.
- 단일 lane, 멱등 close, close 뒤 intent 무시. 생성자는 `internal`.

**홈 줄 target.** `HomeRow`에 `target = Local(submissionId) | Item(itemId)`를 더해 key 문자열을 파싱하지 않는다.

**플랫폼 owner.** `ItemDetailPresenterOwner`는 지금 계약 그대로 화면에 연결한다. `LocalSubmissionDetailPresenterOwner`를 같은 패턴으로 만든다(Android `ViewModel`, iOS `@MainActor @Observable`, `close`/`deinit`).

## 3. 화면과 내비게이션

**Route.** 세 route 모두 탭 바를 숨기고 `Slide`로 연다(홈 줄에 사진이 없어 설정과 같은 가로 밀기). `ItemDetailRoute`·`LocalSubmissionRoute`와 계정 범위 정리는 PR A, `WebViewRoute`와 `web/` codec·왕복 테스트는 PR B다(D20). PR A의 상세 "원본 보기"와 홈 "원본"은 지금처럼 시스템 브라우저(Android `ACTION_VIEW`, iOS `openURL`)로 연다.

| route | Android codec token | 계정 범위 |
| --- | --- | --- |
| `ItemDetailRoute(itemId)` | `item/<id>` | 예 |
| `LocalSubmissionRoute(submissionId)` | `local/<id>` | 예 |
| `WebViewRoute(url)` (PR B) | `web/<base64url(url)>` | 예 |

iOS는 `AppDestination`에 case를 더한다. (PR B) `WebViewRoute`는 복원 때 원래 URL만 다시 연다. URL token은 UTF-8 base64url(padding 없음)로 인코딩해 `/`·`?`·`#`·`%`가 경로 파싱을 깨지 않게 한다. route는 factory로만 만들고 scheme이 `http`/`https`이고 host가 있는 URL만 받는다(그 밖은 null, 원본 보기 버튼 비활성). decode도 같은 검증을 거친다. 테스트: `/ ? # % & =`·한글·emoji가 든 URL 왕복, 잘못된 base64·`javascript:`·`file:` token은 null.

- **계정 범위 정리:** 셸이 `auth.account` 변화를 관찰해, 이전 값이 계정이었을 때(로그아웃·다른 계정·같은 계정 재로그인, D3)만 각 탭 스택에서 첫 계정 범위 route부터 위를 즉시 pop한다. null → 계정(로그인)에는 pop하지 않는다. 설정·로그인은 남는다. Navigator 순수 로직으로 검증한다.
- **`replaceTop`:** `MovedTo` 때 스택 맨 위를 교체하고 화면은 cross-fade. 교체된 entry의 owner(로컬 대기 Presenter)는 pop과 같은 경로로 닫힌다.
- **Android entry별 ViewModel:** `WLNavHost` entry마다 `ViewModelStoreOwner`를 두고(entry id 기반 store 맵을 Activity ViewModel에 보관) pop 때 `clear()`, 구성 변경 동안 유지한다.
- **iOS:** Android와 같은 구조로 맞춘다. ZStack이 화면 view를 유지하므로 view의 `@State`에 수명을 맡기지 않고, 셸의 `WLEntryOwners`(entry id → owner, runtime은 environment로 주입)가 owner를 만들고 보관한다. Navigator가 pop·`replaceTop`·계정 범위 정리로 entry를 없앨 때 셸이 그 entry의 owner를 `close()`하고 지운다. `deinit`은 보조다. Android·iOS 모두 "entry가 스택에서 사라지면 owner가 닫힌다"를 Navigator 테스트로 검증한다.

**홈 진입점.** 로그인 뒤 "분류 중" 줄 탭 → `ItemDetailRoute`(오른쪽 동작 없음 유지). 로컬 대기 줄(로그인 전·후) 탭 → `LocalSubmissionRoute`. 로그인 전 줄의 "원본"은 PR A에서는 지금처럼 시스템 브라우저이고, PR B에서 `WebViewRoute`로 바꾼다. 접근성 레이블은 "상세 보기"·"원본 열기"로 나눈다.

**상세 화면.** 공통 틀은 위쪽 바 56(안전 영역 + 6, 버튼 좌우 20) + 사진 자리 + 글자 묶음 + 하단 고정 "원본 보기"이고 탭 바는 숨긴다.

| 종류 | 내용 |
| --- | --- |
| READY (FProductDetail) | 사진 또는 기본 placeholder, 브랜드·제품명·가격(없으면 숨김), "N일 전 확인한 가격이에요. 지금 가격은 원본에서 확인해 주세요.", 정보 카드(카테고리 세부 이름 / 목적 색 점 + 이름 또는 "목적 미지정"), "M월 d일 저장". ⋯ 없음 |
| PROCESSING (FProductProcessing) | 사진 자리에 분석 중 타일 + "상품 정보 추출 중", 제목 host, "정보를 가져오는 동안은 편집할 수 없어요. 끝나면 앱을 다시 열거나 새로고침할 때 반영돼요.", 저장 시점. ⋯ 없음 |
| INCOMPLETE | READY 틀 + `DetailKind` 표의 action별 안내 한 줄, 빈 이름은 host + "제품명 · 입력해 주세요", 빈 카테고리 "골라 주세요"(누를 수 없음) |
| 로컬 대기 | PROCESSING 틀 + 대기 타일과 대기 이유 문구(홈과 같은 키). ⋯에 삭제만(SENDING이면 비활성), D8 확인창 |

당겨서 새로고침과 foreground 복귀는 `refresh()`(Android `PullToRefreshBox`, iOS `.refreshable`). 로컬 대기 화면은 view 관찰로 갱신하고 새로고침이 없다. 상태 화면은 D12. 새 문구는 ko·en 리소스를 함께 두고 문구 선택 함수를 순수 함수로 두어 JVM 테스트·XCTest로 키를 전수 검증한다.

**이미지.** Android Coil 3 `AsyncImage`(OkHttp fetcher, 기본 디스크 캐시). iOS `RemoteImage`(URLSession + URLCache + NSCache, 표시 크기 다운샘플링). 로딩·실패 때 placeholder.

## 4. 웹뷰

**저장소.** Android 기본 프로필 `WebView`(data directory suffix 없음), iOS `websiteDataStore = .default()`. 설정 "웹뷰 데이터 삭제"(`WebViewDataCleaner`)가 지우는 저장소와 같다. 쿠키는 방문 간 유지하고 Android는 쇼핑몰 로그인을 위해 third-party 쿠키를 허용한다.

**화면(FWebView).** 위쪽 막대: 닫기(X) · 자물쇠(https만) + host / 페이지 제목 · 2px 진행 선. 아래쪽 막대: 뒤로(기록 없으면 닫기), 앞으로(기록 없으면 비활성), 새로고침(로딩 중 중지), 공유. Android 시스템 뒤로도 기록 먼저 이동한다. iOS는 `allowsBackForwardNavigationGestures`로 기록 스와이프를 쓰고 기록이 없을 때만 셸 pop 제스처가 동작한다.

**공유 시트(FWebViewShare).** 블러 배경 규칙. 현재 페이지 URL로 "외부 브라우저로 열기"(Android `ACTION_VIEW`, iOS `UIApplication.open`, 웹뷰 유지), "링크 복사"(D6), "다른 앱으로 공유"(Android `ACTION_SEND` chooser, iOS `UIActivityViewController`).

**오류.** main frame의 네트워크 오류·잘못된 주소만 웹뷰 위 안내(D13). 하위 리소스 오류는 무시한다.

**탐색 규칙(QA-CLI-001, webview-behavior).**

- 새 창 요청(`target=_blank`, `window.open`)은 현재 웹뷰에서 연다(Android `setSupportMultipleWindows(false)`, iOS `createWebViewWith`에서 현재 웹뷰 load). 이때 `window.opener`가 사라져 opener·`postMessage`를 쓰는 PG 팝업(일부 카드사·간편결제)은 결제가 끝나지 않을 수 있다. D16과 함께 실기기 확인 항목이며, 깨지면 결제 팝업만 별도 웹뷰로 여는 방안을 그때 검토한다.
- **main frame**은 `http`/`https`와 `about:blank`만 웹뷰 안에서 연다. `data:`·`blob:`은 하위 리소스(iframe 아님)에만 허용하고 main frame 이동은 막는다(최상위 data: 피싱, Chrome과 같은 규칙). `javascript:`·`file:`·`content:`는 main frame에서 막는다.
- **Android `intent://`:** `Intent.parseUri(url, URI_INTENT_SCHEME)` 뒤 `component = null`, `selector = null`, `addCategory(CATEGORY_BROWSABLE)`을 강제한 intent만 외부로 보낸다(웹 페이지가 기기 안 임의 컴포넌트를 실행하는 intent scheme 공격 방지). intent가 `http`/`https` data를 가리키거나 해석할 앱이 없으면 `browser_fallback_url`(http/https일 때만)을 웹뷰 안에서 연다.
- **Android WebView 설정:** `allowFileAccess = false`, `allowContentAccess = false`, `addJavascriptInterface`는 쓰지 않는다(금지), JavaScript·DOM storage는 쇼핑몰 동작을 위해 켠다, mixed content는 기본값(`NEVER_ALLOW`), Safe Browsing 기본값 유지. third-party 쿠키를 허용하므로 이 제한을 함께 둔다. 설정 함수를 단위 테스트로 고정한다.
- **iOS:** 외부 scheme 판정에 `canOpenURL`을 쓰지 않는다(`LSApplicationQueriesSchemes` 등록 필요). `UIApplication.open(_:options:completionHandler:)`의 결과로 판정하고 false면 아무 일도 하지 않는다. iframe 이동은 `targetFrame?.isMainFrame`으로 구분한다.
- 그 밖의 scheme(결제 앱, `tel:`, `mailto:`, 앱스토어)은 외부 앱. 사용자 탭이면 바로, 아니면 FWebViewExternal 확인창("외부 앱을 열까요?" / "다른 앱이 열려요" · "직접 누르지 않았다면 취소해 주세요" / 취소 · 열기(먹색)). 처리할 앱이 없으면 아무 일도 하지 않는다.
- 판정은 플랫폼별 순수 함수 `WebNavigationPolicy`(scheme, main frame 여부, gesture → 안에서 / 막기 / 외부 바로 / 확인 후)로 두고 표를 전수 테스트한다. Android intent 정화는 별도 순수 함수로 두고 component·selector가 든 intent URI가 정화되는지 테스트한다.
- 외부 앱 복귀 때 강제 새로고침하지 않고 결제 완료를 감지하지 않는다.

**수명.** route가 살아 있는 동안만 웹뷰를 둔다. pop 때 Android `destroy()`, iOS delegate 해제. 계정 전환 때 계정 범위 route와 함께 닫힌다. 쿠키는 계정과 무관한 기기 저장소에 남는다(스펙에 계정별 분리 없음).

## 5. 오류·테스트·검증

| 경로 | 동작 |
| --- | --- |
| 상세 GET 실패 | `ClientError` 분류 유지. 문구: 연결 계열 "불러오지 못했어요", NOT_FOUND "삭제된 상품이에요", 그 밖 "잠시 후 다시 시도해 주세요" |
| 캐시 해독 실패 | 행 삭제 후 네트워크 |
| 새는 취소 | 오류 state, `loading=false`, "잠시 후 다시 시도해 주세요" |
| 로컬 삭제 경합 | IN_FLIGHT는 조용히 무시, `MovedTo` 대기 |
| 웹뷰 로드 실패·외부 앱 없음 | 덮는 안내·다시 시도 / 아무 일도 하지 않음 |
| 이미지 실패 | placeholder 유지 |

**TDD.** 새 테스트마다 해당 수정만 되돌려 실패하는지 확인하고 기록한다. 실패를 고정해 보는 virtual time 테스트는 `runCurrent`를 쓴다(전송 재시도 타이머·200ms 게시 간격).

- **commonTest(Android host·iOS simulator 모두 실행, NO-SOURCE/SKIPPED는 통과 아님):** `ItemMapperTest`(목적 표시), `SchemaMigrationTest`(v1/v2→v3), `LocalStoreContractTest`(GET 경로 같은 version 갱신·accept는 같은 version 무시, 해독 실패 self-heal, `deleteSubmission` 상태·계정별·비로그인 미귀속만, `cachedItemBySubmission`, 대문자 id), `CachedGetItemRepositoryTest`, `ItemDetailPresenterTest`(계정 전환 뒤 retry 무시, refresh, 새는 취소, 대문자 id refresh 유지, 상태 변화 hook), `SubmissionCoordinatorTest`(`deleteLocal` 즉시 게시, queue 읽은 뒤 삭제 → 그 행 POST 없음 + 뒤 행 전송, SUBMITTING 뒤 삭제 거절), `LocalSubmissionDetailPresenterTest`(PROCESSING·READY로 accept된 행 모두 `MovedTo`, DELETED 응답으로 cache에 없으면 `RemovedOnServer`, 로그인 null → A에서 유지, 비로그인 삭제, 로그인 직후 첫 view에 행이 이미 없음 → `MovedTo`, `cachedItemBySubmission` 실패 → `Gone`(삭제 안내 없음), 비로그인에서 행 사라짐 → `Gone`), `DetailKind` 표 전수(lifecycle 먼저), `DisplayFormatTest`, `HomePresenterTest`(줄 target), `RuntimeCloseLeaseTest`(`CountingDriver` query·execute 카운터로 close 뒤 DB 미접촉), `SharedModulesTest`(새 Presenter 연결).
- **Android unit:** `WLNavigatorTest`(계정을 떠날 때만 정리·로그인 때 유지·`replaceTop`), `item/`·`local/` codec 왕복, entry ViewModelStore 정리, 상세·로컬·웹뷰 문구 키 전수, owner 테스트, (PR B) `web/` codec 왕복·검증, `WebNavigationPolicyTest`·`WebViewSettings`·intent 정화.
- **iOS XCTest:** `WLNavigatorTests`, 문구 키 전수(ko·en), owner close/deinit, `SharedInteropTests` 계정 전환 수정, `RemoteImage` 다운샘플링, (PR B) `WebNavigationPolicyTests`·`web/` route 검증·웹뷰와 cleaner 같은 store.
- **화면 확인:** `emulator-5554`·iPhone 17 Pro 시뮬레이터. PR A: 보드 L/D 비교(FProductDetail, FProductProcessing, 로컬 대기), DEBUG hook(`delayItem01`, `pendingCount`, 분석 완료 5초)으로 분석 중 → 당겨서 새로고침 → 완료, 로컬 대기 → 전송 → `MovedTo`(로그인 직후 포함), 로컬 삭제(로그인 전·후), 계정을 떠날 때 상세 닫힘·로그인 때 유지. (PR B) FWebView·Share·External 비교, 웹뷰 기록·새 창·외부 앱 확인, 계정을 떠날 때 웹뷰 닫힘, 설정 데이터 삭제 뒤 쿠키 제거.
- **성능 baseline:** [C3 성능 확인 목록](../../architecture/client/c3-performance-checks.md)의 상세 깊이 1/3/5 push/pop 20회, 이미지 있음·없음 메모리. 통과 기준이 아니라 기록이다.
- **마무리:** KMP_TEST·ANDROID_CHECK·IOS_TEST·iOS Release simulator build·`gen_tokens --check`, PR마다 push 전 독립 리뷰, draft PR에 로컬 검증 결과. PR A는 base `develop`, PR B는 A 위에서 시작해 base `client/c4-product-detail`로 열고 A merge 뒤 develop 기준으로 rebase해 base를 `develop`으로 바꾼다(D20).

**작업 단위.** 계획은 네 묶음으로 나누고 묶음마다 커밋·검증(해당 KMP_TEST/ANDROID_CHECK/IOS_TEST)·중간 확인 지점을 둔다. 묶음 1~3은 PR A, 묶음 4는 PR B다(D20). 각 PR 전에 전체 검증과 독립 리뷰를 한다.

1. **데이터·인계 결함:** 목적 표시 DTO→domain→SQLite v3, 같은 version 갱신, 해독 불가 행 self-heal, UUID 정규화, `deleteSubmission`·flush NOT_FOUND 건너뛰기, close 뒤 DB 미접촉 단언.
2. **Presenter:** `ItemDetailPresenter` 계정 전환·refresh·새는 취소·홈 hook, `DetailKind`·`DisplayFormat`, `LocalSubmissionDetailPresenter`, `HomeRow.target`, runtime 연결.
3. **화면·내비게이션:** route·codec, 계정 범위 정리, `replaceTop`, entry별 owner(Android ViewModelStore·iOS `WLEntryOwners`), 이미지 로더, 상세·분석 중·로컬 대기 화면, 홈 진입점.
4. **웹뷰(PR B):** 화면·공유 시트·외부 앱 확인, 탐색·보안 규칙, 저장소 일치, 상세 "원본 보기"·홈 "원본"을 시스템 브라우저에서 웹뷰로 교체. 상세 쪽 의존은 이 교체뿐이다.

**Baseline(2026-10-09, 이 공간):** shared Android host 434 · iOS simulator 431 · Android unit debug/release 각 94 · XCTest 122, 실패·skip 0, Android assemble·lint 통과.

## 문서

C5/C6 인계: 같은 version 표시 metadata 한계와 겹친 GET의 역전(§1). 실기기 확인 항목: 실제 결제 흐름의 외부 앱 확인창 빈도(D16), 새 창의 `window.opener` 손실(§4).

계획 `docs/superpowers/plans/2026-10-09-client-c4-product-detail.md`, `kmp.md`·`android.md`·`ios.md` C4 절과 한계 표 상태, 로드맵 C3(merge 완료, PR #12)·C4 행, `docs/design/decisions.md`(D2~D4·D6·D8~D10·D12~D17·D19), `server-integration-status.md`, C4 검증 history 기록, 관련 INDEX.
