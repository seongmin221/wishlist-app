# C1 리뷰 수정 계획

**목표:** 사용자 리뷰의 입력·전환 오류, 상태 소유와 데모 분리, 접근성·성능 문제를 현재 브랜치에서 수정한다.

**구조:** 플랫폼별 native UI 경계를 유지한다. 경로의 화면 정책은 feature가 제공하며 공유 요소 등록은 화면 생명주기에 묶는다. 통화 형식은 C2에서 KMP로 옮기기 전까지 플랫폼별 구현을 유지한다.

**범위:** 사용자의 2026-10-06 C1 리뷰. iOS 17+, Android API 26+, 기존 토큰·모션과 한글 서체 유지.

## 첫 리뷰 순서와 담당

- [x] iOS: `WLInput`의 조합 중 편집 보존·확정 후 grapheme 절단, `WLText` 기본 한 줄과 명시적 여러 줄, 글꼴 캐시, 공유 요소 등록 해제·key별 숨김 관찰을 수정한다.
- [x] Android: 입력의 조합 보존·grapheme 절단, 면 정보 등록 갱신·해제, draw 단계 읽기와 drag 상태 쓰기를 수정한다.
- [x] 각 플랫폼: 경로 정책을 feature로 옮기고 데모를 debug로 한정한다. iOS 앱 루트 상태 소유, 접근성, iOS Reduce Motion·완료 콜백, 레이아웃·통화 형식을 수정한다.
- [x] 토큰: 잘못된 색 입력의 실패를 테스트로 확인하고 `#RRGGBB` 검증을 추가한다. `python3 -m unittest client/tools/test_gen_tokens.py`와 `--check`를 실행한다.
- [x] CI: 토큰 검사, Android 단위 테스트·lint·Debug/Release, arm64 iOS 테스트·Release를 workflow로 추가한다.
- [x] 통합: Android Debug/Release 각 53개 테스트·두 빌드·lint, iOS 전체 앱/테스트 타입 검사, 독립 리뷰, architecture 문서·검증 기록·INDEX를 갱신한다. iOS UI 테스트는 Xcode 로딩 오류로 실행하지 못했으며 [검증 기록](../../history/architecture/client/c1-review-2026-10-06.md)에 명시한다.

첫 리뷰의 한 줄 기본 경로·TextFieldValue 입력은 아래 추가 리뷰에서 maxLines 계약·TextFieldState API로 재수정했다.

## 검증 초점

- 39/40자 한글 조합과 한도 초과 붙여넣기, 결합 문자·이모지 grapheme, 커서 이동.
- 공유 이미지의 같은 key 갱신과 뷰 제거 후 등록 해제, 전환 중 원본 숨김.
- 여러 줄 설명의 줄 높이와 잘림, 기본 라벨의 한 줄 렌더링.
- overlay 열기·닫기 중 접근성 클릭, Reduce Motion 활성화 중 전환 종료.
- JPY(0자리)·USD(2자리)·KWD(3자리) 통화 소수와 음수/그룹 구분.

실기기 IME·VoiceOver·TalkBack과 C3 목록 profiling은 자동 검사 결과와 구분해 문서에 남긴다. iOS navigator/overlay 저장·복원과 글꼴 배포 정책은 현재 한계를 명시하고 후속 기능 경계에서 다룬다.

## 추가 리뷰 반영

- [x] 두 플랫폼의 navigator/motion/registry를 overlay 바깥에서 제공하고 시트 안 이동을 검증한다.
- [x] Android navigator의 탭·스택·route codec·entry id·nextId를 저장한다. 복원 뒤 새 화면의 키 재사용을 회귀 테스트한다.
- [x] Android 입력은 `TextFieldState` 한 소유자로 바꾼다. 지연된 부모 callback·이중 값 소유를 제거하고 IME commit 시 grapheme 제한을 적용한다.
- [x] 두 플랫폼의 `maxLines` 계약과 통화 코드 정규화/unknown fallback을 맞추고 iOS 통화 metadata cache를 검증한다.
- [x] Android shared source 활성 항목만 애니메이션, painter 재사용, main AppRoute와 variant 데모 provider, chip semantics·높이 제약을 보완한다.
- [x] iOS Masonry cache·입력 관찰 cache, 두 플랫폼 리소스 문자열과 실제 시트 제목 접근성을 보완한다.
- [x] CI push는 기본 브랜치 develop만 실행한다. iOS 17.5/26.0 matrix와 runtime 준비 스크립트를 추가하고 actionlint·4개 mock 설치 경로 검증을 실행한다.
- [x] [C3 성능/모듈 경계 검토](../../architecture/client/c3-performance-checks.md)를 기록한다.
- [x] 최종 플랫폼 테스트·타입 검사·독립 리뷰와 architecture/history 문서를 갱신한다. 원격 CI 실행은 변경 workflow가 원격에 올라와야 가능하며 현재 실행 이력은 없다.
