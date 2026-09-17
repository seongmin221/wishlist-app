# QA-SRV-004: 전체 목록 대신 anchor 주변을 조회하는 이유

> 상태: **학습 Q&A** · 날짜: 2026-09-13

## 질문

전체 상품 목록을 새로고침하면 상품 수가 많을 때 과부하가 생기지 않는가? 백그라운드 복귀 시 화면 위치의 항목 번호와 offset을 저장하고 주변 항목만 받는 방식은 어떤가?

## 짧은 답변

전체 목록 조회 비용은 상품 수에 따라 계속 증가하므로 현재 화면 주변만 받는 window pagination이 적합하다. 다만 항목 번호와 절대 scroll offset은 목록 앞의 삽입·삭제와 가변 카드 높이 때문에 같은 상품을 가리키지 못할 수 있다.

화면이 살아 있는 동안 `anchorItemId`, 서버의 불투명한 정렬 cursor와 카드 내부 offset을 보관한다. BG에서 FG로 복귀하거나 사용자가 새로고침하면 anchor 앞뒤 20개를 받아 stable item ID로 병합한 뒤 같은 상품의 내부 offset으로 돌아간다. anchor 상품이 이동·삭제됐다면 기존 정렬 위치와 가장 가까운 현재 상품을 사용한다.

앱 프로세스를 새로 시작한 경우에는 이전 viewport를 복원하지 않고 최신 첫 window부터 조회한다.

## cursor의 역할

여기서 cursor는 항목 번호가 아니라 `서버 생성 시각 + item ID` 같은 안정적인 정렬 키를 서버만 해석할 수 있게 표현한 값이다. 전역 변경 내역을 동기화하는 sync cursor와는 목적이 다르며, MVP에서는 전역 증분 동기화는 보류한다.

## 관련 설계

- [WishlistItem 상태 모델과 API 계약](../../../architecture/wishlist-item-state-api.md)
- [ADR-005: WishlistItem 상태와 API 경계](../../../history/architecture/ADR-005-wishlist-item-state-api.md)
