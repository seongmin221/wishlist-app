# B8 후보 이동과 삭제 정책

> 2026-10-24 · 이 세션 사용자 답변으로 확정

- 목적 추가 후보는 ACTIVE이며 PROCESSING이 아닌 상품이다. READY/PARTIAL/FAILED_RETRYABLE/FAILED_TERMINAL 및 수동 완료 상품을 허용한다. 상품명이 없어도 허용한다. 현재 목적 소속은 제외한다.
- 원자 이동은 요청당 서로 다른 상품 1~100개다. 한 상품이라도 version/소속/상태가 충돌하면 전부 거절한다.
- 삭제 확정은 DELETE JSON body의 expectedVersion/impactToken을 사용한다. 전체 영향 상태의 fingerprint가 바뀌면 재확인 409다. 시간 만료는 추가하지 않는다.
- 성공한 같은 token/version의 삭제 재전송은 204다. 이동 key와 삭제 성공 이력은 계정 수명 동안 보존한다.
- 후보 추가 초기 필터·필터 전환 시 선택 유지 정책은 C5로 이관한다. 서버는 요청의 명시 필터만 적용하고 클라이언트 선택 상태를 저장하지 않는다.

기존 [구매 후보 정리](../../../../product/organize-candidates.md)의 사용자 목적 해제 우선·PROCESSING 수정 금지·category와 목적의 독립성을 유지한다. [설계](../../../../superpowers/specs/2026-10-24-b8-candidate-move-deletions-design.md)의 cursor/receipt/잠금 방식은 위 제품 정책을 구현하기 위한 기술 선택이다.
