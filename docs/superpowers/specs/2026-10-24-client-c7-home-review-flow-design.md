# C7 홈·연속 처리·목록 anchor 설계

> 2026-10-24 · 시작 `client/c7-home-review-flow`, develop `896fbc2`(C6 PR #23 병합 후). 사용자 요청 범위에 따른 구현 설계.

## 범위와 근거

[로드맵](2026-10-05-client-implementation-roadmap-design.md) C7: 카테고리·목적 목록의 window·anchor 복원, 홈 할 일·목적 요약, 연속 처리 조회와 카드 모션(FHome, FHomeReviewFlow, FHomeFillFlow). 기준은 [B4 조회 계약](../../architecture/server/wishlist-item-read-api.md)(ITEM-02 window·HOME-01·HOME-02), [상품 확인과 편집](../../product/inspect-and-edit-a-product.md#1-홈의-조치-필요-영역), [구매 후보 정리 연속 처리](../../product/organize-candidates.md#연속-처리-재진입과-처음부터-다시-보기), [B4 제품 정책](../../history/product-planning/mvp/decisions/b4-read-api-policy-2026-10-07.md), `design/handoff/interactions/{screens,motion}.md` §4·§5와 FHome/FHomeReviewFlow/FHomeFillFlow L/D 보드다.

범위 밖: 서버 변경(B9 별도 세션), C8 명령(ITEM-04 편집·ITEM-05 직접 보완·ITEM-06 삭제·ITEM-07 검토·ITEM-08 재분석·MEDIA), C9 후보 추가/이동, C10 중복 비교(DUP-01/02), C11 archive. C5 사진 전환 PR #22(Draft OPEN·develop 미병합)는 가져오지 않는다.

## 확정 계약 확인

- 갱신은 **진입·foreground 복귀·사용자 새로고침**만. polling·timer 갱신을 추가하지 않는다(홈의 1분 상대 시각 tick은 표시 재계산이며 조회가 아니다).
- 홈 목적은 HOME-01 `recentPurposes` 최대 3개, 빈 목적 포함, 서버 순서 유지. 후보 미리보기 최대 4개, null 이미지는 placeholder.
- ‘처음부터 다시 보기’는 **이번 세션에서 건너뛴 기록만 초기화**하고 cursor 없이 현재 미완료 첫 페이지를 다시 조회한다. 서버 review 상태를 되돌리거나 restart API를 만들지 않는다.
- 연속 처리 화면이 살아 있으면 foreground에서 현재 카드 anchor로 재조회하고, 프로세스 신규 실행은 최신 첫 구간이다(화면 위치를 영구 저장하지 않는다).
- HOME-01에 할 일 합계 필드는 없다. 머리 `할 일 N개`는 클라이언트가 분석 대기(기기) + 세 그룹 count를 합산한다.

## 사용자 답변 (2026-10-24)

1. **확정·보류는 C7에서 임시 넘김.** 버튼·스와이프는 보드 모션(확정=오른쪽, 보류=왼쪽)을 그대로 실행하지만 서버에 아무것도 저장하지 않고 이번 세션의 넘김으로만 기록한다. 끝 요약은 `넘김 n`만 보이며 확정·보류 수를 0으로 위장하지 않는다. 카드 아래 안내 줄에 "확정·보류 저장은 다음 단계에서 연결돼요"를 둔다. C8에서 ITEM-07로 교체한다.
2. **정보 보완은 건너뛰기만 동작.** 보드 배치를 유지하되 `저장하고 다음`·`다시 분석하기`·사진·이름·카테고리 입력은 비활성과 C8 연결 안내로 둔다. 요약은 `건너뜀 n`. `원본` 링크(웹뷰, C4)는 동작한다.

## 구현 판단 (사용자 결정 아님, 검토 가능)

- **세션 = 연속 처리 화면 한 번의 수명.** 건너뛴 기록은 Presenter 메모리에만 두고 foreground·anchor 갱신에서는 유지, 화면을 닫거나 계정이 바뀌면 사라진다. 다시 열면 현재 미완료 전체가 보인다.
- **시작 위치:** 카드 영역은 첫 페이지부터, 캐러셀 상품은 그 미리보기의 `anchorCursor`로 `before=20, after=20` window를 받아 그 상품부터 시작한다. 그보다 앞(더 최신)의 항목은 이번 세션에서 지나간 것으로 취급해 `처음부터 다시 보기`로 다시 볼 수 있다.
- **`n / 전체`:** `전체 = 이번 세션에서 넘긴 수 + 현재 카드부터 남은 수`, `n = 넘긴 수 + 1`. 남은 수는 `totalCount − (현재 카드보다 앞에 있는 항목 수)`이며, 앞 항목 수는 window의 `previousCursor`가 없으면 window 안에서 정확히 세고, 있으면 이번 세션이 지나간 ID 수로 대신한다. 다른 기기 변경으로 생기는 오차는 다음 갱신에서 고친다.
- **anchor fallback:** `anchorResolved=false`는 오류가 아니다. 대체 항목부터 큐를 다시 만들고, 이번 세션에서 지나간 ID는 제외한다. 남은 항목이 없으면 끝 요약으로 간다.
- **분석 대기 카드:** 로그인 뒤에도 기기의 미전송 항목은 HOME-01 그룹과 섞지 않고 별도 `분석 대기` 카드(C3 로그인 전 카드와 같은 형태)로 보인다(제품 문서의 네 영역). 분류 중 카드는 HOME-01 `ANALYSIS_IN_PROGRESS`의 count·미리보기 최대 4개이며 미리보기를 누르면 상품 상세로 간다. C3의 "미전송+캐시 PROCESSING을 분류 중 한 카드" 표시(C3-D3)를 대체한다.
- **HOME-01 재조회 시점:** 홈 진입·foreground·당겨서 새로고침과, 이 신호들이 부른 전송 조정기 refresh가 끝날 때(공유 수신·네트워크 복구 포함). 별도 주기 없음. 실패하면 마지막 요약을 유지하고 짧은 실패 안내를 보인다.
- **목록 window:** 카테고리 목록·목적 상세는 `previousCursor`(더 새 항목)와 `nextCursor`(더 오래된 항목)를 함께 유지하고, 카드별 `anchorCursor`를 보관한다. foreground·당겨서 새로고침은 플랫폼이 넘긴 첫 보이는 카드 ID의 anchor로 `before=20, after=20`을 조회해 전체를 그 window로 교체하고, 플랫폼은 해석된 anchor 카드로 위치를 복원한다(Android는 key 기반으로 카드 내부 offset 유지, iOS는 `scrollPosition(id:)`로 같은 카드를 맨 위에). 위로 끝에 닿으면 `previousCursor`로 더 새 항목을 앞에 붙인다. anchor를 모르면(아직 스크롤하지 않음·카드 없음) 첫 페이지로 교체한다. 진입은 첫 페이지다.
- **리뷰 카드 비활성 요소:** 카테고리·목적 `변경`(C8)은 비활성, 중복 비교 줄(C10)은 숨긴다. 카드 맨 아래 한 줄은 AI 연결 설명이다(source 조합으로 문구 선택).

## KMP 공개 면

- `ItemPage` 확장(기존 생성자 호환 기본값): `previousCursor`, `anchorCursors: Map<itemId, String>`, `anchor: ItemAnchor?`(requestedItemId, resolvedItemId?, resolved). page 모드는 anchor=null.
- `ItemListRepository`: `byCategoryAround(categoryId, anchor, before=20, after=20)`, `byPurposeAround(purposeId, anchor, before, after)`. 기존 `cursor` 인자는 이전/다음 cursor 모두 받는다.
- `HomeRepository`(HOME-01/02): `summary(): HomeSummary`, `group(group, cursor?, limit=20): ItemPage`, `groupAround(group, anchor, before=20, after=20): ItemPage`. `HomeSummary(groups: List<HomeGroupSummary(group,count,previews: List<HomePreview(item, anchorCursor)>)>, recentPurposes: List<PurposeSummary>)`. 알 수 없는 group 문자열은 무시하고 세 그룹은 고정 순서로 채운다(없는 그룹은 0).
- Fake: `FakeStore`가 HOME-01/02와 ITEM-02 window를 서버 규칙(정렬, 그룹 집합, before/after 0~20, anchor fallback 다음→이전, 빈 scope, 복귀 cursor, cursor scope 검사, 400 코드)대로 재현한다. Remote: `RemoteHomeRepository`, `RemoteItemListRepository` window, 공용 card parser. MockEngine scripted server가 Fake를 HTTP로 감싼다.
- 캐시: HOME-01 미리보기·HOME-02·window 카드도 `CachedItemListRepository`/`CachedHomeRepository`로 GET 규칙 upsert.
- `HomePresenter` 확장: `HomeState.LoggedIn(pending: List<HomeRow>, groups: List<HomeGroupCard>, purposes: List<PurposeSummary>, todoCount, summaryLoaded, refreshing, refreshFailed)`. intents: 기존 `refresh/refreshNow/tick` + `appeared()`(진입), `foreground()`.
- `HomeFlowPresenter`(group = CLASSIFICATION_REVIEW | INFORMATION_COMPLETION): state `HomeFlowState(group, phase: LOADING|ACTIVE|DONE|ERROR, current: HomeFlowCard?, upcoming: List<HomeFlowCard>(최대 2), position, total, passed, error, refreshing)`. intents: `start(group, startAnchor?)`, `pass()`(임시 넘김/건너뛰기 — 한 번에 하나, 진행 중 무시), `restart()`, `foreground()`, `retry()`. C8용 결정 명령은 이번에 만들지 않는다.
- `CategoryListPresenter`·`PurposeDetailPresenter`: `refresh(anchorItemId?)`, `foreground(anchorItemId?)`, `loadNewer()`, state에 `hasNewer`, `prepending`, `restore: AnchorRestore?(resolvedItemId, sameItem, token)`. 기존 `refresh()`는 anchor 없음과 같다.
- runtime: `homeRepository()`, `homeFlowPresenter()`; DEBUG 바인딩에 HOME-01/02 FAKE, RELEASE UNAVAILABLE.

## 플랫폼

- 홈(FHome): 할 일 카드 접힘 시작·화살표 200ms 180° 회전·캐러셀 펼침, 카드 영역 → 연속 처리, 분류 중은 전체가 펼치기. 목적 요약 3개(카드 탭 → 목적 상세, `목적 전체` → 목적 탭). 빈 할 일 상태는 할 일 섹션 숨김.
- 분류·목적 확인(FHomeReviewFlow): 쌓인 카드(뒤 최대 2장, -2.5°/3°, 8/14), 버튼·드래그로 던지기(340 `throw`, ±480, ±16°, 회전 중심 아래 90%), 다음 묶음 40ms 뒤 300 `emphasized`, 임계 `W×0.3` 또는 800/s, 아니면 250 `emphasized` 복귀, 나가는 동안 입력 잠금, 끝 요약 `scale .94→1`.
- 정보 보완(FHomeFillFlow): 머리·버튼 고정, 내용만 320 `standard`로 왼쪽 밀림·오른쪽 들어옴, 전환 중 버튼 잠금, 키보드 닫기, 새 내용 맨 위.
- 카테고리 목록·목적 상세: 첫 보이는 카드 ID를 Presenter에 넘기고 `restore`로 위치 복원, 위쪽 끝 `loadNewer`, foreground 갱신 연결.
- 모든 문자열은 두 플랫폼 리소스에 추가하고 L/D 화면을 보드와 비교한다.

## 검증

Fake·MockEngine 공통 계약(HOME-01 고정 순서·빈 그룹·최대 3목적·4미리보기, HOME-02 그룹 집합·정렬·page·anchor·fallback·빈 scope·cursor 오류, ITEM-02 window·previous·복귀 cursor), Presenter(홈 합산·실패 유지·계정 전환, 연속 처리 시작 위치·넘김·n/전체·restart·foreground anchor·fallback·끝·빈 그룹·close, 목록 anchor 교체·loadNewer·restore), Android unit/build/lint, iOS XCTest/build, 두 플랫폼 L/D 화면, 예외(오류·빈 상태·오프라인·anchor fallback). 실서버는 인증 연결 전이므로 미실행으로 구분한다.
