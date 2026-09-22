# ADR-026: 출시 전 서버 체력 시험은 세 가지 대표 시나리오로 수행한다

> 상태: **확정** · 날짜: 2026-09-22 · 영역: **server·quality**

## 결정

- 완료 시간은 API가 저장을 수락한 시각부터 `READY` 또는 `PARTIAL` terminal 상태가 된 시각까지다. `FAILED_RETRYABLE`·`FAILED_TERMINAL`은 완료 p95에서 제외하되 성공률에서는 실패로 센다.
- 일반 상품 20개 burst를 Queue가 회복될 때까지 10회 반복한다. 총 200개에서 `READY|PARTIAL` 성공률 **98% 이상**, 성공 항목 완료 p95 **5분 이하**, 중복 반영 **0건**, 마지막 입력 뒤 Queue backlog 회복 **2분 이하**가 기준이다.
- JS-rendered 상품 4개 동시 burst를 Queue 회복 뒤 5회 반복한다. 총 20개에서 browser Worker 최대 2개·동시 dispatch 2개를 넘지 않고, 중복 반영 **0건**, 성공 항목 완료 p95 **5분 이하**가 기준이다.
- 30분 혼합 안정성 시험은 30초마다 1개씩 총 60개를 저장하며 일반 URL 80%·JS-rendered URL 20%로 구성한다. `READY|PARTIAL` 성공률 **95% 이상**, 자동 retry가 전체 시도의 **5% 이하**, 마지막 입력 뒤 Queue backlog 회복 **2분 이하**가 기준이다.
- 각 시나리오는 두 단계로 수행한다. 먼저 fixture HTTP server·OpenAI adapter fake로 Cloud Run·Queue·상태 전이의 구조적 안정성을 확인하고, 그다음 대표 실제 쇼핑몰·실제 OpenAI API로 latency·비용·외부 의존성 영향을 측정한다.
- 실제 OpenAI 품질 평가는 ADR-025의 고정 metadata snapshot corpus로, 실제 URL fetch·browser·Queue 성능 평가는 이 부하 시험으로 분리한다.

## 이유와 trade-off

가짜 외부 서비스 시험은 Queue·idempotency·resource 설정 문제를 빠르고 재현 가능하게 찾는다. 실제 서비스 시험은 쇼핑몰 지연·OpenAI latency와 비용을 확인한다. 두 결과를 분리하지 않으면 실패 원인을 서버 구조와 외부 의존성 중 어디에서 찾아야 하는지 알기 어렵다.

초기 규모에 맞춰 수치화한 burst와 30분 안정성 시험으로 시작한다. 더 큰 실제 traffic 패턴이 확인되면 arrival rate와 browser 비율을 반영한 시험을 추가한다.
