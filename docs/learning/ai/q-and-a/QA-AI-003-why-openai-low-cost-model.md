# 왜 로컬 LLM 대신 OpenAI 저비용 모델을 MVP 기본값으로 선택하는가

## 질문

로컬 LLM을 운영하는 대신 저비용 OpenAI API 모델을 쓰는 이유는 무엇인가?

## 답변

MVP의 작업은 긴 추론이나 자유 형식 생성보다, 추출된 상품 정보를 허용된 category·purpose ID에 연결하는 구조화 분류에 가깝다. 이 경우 OpenAI API의 `gpt-5.6-luna`는 저비용·고처리량 용도와 Structured Outputs를 제공하므로 적합하다.

로컬 모델은 호출료를 줄일 수 있지만 서버 실행에는 모델 RAM, CPU 또는 GPU, cold start, 배포·관측 부담이 추가된다. on-device 실행은 서버 비용을 줄이지만 앱이 닫힌 뒤에도 Worker가 분석하는 현재 비동기 흐름을 바꿔야 한다. 따라서 MVP는 외부 API를 필요한 경우에만 호출하고, 토큰 사용량을 기록해 비용을 제어한다.

API 비용은 기본 인프라 월 30,000원 상한과 분리해 추적한다. 품질, 지연 또는 실제 비용이 기준을 넘으면 로컬 실행을 다시 평가할 수 있다.

## 관련 문서와 근거

- [ADR-010: OpenAI 저비용 모델을 MVP 분류 기본값으로 사용한다](../../../history/architecture/ai/ADR-010-openai-low-cost-model-strategy.md)
- [GPT-5.6 Luna 모델 문서](https://developers.openai.com/api/docs/models/gpt-5.6-luna)
