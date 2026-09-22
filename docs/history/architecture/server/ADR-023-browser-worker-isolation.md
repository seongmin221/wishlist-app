# ADR-023: Playwright는 별도 browser Worker에서 실행한다

> 상태: **확정** · 날짜: 2026-09-20 · 영역: **server·extraction**

## 결정

- Playwright는 일반 분석 Worker가 아니라 별도의 **browser Worker Cloud Run service**에서 실행한다.
- 일반 Worker는 일반 fetch·metadata extraction을 우선 수행하고, Playwright 조건을 만족할 때만 browser Worker용 작업을 Queue에 넣는다.
- browser Worker는 일반 Worker와 별도 Cloud Tasks queue·service identity·resource limit을 사용하며, 유휴 시 scale-to-zero 한다.
- browser Worker 결과는 같은 `AnalysisJob` generation에 안전하게 반영한다. 늦은 browser 결과는 삭제·취소·새 generation 항목을 복원하거나 덮어쓰지 않는다.
- 일반 Worker가 browser fallback을 결정할 때는 같은 DB transaction에서 job 단계를 `BROWSER_PENDING`으로 바꾸고 `browserAttempted=true` 및 browser용 `OutboxEvent`를 함께 기록한다. 따라서 task 생성 전후의 process 중단도 outbox dispatcher가 복구한다.
- browser Worker는 generation·owner·lifecycle 검증 뒤 `BROWSER_PENDING` 단계만 원자적으로 `BROWSER_RUNNING`으로 claim한다. 이미 claim됐거나 취소·삭제·새 generation인 task는 2xx로 끝내고 결과를 쓰지 않는다.
- browser 오류·timeout·대상 차단은 `PARTIAL`과 직접 보완 흐름으로 끝낸다.

## 이유와 trade-off

Chromium은 일반 HTML parser보다 memory·startup 시간·실패 영향이 크다. 별도 browser Worker는 일반 URL 처리를 가벼운 Worker에 유지하고 browser 비용·crash를 JS fallback 작업으로 격리한다. 유휴 시 scale-to-zero하므로 JS URL이 없을 때 상시 비용은 없다.

대신 Queue와 Worker service가 하나 더 생기며 일반 Worker에서 browser 단계로 넘기는 durable·idempotent 상태 전이가 필요하다.

## 후속 결정

- browser Worker의 CPU·memory·concurrency·maximum instance와 browser Queue dispatch rate를 정한다.
- 대표 JS-rendered URL 부하 시험으로 90초 timeout·105초 deadline·p95 5분과 비용 영향을 검증한다.
