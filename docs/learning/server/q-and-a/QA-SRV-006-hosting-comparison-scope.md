# QA-SRV-006: API·Worker 호스팅 비교에서 무엇을 결정하는가

> 상태: **학습 Q&A** · 날짜: 2026-09-19

## 질문

Cloud Run, Railway와 Render를 비교하는 다음 인프라 논의에서는 구체적으로 무엇을 결정해야 하는가?

## 짧은 답변

단순히 Ktor 서버를 어디에 올릴지를 고르는 논의가 아니다. 사용자 요청을 즉시 처리하는 API와 느린 상품 분석을 담당하는 Worker를 어떻게 따로 실행하고, 둘 사이의 작업을 어떤 Queue로 전달하며, 이 전체 구성을 월 30,000원 안에서 운영할지를 결정한다.

```text
iOS / Android
  → 항상 빠르게 응답해야 하는 Ktor API
  → Neon transaction + 작업 등록
  → Queue
  → 유휴 시 중단 가능한 분석 Worker
  → 결과를 Neon에 저장
```

## 이미 고정된 비교 조건

- 공개 출시 후 첫 3개월은 MAU 1,000명 이하로 가정한다.
- 사용자당 월 20개, 전체 월 약 20,000건의 상품 분석을 작업량 기준으로 사용한다.
- API는 유휴 상태에서도 첫 요청에 바로 응답해야 한다.
- Worker는 유휴 시 중단되고 다음 작업에서 시작이 늦어져도 된다.
- Neon, API와 Worker는 Singapore에 가깝게 배치하는 방향을 우선한다.
- 월 30,000원은 Neon, Firebase, API, Worker와 Queue 같은 기본 인프라 예산이다. 외부 LLM API 사용료는 제외한다.

## 비교해야 하는 항목

1. **API 실행 방식**: 상시 실행 또는 minimum instance 비용으로 첫 요청 지연을 없앨 수 있는가?
2. **Worker 실행 방식**: 작업이 있을 때만 실행하거나 안전하게 sleep할 수 있는가?
3. **Queue 결합**: 작업 등록, 재시도, dead-letter와 중복 전달을 어떻게 구현하는가?
4. **리전과 네트워크**: Singapore 배치가 가능하고 Neon 왕복 지연과 egress 비용을 줄일 수 있는가?
5. **월 비용**: API idle, Worker 실행 시간, Queue, 로그와 egress까지 합쳐 30,000원 안에 들어오는가?
6. **운영 복잡도**: 배포, secret, health check, migration, 로그와 장애 확인을 한 사람이 감당할 수 있는가?
7. **비용 안전장치**: 예산 알림, hard limit 또는 최대 instance 제한으로 예상 밖 비용을 막을 수 있는가?

## 후보별로 확인할 핵심

### Google Cloud Run

Cloud Run service는 기본적으로 scale-to-zero가 가능하고 minimum instance를 두면 idle 비용을 지불하는 대신 cold start를 줄일 수 있다. 따라서 API는 minimum instance 1, Worker는 event-driven service 또는 job으로 필요할 때만 실행하는 구성이 가능한지 검토한다. Google Cloud의 Queue 선택과 권한 설정까지 함께 이해해야 하므로 구성 요소는 가장 많을 수 있다.

### Railway

Railway는 기본 구독료에 포함 사용량이 있고 CPU·RAM 사용량을 기준으로 과금한다. Serverless 기능은 유휴 서비스를 sleep시킬 수 있지만 첫 요청 지연이나 502 가능성이 있고, DB connection·telemetry 같은 outbound traffic이 sleep을 막을 수 있다. 따라서 API에는 sleep을 적용하지 않고 Worker에만 적용했을 때의 실제 비용을 검토한다.

### Render

Render는 web service와 background worker를 명시적으로 분리하기 쉽다. 일반 background worker는 계속 실행되며 compute plan별 비용이 발생하고, Workflows는 managed queue와 retry를 제공한다. 구성은 이해하기 쉽지만 API와 Worker 각각의 고정 compute 비용이 예산에 미치는 영향을 확인해야 한다.

## 이 논의의 완료 조건

다음 결과가 한 세트로 나와야 호스팅 선택이 끝난다.

- 선택한 공급자와 Singapore 리전
- API와 Worker의 실행 형태 및 최소 resource
- Queue 제품과 전달 흐름
- 정상·유휴·작업량 증가 시 월 비용 추정
- retry, dead-letter와 비용 상한의 기본 정책
- 선택하지 않은 후보와 핵심 이유

Queue의 세부 신뢰성 정책은 호스팅을 선택한 직후 별도 논의에서 확정한다.

## 현재 결정

2026-09-19에 Cloud Run과 Singapore 리전을 선택했다. API는 Cloud Run service와 minimum instance 1을 초기 기준으로 삼고, Worker 실행 형태와 Queue는 후속 설계에서 확정한다. 선택 근거는 [ADR-007](../../../history/architecture/server/ADR-007-cloud-run-hosting.md)에 기록했다.

## 공식 자료

- [Cloud Run 개요와 scale-to-zero](https://docs.cloud.google.com/run/docs/overview/what-is-cloud-run)
- [Cloud Run minimum instances](https://docs.cloud.google.com/run/docs/configuring/min-instances)
- [Cloud Run pricing](https://cloud.google.com/run/pricing)
- [Railway pricing](https://docs.railway.com/pricing/plans)
- [Railway regions](https://docs.railway.com/deployments/regions)
- [Railway Serverless](https://docs.railway.com/deployments/serverless)
- [Render pricing](https://render.com/pricing)
- [Render background workers](https://render.com/docs/background-workers)
- [Render regions](https://render.com/docs/regions)

## 관련 설계

- [2026-09-19 인프라 설계 체크포인트](../../../history/architecture/server/technical-design-checkpoint-2026-09-19.md)
- [Server 구조](../../../architecture/server/overview.md)
