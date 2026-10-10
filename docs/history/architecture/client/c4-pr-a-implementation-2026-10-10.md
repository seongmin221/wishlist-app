# C4 PR A 구현 기록 (2026-10-10)

> 브랜치 `client/c4-product-detail`(시작 `abae37d`, origin/develop). 설계 [spec](../../../superpowers/specs/2026-10-09-client-c4-product-detail-design.md)·[plan](../../../superpowers/plans/2026-10-09-client-c4-product-detail.md). PR A는 상품 상세·분석 중·로컬 대기 화면까지이고, 원본 웹뷰는 PR B다(D20).

## 작업 요약

| Task | 내용 | 커밋 |
| --- | --- | --- |
| 1 | 목적 표시값(name/colorKey/iconKey) DTO→domain→SQLite, 캐시 v3 | `7ecc4b2` |
| 2 | 캐시 같은 version GET 갱신, 해독 불가 행·대문자 id 처리, `cachedItemBySubmission` | `da68a51` |
| 3 | 로컬 대기 항목 삭제(`deleteLocal`)와 삭제된 행 건너뛰기, close 뒤 DB 미접촉 단언 | `932281e` |
| 4 | 상세 Presenter 계정 전환 정책(D3)·`refresh()` | `84c60bb`, `8db2b70` |
| 5 | 상세 화면 종류(`DetailKind`)·저장 시점 표시 | `495fb90` |
| 6 | 로컬 대기 상세 Presenter | `f2a1267` |
| 7 | Android 계정 범위 화면 정리와 칸별 ViewModel 수명 | `a3e5f0e`, `b6952eb`, `6560a27` |
| 8 | iOS 계정 범위 화면 정리와 화면별 owner 수명 | `96300c6` |
| 9 | 상품 사진 로더(Android Coil 3.5.0, iOS 자체 로더) | `8300c31`, `0855656` |
| 10 | Android 상세·분석 중·로컬 대기 화면, DEBUG 상세 분석 진행, Fake 목적 값 | `cc58d3a`, `b1693e6`, `d072c8e`, `4ef201c` |
| 11 | iOS 같은 화면 | `d860113`, `7711c2c` |
| 12 | 전체 검증·문서 | 이 기록의 커밋 |

## 판단(Ruling)

1. 삭제 가능 여부는 `submissionStatus != SUBMITTING`으로 정한다. 계획의 RowStatus 집합과 같고(로그아웃 view의 LOCAL_ONLY 포함) D17과 맞다.
2. iOS `refreshAndWait`은 `loading=true`가 된 뒤 `false`가 될 때까지 기다린다(마지막 id가 없으면 바로 반환). 새로고침이 시작되기 전에 끝나는 것을 막는다.
3. A1 중간 보고는 유지하되(사용자 요청) push·draft PR 생성은 사용자 확인을 기다린다.
4. 사용자 지시(2026-10-10 "PR A 올린 다음에 바로 PR B 까지 진행해")로 PR A push와 draft PR 생성이 허용되었다. A1은 정지 없이 상태 줄로만 남긴다.
5. 로컬 대기 Presenter는 계정을 떠난 뒤 다음 load까지 아무것도 판정하지 않는다(sticky skip). 화면마다 이전 view와 비교하면 A → 로그아웃 → B에서 잘못된 `RemovedOnServer`가 나온다. 셸이 어차피 화면을 닫는다([QA-CLI-014](../../../learning/client/q-and-a/QA-CLI-014-local-presenter-sticky-skip.md)).
6. 별도 중간 리뷰는 하지 않고 모든 task 리뷰와 Task 12 전체 리뷰로 대신한다.
7. 로컬 대기 화면은 홈과 같은 문구 키(`home_pending_title`, `home_pending_meta`)를 쓰고 iOS도 같게 맞춘다.
8. DEBUG의 상세 GET이 Fake 분석을 진행시켜 D7의 "당겨서 새로고침 → READY" 시연이 된다. DEBUG 전용이며 RELEASE·REMOTE는 건드리지 않는다.

## 계획과 달라진 점

- 가격은 새 함수 대신 기존 `PriceFormatter`를 재사용했다.
- 같은 계정 재로그인은 로그아웃을 거치는 accountId 변화로 잡고, 상세 화면의 `Initial` pop을 안전장치로 둔다.
- `processingItems`의 해독 불가 행에도 같은 규칙(없는 것으로 읽기)을 적용했다(spec §1 확장).
- Coil은 3.5.0이다. 3.6.x는 AGP 9.1·compileSdk 37이 필요하다([의존성 기록](c4-dependency-coil-2026-10-10.md)).
- 이름: `LocalSubmissionDetailPresenterOwner`를 `LocalSubmissionPresenterOwner`로 바꿨고 spec·plan에도 반영했다.
- Android는 lifecycle-viewmodel-compose 없이 칸별 `ViewModelStore`를 직접 둔다([QA-CLI-013](../../../learning/client/q-and-a/QA-CLI-013-android-per-entry-viewmodel-store.md)).

## 남긴 점(task 리뷰에서 미룬 minor)

