# C5 PR A 구현 기록 (2026-10-15)

> 브랜치 `client/c5-category`(시작 `e2653f6`, origin/develop C4 PR #15·#16 merge 뒤). 설계 [spec](../../../superpowers/specs/2026-10-14-client-c5-category-design.md)(D1~D15)·[plan](../../../superpowers/plans/2026-10-14-client-c5-category.md) Task 0~19, 설계 검토 [기록](c5-category-design-review-2026-10-14.md). PR A는 카테고리 기능 전체이고, 카드 사진 → 상세 공유 요소 전환은 PR B다(D4).

## 작업 요약

| Task | 내용 |
| --- | --- |
| 0 | baseline: host 501 · simulator 498 · Android unit 183 · XCTest 211 |
| 1 | `ClientError.fields`, 카테고리 모델, 엄격한 CAT mapper |
| 2 | `CategoryInputRules`(서버 `UserTextRules`), `CardMetaLine`, D15 상대 시각(N주 전·날짜) + 두 플랫폼 문구 |
| 3 | `FakeTaxonomy`(V11), CAT-01~04·ITEM-02 Fake 규칙, 69개 보드 seed, 120개 fixture, Fake·Remote 공통 계약 |
| 4 | Remote CAT-01~04·ITEM-02, `RoutedCategoryRepository`, binding, DEBUG 앱 binding |
| 5 | `CachedItemListRepository`·`LocalStore.upsertItems` |
| 6~9 | `SessionLane` 기반 Presenter 넷, runtime factory, DEBUG hook, Swift interop |
| 10~14 | Android: code point 입력·시트 guard·사진 배치, route·owner, 첫 화면·추가 시트, 목록·편집 시트, 선택 시트·데모 정리 |
| 15~18 | iOS: 로더 취소·동시 제한·code point 입력·시트 guard·사진 배치, 같은 화면, DEBUG 진입 |
| 19 | 기기 확인 결함 수정, 사용자 결정 반영, 전체 검증, 문서 |

Android(묶음 3)와 iOS(묶음 4)는 Presenter 공개 면만 보고 병렬로 진행했다(각자 worktree, 결과를 이 브랜치로 fast-forward·cherry-pick).

## 판단(Ruling)

1. **한도 선검사 예외 분기를 두지 않는다.** 검토 기록은 "이미 보낸 생성 payload replay를 한도 선검사가 막지 않게" 하라고 했다. 한 폼의 사용 개수는 폼을 열 때의 SELECT 값이고 확실한 `CATEGORY_LIMIT_REACHED`에서만 바뀌는데, 그 거절은 보관한 payload도 지운다. 그래서 재전송이 선검사에 막히는 경우가 생기지 않아 분기를 지우고 실제 경우(19/20에서 응답 유실 → 같은 key 재전송)를 테스트로 고정했다([QA-CLI-016](../../../learning/client/q-and-a/QA-CLI-016-category-create-idempotency.md)).
2. **key 이어 쓰기는 결과를 모르는 실패 뒤에만.** 서버는 실패한 요청의 receipt를 남기지 않으므로 409·422·429 같은 확실한 거절 뒤에는 key와 payload를 지운다.
3. **D15는 홈 줄에도 적용된다.** `DisplayFormat.relative`는 홈 줄 저장 시각에도 쓰인다. spec이 "공용 상대 시각에 더한다"고 정했으므로 그대로 따랐다.
4. **마지막 상위는 기존 `app_state`.** 새 LocalStore 메서드나 multiplatform-settings 없이 `category.lastGroup.<accountId>` key로 둔다.
5. **`NAME_LIMIT` 등 상수 이름.** `NAME_MAX`는 POSIX `<limits.h>` 매크로라 ObjC 헤더가 깨졌다.
6. **MockEngine 서버는 Fake store를 HTTP로 감싼다.** 서버 규칙을 테스트 안에 다시 쓰지 않고 wire 모양(경로·헤더·상태·envelope)만 계약 문서대로 둔다. 서버 구현 검증이 아님을 문서에 적었다.
7. **보드 seed는 칩 숫자만큼 실제 상품.** BROWSE count가 membership에서 나오므로 `BoardDisplayMetadata.categoryChipCounts`를 지웠다. DEBUG 분석 완료는 "seed의 첫 leaf"를 store가 기억해 C4 동작(seed 없는 계정은 PARTIAL)을 유지한다.
8. **Fake 이름 비교의 NFC는 근사.** common Kotlin에 NFC가 없어 Fake만 trim·collapse·소문자로 비교한다(한계 표).

## 사용자 결정 (2026-10-15, 기기 확인 뒤)

칩 순서는 서버 taxonomy 순서(보드의 개수 순은 따르지 않음), 입력칸 오류 문구는 #C62828, 제출 중 주 버튼은 작은 진행 표시, 빈 공용 목록은 제목만. 시트 버튼 줄은 보드대로 하단 고정([디자인 결정](../../../design/decisions.md)).

## 기기 확인과 고친 결함

Android는 사용자 지시로 실기기 대신 에뮬레이터(Pixel_5 API 30, ko-KR, `emulator-5556`)에서, iOS는 iPhone 17 Pro 시뮬레이터에서 확인했다. 사진은 [`c5-shots/`](c5-shots/)(Android 23장, iOS 23장).

- 첫 화면·레일·페이저·마지막 상위, 목록(목적 줄·✳·"1주 전"·"7월 8일" 확인), 120개 fixture의 skeleton → 배치·늦은 사진 1:1·1:4 사진·이어 붙이기, 직접 만든 목록·빈 목록, 편집 저장 → 머리 이름, 추가 시트, 선택 시트와 시트 안 생성, 다크 모드, 로그인 전·빈 탭(iOS), 편집 409(iOS hook `-wl.fake.editCategoryOnSave`).
- **고친 결함(Android):** 시트 버튼 줄이 내비게이션 막대 아래로 밀려 만들 수 없음 → 하단 고정, 편집 뒤 첫 화면 칩이 옛 이름 → `CategoryChanges` 카운터로 돌아올 때 갱신, 선택 시트 레일 들여쓰기·머리 버튼 모양·구분선, 넘김 직후 갱신이 페이저를 되돌리는 경합.
- **고친 결함(iOS):** 버튼 줄 하단 고정, 취소 버튼 흐림, 신규 카테고리 뒤로 버튼 면 색.
- 기존 emulator-5554는 다른 앱이 저장 공간을 채워 설치가 실패해 쓰지 않았다(다른 앱은 건드리지 않음, 이 앱만 지워짐).

## 계획과 달라진 점

- `CategoryRepository`와 `ItemListRepository`는 한 파일. 카테고리 Patch는 Fake의 `Patch`를 옮기지 않고 `DescriptionChange`를 따로 뒀다.
- Android owner는 공용 하나, iOS 목록은 `Masonry2Col` 대신 지연 생성 두 열, Android 사진 비율은 디코드 결과 크기에서 얻는다(플랫폼 절).
- `CatalogRepository`의 카테고리·목록 메서드는 지우지 않았다(seed 조회가 C2 테스트와 iOS interop에 남음). C6에서 목적과 함께 정리한다.

## 남긴 점

- 실기기(Flip6) 확인, Android 409 진행 표시 기기 확인, iOS 소프트웨어 키보드 위 footer 확인.
- 두 플랫폼 Compose/SwiftUI 제스처·IME 조합은 단위 테스트가 없다.
- 한계 표는 [kmp.md](../../../architecture/client/kmp.md#c5에서-생긴-항목), 플랫폼 남은 점은 [android.md](../../../architecture/client/android.md#카테고리-탭-c5)·[ios.md](../../../architecture/client/ios.md#카테고리-탭-c5).

## 검증(2026-10-15, 로컬)

| 항목 | baseline(`e2653f6`) | PR A 끝 |
| --- | --- | --- |
| shared Android host / iOS simulator | 501 / 498 | 633 / 630 (실패·skip 0) |
| Android unit debug / release | 183 / 183 | 202 / 202 |
| iOS XCTest | 211 | 242 |
| assembleDebug·assembleRelease·lintDebug(0 errors, 경고 27 — DEBUG seed fetcher UseKtx 1개 늘어남)·`linkReleaseFrameworkIosArm64`·iOS Release simulator build | 통과 | 통과 |
| `gen_tokens.py --check`·`test_gen_tokens.py` | 통과 | 통과 |

새 테스트마다 해당 수정만 되돌려 실패를 확인했다(Task별 기록은 plan과 각 커밋 작업 중). 메모리 기록은 [성능 확인](../../../architecture/client/c3-performance-checks.md#c5-측정-결과-2026-10-15). 플랫폼 CI job은 꺼져 있어 로컬 결과가 근거다.

## push 전 독립 리뷰 반영(2026-10-15, `/code-review high`)

10건 중 9건을 고쳤다.

- **Android 계정을 떠날 때 시트가 남음:** 시트에 `accountScoped`를 두고 셸이 계정 범위 화면을 닫는 지점에서 `OverlayHostState.dismissAccountScoped()`로 그 위 확인창까지 닫는다(열리는 중이면 열리자마자). DEBUG hook `wl.fake.signOutAfterMs`로 확인.
- **Android 선택 시트 생성 페이지의 X가 칩 페이지로 감:** X·바깥 누르기·끌기·시스템 뒤로는 시트를 닫고(입력이 있으면 버리기 확인), 머리 뒤로·취소만 칩 페이지로 간다.
- **Android 뒤 페이지가 앞 페이지보다 먼저 배치됨:** `PhotoPagePlanner.settle`이 배치된 페이지 수를 세어 다음 페이지만 통과시킨다(iOS와 같은 규칙).
- **두 플랫폼 생성 폼 입력이 SELECT 도착 때 덮일 수 있음:** 칸 덮어쓰기는 편집 baseline(첫 READY)과 409 교체 때만 한다(Android `FormFieldSync`, iOS `replacesDraft`).
- **다음 페이지 요청이 다시 일어나지 않는 경우:** 첫 페이지가 화면을 채우지 못하거나 Presenter가 진행 중이라 무시한 뒤에도, 끝 근처 + 남은 페이지 + 받는 중 아님이면 상태가 바뀔 때마다 다시 요청한다(Android `shouldLoadMore`, iOS `wantsMore`).
- **Android 자동 갱신에도 당겨서 새로고침 표시:** 사용자가 당겼을 때만 표시한다.
- **iOS 카드마다 선형 탐색:** state를 적용할 때 id 색인을 한 번 만든다.
- **shared 저장된 상위가 없는 계정이 매번 app state를 읽음, 계정 변경을 먼저 본 intent가 같은 갱신을 또 함:** 읽음 표시와 "세션 갱신 대기 중" 표시를 두었다.

고치지 않은 것: **목록 페이지가 캐시 쓰기를 기다림.** 40개를 한 transaction에 쓰는 짧은 작업이고, 기다리지 않으면 캐시 쓰기와 다음 상세 GET 쓰기의 순서가 보장되지 않는다.

검증: shared Android host 635 · iOS simulator 632, Android unit debug·release 212, iOS XCTest 244(실패·skip 0), assemble·lint(0 errors, 경고 27)·`linkReleaseFrameworkIosArm64`·iOS Release simulator build·`gen_tokens --check` 통과.

## PR 리뷰 반영(2026-10-24, PR #21)

5건 모두 고쳤다.

- **Android 여러 페이지를 읽은 뒤 새로고침하면 skeleton이 남음:** 목록이 다시 시작될 때 세대 값을 올리고, 페이지 배치 작업의 `key`·`LaunchedEffect` 인자에 넣는다. 첫 페이지의 id·순서가 같아도 배치 작업을 다시 시작한다.
- **iOS 계정 복원이 늦으면 첫 조회 오류가 남음:** 카테고리 첫 화면은 `runtime.ready`가 true가 된 뒤 `loadOnce()`한다(Android와 같은 규칙).
- **iOS 시트가 열리는 중 계정을 떠나면 시트가 남음:** `OverlayHostState.dismissAllWhenSettled()`로 열리는 중이면 한 프레임씩 기다렸다 모두 닫는다. 같은 로직을 쓰던 로컬 제출 상세도 이 함수를 쓴다.
- **iOS 사진 URL이 바뀌어도 이전 사진을 표시함:** 카드가 사진을 받은 URL을 기억하고, URL이 바뀌면 새 사진을 받고 없어지면 사진을 지운다.
- **Fake ITEM-02 cursor가 밀리초로 잘려 다음 페이지 상품이 빠짐:** cursor에 `createdAt`의 전체 Instant 문자열을 담는다(`fake2:`). 같은 `.123456Z` 시각의 상품 41개가 40·1로 나뉘는 테스트를 더했고, 고치기 전 코드에서는 40·0으로 실패하는 것을 확인했다.

Android 세대 값 수정은 Compose UI 테스트 기반이 없어 테스트를 더하지 않았다.

검증: shared Android host 636, Android unit debug 212(실패 0), `:android:compileDebugKotlin`·iOS Debug build 통과. iOS XCTest는 `WLTypographyTests.testMultilineTextReadsAsOneStaticText` 1건이 실패하며, 이번 변경 전 커밋에서도 같은 시뮬레이터에서 같은 결과라 이번 수정과 무관하다.
