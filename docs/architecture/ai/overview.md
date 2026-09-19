# AI 구조

> 상태: **확정된 원칙 + 제안된 구현**

## 확정된 원칙

- MVP에서는 자체 모델 학습, GPU 운영, embedding/vector DB/RAG를 하지 않는다.
- MVP의 분류·목적 연결은 OpenAI API를 사용하며, 기본 모델은 `gpt-5.6-luna`다.
- OpenAI API 호출은 최소화하고, 기본 요청은 `reasoning.effort`를 `none`으로 설정하며 Structured Outputs를 사용한다.
- 모바일 on-device와 서버 자체 호스팅 LLM은 MVP에서 제외한다.
- 코드로 확실히 처리할 수 있는 URL 정규화·HTML 구조 parsing은 AI에 맡기지 않는다.
- [추출 pipeline](../server/extraction-pipeline.md)의 deterministic parser 결과가 충분하면 AI는 추출 대체 수단이 아니라 taxonomy 분류·정규화 보조 수단으로만 사용한다.
- 카테고리·목적의 사용자 정책, AI 제안·확정·재판단 조건은 [구매 후보 정리의 AI 분류와 목적 연결](../../product/organize-candidates.md#5-ai-분류와-목적-연결)을 따른다.
- AI 구현은 요청마다 허용된 category·purpose ID를 구조화해 전달하고, 응답 ID를 서버 schema로 검증한 뒤 predicted 값과 model/prompt version을 기록한다.

## 처리 흐름

```text
추출된 title / brand / merchant / description
  + 공용 taxonomy 및 검증된 사용자 전용 세부 카테고리
  → LLM inference adapter를 통해 OpenAI Responses API에 카테고리 분류 요청
  → schema 검증된 category ID 응답
  + 사용자가 만든 목적과 최근 활성 위시리스트 문맥
  → LLM inference adapter를 통해 OpenAI Responses API에 기존 목적 연결 요청
  → schema 검증된 purpose ID 또는 목적 미지정 응답
  → predicted 값과 model/prompt version 저장
  → 사용자 수정 시 final 값 갱신
```

재판단을 수행할 때는 제품 정책에서 허용한 변경만 입력으로 사용하고, 주기적 재분류 작업을 예약하지 않는다.

사용자 전용 카테고리 입력은 신뢰하지 않는 입력으로 다루고, [구매 후보 정리의 카테고리 선택과 사용자 전용 세부 카테고리](../../product/organize-candidates.md#1-카테고리-선택과-사용자-전용-세부-카테고리)의 검증 결과를 통과한 값만 AI 요청에 넣는다.

명령문 형태의 prompt injection, URL·코드·스크립트 중심 입력, AI 입력에 부적절한 유해 텍스트는 해당 카테고리를 변경하거나 삭제하지 않은 채 AI 후보에서만 제외한다. 사용자는 이 카테고리를 계속 보고 직접 지정할 수 있다. AI 후보 제외 여부는 MVP 화면에 노출하지 않고 내부 상태로 관리한다.

모델에는 허용된 값을 데이터 필드로 구조화해 전달하며, 사용자 텍스트를 모델 지시문으로 해석하거나 공용 taxonomy에 반영하지 않는다.

## 기록할 데이터

- `predicted_category_id`
- `final_category_id` 또는 사용자 override
- `predicted_purpose_id` 또는 목적 미지정
- `final_purpose_id` 또는 사용자 override
- model provider/model identifier
- prompt/taxonomy version
- confidence 또는 후보 목록(지원될 경우)
- 분류 시각 및 실패 사유

이 데이터는 이후 정확도 측정과 taxonomy 개선의 근거가 된다. semantic search, similar product, recommendation이 실제 제품 요구가 되었을 때 embedding 도입을 별도 결정한다.

LLM 비용 범위와 provider/model 변경 재검토 조건은 [ADR-006](../../history/architecture/ai/ADR-006-llm-cost-and-execution-strategy.md), OpenAI API 기본 전략은 [ADR-010](../../history/architecture/ai/ADR-010-openai-low-cost-model-strategy.md)을 따른다.