- 테스트·fixture: v1 migration 행 데이터 보존 미검증, v2 DDL 손복사 drift, 대문자 `client_submission_id`·decode 실패의 `cachedItemBySubmission` 테스트 없음, snapshot-match guard·close 중 lookup·generic 삭제 실패 미검증, 윤일·미래 시각·정확히 60초 경계, Compose 제스처 wiring(Robolectric 없음), 실제 사진 캡처(Fake imageUrl 없음).
- 구조·동작: `selectSubmissionByKey` `LIMIT 1` 정렬 없음, iOS 다운샘플이 `scaledToFill` 대비 긴 변 기준이라 흐릴 수 있음, 크기 0일 때 1px 캐시, 진행 중 요청 dedupe 없음, 메모리 hit 때 자리표시 깜빡임, Android `java.net.URI`가 iOS `URL`보다 엄격, 숨은 상세가 foreground에서 새로고침, 로컬 메뉴 탭에서 `!deleting` 재확인 없음, 복원 후 `seenWork`·`resumes` 미저장, 떠나는 화면이 모션 끝까지 구 계정 상태로 보임.
- 한계 표와 인계는 [kmp.md](../../../architecture/client/kmp.md#c4에서-생긴-항목)에 있다. 플랫폼 화면 한계는 [android.md](../../../architecture/client/android.md)·[ios.md](../../../architecture/client/ios.md)의 C4 절에 있다.

## 검증(2026-10-10, 로컬)

| 항목 | 시작 baseline | Task 12 |
| --- | --- | --- |
| shared Android host / iOS simulator | 434 / 431 | 492 / 489 (실패·skip 0) |
| Android unit debug / release | 94 / 94 | 127 / 127 |
| iOS XCTest | 122 | 163 |
| assembleDebug·assembleRelease·lintDebug·`linkReleaseFrameworkIosArm64`·iOS Release simulator build | 통과 | 통과 |
| `gen_tokens.py --check`·`test_gen_tokens.py` | 통과 | 통과·8개 |

화면 확인은 [C4 화면 확인 기록](c4-detail-verification-2026-10-10.md), 메모리 baseline은 [성능 기록](../../../architecture/client/c3-performance-checks.md#c4-측정-결과-2026-10-10)에 있다. 플랫폼 CI job은 꺼져 있어 로컬 결과가 근거다.

## PR #15 2차 리뷰 반영(2026-10-10)

- **Android 복원 시 상세가 닫히거나 오류에 멈춤:** `ItemDetailRoute`는 프로세스 종료 뒤 복원되는데, 화면이 DEBUG bootstrap(세션 복원·seed → ready)보다 먼저 `loadOnce`를 불렀다. ready 전이면 `RUNTIME_NOT_READY` 오류가 나고, 뒤이은 세션 복원((null,0) → (A,1))이 Presenter를 `Initial`로 되돌려 `shouldClose`가 화면을 pop했다. 이제 화면이 `runtime.ready`를 기다린 뒤 load한다. Presenter 정책(세션 변경 = 계정 떠남)은 바꾸지 않았다. iOS는 내비게이션 스택을 복원하지 않아 해당하지 않는다.
- **로컬 대기 화면의 메뉴·삭제 확인창 잔류(Android·iOS):** 확인창이 열린 동안 전송이 시작되거나(`canDelete`가 true → false) outcome(`MovedTo` 등)이 오면, 이 화면이 연 overlay를 모두 닫은 뒤 스택을 바꾼다. 열리는 중이면 `dismissAll`이 거절하므로 한 프레임씩 기다렸다가 다시 시도한다.
- `Wishlist.sq` 머리 주석을 schema v3로 고쳤다.

## PR #15 3차 리뷰 반영(2026-10-10, `/code-review`)

10건 중 6건을 고쳤다.

- **iOS 당겨서 새로고침이 2초 동안 돎:** `refreshAndWait`가 50ms 폴링이라, 같은 항목으로 빨리 끝난 새로고침을 놓쳤다. 공유 `ItemDetailPresenter.refreshNow()`를 추가했고, iOS는 이것을 await한 뒤 마지막 상태를 바로 반영한다.
- **item id 대소문자:** 조회(`cachedItem`·`removeCachedItem`)는 소문자 UUID로 하는데, 쓰기는 서버 id를 그대로 썼다. 이제 `mapItem`이 id를 canonical 소문자로 바꾼다. 캐시 키와 상세 Presenter의 `shown` 비교가 한 형태가 된다.
- **이미 떠난 행의 삭제:** NOT_FOUND를 일반 실패("지우지 못했어요")로 보였다. 이제 `SUBMISSION_IN_FLIGHT`처럼 `deleting`만 내리고, 다음 view가 MovedTo·Gone을 정한다.
- **분류 중 정렬:** 줄은 `savedAt`(`clientCreatedAt ?: createdAt`)을 보여 주는데, 정렬은 `createdAt`으로 했다. 정렬도 `(savedAt, id)`로 바꿨다.
- **iOS retired id:** 요청마다 Presenter를 만들고 닫았다. 이제 종류별로 이미 닫힌 owner 하나를 돌려준다.
- `upsertFromGet`·`upsertIfNewer`를 같은 버전 허용 여부만 다른 한 함수로 합쳤다.

고치지 않은 것:

- Activity 재생성 중 계정 변경을 놓친다는 지적은 고치지 않았다. Activity가 없을 때는 계정을 바꾸는 입력이 없고, 로그아웃 상태 복원은 UNAUTHENTICATED 닫기가 맡는다.
- iOS `whenSettled` 30ms 폴링: 닫기·교체 때만 짧게 돈다.
- 화면 쪽 시계·UTC 오프셋: 상세 라벨을 Presenter로 옮기는 것은 별도 정리 대상이다.
- `retry`/`refresh` 동일: 의도를 이름으로 구분하려고 둔다.

검증: shared Android host 501개, shared iOS simulator 498개, Android unit debug, `assembleDebug`·`lintDebug`, iOS XCTest 163개가 모두 통과했다(실패 0).
