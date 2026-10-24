# C6 목적 화면 구현 기록 (2026-10-24)

시작 브랜치 `client/c6-purpose`, develop `0b95e90`. C5 PR A #21은 MERGED(2026-10-24), PR B #22는 Draft OPEN·develop 미병합이다. 사진 공유 요소 전환을 이 작업에 가져오지 않았으며 C5 전체 완료로 기록하지 않는다. 서버 B8는 별도 작업이고 이 브랜치에서 서버 파일을 변경하지 않는다.

[설계](../../../superpowers/specs/2026-10-24-client-c6-purpose-design.md) · [계획](../../../superpowers/plans/2026-10-24-client-c6-purpose.md) · [B3 계약](../../../architecture/server/purpose-management-api.md).

## 구현

- PUR-01 SUMMARY 전체 snapshot, PUR-02~04 Fake/Remote·계정별 routing·ready gate·오류 및 표시 key 변환. ITEM-02 purposeId 필터와 GET cache 동기화.
- 목적 탭·생성 시트·상세·빈 상세·그 자리 편집을 KMP Presenter에 연결. 팔레트·아이콘·목적 표시값의 원본과 wire 경계를 분리한다.
- 두 플랫폼은 기존 `WLHeaderSheet`로 실제 목록 시트와 머리 연속 변형을 구현한다. 후보 카드의 판매처·마지막 확인 표시는 C5 기본 목적 metadata를 바꾸지 않고 목적 화면에서 선택한다.
- 사용자 답변: 이름 없는 목적 후보 카드 제목은 `분류 중` / `정보 보완 필요` 상태 문구를 사용한다.
- 사용자 답변: 후보 추가·삭제·비교 끝내기·끝난 비교는 C6에서 **비활성 진입점 유지**. 후보 없는 목적의 종료 버튼은 보드대로 숨긴다.

## 구현 판단과 검토

1. C5의 SessionLane·폼 멱등 재시도·충돌 복구 정책을 재사용한다. 새로운 제품 정책으로 확정한 것이 아니라 현재 화면 일관성을 위한 구현이다.
2. `CatalogRepository`의 C2 seed 경계는 기존 테스트·interop 호환으로 유지한다. 새 목적 화면은 `PurposeRepository`를 사용한다. PUR-01 SELECT는 C8 소비 화면에서 추가하며 C6는 SUMMARY만 구현한다.
3. iOS 상세의 단순 onDisappear에서 편집 폼을 닫으면 탭 이동·상품 상세 왕복 뒤 초안이 닫힌 Presenter를 참조한다. 폼을 상세 entry owner 수명에 묶고 명시 취소·성공 또는 entry 제거에서 해제한다.
4. MockEngine 공통 계약은 Fake 규칙을 HTTP 표현으로 감싸므로 클라이언트 wire 처리 검증이다. 실서버 DB·Firebase·worker 검증과 구분한다. 서버가 commit한 뒤 앱 계정이 바뀌는 경우까지 서버 commit 취소로 간주하지 않는다.
5. 보드의 목적 숫자는 장식 metadata가 아닌 실제 활성 상품 연결로 `5/3/2/4/3/2/0`을 만든다. 출퇴근 목적의 다섯 번째는 FiiO BTR7이며 기존 헤드폰 네 상품·Marshall 미지정/검토 상태와 카테고리 69개는 보존한다. 목적 activity snapshot을 별도로 두어 최근 활동순을 재현한다. 이미지가 없는 시드는 중립 placeholder를 사용한다.
6. 빌드 환경의 `/usr/libexec/java_home -v 17`은 등록된 25로 되돌아간다. 설치된 `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`과 `-Dorg.gradle.java.installations.paths`를 명령에 명시했다. 머신 경로는 프로젝트 설정에 넣지 않는다.

## 검증

아래는 최종 로컬 실행 결과다.

