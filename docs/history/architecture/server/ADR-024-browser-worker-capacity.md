# ADR-024: browser Worker는 2 vCPU·2 GiB와 동시 2개로 시작한다

> 상태: **확정** · 날짜: 2026-09-20 · 영역: **server·extraction·infrastructure**

## 결정

- browser Worker는 request-based billing, **2 vCPU**, **2 GiB memory**, **concurrency 1**, **minimum instances 0**, **maximum instances 2**로 시작한다.
- browser Worker request timeout은 **90초**, browser Queue Cloud Tasks deadline은 **105초**로 둔다.
- browser Queue는 초당 최대 **1 dispatch**, 최대 **2개 동시 dispatch**로 시작한다.
- 대표 JS-rendered URL 부하 시험에서 memory·실행 시간·timeout·queue backlog·전체 분석 p95와 비용을 측정해 resource·Queue limit을 조정한다.

## 이유와 trade-off

Chromium browser 하나를 안정적으로 실행할 2 GiB memory와 concurrency 1을 주고, 최대 instance·동시 dispatch를 2개로 제한해 JS URL burst를 일부 처리하면서 비용·crash 영향을 제한한다. min instance 0이므로 JS URL이 없을 때 상시 browser 비용은 없다.

2개보다 많은 JS URL은 대기할 수 있다. 이는 JS-rendered 지원이 best-effort fallback이며 일반 분석 비용·안정성을 우선한다는 선택이다.

## 재검토 조건

- OOM·crash 또는 browser 실행 p95가 90초를 넘는다.
- browser Queue backlog가 전체 분석 p95 5분을 반복적으로 넘는다.
- browser Worker가 평균 월 30,000원 비용 상한을 위협한다.
