# C7 홈·연속 처리·목록 anchor 구현 기록 (2026-10-24)

시작 브랜치 `client/c7-home-review-flow`, develop `896fbc2`(C6 PR #23 병합 commit). C5 PR B #22(사진 공유 요소 전환)는 Draft OPEN·develop 미병합이며 이 작업에 가져오지 않았다. 서버 파일은 참고만 했고 변경하지 않았다.

[설계](../../../superpowers/specs/2026-10-24-client-c7-home-review-flow-design.md) · [계획](../../../superpowers/plans/2026-10-24-client-c7-home-review-flow.md) · [B4 조회 계약](../../../architecture/server/wishlist-item-read-api.md) · [화면](c7-shots/INDEX.md).

## 범위

- **ITEM-02 window:** 카테고리·목적 범위에 `anchor`·`before`/`after`(기본 20, 0~20)를 더했다. 카드별 `anchorCursor`, `previousCursor`, anchor 해석 결과(`requested`·`resolved`·`anchorResolved`)를 읽는다.
- **HOME-01·HOME-02:** Fake·Remote·MockEngine·캐시를 구현했다. DEBUG FAKE / RELEASE UNAVAILABLE이다.
- **홈(FHome):** 분석 대기 카드, HOME-01 세 그룹(접힘 시작, 화살표 200ms 180°, 미리보기 캐러셀 최대 4), 비교 중인 목적 최대 3개, 머리 `할 일 N개`(분석 대기 + 세 그룹 count)를 만들었다.
- **연속 처리:** 분류·목적 확인(FHomeReviewFlow, 카드 던지기)과 정보 보완(FHomeFillFlow, 내용 넘김)을 만들었다. 시작은 첫 페이지 또는 미리보기 anchor다. `n / 전체`, 끝 요약, `처음부터 다시 보기`, foreground anchor 재조회를 지원한다.
- **목록 anchor:** 카테고리 목록·목적 상세에서 첫 보이는 카드 기준으로 window 교체·위치 복원을 한다. 위쪽 끝에 닿으면 `loadNewer`로 더 새 항목을 앞에 붙인다. 갱신 계기는 foreground와 당겨서 새로고침뿐이다.
- **갱신 규칙:** 진입·foreground·사용자 새로고침(과 그 신호로 도는 전송 조정기 refresh)만 쓴다. polling·주기 조회는 없다.

## 사용자 답변 (2026-10-24)

1. **확정·보류는 C7에서 임시 넘김이다.** 버튼과 스와이프는 보드 모션(확정 → 오른쪽, 보류 → 왼쪽)을 그대로 실행하고 `pass()`만 부른다. 서버에는 저장하지 않는다. 끝 요약은 `넘김 n`만 보이며 확정·보류 수를 0으로 꾸미지 않는다. 카드 아래에 "확정·보류 저장은 다음 단계에서 연결돼요"를 보인다.
2. **정보 보완은 건너뛰기만 동작한다.** `저장하고 다음`·`다시 분석하기`·사진·이름·카테고리 입력은 보드 배치대로 그리되 비활성(DISABLED_ALPHA·비활성 접근성 상태)과 다음 단계 안내로 둔다. 요약은 `건너뜀 n`이다. `원본` 링크는 기존 웹뷰(C4)로 동작한다.

이 답변은 C7 구현 한정이다. 제품 문서([상품 확인과 편집](../../../product/inspect-and-edit-a-product.md), [구매 후보 정리](../../../product/organize-candidates.md))의 확정·보류·보완 규칙은 바꾸지 않았다. C8에서 ITEM-04~08로 교체한다.

## 구현 판단 (Ruling)

진행 기록의 판단을 옮긴다. 각 항목 끝에 틀렸을 때 고치는 비용을 적었다.

1. **홈 카드 순서:** 화면은 분류·목적 확인 → 정보 보완 → 분류 중(보드 위→아래)이다. `HomeSummary`는 서버·enum 순서를 그대로 둔다. 보드를 시각 기준으로 삼았다. 틀리면 목록 하나의 순서만 바꾸면 된다.
2. **중간 빌드 유지:** Task 3에서 `HomeState.processing`을 없앨 때 플랫폼 호출부를 최소로 고쳐 Android·iOS가 계속 빌드되게 했다(전체 화면은 Task 6·7). 비용은 잠깐 쓰는 작은 호환 코드다.
3. **Fake 오류 code:** 기존 테스트가 `INVALID_WISHLIST_ITEM_QUERY`/`_CURSOR`에 기대고 있어 Fake·scripted server는 이 code를 유지했다. 서버 계약은 `INVALID_WISHLIST_QUERY`/`_CURSOR`이고 Remote는 서버 code를 그대로 전달한다. 틀리면 테스트 fixture를 함께 바꾸는 비용이다.
4. **foreground 중복:** 홈이 보이는 중 foreground에서는 앱 전역 신호의 `submissions().refresh()`와 `HomePresenter.foreground()`가 함께 돈다. 전역 신호는 구조를 바꾸지 않고 두었다. coordinator single-flight가 둘을 합친다. 비용은 refresh 한 번이 더 도는 정도다.
5. **플랫폼 병렬 진행:** Android(Task 6)와 iOS(Task 7)를 병렬로 진행했다. iOS는 격리 worktree에서 만든 뒤 이 브랜치에 cherry-pick했다(`9a01001`·`e34dc6d`). 디렉터리가 겹치지 않았다. 틀렸다면 비용은 c7-shots 문서의 merge conflict 정도였다.
6. **던지기 판정 우선순위:** |vx| ≥ 800/s면 fling 방향이 이긴다. 아니면 |dx| ≥ 0.3W일 때 dx 부호로 정하고, 둘 다 아니면 복귀한다. motion.md는 두 조건만 적고 우선순위는 없다. 반대 방향의 빠른 fling을 명시적 의도로 보았다. 틀리면 두 플랫폼에서 비교 하나씩 뒤집으면 된다.
7. **AI 안내 문구:** 카테고리·목적 모두 AI면 "AI가 카테고리와 목적을 연결했어요", 카테고리만이면 "AI가 카테고리를 정했어요", 목적만이면 "AI가 목적을 연결했어요"다. 그 밖에는 줄이 없다. Android에 있던 "맞는 목적은 찾지 못했어요" 변형은 뺐다. 비용은 문구 조정이다.
8. **null 복원:** 목적 상세는 `resolvedItemId=null` 복원에서 위치를 유지한다(머리 시트가 튀지 않게). 카테고리 목록은 맨 위로 간다. 비용은 platform-context와의 작은 차이다.
9. **`loadNewer` 재무장:** 목록이 위쪽 끝을 떠났다가 돌아올 때만 다시 부른다. 실패 뒤 맨 위에서 곧바로 반복 요청하는 고리를 막아 polling 금지 제약을 지킨다.

그 밖의 구현 판단:

- `HomeFlowCard.anchorCursor`는 `String?`이다. anchor가 없는 카드도 보여 주고 `anchorCursors[id]`만 읽는다(`getValue` 금지).
- HOME-01에 같은 그룹이 두 번 오면 INVALID_RESPONSE다. 알 수 없는 그룹은 무시하고 없는 그룹은 0이다.
- **세션:** 연속 처리 화면 한 번의 수명이다. 넘김 기록은 Presenter 메모리에만 있고 foreground에서는 유지된다. 화면을 닫거나 계정이 바뀌면 사라진다.
- **Android 위치 기준:** masonry 목록이 lazy가 아니다. 그래서 첫 보이는 카드는 배치된 카드 box로 정하고, 같은 카드면 카드 안 offset을 유지한다.
- **iOS 회전 순서:** `.rotationEffect(anchor: 0.5, 0.9)`를 `.offset` 앞에 적용한다([QA-CLI-018](../../../learning/client/q-and-a/QA-CLI-018-card-rotation-pivot-order.md)).

## C8 경계

- **구현하지 않은 것:** 확정·보류 저장(ITEM-07 검토), 정보 보완 저장(ITEM-05), 다시 분석하기(ITEM-08), 카테고리·목적 `변경`(ITEM-04), 사진 입력(MEDIA-01/02), 삭제(ITEM-06). 화면에서 완료로 표시하지 않고 비활성·안내로 둔다.
- **상태 표:** ITEM-04~08은 [서버 연동 상태](../../../architecture/client/server-integration-status.md)에서 C8 미구현으로 남는다.
- **다른 단계:** 중복 비교 줄(C10 DUP-01/02)은 숨긴다. 후보 추가·이동(C9)과 archive(C11)는 범위 밖이다.

## 검증 (Task 8, HEAD `e34dc6d` + 이 문서 커밋 전)

저장소 루트에서 `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=$HOME/Library/Android/sdk ./client/gradlew --rerun-tasks -p client -Dorg.gradle.java.installations.paths=…`로 실행했다. 첫 실행이 Gradle 캐시(UP-TO-DATE)로 끝나 `--rerun-tasks`로 다시 돌린 결과다.

- `:shared:allTests :shared:linkReleaseFrameworkIosArm64`: BUILD SUCCESSFUL. Android host **784**, iosSimulatorArm64 **781**, 실패·오류·skip 0(XML 집계). Release device framework 링크 통과.
- `:android:testDebugUnitTest :android:assembleDebug :android:testReleaseUnitTest :android:assembleRelease :android:lintDebug`: BUILD SUCCESSFUL. debug/release unit 각각 **251**, 실패 0. assemble 두 변형 통과. lint **0 errors / 27 warnings**(C6와 같은 기존 경고).
- iOS `xcodebuild test -project client/ios/Wishlist.xcodeproj -scheme Wishlist -configuration Debug -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro'`: **284 tests, 0 failures**, TEST SUCCEEDED.
- iOS Release `-configuration Release -destination 'generic/platform=iOS Simulator' build`: BUILD SUCCEEDED(경고는 서명된 ShareExtension strip 생략 1건).
- `python3 client/tools/gen_tokens.py --check` exit 0, 토큰 생성기 단위 테스트 **8개** 통과.
- `git diff --check origin/develop...HEAD`: 처음에는 계획 문서 94행의 끝 공백 1건으로 실패했다. 이 문서 커밋에서 고친 뒤 통과했다.
- C6 대비 증가: shared host 699 → 784, Android 225 → 251, iOS 257 → 284.

## 예외 경로 확인

| 경로 | 근거 | 방식 |
| --- | --- | --- |
| HOME-01 실패: 보이는 요약 유지 + `refreshFailed`, 첫 실패는 `summaryError` + 재시도 | `HomePresenterTest.summaryFailureKeepsLast` | 자동(대본 HomeRepository) |
| HOME-02 시작 실패 → ERROR → retry | `HomeFlowPresenterTest.startFailureThenRetry`, `retryAfterFailedNextPageOnEmptyQueue` | 자동(FakeStore `failNext(HOME_02)`) |
| HOME-02 foreground·이어 읽기 실패 → 큐 조용히 유지 | `foregroundFailureKeepsQueue`, `appendFailureKeepsQueueAndRetriesOnNextPass` | 자동(FakeStore `failNext`) |
| ITEM-02 window foreground 실패 → 목록 유지, 오류 표시 없음 | 임시 probe 테스트 2개(`CategoryListPresenterTest`에 넣어 통과 확인 후 되돌림, 커밋 안 함). 머리 조용한 실패는 `foregroundIgnoredWhileLoading` | 자동(임시)·기존 |
| `loadNewer` 실패 → `prepending`만 해제, `hasNewer` 유지 | 같은 임시 probe | 자동(임시) |
| 빈 그룹 | `emptyGroupIsDone`, `groupsHideZeroAndKeepBoardOrder`, `summaryFixedOrderWithEmptyGroups` | 자동 |
| anchor fallback(현재 카드가 다른 곳에서 처리됨, 목록 맨 위 카드 삭제) | `foregroundFallbackSkipsPassed`, `refreshWithRemovedAnchorRestoresFallback`(카테고리·목적), `groupAroundRemovedAnchorFallsBack`, `removedAnchorFallsToOlderThenNewer` | 자동 |
| 계정 전환 중 늦은 응답 | `staleSessionSummaryDropped`, `staleSessionFlowDropped`, `staleSessionWindowDropped`, 계약 `staleSessionNotPublished` | 자동 |
| 오프라인 | NETWORK 실패를 위 테스트들이 대신한다 | 기기 오프라인 수동 확인 안 함 |

이번 Task 8에서는 DEBUG 앱 수동 확인을 하지 않았다. `DebugControls`에는 HOME/ITEM-02 `failNext` hook이 없어(ITEM-01 지연·카테고리 hook만 있음) 앱에서 실패를 일으킬 수 없다. 아래는 Task 6·7 구현자가 에뮬레이터·시뮬레이터에서 확인한 것이다.

- **Android:** 던지기와 짧은 끌기의 복귀, 속도 판정(느린 1500ms 스와이프는 복귀, 60ms 스와이프는 넘김), `처음부터 다시 보기`, 카테고리 목록의 foreground 같은 카드 복원(`sameItem=true`, 임시 logcat), 목적 상세의 위치 유지를 확인했다.
- **iOS:** DEBUG launch hook으로 홈·확인·보완·요약과 카테고리 `refreshNow(anchor)` 복원을 정지 화면으로 확인했다.

## 알려진 한계

- **Fake 오류 code 차이:** ITEM-02 Fake code가 서버와 다르다(Ruling 3). code 이름을 바꿀 때 Fake와 테스트를 함께 고친다.
- **mapper 관대함:** `anchorCursor`가 없는 카드를 받아들인다(기존 `ScriptedPurposeServer` 호환). `anchorResolved=true`일 때 requested == resolved를 교차 확인하지 않는다. HOME-01 미리보기 ≤4·≤count 검사가 없다.
- **시드 메모 (DEBUG):**
  - 보드의 "카테고리 삭제됨" 카드를 위해 홈 전용 "FiiO BTR7"을 추가했다. 그래서 같은 이름의 상품이 둘이다.
  - Marshall은 목적 없이 남아 확인 카드가 `목적 미지정`이다. 보드는 출퇴근 헤드폰이다.
  - 시드의 분류 중 항목은 DEBUG 분석 driver에서 제외되어(`heldAnalysis`) 끝나지 않는다. 재시도에서도 이 표시는 풀리지 않는다.
  - 카테고리 칩 69개와 목적 숫자는 보존했다.
- **window 교체 때 skeleton 깜빡임:** window 교체나 prepend로 앞쪽 id가 바뀌면 masonry 배치가 다시 시작된다. Android는 약 100ms, iOS는 최대 3초 skeleton이 보인 뒤 복원된다.
- **기기에서 확인하지 않은 경로:**
  - 삭제된 anchor의 대체 복원(`sameItem=false`)과 `loadNewer` prepend 위치 유지는 두 플랫폼 모두 기기에서 보지 않았다. Fake 목록이 짧아 순수 로직 테스트만 있다.
  - iOS 목적 상세 복원도 시뮬레이터에서 확인하지 않았다.
  - 모션 시간과 입력 잠금은 iOS에서 정지 화면으로만 봤다. Android 보완 넘김의 연타 잠금은 adb로 검증하지 못했다.
  - Compose·SwiftUI 제스처와 애니메이션 자동 테스트는 없다.
- **연속 처리 큐:** anchor 없는 카드가 현재 카드와 window anchor 사이에 있으면 빠질 수 있다(실서버는 늘 anchor를 보낸다). foreground window 뒤 `전체`가 한 번 바뀔 수 있다. 다른 기기 변경으로 생기는 오차는 다음 조회에서 고쳐진다.
- **목록 Presenter:** `AnchorRestore` 계산이 카테고리·목적 Presenter에 중복되어 있다. `prependFailed` 표시는 없다. 조용한 foreground·`loadNewer` 실패의 Presenter 테스트는 커밋되지 않았다(위 임시 probe).
- **실행 범위:** 실서버·Firebase·실기기는 실행하지 않았다(인증 연결 단계). iOS 스크린샷은 402×874pt로, 보드의 390×844가 아니다.

## PR #22와 겹치는 파일

GitHub 조회(2026-10-24): #22 `state=OPEN`, `isDraft=true`, `baseRefName=develop`, `mergedAt=null`. #23 `state=MERGED`, `mergedAt=2026-10-24T12:47:18Z`, mergeCommit `896fbc26fa736976c11ad273f6a73f9b5b9e2ef6`.

`gh pr view 22 --json files`(100개)와 `git diff --name-only origin/develop...HEAD`(148개)가 겹치는 파일은 17개다. 모두 최소 변경(route 한 줄, 문자열·pbxproj 추가만 등)이며 새 코드는 새 파일에 두었다. #22를 병합하거나 rebase하지 않았다.

- `client/android/src/main/kotlin/app/wishlist/android/designsystem/WLLineIcon.kt`
- `client/android/src/main/kotlin/app/wishlist/android/di/AppRuntimeConfig.kt`
- `client/android/src/main/kotlin/app/wishlist/android/feature/category/CategoryListScreen.kt`
- `client/android/src/main/kotlin/app/wishlist/android/feature/category/CategoryRoutes.kt`
- `client/android/src/main/kotlin/app/wishlist/android/ui/AppRoute.kt`
- `client/android/src/main/kotlin/app/wishlist/android/ui/AppRoutes.kt`
- `client/android/src/test/kotlin/app/wishlist/android/ui/AppRouteCodecTest.kt`
- `client/android/src/testDebug/kotlin/app/wishlist/android/di/AppRuntimeConfigDebugTest.kt`
- `client/ios/Wishlist.xcodeproj/project.pbxproj`
- `client/ios/Wishlist/AppRoutes.swift`
- `client/ios/Wishlist/AppRuntimeConfig.swift`
- `client/ios/Wishlist/ContentView.swift`
- `client/ios/Wishlist/Features/Category/CategoryListScreen.swift`
- `client/ios/Wishlist/Features/Category/CategoryOwners.swift`
- `client/ios/Wishlist/Features/Detail/ProductPhoto.swift`
- `client/ios/Wishlist/Navigation/WLEntryOwners.swift`
- `client/ios/Wishlist/Resources/Localizable.xcstrings`

`strings.xml`·`HomeRowText`·`WLNavHost`는 건드리지 않았다. Android 문자열은 새 `home_flow_strings.xml`에 있다.

## 커밋

- `4354638` docs: C7 설계와 구현 계획
- `fe3e059` feature(kmp): ITEM-02 window·anchor 계약
- `3ce4727` feature(kmp): HOME-01·HOME-02 Fake·Remote
- `38564c0` feature(kmp): 홈 할 일·목적 요약 Presenter
- `2266f13`, `f3605fe` feature(kmp): 연속 처리 큐와 임시 넘김, 재시도·갱신 실패 보완
- `536f697` feature(kmp): 카테고리·목적 목록 anchor 갱신
- `46b73a7`, `d0640fd`, `07230f3` feature(android): 홈·연속 처리·목록 anchor 화면, 속도 판정·잠금, Ruling 6~9 정렬
- `9a01001`, `e34dc6d` feature(ios): 홈·연속 처리·목록 anchor 화면, 회전 기준·위쪽 이어 받기 보완
- 이 문서 커밋: docs: C7 홈·연속 처리 구현 기록과 연동 상태 갱신
