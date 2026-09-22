# ADR-026: 출시 전 서버 체력 시험은 세 가지 대표 시나리오로 수행한다

> 상태: **확정** · 날짜: 2026-09-22 · 영역: **server·quality**

## 결정

- 출시 전에는 일반 상품 20개를 짧은 시간에 저장하는 burst 시험을 수행하고, 저장부터 분석 완료까지 p95 5분·중복 처리·retry·Queue backlog를 확인한다.
- JS-rendered 상품 4개를 동시에 저장해 browser Worker 최대 2개와 browser Queue가 browser 작업을 안전하게 나눠 처리하는지 확인한다.
- 일반·JS 상품을 섞어 30분 동안 계속 저장하는 안정성 시험을 수행해 Queue가 지속적으로 쌓이지 않고 duplicate·failure·retry가 증가하지 않는지 확인한다.
- 각 시나리오는 두 단계로 수행한다. 먼저 fixture HTTP server·OpenAI adapter fake로 Cloud Run·Queue·상태 전이의 구조적 안정성을 확인하고, 그다음 대표 실제 쇼핑몰·실제 OpenAI API로 latency·비용·외부 의존성 영향을 측정한다.
- 실제 OpenAI 품질 평가는 ADR-025의 고정 metadata snapshot corpus로, 실제 URL fetch·browser·Queue 성능 평가는 이 부하 시험으로 분리한다.

## 이유와 trade-off

가짜 외부 서비스 시험은 Queue·idempotency·resource 설정 문제를 빠르고 재현 가능하게 찾는다. 실제 서비스 시험은 쇼핑몰 지연·OpenAI latency와 비용을 확인한다. 두 결과를 분리하지 않으면 실패 원인을 서버 구조와 외부 의존성 중 어디에서 찾아야 하는지 알기 어렵다.

초기 규모에 맞춰 작은 burst와 30분 안정성 시험으로 시작한다. 더 큰 실제 traffic 패턴이 확인되면 arrival rate와 browser 비율을 반영한 시험을 추가한다.
