# ADR-021: Worker 분석은 90초, Cloud Tasks deadline은 105초로 시작한다

> 상태: **확정** · 날짜: 2026-09-20 · 영역: **server·operations**

## 맥락

상품 분석 Worker는 URL fetch·HTML metadata 추출·OpenAI 분류를 수행한다. 특정 대상 사이트, 네트워크 또는 외부 API가 느리거나 멈추면 Worker request가 오래 실행되고 Queue backlog·instance 비용·분석 완료 지연이 늘 수 있다. 제품의 저장부터 분석 완료까지 p95 목표는 5분이며, retry는 최대 3회·30분 정책을 따른다.

## 결정

- Cloud Run Worker service의 request timeout은 초기 **90초**로 둔다.
- Cloud Tasks HTTP task의 dispatch deadline은 초기 **105초**로 둔다.
- Worker는 90초 안에 URL fetch, extraction과 OpenAI 호출을 완료하지 못하면 내부 실패를 기록하고 retryable 조건이면 기존 retry 정책으로 넘긴다.
- Task deadline은 Worker timeout보다 15초 길게 둬 Worker가 timeout·실패 결과를 저장하고 HTTP 응답을 반환할 여유를 둔다.
- 출시 전 대표 URL 부하 시험에서 정상 분석 p95, 전체 완료 p95 5분, timeout 비율·retry 비율과 queue backlog를 측정한다. JS-rendered 사이트의 Playwright 보조 경로도 이 시험에 포함한다. 결과가 기준을 충족하지 않으면 Worker timeout·task deadline·Worker capacity를 함께 조정한다.

## 이유와 trade-off

90초는 일반 extraction 뒤 제한적인 Playwright rendering까지 시도할 시간을 주면서도, 외부 대상과 LLM 호출이 멈춘 요청이 Worker instance를 장시간 점유하지 못하게 하는 초기 안전장치다. 105초 deadline은 Queue가 Worker가 처리 중인 요청을 너무 이르게 중복 전달하지 않도록 한다.

실제 JS-rendered extraction이나 provider latency가 90초를 지속적으로 넘으면 정상 작업도 재시도될 수 있다. 따라서 이 값은 고정된 SLO가 아니라 measurement 기반 초기값이며, Playwright 정책·실제 latency 검증과 함께 재검토한다.

## 재검토 조건

- 정상 대표 URL의 분석 p95가 90초를 넘는다.
- 저장부터 분석 완료까지 p95가 5분을 넘는다.
- timeout·retry가 queue backlog 또는 평균 월 비용을 유의미하게 높인다.
- Playwright 적용이 60초 안에 처리될 수 없는 요구를 만든다.
