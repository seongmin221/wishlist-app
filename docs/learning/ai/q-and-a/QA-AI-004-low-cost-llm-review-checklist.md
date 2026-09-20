# 저비용 LLM 분류 설계에서 무엇을 확정해야 하는가

## 질문

저비용 외부 LLM으로 상품 category와 purpose를 분류할 때, 호출 수·캐시·오류·비용·개인정보에서 어떤 설계 결정을 빠뜨리면 안 되는가?

## 답변

- category와 purpose를 한 요청으로 받을지 두 요청으로 나눌지 명확히 정한다. 이 프로젝트는 저비용 MVP에서 두 결과를 하나의 구조화 응답으로 받는다.
- `Product` metadata 캐시와 AI 결과 캐시를 혼동하지 않는다. 현재 `Product` 캐시는 추출 metadata만 재사용하며, 캐시 적중만으로 사용자별 category·purpose 판단을 생략하지 않는다. MVP는 별도 AI 결과 캐시를 도입하지 않는다.
- category에는 `id: null`과 제한된 abstain 사유를 허용한다. 근거 없는 분류보다 `PARTIAL`과 사용자의 직접 선택이 안전하다.
- Structured Outputs라도 refusal과 incomplete를 별도로 처리한다. timeout·429·5xx만 재시도하고, refusal·content filter·잘못된 ID·인증 또는 요청 구성 오류는 재시도하지 않는 결과 경로와 운영 알림을 정의한다.
- 요청 때 후보 ID와 그 버전을 기록하고, 반영 transaction에서 item generation·소유자·활성 상태·후보의 현재 유효성을 다시 검사한다. 오래된 결과는 반영하지 않는다.
- 입력·출력 토큰, description 길이, purpose 후보 수와 문맥 수를 제한하고, 일·월 예산은 알림뿐 아니라 초과 시 AI 호출을 멈추는 정책까지 정한다. 이 프로젝트는 4,096/256 token, 목적 10개, 일 1,000원·월 10,000원을 사용한다.
- URL query 제거, `store: false`, raw prompt/response 자체 로그 미보존, 개인정보 처리 고지를 설계에 포함한다. `store: false`는 Responses의 application state 보존을 제어하지만 abuse monitoring 로그 정책까지 없애지는 않는다.
- 평가는 최초 출시와 model·prompt·taxonomy 변경 전마다 수행한다. category 정확도뿐 아니라 purpose 오연결, abstain 품질, 저품질 metadata와 prompt injection 사례를 포함한다.

## 근거

- [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs): refusal과 incomplete 응답은 schema와 별도로 처리해야 한다.
- [OpenAI 데이터 제어](https://developers.openai.com/api/docs/guides/your-data): Responses의 application state와 abuse monitoring 로그 보존 정책을 구분한다.
- [WishlistItem 상태 모델과 API 계약](../../../architecture/wishlist-item-state-api.md)
- [OpenAI 저비용 모델 기반 분류 설계](../../../superpowers/specs/2026-09-19-openai-low-cost-classification-design.md)
- [ADR-011: AI 분류 호출·상태·보호 정책](../../../history/architecture/ai/ADR-011-ai-classification-control-policy.md)
