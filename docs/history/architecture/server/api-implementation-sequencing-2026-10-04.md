# MVP API 구현 순서 결정

> 날짜: 2026-10-04 · 범위: 구현 순서 계획, 서버 코드 변경 없음

## 요청

사용자가 와이어프레임·기획·기능 문서에서 도출한 API별 구현 순서를 묶음 단위로 결정하고, 이후 이 순서대로 구현할 수 있게 정밀하게 정리하도록 요청했다.

## 결정

[구현 순서 계획](../../../architecture/server/mvp-api-implementation-order.md)에 제품 API 37개를 각각 한 묶음에 최초 배정했다. 내부 작업 3개와 health 1개는 별도로 다뤘다.

최소 안전 기반 → 상품 상세/생성 → category → purpose → 목록/홈 → 분석/복구 → 이미지 → 상품 변경 → 후보 이동/영향 삭제 → 중복/cache → archive → 출시 검증 순서다.

상품 변경이 존재하지 않는 category/purpose/media를 참조하는 재작업을 줄이기 위해 참조 자원을 앞당겼다. 기존 개요에서 마지막에 있던 browser/maintenance runtime은 재분석 전에 연결하며, 오래된 Worker 결과 차단은 첫 기반에서 보완한다. 영향 확인 삭제와 archive는 각각 하위 묶음과 원자성·동시 변경 검증 기준을 둔다.

## 완료 기준과 한계

각 묶음의 계약 확정 시점·주요 변경 파일·검증 사례·미결정 정책 해결 시점을 기록했다. 이전 API의 후속 확장도 명시해 조회 API의 최초 구현과 전체 제품 기능 완료를 구분한다.

이 기록은 미결정 제품 정책이나 상세 DTO를 자동 확정하지 않는다. 각 묶음 시작 전에 정확한 코드 작업 계획을 작성하고 실제 PostgreSQL 검증과 회귀 결과를 확인한다. 배포·신규 API 실행 테스트는 이번 문서 작성에서 수행하지 않았다.

## B0 세부 계획 후속

사용자의 superpowers 계획 요청에 따라 [B0 상세 구현 계획](../../../superpowers/plans/2026-10-04-b0-server-foundation.md)을 작성했다. 기존 코드의 processor/classifier/budget/reconciler 쓰기를 대조해 9개 Task, 타입·파일·실패/통과 검증을 기록했다. 같은 generation의 옛 실행과 실제 AI 비용 정산, V7 upgrade 및 Worker drain 순서를 포함했다.

현재 디자인 `d23c857`의 추가 diff는 색·표시 규칙이며 B0 제품 상태에는 변화가 없다. Docker daemon 연결 실패를 확인해 실제 DB 검증의 착수 조건으로 기록했다. 이번 작업은 실행 전 계획 작성이며 product code·migration·테스트를 변경하거나 신규 검증 통과를 주장하지 않았다.
