# ADR-012: Cloud Run과 Cloud Tasks의 초기 용량을 균형형으로 시작한다

> 상태: **확정** · 날짜: 2026-09-20 · 영역: **server·infrastructure**

## 맥락

MVP는 월 약 20,000건의 상품 분석을 가정한다. API는 cold start를 줄이기 위해 최소 한 인스턴스를 유지하고, 분석 Worker는 유휴 시 scale-to-zero 한다. 기본 인프라 비용은 월 30,000원을 절대 초과하지 않아야 하는 한도가 아니라 평균 월 비용 상한이며, 상품 저장부터 분석 완료까지의 p95 목표 시간은 5분이다.

분석은 URL fetch·metadata 추출과 외부 LLM 호출을 포함하므로 실제 실행 시간과 단기 유입량은 구현 전에는 검증할 수 없다. 따라서 비용을 과도하게 선점하지 않으면서도 작은 burst를 처리할 초기 상한과, 측정 기반 조정 기준이 필요하다.

## 결정

- Cloud Run API는 request-based billing, **1 vCPU**, **1 GiB memory**, **concurrency 20**, service-level **minimum instances 1**, **maximum instances 3**으로 시작한다.
- Cloud Run Worker는 request-based billing, **1 vCPU**, **1 GiB memory**, **concurrency 1**, service-level **minimum instances 0**, **maximum instances 5**로 시작한다.
- Cloud Tasks queue는 초당 최대 **1 dispatch**, 최대 **5개 동시 dispatch**로 시작한다. 이는 Worker 최대 인스턴스 수와 일치시킨다.
- p95 분석 완료 목표는 **5분 이내**로 둔다. 분석 실행 시간이 60초 이내라는 초기 가정에서는 단기 유입 약 25건을 5분 내에 처리할 수 있다. 이는 출시 전 보장이 아니라 부하 시험으로 검증할 가설이다.
- 기본 인프라의 월 **30,000원은 평균 비용 상한**으로 관리한다. Cloud Run 비용 예측과 실제 비용은 Neon·Firebase·네트워크·Artifact Registry 등을 포함해 별도로 관측한다.
- 출시 전과 출시 후에는 Worker의 실제 실행 시간, p95 완료 시간, queue depth, retry 비율, API CPU·memory 사용량과 평균 월 비용을 관측한다. p95 5분 목표를 놓치면 Worker 최대 인스턴스와 queue 동시 dispatch를 같은 값으로 함께 올리고, 자원 사용량이나 평균 비용이 과하면 낮춘다.

## 이유와 trade-off

API에 1 vCPU·1 GiB를 배정하면 Ktor JVM의 기본 메모리 여유를 확보하면서 minimum instance 하나로 첫 요청 지연을 줄일 수 있다. Worker의 concurrency를 1로 고정하면 fetch, HTML parsing, 외부 LLM 호출과 항목별 상태 갱신을 한 요청 단위로 격리해 초기 idempotency·메모리 문제를 단순화한다.

Worker 최대 5개와 Cloud Tasks 동시 dispatch 5개는 비용 폭주와 Neon 연결 수를 제한한다. 반면 대규모 burst나 60초가 넘는 분석에서는 5분 목표를 만족하지 못할 수 있다. 이 위험은 더 큰 초기 resource를 예약하지 않고 측정 결과로 조정한다.

Cloud Run의 minimum instance는 유휴 중에도 비용이 발생하고, maximum instance는 비용과 downstream 연결 수의 안전장치가 된다. Cloud Tasks는 월 100만 operation까지 무료이므로 월 20,000건 가정에서는 queue operation 자체보다 Worker 실행 시간이 주요 변동 비용이다.

## 재검토 조건

- 대표 URL 부하 시험에서 p95 분석 완료가 5분을 넘는다.
- Worker의 p95 실행 시간이 60초를 넘거나 1 GiB memory 부족·OOM이 관측된다.
- 평균 월 기본 인프라 비용이 30,000원을 넘거나 그에 근접하는 추세가 지속된다.
- API concurrency 20에서 Ktor의 latency, CPU 또는 DB connection 사용량이 허용 범위를 넘는다.

## 후속 결정

- Worker request timeout, Cloud Tasks task deadline와 분석 단계별 timeout은 실제 extraction·LLM latency를 측정한 뒤 정한다.
- 비용 alert의 정확한 금액과 운영 대응은 환경·secret·관측 설계에서 정한다.

## 근거

- [Cloud Run 가격](https://cloud.google.com/run/pricing)
- [Cloud Run concurrency](https://cloud.google.com/run/docs/about-concurrency)
- [Cloud Run minimum instances](https://cloud.google.com/run/docs/configuring/min-instances)
- [Cloud Run maximum instances](https://cloud.google.com/run/docs/configuring/max-instances)
- [Cloud Tasks 가격](https://cloud.google.com/tasks/pricing)
