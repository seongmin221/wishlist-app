# C5 PR B 구현 기록 (2026-10-15)

> draft PR #22(base `client/c5-category`, #21 merge 뒤 `develop`으로 rebase). 브랜치 `client/c5-photo-transition`(시작 `6b1ffbb` = PR A `client/c5-category` draft PR #21 위, D4). 설계 [spec](../../../superpowers/specs/2026-10-14-client-c5-category-design.md) §4·D4, [plan](../../../superpowers/plans/2026-10-14-client-c5-category.md) Task 20~23. PR A 기록은 [여기](c5-pr-a-implementation-2026-10-15.md).

## 작업 요약

| Task | 내용 |
| --- | --- |
| 20 | Android `PhotoHandoff`(정방향 hold·상세 사진+모션 끝에 놓기·칸 제거 때 놓기, 역방향), 상세 요청 자리표시·상자 비율 순수 함수, iOS `PhotoHandoff`·`RemoteImageLoader.bestCached(for:)` 주소별 색인(NSCache 축출 delegate), 두 플랫폼 DEBUG `clearImageMemory`와 로더 요청·fetch 로그 |
| 21 | Android 카드 → 상세 `ItemDetailRoute(viaPhoto)` 사진 공유 요소, handoff 연결, 뒤로 상세 사진, 모서리, 목록 사진 배치 상태 보존 |
| 22 | iOS `.item(viaPhoto)`, `WLSharedPhotoTarget` target 사진 등록, 방향별 공급원 `WLFlightPhoto`, 상세 사진 자리 `DetailSharedPhoto`, 모서리 |
| 23 | 전체 검증, 성능 기록, 문서, 독립 리뷰, draft PR |

## 판단(Ruling)

1. **Android 역방향은 "보이는 동안 알림 + 떠나는 동안만 사용".** spec은 "상세가 pop을 시작할 때 자기 이미지를 넣는다"고 했다. effect로 넣으면 뒤로 첫 프레임은 카드 자기 사진이라, 상세가 지금 그리는 이미지를 `showing`으로 알리고 카드가 그 상세가 떠나는 동안(`isLeaving`, 같은 composition에서 navigator 상태를 읽음)만 쓴다. 상세 화면이 이미 든 객체라 메모리가 늘지 않는다.
2. **정방향 key는 칸 id, 역방향 조회는 sourceKey.** push가 칸 id를 돌려주지 않아 push 성공 직후 맨 위 칸 id로 hold한다. 카드는 자기 sourceKey만 알아 역방향은 sourceKey로 찾는다.
3. **상세 사진 자리는 한 노드(두 플랫폼).** 항목을 받는 첫 프레임과 상품 표시가 다른 노드면 Android는 목표가 날아가는 도중 바뀌어 사진이 카드 자리에 머물다 튀었다(에뮬레이터). iOS는 push가 시작 때 target 탐침을 잰다.
4. **목록 사진 배치 상태를 칸 ViewModel로(Android, PR A 동작 수정).** 상세가 열린 동안 목록은 composition을 떠나 `remember`의 배치가 사라진다. memory cache가 비어 있으면(DEBUG hook, 실제로는 축출) 돌아올 때 skeleton부터 다시 배치해 스크롤 위치(D3)와 사진이 돌아갈 카드를 잃었다. 페이지·크기·비율(숫자)만 `CategoryGridLayout`에 둔다.
5. **iOS 전환층 내용은 값으로 그린다.** 전환층은 등록된 내용을 그 뷰 밖에서 다시 만들어 `@State` 이미지가 비어 보인다. 카드·상세 모두 넘겨받은 이미지 값을 그린다.
6. **모서리는 카드 20 → 상세 0(두 플랫폼, 사용자 확인 대기).** 보드 FProductDetail은 칸 안 사진에 모서리가 없고 카드는 20이다. Android는 overlay에 들어오는 쪽 내용이 그려지므로 들어오는 쪽만 모서리를 바꾸고(떠나는 쪽까지 바꾸면 pop 시작에 모서리가 깜빡였다), iOS는 전환층이 phase로 보간한다.
7. **카드 공유 요소 수를 줄이지 않았다.** 120개 카드가 모두 공유 요소라 push가 느린지 확인하려고 상세가 스택에 있는 카드만 공유하게 바꿔 쟀으나 차이가 없어 되돌렸다(아래 성능).
8. **DEBUG `clearImageMemory`는 전환 시작마다 비운다.** 가장 어려운 경우(카드를 누른 직후·뒤로 시작 직후 cache가 빔)를 스크립트로 재현할 수 있다.

## 기기 확인

Android는 사용자 지시대로 에뮬레이터 `emulator-5556`(Pixel_5 API 30)에서 했다. 이번에 띄운 AVD는 en-US·로그아웃 상태여서 fake 로그인 뒤 확인했다(목록 이름·카테고리는 한국어 seed). iOS는 iPhone 17 Pro 시뮬레이터. 두 플랫폼 모두 `longCategory C024`(키보드 123개, 1:4 사진·5초 사진 포함)와 `clearImageMemory`로 했다. 화면 녹화(Android `screenrecord`, 프레임이 빠져 `animator_duration_scale 5`로 늦춰 확인 / iOS `simctl io recordVideo`)에서 프레임을 뽑았다. 사진은 [`c5-shots/`](c5-shots/)의 `android/pr-b-*.png`·`ios/pr-b-*.png`.

