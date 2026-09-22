# ADR-011: AI 분류 호출·상태·보호 정책을 단일 흐름으로 정한다

> 상태: **확정** · 날짜: 2026-09-19 · 영역: **AI·server**

## 맥락

ADR-010은 OpenAI API와 저비용 모델을 MVP 기본값으로 정했지만, category와 purpose의 호출 수, metadata cache의 범위, category를 판단하지 않는 경우, Structured Outputs 예외, stale 결과, 예산과 개인정보 보호의 세부 정책은 정하지 않았다. 이 공백은 호출 비용 증가, 사용자별 후보를 오래된 결과로 덮어쓰는 문제, 근거 없는 분류와 개인정보 노출 위험을 만든다.

## 결정

### 단일 호출과 cache 경계

- Worker는 category와 purpose를 OpenAI Responses API **한 번의** Structured Output으로 요청한다.
- category는 `ASSIGNED` 또는 `ABSTAINED`, purpose는 `ASSIGNED` 또는 `UNASSIGNED`를 명시한다. `ABSTAINED` category는 `id=null`으로 허용한다.
- `Product` cache는 canonical URL의 추출 metadata만 7일간 재사용한다. cache 적중은 사용자별 AI 판단을 생략하지 않는다.
- MVP에는 공용 category나 사용자별 purpose의 별도 LLM 결과 cache를 두지 않는다.

### 상태·오류·stale 결과

- `AI_ABSTAINED`는 `PARTIAL`이며 사용자의 `CATEGORY_ASSIGNMENT` 조치를 요구한다. 목적 미지정은 정상 결과다.
- timeout·네트워크·429·5xx만 최대 3회·30분의 기존 retry 정책을 적용한다.
- refusal, content filter, schema-invalid ID는 `PARTIAL`과 `AI_RESPONSE_UNUSABLE`로 끝낸다. 400·401·403, output token limit의 incomplete, 요청 구성 오류는 `FAILED_TERMINAL`과 운영 알림으로 처리한다.
- 요청 시 후보 ID snapshot, metadata fingerprint와 model/prompt/taxonomy version을 `AnalysisJob`에 기록한다. 반영 transaction은 generation, owner, lifecycle과 후보 유효성을 재검증하고 stale 결과를 폐기한다.

### 비용·개인정보·평가

- 시스템 절대 상한은 입력 4,096 token·출력 256 token이지만, 월 20,000건 release 기본 요청은 입력 1,000 token·출력 80 token으로 더 낮게 제한한다. 이 기본 제한을 넘기는 release는 별도 비용 평가 없이는 허용하지 않는다. 목적 후보는 최근 활성 목적 10개 이하로 제한한다.
- 일별 1,000원·월별 10,000원의 hard cap과 각 80%의 알림 threshold를 둔다. 호출 전 DB의 일·월 budget window에서 요청 최대 비용을 reservation하는 조건부 원자 update를 수행하며, 동시 Worker도 ceiling을 넘겨 reservation을 얻을 수 없다. 응답 뒤 실제 token 비용을 기록하고 남은 reservation을 해제한다. reservation 실패 시 새 AI 호출·재시도 없이 `PARTIAL`과 `AI_BUDGET_EXCEEDED`로 남긴다.
- 요청은 `store: false`를 사용하고 URL query를 제거한다. raw prompt/response는 애플리케이션 DB·로그에 저장하지 않으며, 개인정보 처리 고지에 외부 API 전송을 명시한다.
- 최초 출시와 model·prompt·taxonomy 변경 전에는 category 정확도, purpose 오연결, abstain 품질, 저품질 metadata와 prompt injection 사례를 포함한 평가를 수행한다.

## 이유와 trade-off

단일 호출은 두 번의 prompt와 재시도를 없애 비용과 실패 상태를 단순화한다. LLM 결과 cache를 보류하면 반복 호출 비용은 남지만, 사용자별 목적·사용자 전용 category와 version invalidation을 잘못 재사용할 위험을 피한다. `store: false`는 Responses application state 보존을 줄이지만 abuse monitoring 로그 정책까지 바꾸지는 않는다.

## 재검토 조건

- 입력·출력 cap 또는 목적 후보 10개가 실제 분류 품질을 낮춘다.
- 일·월 hard cap이 정상 사용을 과도하게 막거나 예산을 충분히 제한하지 못한다.
- 평가에서 단일 호출이 category 또는 purpose 품질 기준에 미달한다.
- LLM 결과 cache가 측정 가능한 비용 절감과 안전한 invalidation을 함께 입증한다.

## 근거

- [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)
- [OpenAI 데이터 제어](https://developers.openai.com/api/docs/guides/your-data)
- [OpenAI 저비용 모델 기반 분류 설계](../../../superpowers/specs/2026-09-19-openai-low-cost-classification-design.md)
