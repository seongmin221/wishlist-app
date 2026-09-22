# ADR-010: OpenAI 저비용 모델을 MVP 분류 기본값으로 사용한다

> 상태: **확정** · 날짜: 2026-09-19 · 영역: **AI·인프라**

## 맥락

ADR-006에서는 외부 LLM 비용을 기본 인프라 예산과 분리하고, 로컬 실행 모델을 우선 검토하되 실행 위치를 미정으로 남겼다. 이후 로컬 LLM은 Cloud Run에서 모델 메모리와 추론 리소스를 추가로 요구하고, GPU 운영은 월 30,000원 기본 인프라 예산 및 MVP의 GPU 비운영 원칙에 맞지 않는다는 점을 확인했다. on-device 실행은 앱이 닫혀도 Worker가 비동기 분석을 수행하는 현재 흐름을 변경한다.

## 결정

- MVP의 category 분류와 purpose 연결은 서버의 OpenAI API 호출을 기본 전략으로 사용한다.
- 기본 모델 후보는 `gpt-5.6-luna`이며, 실제 release는 이 alias가 가리키는 **snapshot ID**를 설정에 고정한다. Responses API의 Structured Outputs로 허용된 ID만 받는다.
- 기본 요청은 `reasoning.effort`를 `none`으로 설정하고, 입력은 deterministic parsing·캐시를 통과한 최소 상품 메타데이터와 검증된 taxonomy로 제한한다.
- alias와 실제 snapshot ID를 함께 기록하고, provider/model identifier·prompt/taxonomy version·토큰 사용량·실패 사유를 분석 결과와 함께 기록한다. alias 또는 snapshot 변경은 새 model 변경으로 보고 출시 평가를 다시 수행한다.
- API 키는 모바일 클라이언트가 아닌 서버의 비밀 관리 영역에만 둔다.
- 로컬 on-device, Cloud Run CPU/GPU 자체 호스팅과 자체 모델 학습은 MVP 범위에서 제외한다.

## 이유와 trade-off

- `gpt-5.6-luna`는 저비용·고처리량 작업을 목적으로 하며 Structured Outputs를 지원해, 정해진 taxonomy ID를 고르는 현재 작업에 맞는다.
- Worker의 CPU·메모리 산정을 일반적인 HTTP 호출·HTML 처리 수준으로 유지할 수 있어 별도 모델 서버나 GPU 운영이 필요 없다.
- 외부 API에는 네트워크·공급자 의존성과 토큰 사용량에 따른 변동 비용이 남는다. 따라서 필요할 때만 호출하고 사용량을 관측한다.
- 제공자 또는 모델에 대한 결합을 줄이기 위해 기존 LLM inference adapter 경계는 유지한다.

## 재검토 조건

- 대표 상품 평가에서 category·purpose 품질이 제품 기준에 미달한다.
- 실제 토큰 비용 또는 지연이 운영 예산과 사용자 경험 기준을 넘는다.
- OpenAI API의 모델 지원, 가격, 정책 또는 서비스 가용성이 바뀐다.
- 로컬 실행 방식이 위 조건을 더 낮은 총비용으로 충족한다는 측정 결과가 나온다.

## 대체 관계

이 결정은 ADR-006의 “로컬 실행 모델을 우선한다”와 “실행 위치는 미정” 부분을 대체한다. 기본 인프라 예산과 외부 API 비용을 분리하고 adapter 경계를 유지한다는 ADR-006의 나머지 결정은 계속 적용한다.

## 근거

- [GPT-5.6 Luna 모델 문서](https://developers.openai.com/api/docs/models/gpt-5.6-luna)
- [OpenAI 저비용 모델 기반 분류 설계](../../../superpowers/specs/2026-09-19-openai-low-cost-classification-design.md)
