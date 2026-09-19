# 로컬 LLM을 실행하면 리소스가 어떻게 달라지는가

## 질문

로컬 LLM을 실행하면 현재 Cloud Run 기반 구조의 리소스가 어떻게 달라지는가?

## 답변

차이는 모델 연산을 **외부 사업자, Cloud Run 서버, 사용자 기기 중 어디에서 부담하느냐**에서 생긴다.

| 실행 방식 | 서버 리소스 변화 | 주요 trade-off |
| --- | --- | --- |
| 외부 LLM API | Worker는 전처리와 HTTP 호출만 담당하므로 비교적 작은 CPU·메모리로 시작할 수 있다. | 서버 운영은 단순하지만 호출량에 따라 외부 비용이 발생한다. |
| Cloud Run CPU 추론 | 모델 가중치와 추론 런타임을 RAM에 올리고 CPU로 계산해야 한다. 작은 양자화 모델도 보통 수 GiB 메모리와 여러 vCPU를 검토해야 한다. | 별도 호출료는 줄지만 응답이 느리고 모델 로딩에 따른 cold start가 길어진다. |
| Cloud Run GPU 추론 | GPU 외에도 최소 CPU·메모리를 함께 할당해야 한다. Cloud Run의 L4 구성은 최소 4 vCPU와 16 GiB 메모리가 필요하다. | 추론은 빨라지지만 현재 예산과 MVP의 GPU 비운영 원칙에는 맞지 않는다. |
| 모바일 on-device 추론 | 서버 추론 리소스는 거의 들지 않는다. | 앱 크기, 기기 RAM, 발열·배터리, 기기별 성능 차이를 감당해야 하며 앱이 닫힌 뒤 처리하는 현재 Worker 흐름도 바뀐다. |

모델 가중치만 계산하면 대략 `파라미터 수 × 양자화 bit ÷ 8`만큼의 메모리가 필요하다. 예를 들어 4-bit 3B 모델은 가중치만 약 1.5 GB, 7B 모델은 약 3.5 GB다. 실제 실행에는 KV cache, 임시 버퍼, 추론 런타임과 애플리케이션 메모리가 추가되므로 더 큰 메모리가 필요하다. 정확한 크기는 모델과 입력 길이, 동시 처리 수로 벤치마크해야 한다.

Cloud Run에서 L4 GPU 한 개를 30일 내내 실행하면 GPU 요금만 약 484달러이며 CPU와 메모리 요금은 별도다. scale-to-zero를 사용하면 유휴 요금은 줄지만 첫 요청마다 인스턴스와 모델을 준비하는 지연이 생긴다. 따라서 월 30,000원 수준의 기본 인프라 예산에서는 상시 GPU 운영을 전제로 삼기 어렵다.

현재 단계에서는 기본 Worker 리소스 산정에서 로컬 LLM을 제외하고, 작은 양자화 CPU 모델 또는 on-device 모델을 별도 실험하는 편이 안전하다. 품질·응답 시간·실행 비용을 측정한 뒤 외부 LLM fallback과 비교해 실행 위치를 결정한다.

## 관련 문서와 근거

- [AI 아키텍처 개요](../../../architecture/ai/overview.md)
- [ADR-006: LLM 비용과 실행 전략을 분리한다](../../../history/architecture/ai/ADR-006-llm-cost-and-execution-strategy.md)
- [Cloud Run GPU 구성](https://docs.cloud.google.com/run/docs/configuring/services/gpu)
- [Cloud Run 메모리 제한](https://docs.cloud.google.com/run/docs/configuring/services/memory-limits)
- [Cloud Run 가격](https://cloud.google.com/run/pricing)
