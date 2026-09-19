# AI 학습

이 프로젝트의 AI는 LLM adapter를 통한 structured classification과 metadata normalization에 한정한다. MVP는 OpenAI API의 `gpt-5.6-luna`를 기본 모델로 사용하고, 외부 API 호출은 최소화한다. 로컬 실행 모델은 MVP 범위에서 제외한다.

- [AI Q&A](q-and-a/INDEX.md)

추가할 학습 주제:

- structured output과 schema validation
- taxonomy 기반 분류
- prompt/model versioning 및 평가 데이터
- 비용, latency, fallback 설계
- on-device와 서버 자체 호스팅 LLM의 제약 비교
- embedding/vector DB를 MVP에서 보류하는 이유
