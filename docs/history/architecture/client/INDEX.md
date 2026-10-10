# Client 의사결정

## 초기 결정

- **확정**: iOS UI는 SwiftUI, Android UI는 Jetpack Compose로 각 플랫폼 native UI를 유지한다.
- **확정**: domain model, repository, use case, API client, local cache/sync, URL normalization은 KMP 공유 대상으로 둔다.
- **확정**: iOS Share Extension과 Android `ACTION_SEND` 처리는 플랫폼 native로 구현한다.

## 구현 결정

- [ADR-027: 클라이언트는 계약 우선 fake와 KMP Presenter로 기능 단위 구현한다](ADR-027-client-implementation-strategy.md) — 서버 의존 방식, KMP 스택, 화면 상태 소유, 진행 단위
- [ADR-028: iOS 화면 이동은 NavigationStack 없는 자체 라우터로 구현한다](ADR-028-ios-custom-router.md) — 조건부 go, window 수준 끌어서 뒤로, 실기기 VoiceOver 확인 조건
- [ADR-029: 디자인 토큰은 Python 표준 라이브러리 생성기로 두 플랫폼 상수를 만든다](ADR-029-design-token-generator.md) — 두 입력, 생성물 커밋과 `--check`
- [ADR-030: 공유 수신은 플랫폼별로 나누고 iOS 확장은 app group inbox에 기록만 한다](ADR-030-share-receipt-mode.md) — C3-D1 A/B/C 비교와 C안(확장 전송은 인증 연결 때), 시뮬레이터 서명·app group 발견, iOS 26 불투명 확장 시트

## 검증 기록

- [Client·Server 연동 정밀검사 (2026-10-09)](client-server-integration-audit-2026-10-09.md) — C3/B4 병합 계약 대조·실HTTP/DB 검증, URL scheme·계정 전환 실패 재현, B3/B4 모델·목록 인계와 인증 연결 선행 작업
- [C3 PR #12 리뷰 반영 (2026-10-09)](c3-pr12-review-2026-10-09.md) — 리뷰 10건: refresh revision으로 상대 시각 갱신, 복원 계정 첫 실행 기록, inbox import 무기한 ready 대기, 시각별 offset, 게시 합치기, Android LAUNCH 단일화, iOS `refreshNow` 대기, 정렬 단일화, parser 죽은 분기, debug hook io. 2차 10건: 429 계정 단위 대기, 재시도 타이머(서버 오류 30초), accept·게시 한 시점, 대기 줄 문구 3종, 조회 중 새 공유 우선, iOS 실제 복귀만, `flushing`·`FlushTrigger` 삭제, 영어 plural, UUID 헬퍼 하나. 3차 9건: ready 전 view 없음, 서버 오류 backoff·flush 중단, 모르는 enum 허용, iOS 임시 파일 복구, Android 늦은 공유 파일 inbox, 분 단위 tick, 선형 괄호 다듬기, 게시 간격, host 규칙 하나
- [C3 화면 비교·예외 경로·성능 측정·최종 검증 (2026-10-07)](c3-verification-2026-10-07.md) — 보드 대비 차이와 수정, 계정 전환·강제 종료 복구, 대기 목록 20/100/300 baseline, debug 시연 hook, runtime 종료 중 DB 조회 충돌 수정(Task 7c), 최종 명령별 건수(Task 8)
- [C2 최종 로컬 검증 (2026-10-07)](c2-final-verification-2026-10-07.md) — 실행 명령별 건수·환경·미실행과 C3/C12 인계
- [C4 의존성: Coil 3 (2026-10-10)](c4-dependency-coil-2026-10-10.md) — 3.5.0 선택 이유(3.6.x는 compileSdk 37·AGP 9.1 필요), OkHttp 재사용, 자리표시
- [C2 `:localdb` 모듈 분리 (2026-10-07)](c2-localdb-module-split-2026-10-07.md) — 계획의 `:shared` 단일 모듈에서 벗어난 이유(SQLDelight 생성 public 타입의 ObjC 노출 차단)
- [C2 전반부 리뷰 후속 보완 (2026-10-07)](c2-first-half-review-followup-2026-10-07.md) — Fake 재분석 병합, 상품 ID 정규화, 정책 평가 되먹임 제거
- [C2 의존성과 Swift ABI 호환성 (2026-10-07)](c2-dependency-compatibility-2026-10-07.md) — 후보/선택 버전, actual-use spike, Flow·suspend·callback 관문

- [C1 리뷰 수정과 검증 (2026-10-06)](c1-review-2026-10-06.md) — 입력·전환 생명주기·접근성 보완, CI와 글꼴 용량 검토
- [iOS 라우터 spike 결과 (2026-10-05)](ios-router-spike-2026-10-05.md) — `NavigationStack` 없는 자체 라우터 진행 판단, 끌어서 뒤로는 window 수준 UIKit pan

추가 결정은 `ADR-번호-제목.md` 형식으로 이 폴더에 기록한다.
