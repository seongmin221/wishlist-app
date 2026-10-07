# 서버 연동 상태

> 2026-10-07 C2 Task 8 기준. 서버 계약 기준은 병합된 B2 `1c6d949081d47ddb28e60c00eda44b4aa0d91fb0`이다. 서버 구현 상태는 저장소의 API inventory를 대조한 값이며 이 task에서 서버 테스트를 재실행하지 않았다.

C2 Remote 대상은 **ITEM-01·ITEM-03만**이다. Task 5는 Create/Get Fake와 seed 전용 `CatalogRepository`를 구현했고, 공통 계약 harness의 같은 7개 시나리오를 Fake에서 실제 실행했다. Task 6b는 ITEM-01·03의 Remote(`RemoteItemRepository`)와 MockEngine 기반 공통 계약 7개를 구현·실행했다(fixture는 서버 develop `1c6d949`의 DTO·mapper에서 손으로 옮겼다). 모든 행의 실서버 검증은 미실행이다.

`CatalogRepository`의 category/purpose/item 조회는 화면 개발용 시드 경계다. CAT-01의 SELECT/BROWSE·count, PUR-01의 요약, ITEM-02의 cursor/anchor wire 계약 완료를 의미하지 않는다. BoardDisplayMetadata의 69개 chip 합계·목적 후보 숫자는 이미지 비교 fixture이고 실제 저장소 집계에 사용하지 않는다. Task 8 runtime 조립 기준 앱 backend는 아래 표의 `C2 앱 backend` 열과 같다. DEBUG 앱은 ITEM-01·03만 FAKE이고 나머지는 UNAVAILABLE이며, RELEASE 앱은 37개 모두 UNAVAILABLE이다. 두 앱 모두 REMOTE가 없다(Remote는 MockEngine 테스트에서만 ITEM-01·03을 REMOTE로 조립). 인증과 실서버 연결은 후속 단계다.

FakeStore가 단독으로 생성 idempotency·최신 snapshot 재전송·삭제 tombstone·version CAS·직접 보완·검토·재분석 generation을 관리한다. `FakeControls`의 edit/manual-completion/review/delete/reanalyze는 개발용 상태 제어이며 ITEM-04~08의 공개 wire 저장소 구현이 아니다. 삭제 반복과 늦은 분석 무시를 포함한 상태 규칙 및 계정 변경 중 지연 요청을 host/Native에서 검증했다. 카테고리/목적 CRUD·archive/restore·목적 삭제는 구현하지 않았다.

| API ID | 서버 B단계·상태 | 클라이언트 C단계 | C2 앱 backend (DEBUG / RELEASE) | Fake | Remote | MockEngine 검증 | 실서버 검증 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ITEM-01 | B1 완료 | C2 기반 → C3 | FAKE / UNAVAILABLE | 구현 · 공통 계약 7개 통과 | 구현 · `RemoteItemRepository` | 공통 계약 7개 + Remote 집중 테스트 통과 | 미실행 |
| ITEM-02 | B4 미구현 | C5/C6 → C7 | UNAVAILABLE / UNAVAILABLE | 시드 조회 구현 · wire projection 미구현 | 미구현 · C5/C6 → C7 | 미실행 | 미실행 |
| ITEM-03 | B1 완료 | C2 기반 → C4 | FAKE / UNAVAILABLE | 구현 · 공통 계약 7개 통과 | 구현 · `RemoteItemRepository` | 공통 계약 7개 + Remote 집중 테스트 통과 | 미실행 |
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

근거: [서버 API 목록](../server/mvp-api-inventory.md), [서버 구현 순서](../server/mvp-api-implementation-order.md), [B1 생성·상세](../server/wishlist-item-read-api.md), [B2 카테고리 계약](../server/category-management-api.md), [클라이언트 로드맵](../../superpowers/specs/2026-10-05-client-implementation-roadmap-design.md), [C2 실행 계획](../../superpowers/plans/2026-10-07-client-c2-kmp-core.md).
