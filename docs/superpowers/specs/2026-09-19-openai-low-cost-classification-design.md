# OpenAI 저비용 모델 기반 분류 설계

## 사용자 목표와 제약

상품 URL에서 추출한 정보로 카테고리와 목적을 제안하되, 로컬 LLM을 운영하지 않고 예측 가능한 비용과 단순한 운영 구조를 유지한다. MVP는 Cloud Run Worker, transactional outbox와 Cloud Tasks의 비동기 분석 흐름을 유지하며, 자체 모델 학습·GPU 운영·embedding/vector DB/RAG는 도입하지 않는다.

## 결정

- LLM 실행 위치는 외부 **OpenAI API**로 확정한다. ChatGPT 구독이 아니라 서버에서 호출하는 OpenAI API를 사용한다.
- 기본 모델은 `gpt-5.6-luna`로 한다. 이 모델은 저비용·고처리량 작업용이며 Structured Outputs를 지원한다.
- Worker는 Responses API를 사용하고, 기본 요청의 추론 수준은 `none`으로 설정한다.
- deterministic parser는 입력 metadata를 만들고, Worker는 최소화한 상품 metadata·허용 taxonomy·최근 활성 목적 최대 10개를 한 요청으로 보낸다.
- 응답은 category ID 또는 abstain, purpose ID 또는 목적 미지정을 함께 담는 구조화 JSON으로 받고 서버 schema를 다시 검증한다.

## 처리와 장애 흐름

```text
상품 URL 저장 → HTML 추출·정규화·Product metadata cache 확인
  → Cloud Run Worker가 category·purpose를 OpenAI Responses API 한 번으로 요청
  → 구조화 응답·후보 snapshot 재검증 → predicted 값·모델·프롬프트 버전·사용량 기록
  → 사용자 수정값은 final 값으로 별도 보존
```

timeout·네트워크 오류·429·5xx는 기존 `AnalysisJob` 재시도 정책을 따른다. refusal·content filter·schema-invalid ID는 `PARTIAL`로, 400·401·403·출력 토큰 한도 incomplete는 `FAILED_TERMINAL`과 운영 알림으로 끝낸다. 결과 반영 때 후보·owner·generation·lifecycle이 요청 snapshot과 다르면 결과를 폐기한다.

## 비용과 관측

- 외부 API 사용료는 기본 인프라 월 30,000원 상한과 분리해 추적한다.
- 요청마다 provider/model identifier, prompt version, 입력·출력 토큰 사용량, 성공·실패 사유를 기록한다.
- 웹 검색·도구 호출·이미지 생성처럼 분류에 불필요한 기능은 사용하지 않는다.
- 입력은 최대 4,096 token, 출력은 최대 256 token으로 제한한다. title은 300자, brand·merchant는 각각 160자, description은 2,000자로 절단한다.
- 일별 1,000원·월별 10,000원의 hard cap과 80% 알림 threshold를 적용한다. cap 초과 시 AI 호출 대신 `PARTIAL`을 남긴다.
- 모델·prompt·taxonomy 변경 전뿐 아니라 최초 출시 전에도 category 정확도, purpose 오연결, abstain 품질, 저품질 metadata와 prompt injection 사례를 평가한다.

## 개인정보

- OpenAI 요청은 `store: false`를 사용하고 URL query를 제거한다.
- raw prompt/response는 애플리케이션 DB와 로그에 보관하지 않는다. 개인정보 처리 고지에는 외부 API 전송을 명시한다.
- `store: false`는 Responses application state를 제어하지만 abuse monitoring 로그 보존 정책까지 바꾸지는 않는다.

## 범위 밖과 재검토 조건

MVP에서 on-device LLM, Cloud Run CPU/GPU 자체 호스팅, 자체 모델 학습은 범위 밖이다. 모델 품질이 taxonomy 분류에 부족하거나, 실제 토큰 비용·지연이 허용 범위를 넘거나, 모델 API의 지원·가격 정책이 바뀌면 모델 또는 실행 전략을 재검토한다.

## 근거

- [GPT-5.6 Luna 모델 문서](https://developers.openai.com/api/docs/models/gpt-5.6-luna): 저비용·고처리량 용도, Responses API 및 Structured Outputs 지원, 현행 토큰 가격
- [AI 아키텍처](../../architecture/ai/overview.md)
- [ADR-006: LLM 비용과 실행 전략을 분리한다](../../history/architecture/ai/ADR-006-llm-cost-and-execution-strategy.md)