- **push(cache 비움):** 첫 프레임부터 카드 사진이 커진다(두 플랫폼).
- **pop(cache 비움):** 첫 프레임이 상세 사진(상세 크기, 모서리 0)이고 카드 자리로 줄며 20이 된다. Android는 목록이 배치된 채로 돌아온다(Ruling 4 뒤).
- **끌어서 뒤로 취소:** iOS는 왼쪽 가장자리에서 35%까지 끌고 멈췄다 놓았다. 상세 사진이 손가락을 따라 줄었다가 돌아오고 상세에 사진이 남았다. Android 에뮬레이터 API 30에는 predictive back이 없어 보지 못했다.
- **사진 없는 카드:** 자리표시가 같은 모션으로 커진다(두 플랫폼).
- **로더 요청·다운로드:** Android push 한 번은 상세 요청 `start` 1·`fetch` 1(cache를 비웠으므로 상세 크기로 다시 만듦), pop은 목록에 다시 그려지는 카드의 `start`·`fetch`가 화면의 사진 수만큼(5)이었다. cache를 비우지 않으면 pop 때 fetch는 없다.

## 성능 기록

통과 기준이 아니라 기록이다([성능 확인](../../../architecture/client/c3-performance-checks.md#c5-pr-b-측정-결과-2026-10-15)).

## push 전 독립 리뷰 반영(2026-10-15, `/code-review high`)

10건 중 4건을 고쳤다.

- **iOS handoff를 놓는 곳이 없음:** 상세 사진을 못 그리고 닫힌 상세(주소 없음·실패·받기 전 뒤로·계정 떠남)가 카드 `UIImage`를 프로세스 끝까지 들었다. `drainRemoved` 뒤 `releaseRemoved`, `dropAccountScoped`에서 놓는다.
- **Android overlay clip이 모서리 20 고정:** 공유 요소 overlay가 고정 20 모양으로 잘라 push 내내 20이었다가 끝에서 0으로 튀었다(에뮬레이터 프레임으로 확인). overlay clip도 같은 움직이는 모서리를 쓴다.
- **Android 상세 사진 실패 때 빈 칸, 요청이 카드 bitmap을 붙잡음:** 카드 사진을 요청의 `placeholder`로 넘기던 것을 상세가 직접 그리게 바꿨다(실패하면 카드 사진이 남고, handoff를 놓으면 bitmap도 풀린다). 같은 값만 옮기던 `DetailPhotoStart`도 지웠다.

고치지 않은 것(경미, 후속): iOS 사진 진입인데 첫 상태가 분석 중·삭제됨·오류면 target이 없어 제자리 전환(카테고리 목록은 완료 상품이라 드묾), iOS target은 시작 때 한 번 재므로 사진을 아직 못 받은 카드는 칸 전체로 날아간 뒤 맞춘 사진으로 바뀜, Android 카드 ✳ 표시가 공유 요소 안(뒤로 갈 때 함께 줄어듦, iOS는 밖), Android 카드마다 역방향 판정(전환 상태가 바뀌면 모든 카드가 다시 그려짐, 측정상 차이 없음), Android DEBUG 떠남 감시 effect가 release에도 돎(아무 일도 안 함).

## 고치지 않은 것·남은 점

- Android 끌어서 뒤로·취소 기기 확인(API 33+ 기기·에뮬레이터 필요), 실기기(Flip6) 확인.
- 120개 목록으로 돌아올 때 목록 재구성 비용(PR A부터, slide도 같음). C7 window·지연 목록에서 다시 본다.
- 모서리 결정은 사용자 확인 뒤 [디자인 결정](../../../design/decisions.md)에 적는다.

## 검증(2026-10-15, 로컬)

| 항목 | PR A 끝 | PR B 끝 |
| --- | --- | --- |
| Android unit debug / release | 212 / 212 | 227 / 227 |
| iOS XCTest | 244 | 258 |
| assembleDebug·assembleRelease·lintDebug(0 errors, 경고 27)·iOS Release simulator build | 통과 | 통과 |

shared·localdb는 바뀌지 않아 KMP_TEST는 다시 돌리지 않았다. 새 테스트마다 해당 수정만 되돌려 실패를 확인했다(handoff 놓기 조건, 역방향 떠남 판정, 상자 비율 순서, 상세 자리 유지 규칙, 색인 큰 것 우선·축출 정리, 방향별 공급원, 끌기 방향 표시). 전체 XCTest 한 번에서 손대지 않은 `HomePresenterOwnerTests.testRefreshReturnsOnlyAfterTheRefreshFinished`가 시간 조건으로 실패했고, 단독 3회·다음 전체 실행은 통과했다(수동 시뮬레이터 확인 직후).
