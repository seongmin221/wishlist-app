# ADR-005: WishlistItem 상태와 API 경계를 분리한다

> 상태: **확정** · 날짜: 2026-09-13 · 영역: **클라이언트·서버**

## 맥락

공유 URL은 로그인과 네트워크 상태에 따라 기기에만 머물 수 있고, 서버에 전달된 뒤에는 비동기 분석·재시도·직접 보완·검토·삭제를 거친다. 같은 요청의 네트워크 재전송은 중복 생성을 막아야 하지만 사용자가 같은 URL을 다시 공유하는 행위는 새 저장으로 허용해야 한다.

상품 수 제한 없이 전체 목록을 매번 조회하면 사용자 데이터가 늘수록 DB, 응답 크기와 로컬 병합 비용이 증가한다. 반면 MVP에서 완전한 증분 동기화 protocol을 도입하면 tombstone, cursor 수명과 충돌 처리 범위가 크게 늘어난다.

## 선택지

1. 로컬 대기부터 서버 상품까지 하나의 상태 enum과 전체 목록 조회로 처리
2. 로컬 제출과 서버 상품을 분리하고 독립 상태 축, idempotent API와 anchor window 조회 사용
3. 모든 상태 변경을 event sourcing으로 기록하고 전역 증분 동기화 제공

## 결정

- 기기 전송 명령인 `LocalSubmission`과 서버 사용자 자원인 `WishlistItem`을 분리한다.
- `WishlistItem`의 분석, 검토와 생명주기를 독립된 상태 축으로 관리한다.
- 공유 시 생성한 `clientSubmissionId`를 사용자 범위 Idempotency-Key로 사용한다. URL은 중복 요청 판단 기준으로 사용하지 않는다.
- 재분석은 별도 analysis generation을 만들고, Worker는 현재 generation과 활성 생명주기가 일치할 때만 결과를 반영한다.
- 일반 변경은 optimistic concurrency를 적용하고 사용자의 삭제는 Worker 갱신보다 우선한다.
- 서버가 홈의 `requiredAction`을 계산하고 KMP는 기기의 로컬 대기 항목을 합성한다.
- 앱 신규 실행은 최신 첫 window를 조회한다. BG에서 FG로 복귀하거나 사용자가 새로고침하면 stable anchor 주변 앞뒤 20개를 갱신하고 위치를 유지한다.
- 앱 프로세스 종료 뒤 viewport는 복원하지 않는다.
- MVP에서 push, realtime, polling, 전역 증분 sync cursor와 오프라인 편집 명령 큐는 제공하지 않는다.

세부 모델과 API는 [WishlistItem 상태 모델과 API 계약](../architecture/wishlist-item-state-api.md)을 따른다.

## 이유와 trade-off

독립 상태 축은 `PARTIAL`이면서 수동 완료되고 검토는 확정된 상태처럼 실제로 함께 존재할 수 있는 조건을 조합 상태 폭증 없이 표현한다. LocalSubmission을 분리하면 서버 생성 전 전송 오류와 생성 후 분석 오류가 섞이지 않는다. Idempotency-Key는 응답 유실에 안전하면서도 같은 URL의 의도적 중복 저장을 허용한다.

anchor window는 전체 목록 비용을 제한하고 화면 위치를 유지한다. 대신 현재 불러오지 않은 범위에서 다른 기기가 변경한 데이터는 그 window를 다시 조회하기 전까지 로컬 캐시에 남을 수 있다. MVP는 전역 정합성보다 현재 화면의 최신성과 구현 범위를 우선한다.

## 재검토 조건

- 다중 기기의 화면 밖 변경이 반복적으로 잘못 노출될 때 전역 변경 cursor와 tombstone 동기화를 검토한다.
- 오프라인 편집이 필요해지면 명령 큐와 충돌 해결 방식을 별도로 결정한다.
- 사용자별 상품 수와 조회 부하 측정 결과에 따라 window 크기와 index를 조정한다.
- 분석 완료 인지가 늦다는 사용자 문제가 확인되면 polling, realtime과 push를 다시 비교한다.
