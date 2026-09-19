# OpenAI 저비용 모델 기반 분류 설계

## 사용자 목표와 제약

상품 URL에서 추출한 정보로 카테고리와 목적을 제안하되, 로컬 LLM을 운영하지 않고 예측 가능한 비용과 단순한 운영 구조를 유지한다. MVP는 Cloud Run Worker, transactional outbox와 Cloud Tasks의 비동기 분석 흐름을 유지하며, 자체 모델 학습·GPU 운영·embedding/vector DB/RAG는 도입하지 않는다.

## 결정

- LLM 실행 위치는 외부 **OpenAI API**로 확정한다. ChatGPT 구독이 아니라 서버에서 호출하는 OpenAI API를 사용한다.
- 기본 모델은 `gpt-5.6-luna`로 한다. 이 모델은 저비용·고처리량 작업용이며 Structured Outputs를 지원한다.
- Worker는 Responses API를 사용하고, 기본 요청의 추론 수준은 `none`으로 설정한다.
- deterministic parser와 캐시가 먼저 동작하고, 부족한 경우에만 최소화한 상품 메타데이터와 허용된 taxonomy ID를 보낸다.
- 응답은 category ID, purpose ID 또는 목적 미지정만 담는 구조화 JSON으로 받고 서버 schema를 다시 검증한다.

## 처리와 장애 흐름

```text
상품 URL 저장 → HTML 추출·정규화·캐시 확인
  → AI 보조가 필요한 경우에만 Cloud Run Worker가 OpenAI Responses API 호출
  → 구조화 응답 schema 검증 → predicted 값·모델·프롬프트 버전·사용량 기록
  → 사용자 수정값은 final 값으로 별도 보존
```

API 네트워크 오류, rate limit 또는 일시적 공급자 오류는 기존 `AnalysisJob` 재시도 정책을 따른다. 모든 재시도가 소진되면 분석 실패 상태만 기록하며, 부분 응답으로 상품의 확정 값을 덮어쓰지 않는다.

## 비용과 관측

- 외부 API 사용료는 기본 인프라 월 30,000원 상한과 분리해 추적한다.
- 요청마다 provider/model identifier, prompt version, 입력·출력 토큰 사용량, 성공·실패 사유를 기록한다.
- 웹 검색·도구 호출·이미지 생성처럼 분류에 불필요한 기능은 사용하지 않는다.
- 모델 변경 전에는 대표 상품과 taxonomy를 사용한 평가 데이터를 통과해야 한다. 정량 품질 기준은 구현 전에 별도 확정한다.

## 범위 밖과 재검토 조건

MVP에서 on-device LLM, Cloud Run CPU/GPU 자체 호스팅, 자체 모델 학습은 범위 밖이다. 모델 품질이 taxonomy 분류에 부족하거나, 실제 토큰 비용·지연이 허용 범위를 넘거나, 모델 API의 지원·가격 정책이 바뀌면 모델 또는 실행 전략을 재검토한다.

## 근거

- [GPT-5.6 Luna 모델 문서](https://developers.openai.com/api/docs/models/gpt-5.6-luna): 저비용·고처리량 용도, Responses API 및 Structured Outputs 지원, 현행 토큰 가격
- [AI 아키텍처](../../architecture/ai/overview.md)
- [ADR-006: LLM 비용과 실행 전략을 분리한다](../../history/architecture/ai/ADR-006-llm-cost-and-execution-strategy.md)
