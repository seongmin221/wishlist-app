# 서버 연동 상태

> 2026-10-07 C3 Task 8(최종 검증) 기준. API별 backend는 C2 Task 8 조립 결과이며 C3에서 바뀌지 않았다(C3는 ITEM-01·03을 실제 기능 경로에서 쓰기 시작했다). 서버 계약 기준은 병합된 B2 `1c6d949081d47ddb28e60c00eda44b4aa0d91fb0`이다. 서버 구현 상태는 저장소의 API inventory를 대조한 값이며 이 task에서 서버 테스트를 재실행하지 않았다.

C2 Remote 대상은 **ITEM-01·ITEM-03만**이다. Task 5는 Create/Get Fake와 seed 전용 `CatalogRepository`를 구현했고, 공통 계약 harness의 같은 7개 시나리오를 Fake에서 실제 실행했다. Task 6b는 ITEM-01·03의 Remote(`RemoteItemRepository`)와 MockEngine 기반 공통 계약 7개를 구현·실행했다(fixture는 서버 develop `1c6d949`의 DTO·mapper에서 손으로 옮겼다). 모든 행의 실서버 검증은 미실행이다. 최종 로컬 검증 결과는 [C2 최종 검증 기록](../../history/architecture/client/c2-final-verification-2026-10-07.md)에 있다.

`CatalogRepository`의 category/purpose/item 조회는 화면 개발용 시드 경계다. CAT-01의 SELECT/BROWSE·count, PUR-01의 요약, ITEM-02의 cursor/anchor wire 계약 완료를 의미하지 않는다. BoardDisplayMetadata의 69개 chip 합계·목적 후보 숫자는 이미지 비교 fixture이고 실제 저장소 집계에 사용하지 않는다. Task 8 runtime 조립 기준 앱 backend는 아래 표의 `앱 backend` 열과 같다. DEBUG 앱은 ITEM-01·03만 FAKE이고 나머지는 UNAVAILABLE이며, RELEASE 앱은 37개 모두 UNAVAILABLE이다. 두 앱 모두 REMOTE가 없다(Remote는 MockEngine 테스트에서만 ITEM-01·03을 REMOTE로 조립). 인증과 실서버 연결은 아래 "인증 연결" 단계다.

## C3: ITEM-01 사용 경로

