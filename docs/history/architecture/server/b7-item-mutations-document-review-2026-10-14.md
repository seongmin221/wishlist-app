# B7 상품 사용자 변경 문서 검토

> 2026-10-14 · 3차 정적 검토 완료 · 기존 지적 5건 반영 확인 · 추가 지적 없음 · 구현 검증 아님

[확정 결정](../../product-planning/mvp/decisions/b7-item-mutation-policy-2026-10-14.md), [설계](../../../superpowers/specs/2026-10-14-b7-item-mutations-design.md), [Task 1~10 계획](../../../superpowers/plans/2026-10-14-b7-item-mutations.md)과 INDEX를 기존 서버 코드와 대조했다. 일곱 확정 결정을 변경할 필요는 발견하지 않았다. 원본 문서는 수정하지 않았다.

## 1차 보완 제안 — 반영 확인

### 1. P2 — Task 5의 출처 기대를 같은 값 재전송 규칙과 맞추기

Task 5는 “함께 보낸 category·목적 USER”라고 하지만 설계 2절과 ITEM-08 계약은 실제로 바뀐 값만 USER로 만들고 같은 AI 값은 출처를 유지한다. Task 2도 같은 값의 출처 유지와 ITEM-07 이름·category만 예외임을 명시한다. AI category·목적을 그대로 포함한 CONFIRM/DEFER를 Task 5대로 구현하면 공통 변경 계산과 충돌한다.

Task 5를 “변경한 category·목적은 USER, 같은 값은 기존 출처 유지, 목적 null은 명시적 해제 규칙 적용”으로 고치고 같은 AI 값 재전송 테스트를 추가한다.

### 2. P2 — deletionImpact의 snapshot 보장 방법과 경합 테스트 명시

설계 deletionImpact 절은 상세와 같은 transaction·snapshot을 요구하지만 Task 6은 보장 방법과 동시 변경 테스트를 지정하지 않는다. 현재 `WishlistItemRepository.findOwned(ownerId,itemId)`는 connection에서 단일 조회하며, `inTransaction()`은 `readOnly=true`일 때만 REPEATABLE READ를 설정한다. 기본 transaction에서 상세와 impact를 두 SQL로 읽는 것만으로는 같은 snapshot이 보장되지 않는다.

상세가 목적 A 연결을 읽은 뒤 다른 기기가 해당 상품을 B로 이동하면, 뒤의 A 후보 수에서는 해당 상품이 이미 빠진다. 이전 목적 기준으로 `candidateCount - 1`을 계산하면 잔여 수가 틀리거나 음수가 될 수 있다.

ITEM-03은 `inTransaction(readOnly=true)` 안에서 같은 connection으로 상세와 impact를 읽거나 단일 SQL로 구성하도록 Task 6에 명시한다. 두 조회 사이 목적 이동·삭제를 끼워 넣는 경합 테스트를 추가한다. owner 잠금으로 보호되는 생성·변경 응답과 구분하며, media 경합을 다루는 쓰기 transaction 전체의 격리 수준을 변경하지 않는다.

### 3. P2 — 재분석 dispatch 실패 처리에서 취소 예외 제외

설계 ITEM-06 7단계와 Task 7의 “실패해도 성공 응답”은 취소 예외를 구분하지 않는다. 기존 `CreateWishlistItemService`는 `CancellationException`을 다시 던지고 일반 발행 실패만 경고 로그로 처리한다. Task 8도 취소 전파를 요구한다. 일반 예외를 모두 삼키면 취소까지 성공으로 처리해 기존 생성 경로 및 Task 8과 어긋난다.

“일반 발행 실패는 로그 후 성공, CancellationException은 전파; commit된 상품·job·outbox·receipt는 유지”를 설계와 Task 7에 명시한다. 일반 발행 예외와 취소를 별도 테스트하고 취소 후 같은 key 재전송이 replay로 복구되는지 확인한다.

## 2차 보완 제안 — 반영 확인

수정된 spec의 ITEM-08·ITEM-06·deletionImpact 계약과 계획 Task 5~7에서 1차 지적 3건의 반영을 확인했다. 아래 두 항목은 계획 Task 6의 테스트 기대를 추가로 보완하기 위한 제안이다.

### 4. P2 — snapshot 경합 테스트에서 후보 수 변화가 상쇄됨

Task 6은 읽기를 멈춘 동안 해당 상품을 A→B로 이동하고 A에 다른 후보 하나를 추가한 뒤, 이동 전 candidateCount를 기대한다. A 후보 수가 처음 3이었다면 이동 후 2, 추가 후 다시 3이다. impact가 첫 조회에서 읽은 목적 A를 기준으로 count를 읽는 구현은 READ COMMITTED/autocommit이어도 같은 3과 remainingCount 2를 반환할 수 있다. 목적 ID·이름도 같다면 현재 기대만으로 snapshot 오류를 검출하지 못한다.

이동만 하는 테스트(3→2), 추가만 하는 테스트(3→4)를 분리하거나 최종 후보 수가 초기 수와 다르게 fixture를 구성한다. 잘못된 구현과 올바른 구현에 동일한 pause hook·latch를 적용해 조회 사이 commit을 보장한다. 읽기 격리 수준을 READ COMMITTED로 낮춘 구현이 반드시 실패하는지 확인한다.

### 5. P2 — 삭제 테스트의 취소 대상이 설계보다 넓음

Task 6은 READY·PROCESSING 삭제에서 “job 전부 CANCELLED”를 기대하지만 spec ITEM-05는 `GENERAL_PENDING/RUNNING`, `BROWSER_PENDING/RUNNING`만 취소한다. 기존 Worker finish는 READY 결과의 job을 COMPLETE, 부분 결과를 PARTIAL, 실패 결과를 FAILED로 끝낸다. 따라서 READY 상품의 COMPLETE job까지 CANCELLED로 바꾸는 구현은 설계와 다르고 완료·실패 이력도 변경한다.

Task 6을 “진행 중 job만 CANCELLED·실행 identity 해제, COMPLETE/PARTIAL/FAILED 및 기존 CANCELLED는 유지”로 구체화한다. 과거 generation의 terminal job과 현재 진행 중 job을 함께 둔 fixture에서 terminal 상태·시도 횟수·진단 값 유지, 진행 중 job의 token·lease·claimed version 해제를 확인한다.

## 3차 검토 결과

- 계획 Task 6의 snapshot 테스트가 이동만·추가만 하는 두 사례로 분리됐으며, 각 사례에서 잘못된 snapshot의 후보 수가 기대값과 다르게 됨을 확인했다.
- 설계 ITEM-05와 계획 Task 6이 진행 중 job만 취소하고 terminal job의 stage·진단을 유지하도록 일치하는 것을 확인했다.
- 1차 출처 규칙·REPEATABLE READ 상세 조회·dispatch 취소 전파 보완도 유지됐다.
- 확정 결정, 명령별 입력·오류·no-op·membership·media·재분석 replay 계약과 Task 1~10을 다시 대조한 결과, 구현 전에 추가로 수정해야 할 지적은 발견하지 못했다. 실제 구현의 정확성은 계획의 테스트와 코드 리뷰에서 확인한다.

## 확인 범위

- 일곱 결정과 spec 요약의 대응, V19 전제, allowedActions, Worker 보호, media 잠금·정리 규칙을 대조했다.
- decisions/specs/plans INDEX의 대상 문서 등록을 확인했다.
- 서버 코드 변경과 Gradle·DB 테스트 실행은 하지 않았다. 위 항목은 구현 전 문서와 테스트 기대를 구체화하는 제안이다.
