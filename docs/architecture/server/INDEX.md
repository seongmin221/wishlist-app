# Server Architecture Index

| 주제 | 문서 |
| --- | --- |
| modular monolith, API, Worker 경계 | [overview.md](overview.md) |
| Product metadata extraction pipeline | [extraction-pipeline.md](extraction-pipeline.md) |
| 와이어프레임 기반 제품 API·데이터 모델·구현 순서 제안 | [mvp-product-api-design.md](mvp-product-api-design.md) |
| 전체 화면·행동과 API 37개 대응, 최소 입출력·현재 구현 상태 | [mvp-api-inventory.md](mvp-api-inventory.md) |
| API 37개 구현 묶음·실행 순서·선행 조건·통과 기준 | [mvp-api-implementation-order.md](mvp-api-implementation-order.md) |
| 완료된 B0 구현 계획·변경 파일·실패/통과 검증 | [B0 구현 계획](../../superpowers/plans/2026-10-04-b0-server-foundation.md) |
| 상품 상태·허용 행동·공통 DTO·공개 오류 계약 | [wishlist-item-state-api.md](../wishlist-item-state-api.md) |
| 상품 상태·claim·중간/최종 쓰기 보호·lease 복구·AI 예산 경계 | [wishlist-state-persistence.md](wishlist-state-persistence.md) |
| B1 생성·재전송·상세 projection·공유 시각·공개 실패·지정 event 발행 | [wishlist-item-read-api.md](wishlist-item-read-api.md) |
| B1 구현·TDD·migration/경합·전체 회귀 결과 | [B1 구현 기록](../../history/architecture/server/b1-item-read-and-create-2026-10-06.md) |
| 요청 IO·역할별 DB pool·client 재사용·종료 gate | [runtime-resources.md](runtime-resources.md) |
| B0 전체 회귀·쓰기 감사·legacy rollout·B1 인계 | [완료 기록](../../history/architecture/server/b0-foundation-implementation.md) |
| B5 오래된 PENDING·queue 소진·미발행 outbox 복구 설계 | [analysis-pending-recovery.md](analysis-pending-recovery.md) |
| B0 외부 리뷰의 실행 시간·동시 발행·오류·복구 보완 | [보완 기록](../../history/architecture/server/b0-review-hardening-2026-10-05.md) |
| B0 후속 리뷰의 queue claim·LLM 예산/마감·최종 version 복구·DB 시각 | [후속 기록](../../history/architecture/server/b0-followup-review-2026-10-06.md) |