- **경로:** 공유 수신(Android Activity 즉시, iOS 앱의 inbox import) → `LocalStore`에 미귀속 또는 현재 계정 귀속 `PENDING` 저장 → `SubmissionCoordinator` flush(로그인·공유·foreground·네트워크 복구·당겨서 새로고침 신호, single-flight) → `prepareFlush`로 binding을 POST 전에 commit → 행마다 `SUBMITTING` 기록 → ITEM-01 → 성공이면 `accept`(캐시 upsert + 행 삭제 한 transaction). 같은 로컬 공유의 재전송은 같은 UUID key이고 서버(지금은 Fake)의 멱등 replay가 같은 항목을 돌려준다. 오류 분류는 C3-D8 표([KMP 구조](kmp.md#공유-수신전송-조정기submissioncoordinator)).
- **snapshot-aware create(Ruling 11):** coordinator는 공개 `CreateItemRepository`가 아니라 Kotlin internal `SnapshotCreateItemRepository.create(command, expected)`를 flush 시작의 `SessionSnapshot`으로 부른다. Fake는 현재 session이 `expected`가 아니면 owner 저장소를 건드리지 않고, Remote는 `AuthenticatedTransport`가 보내기 전·token 뒤에 같은 비교를 해 `SESSION_CHANGED`로 끝낸다. 그래서 `SUBMITTING` commit과 POST 사이에 계정이 바뀌어도 A의 key가 B의 token으로 가지 않는다(Review Focus 1).
- **ITEM-03:** refresh 때 캐시의 `PROCESSING` 항목마다 GET(캐시 decorator가 갱신)으로 "분류 중" 줄을 정리한다. DEBUG는 `DebugAnalysisDriver`가 refresh 전에 5초 이상 된 Fake 항목을 완료한다.
- **실행 범위:** 앱은 DEBUG Fake만 쓴다. Remote ITEM-01은 C2의 MockEngine 계약 테스트 그대로이며 C3 coordinator와 Remote를 함께 실서버로 돌린 적은 없다. RELEASE는 ITEM-01이 UNAVAILABLE이라 로그인 전 로컬 저장만 동작한다.

## 인계: "인증 연결" 단계

C3는 fake 인증만 쓴다(C3-D2). Apple Developer 가입 뒤 별도 "인증 연결" 단계에서 다음을 한다(로드맵에 단계 행을 추가할지는 사용자 확인 중).

- Firebase 프로젝트와 Apple·Google 로그인을 `AuthFacade` 뒤에 연결하고 `PlatformTokenSource`가 실제 ID token을 준다.
- DEBUG ITEM-01·03을 REMOTE로 바꿔 local 서버 + Firebase Auth Emulator로 실서버 검증한다(아래 표 "실서버 검증" 열). [KMP 알려진 한계](kmp.md#c3에서-생긴-항목)의 ITEM-01 `NOT_FOUND`·`SUBMISSION_ITEM_MISMATCH` 처리를 실제 응답으로 다시 본다.
- iOS 확장의 background URLSession 직접 전송을 켠다(Keychain 공유 access group, token 만료 시 앱 전송으로 대체, 401에서 inbox 파일 보존). [ADR-030](../../history/architecture/client/ADR-030-share-receipt-mode.md).
- 개발자 팀 서명, 실기기 공유 확장 확인.

## Fake 상태 규칙

FakeStore가 단독으로 생성 idempotency·최신 snapshot 재전송·삭제 tombstone·version CAS·직접 보완·검토·재분석 generation을 관리한다. `FakeControls`의 edit/manual-completion/review/delete/reanalyze는 개발용 상태 제어이며 ITEM-04~08의 공개 wire 저장소 구현이 아니다. 삭제 반복과 늦은 분석 무시를 포함한 상태 규칙 및 계정 변경 중 지연 요청을 host/Native에서 검증했다. 카테고리/목적 CRUD·archive/restore·목적 삭제는 구현하지 않았다.

## API별 상태

| API ID | 서버 B단계·상태 | 클라이언트 C단계 | 앱 backend (DEBUG / RELEASE, C2·C3 같음) | Fake | Remote | MockEngine 검증 | 실서버 검증 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ITEM-01 | B1 완료 | C2 기반 → C3 사용(공유 전송) | FAKE / UNAVAILABLE | 구현 · 공통 계약 7개 통과 · C3 snapshot create | 구현 · `RemoteItemRepository` | 공통 계약 7개 + Remote 집중 테스트 통과 | 미실행 |
| ITEM-02 | B4 미구현 | C5/C6 → C7 | UNAVAILABLE / UNAVAILABLE | 시드 조회 구현 · wire projection 미구현 | 미구현 · C5/C6 → C7 | 미실행 | 미실행 |
| ITEM-03 | B1 완료 | C2 기반 → C3 refresh 사용 → C4 | FAKE / UNAVAILABLE | 구현 · 공통 계약 7개 통과 | 구현 · `RemoteItemRepository` | 공통 계약 7개 + Remote 집중 테스트 통과 | 미실행 |
| ITEM-04 | B7 미구현 | C8 | UNAVAILABLE / UNAVAILABLE | 상태 규칙 구현 · FakeControls 전용 · wire 명령 미구현 | 미구현 · C8 | 미실행 | 미실행 |
| ITEM-05 | B7 미구현 | C8 | UNAVAILABLE / UNAVAILABLE | 상태 규칙 구현 · FakeControls 전용 · wire 명령 미구현 | 미구현 · C8 | 미실행 | 미실행 |
| ITEM-06 | B7 미구현 | C8 | UNAVAILABLE / UNAVAILABLE | 상태 규칙 구현 · FakeControls 전용 · wire 명령 미구현 | 미구현 · C8 | 미실행 | 미실행 |
| ITEM-07 | B7 미구현 | C8 | UNAVAILABLE / UNAVAILABLE | 상태 규칙 구현 · FakeControls 전용 · wire 명령 미구현 | 미구현 · C8 | 미실행 | 미실행 |
| ITEM-08 | B7 미구현 | C8 | UNAVAILABLE / UNAVAILABLE | 상태 규칙 구현 · FakeControls 전용 · wire 명령 미구현 | 미구현 · C8 | 미실행 | 미실행 |
| HOME-01 | B4 미구현 | C7 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C7 | 미실행 | 미실행 |
| HOME-02 | B4 미구현 | C7 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C7 | 미실행 | 미실행 |
| DUP-01 | B9 미구현 | C10 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C10 | 미실행 | 미실행 |
| DUP-02 | B9 미구현 | C10 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C10 | 미실행 | 미실행 |
| CAT-01 | B2 완료 | C5 | UNAVAILABLE / UNAVAILABLE | 시드 조회 구현 · wire projection 미구현 | 미구현 · C5 | 미실행 | 미실행 |
| CAT-02 | B2 완료 | C5 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C5 | 미실행 | 미실행 |
| CAT-03 | B2 완료 | C5 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C5 | 미실행 | 미실행 |
| CAT-04 | B2 완료 | C5 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C5 | 미실행 | 미실행 |
| CAT-05 | B8 미구현 | C9 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C9 | 미실행 | 미실행 |
| CAT-06 | B8 미구현 | C9 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C9 | 미실행 | 미실행 |
| PUR-01 | B3 미구현 | C6 | UNAVAILABLE / UNAVAILABLE | 시드 조회 구현 · wire projection 미구현 | 미구현 · C6 | 미실행 | 미실행 |
| PUR-02 | B3 미구현 | C6 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C6 | 미실행 | 미실행 |
| PUR-03 | B3 미구현 | C6 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C6 | 미실행 | 미실행 |
| PUR-04 | B3 미구현 | C6 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C6 | 미실행 | 미실행 |
| PUR-05 | B8 미구현 | C9 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C9 | 미실행 | 미실행 |
| PUR-06 | B8 미구현 | C9 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C9 | 미실행 | 미실행 |
| PUR-07 | B8 미구현 | C9 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C9 | 미실행 | 미실행 |
| PUR-08 | B8 미구현 | C9 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C9 | 미실행 | 미실행 |
| ARC-01 | B10 미구현 | C11 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C11 | 미실행 | 미실행 |
| ARC-02 | B10 미구현 | C11 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C11 | 미실행 | 미실행 |
| ARC-03 | B10 미구현 | C11 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C11 | 미실행 | 미실행 |
| ARC-04 | B10 미구현 | C11 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C11 | 미실행 | 미실행 |
| ARC-05 | B10 미구현 | C11 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C11 | 미실행 | 미실행 |
| ARC-06 | B10 미구현 | C11 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C11 | 미실행 | 미실행 |
| ARC-07 | B10 미구현 | C11 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C11 | 미실행 | 미실행 |
| ARC-08 | B10 미구현 | C11 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C11 | 미실행 | 미실행 |
| ARC-09 | B10 미구현 | C11 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C11 | 미실행 | 미실행 |
| MEDIA-01 | B6 미구현 | C8 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C8 | 미실행 | 미실행 |
| MEDIA-02 | B6 미구현 | C8 | UNAVAILABLE / UNAVAILABLE | 미구현 | 미구현 · C8 | 미실행 | 미실행 |

37개 API를 `ApiId`와 같은 순서로 등록했다(ITEM 8 / HOME 2 / DUP 2 / CAT 6 / PUR 8 / ARC 9 / MEDIA 2). SYS/WORK/OPS는 제품 API 표에 포함하지 않는다.

근거: [서버 API 목록](../server/mvp-api-inventory.md), [서버 구현 순서](../server/mvp-api-implementation-order.md), [B1 생성·상세](../server/wishlist-item-read-api.md), [B2 카테고리 계약](../server/category-management-api.md), [클라이언트 로드맵](../../superpowers/specs/2026-10-05-client-implementation-roadmap-design.md), [C2 실행 계획](../../superpowers/plans/2026-10-07-client-c2-kmp-core.md), [C3 실행 계획](../../superpowers/plans/2026-10-07-client-c3-share-save.md).