- 최초 목적 Presenter/Fake/mapper 행동 red: 27개 중 24개 실패(stub), 구현 뒤 목적 Presenter 18개 통과. 이후 페이지·close·계정·한도·문자 규칙을 추가해 Presenter 27개 통과.
- 최종 공통 계층 명령: `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=$HOME/Library/Android/sdk ./client/gradlew -p client -Dorg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home :shared:allTests :shared:linkReleaseFrameworkIosArm64`. 결과: host **690**, iOS simulator **687**, 실패·오류·skip **0**, Release device framework 링크 통과. 로컬 로그 `/tmp/c6-shared-verified.log`.
- Android 최종 명령: `:android:testDebugUnitTest :android:assembleDebug :android:testReleaseUnitTest :android:assembleRelease :android:lintDebug`. debug/release 각각 **225개** 통과, assemble 두 변형 통과, lint **0 errors / 27 warnings**. 새 경고는 남기지 않았다. 최종 레이아웃 회귀 수정 후 `/tmp/c6-android-collapse-verified.log`로 재확인했다.
- iOS 최종 `xcodebuild test`(scheme Wishlist, Debug, `-sdk iphonesimulator`, iPhone 17 Pro/iOS 26.2, 테스트 접근성 설정 활성화): **255개 통과**, 실패 0. `/tmp/wishlist-c6-ios-test.log`. Release simulator `xcodebuild ... -configuration Release -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' build` 통과(`/tmp/wishlist-c6-ios-release.log`).
- 화면: [화면 INDEX](c6-shots/INDEX.md), 목적 보드 L/D 대비 탭·생성·상세·접힘·편집·빈 상세, Android dirty 버리기·IME footer·작은 표시 크기에서 200자 편집 후 저장을 확인했다. 픽셀 자동 비교나 실기기 검증은 아니다.
- `python3 client/tools/gen_tokens.py --check` 및 토큰 생성기 단위 테스트 **8개** 통과. `git diff --check` 통과.
- runtime/cache 연결 red 3건을 확인한 뒤 구현. 플랫폼 바인딩은 PUR-01~04를 DEBUG FAKE에 추가한다.

## 독립 리뷰와 화면 검증 보완

- 목적 홈으로 돌아오는 진입에서 전체 SUMMARY 재조회가 빠졌다. Android는 entry owner의 최초 실행 여부와 화면 재등장을 분리했고, iOS는 유지된 홈이 stack 최상단이 되는 것을 관찰한다.
- 목적 후보는 이름 없는 ACTIVE 상품도 포함하므로 카테고리 카드의 빈 제목 처리를 그대로 사용할 수 없다. 사용자에게 문구를 질문했고 `분류 중` / `정보 보완 필요`를 선택해 양 플랫폼에 적용했다. 카테고리 기본 동작은 유지한다.
- 설명은 B3가 허용하는 LF/CRLF 입력을 보존하도록 여러 줄로 편집한다. 이름은 한 줄이다.
- 팔레트의 라이트 테두리 token과 편집 취소·저장 버튼 면을 보드에 맞췄다. 목적 8개 아이콘도 플랫폼별 시스템 심볼 대신 같은 handoff path를 사용한다.
- Android 편집 패널의 높이 애니메이션과 시트 anchor 애니메이션이 경쟁하던 부분을 정리했다. 작은 화면 스크롤 영역이 접힌 제목·아이콘을 자르던 회귀는 제목을 스크롤 영역 밖으로 분리해 보완했다. iOS는 실측 머리가 늘어날 때 resting 상태의 clip/hit-test 위치도 같이 갱신하도록 기존 시트를 보완했다.

긴 설명은 여러 줄을 보존하되 편집칸은 최대 3줄 높이 안에서 스크롤한다. Android는 작은 화면에서 편집 본문과 취소·저장 footer를 분리하고, iOS는 키보드 toolbar에도 취소·저장을 둔다.

상세의 수동 갱신은 Android 목록 끝 버튼·iOS 후보 머리의 새로고침 버튼으로 제공한다. 상세의 아래로 끌기는 목록 시트 확장/축소에 사용한다. 목적 탭은 당겨서 갱신한다.

## 남은 경계

- DEBUG Fake / RELEASE UNAVAILABLE, 실제 Firebase·앱 실서버 연동 미실행.
- C7 window/anchor·홈 목적 요약, C8 상품 목적 선택·편집, C9 후보 추가/삭제, C11 종료/아카이브는 범위 밖.
- #22 미병합 사진 공유 요소 전환과 Android 실기기 검증을 완료로 표기하지 않는다.

## 커밋과 작업 상태

- `3f10a57` — 공통 목적 계약·Presenter·시드·테스트
- `ccb9cca` — Android 목적 화면·폼·내비게이션
- `816dbca` — iOS 목적 화면·폼·시트·아이콘
- `c546c10` — Android 접힘 제목 clipping 회귀 수정

구현은 `client/c6-purpose`에 커밋했다. 구현 마무리 조회에서도 #22는 OPEN·isDraft=true·base develop이었다. 서버 파일 변경은 없으며 PR 게시와 병합 상태는 GitHub에서 확인한다.
