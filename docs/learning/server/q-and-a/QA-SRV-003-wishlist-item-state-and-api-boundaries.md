# QA-SRV-003: 왜 LocalSubmission과 WishlistItem 상태를 분리하는가

> 상태: **학습 Q&A** · 날짜: 2026-09-13

## 질문

WishlistItem 상태 모델과 API를 왜 로컬 제출, 독립 상태 축과 행동별 endpoint로 나누어 설계하는가?

## 짧은 답변

서버 생성 전의 전송 실패와 생성 후의 상품 분석 실패는 소유권과 복구 방법이 다르기 때문이다. 기기의 `LocalSubmission`은 URL을 서버에 전달하려는 명령이고, 서버의 `WishlistItem`은 사용자 계정에 귀속된 상품 자원이다.

분석 결과, 사용자 검토, 활성·아카이브·삭제와 수동 보완도 동시에 존재할 수 있으므로 하나의 상태 enum으로 합치지 않는다. 독립 상태 축으로 저장하고 서버가 허용되는 조합과 전이를 검사하면 조합 상태가 폭증하지 않는다.

재분석·직접 보완·검토는 단순 필드 변경 이상의 규칙을 동반하므로 일반 `PATCH`에 맡기지 않고 행동별 API로 표현한다. 이렇게 하면 클라이언트가 내부 필드를 조합하지 않아도 서버가 일관된 전이를 보장한다.

## 같은 URL과 Idempotency-Key가 다른 이유

응답이 유실돼 같은 공유 요청을 재전송할 때는 한 항목만 생성돼야 한다. 하지만 사용자가 같은 URL을 나중에 다시 공유하면 새 항목을 만들 수 있어야 한다. 따라서 URL이 아니라 공유 행위마다 생성한 `clientSubmissionId`를 Idempotency-Key로 사용한다.

## 관련 설계

- [WishlistItem 상태 모델과 API 계약](../../../architecture/wishlist-item-state-api.md)
- [ADR-005: WishlistItem 상태와 API 경계](../../../history/ADR-005-wishlist-item-state-api.md)
