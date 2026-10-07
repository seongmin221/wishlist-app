# Client 의사결정

## 초기 결정

- **확정**: iOS UI는 SwiftUI, Android UI는 Jetpack Compose로 각 플랫폼 native UI를 유지한다.
- **확정**: domain model, repository, use case, API client, local cache/sync, URL normalization은 KMP 공유 대상으로 둔다.
- **확정**: iOS Share Extension과 Android `ACTION_SEND` 처리는 플랫폼 native로 구현한다.

## 구현 결정

- [ADR-027: 클라이언트는 계약 우선 fake와 KMP Presenter로 기능 단위 구현한다](ADR-027-client-implementation-strategy.md) — 서버 의존 방식, KMP 스택, 화면 상태 소유, 진행 단위
- [ADR-028: iOS 화면 이동은 NavigationStack 없는 자체 라우터로 구현한다](ADR-028-ios-custom-router.md) — 조건부 go, window 수준 끌어서 뒤로, 실기기 VoiceOver 확인 조건
- [ADR-029: 디자인 토큰은 Python 표준 라이브러리 생성기로 두 플랫폼 상수를 만든다](ADR-029-design-token-generator.md) — 두 입력, 생성물 커밋과 `--check`

## 검증 기록

- [C2 전반부 리뷰 후속 보완 (2026-10-07)](c2-first-half-review-followup-2026-10-07.md) — Fake 재분석 병합, 상품 ID 정규화, 정책 평가 되먹임 제거
- [C2 의존성과 Swift ABI 호환성 (2026-10-07)](c2-dependency-compatibility-2026-10-07.md) — 후보/선택 버전, actual-use spike, Flow·suspend·callback 관문

- [C1 리뷰 수정과 검증 (2026-10-06)](c1-review-2026-10-06.md) — 입력·전환 생명주기·접근성 보완, CI와 글꼴 용량 검토
- [iOS 라우터 spike 결과 (2026-10-05)](ios-router-spike-2026-10-05.md) — `NavigationStack` 없는 자체 라우터 진행 판단, 끌어서 뒤로는 window 수준 UIKit pan

추가 결정은 `ADR-번호-제목.md` 형식으로 이 폴더에 기록한다.
