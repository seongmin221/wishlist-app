# AI 의사결정

## 초기 결정

- **확정**: MVP에서 자체 ML 모델 학습·호스팅과 GPU 운영은 하지 않는다.
- **초기 결정, 대체됨**: 외부 LLM API를 사용한다.
- **확정**: deterministic parsing을 AI보다 먼저 사용한다.
- **확정**: AI는 predefined taxonomy의 category ID만 선택한다.
- **확정**: 사용자 category 수정값과 model/taxonomy version을 보존한다.
- **확정**: MVP에서는 embedding, vector DB, RAG를 도입하지 않는다.

## 후속 결정

- [ADR-025: OpenAI 분류는 고정 holdout 평가와 출시 기준을 통과해야 한다](ADR-025-openai-classification-release-evaluation.md)

- [ADR-006: LLM 비용과 실행 전략을 분리한다](ADR-006-llm-cost-and-execution-strategy.md) — 외부 LLM 비용을 기본 인프라 예산에서 분리하고 adapter 경계를 유지한다. 실행 위치 결정은 부분 대체됐다.
- [ADR-010: OpenAI 저비용 모델을 MVP 분류 기본값으로 사용한다](ADR-010-openai-low-cost-model-strategy.md) — OpenAI API와 `gpt-5.6-luna` snapshot을 기본 모델로 정하고 로컬 LLM을 MVP에서 제외한다.
- [ADR-011: AI 분류 호출·상태·보호 정책을 단일 흐름으로 정한다](ADR-011-ai-classification-control-policy.md) — 단일 호출, abstain·오류·stale 결과, DB reservation 기반 비용 상한과 개인정보 보호를 확정한다.

추가 결정은 `ADR-번호-제목.md` 형식으로 이 폴더에 기록한다.
