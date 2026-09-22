# ADR-020: 평균 월 30,000원 비용은 단계적 alert와 지표 관측으로 관리한다

> 상태: **확정** · 날짜: 2026-09-20 · 영역: **infrastructure·operations**

## 맥락

MVP의 기본 인프라 평균 월 비용 상한은 30,000원이다. 이 비용은 GCP의 Cloud Run·Cloud Tasks·Cloud Scheduler·Secret Manager·네트워크와 Neon PostgreSQL 비용으로 나뉜다. GCP budget alert는 경고일 뿐 자동으로 사용량을 중단하지 않으며, 사용자 서비스 중단 없이 비용 원인을 확인하고 조정할 단계가 필요하다.

## 결정

- 기본 인프라의 월 30,000원은 **평균 비용 상한**으로 유지하며 자동 billing disable 또는 서비스 자동 중단은 MVP에 적용하지 않는다.
- GCP production project에는 월 **20,000원** budget alert를 두고, 실제 또는 forecast 비용의 50%(10,000원), 80%(16,000원), 100%(20,000원)에서 알림을 보낸다.
- Neon은 월 **10,000원** 목표 비용으로 별도 청구 화면에서 확인한다. 매월 GCP와 Neon의 실제 비용을 합산해 30,000원 상한을 검토한다.
- 50% alert에서는 비용·증가 추이만 확인한다.
- 80% alert에서는 Cloud Run Worker 실행 시간·instance 수·request concurrency, Queue backlog·retry, Neon 사용량을 점검하고 비필수 resource 상향이나 비용 증가 배포를 보류한다.
- 100% alert에서는 원인을 판단해 Worker maximum instance·Cloud Tasks 동시 dispatch를 낮추거나, 비용 초과를 명시적으로 승인할지 결정한다. 사용자 서비스나 billing을 자동으로 끄지 않는다.
- 운영 대시보드·alert는 최소한 다음을 관측한다.
  - API: request latency, error rate, container startup latency, instance count
  - Worker: 실행 시간, 성공·실패·retry 수, memory·CPU와 instance count
  - Queue: dispatch backlog와 retry attempt
  - 제품 결과: 저장부터 분석 완료까지 p95 시간
  - 비용: GCP project 비용과 Neon 월 비용
- p95 분석 완료 5분, 평균 월 30,000원 상한, Worker 실행 시간과 queue backlog를 함께 보고 초기 capacity를 조정한다.

## 이유와 trade-off

단계적 alert는 비용 초과 전에 운영자가 원인을 확인할 시간을 준다. budget alert만으로는 지출이 자동 제한되지 않고 집계도 최종 청구 전 변경될 수 있으므로, 자동 billing disable은 API·Worker를 함께 중단할 수 있는 MVP에는 맞지 않는다.

GCP 20,000원과 Neon 10,000원 배분은 초기 관측 기준일 뿐 고정 예산 항목이 아니다. 실제 비용 분포가 달라지면 월 합계 30,000원 상한을 유지한 채 배분과 initial capacity를 조정한다.

## 후속 결정

- Worker request timeout과 Cloud Tasks task deadline은 extraction·LLM latency 부하 시험 결과와 p95 5분 목표를 바탕으로 정한다.
- 실제 alert channel, dashboard·log retention과 운영 대응 담당은 구현 전 deployment 계획에서 정한다.

## 근거

- [Cloud Billing budgets](https://cloud.google.com/billing/docs/how-to/budgets)
- [Cloud Billing programmatic notifications](https://cloud.google.com/billing/docs/how-to/budgets-programmatic-notifications)
- [Cloud Run monitoring](https://cloud.google.com/run/docs/monitoring)
