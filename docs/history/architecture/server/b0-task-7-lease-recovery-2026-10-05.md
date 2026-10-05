# B0 Task 7 — lease 기반 분석 작업 복구

> 날짜: 2026-10-05 · 브랜치: `server/b0-foundation` · 기준: `de0aa30`

## 구현과 결정

AnalysisJobReconciler의 updated_at·앱 시각·job 우선 잠금을 제거했다. 만료 RUNNING 후보를 잠금 없이 발견하고 각 후보에서 item→job SKIP LOCKED와 실행 identity/lease 재검증을 수행한다. 사용 중인 후보는 다음 스캔으로 넘기며 반환값은 commit된 전이 수다. 공통 내부 lock helper에 기본 false인 skipLocked 인자를 추가해 claim/guard의 기존 blocking 잠금을 유지했다.

상품의 활성·PROCESSING·현재 generation·version·수동 완료 조건이 맞지 않으면 job만 CANCELLED로 바꾸고 token/lease/claimed version을 해제한다. 유효 실행은 lane별 한도에서 FAILED와 현재 상품 FAILED_RETRYABLE/version +1, 또는 lane PENDING과 recovery outbox를 원자적으로 반영한다. 복구는 attempt를 올리지 않는다. recovery task_name은 옛 token까지 포함해 각 실행을 구분한다.

V9 업그레이드는 legacy RUNNING의 token/claimed version을 null로 두고 lease를 migration 시각에 만료시킨다. 이 행과 identity/lease가 모두 null인 legacy를 재처리 가능하게 했다. 유효 claim은 항상 identity가 있으므로 기존 실행으로 결과를 반영할 수 없으며 새 claim에서 version과 token을 발급한다. browser fallback flag가 없으면 취소한다.

## 검증

초기 관련 테스트 19개 중 7개 assertion 실패를 확인했다. 만료된 lease라도 최근 updated_at이면 복구하지 않고, 살아 있는 lease도 오래된 updated_at이면 복구하는 기존 동작과 legacy·취소·한도·중복 복구 경로를 검출했다. 후보 교체 경합, 실제 V9 형태의 legacy와 기존 Worker의 lease 기반 fixture를 추가한 좁은 3개 테스트도 실패했다.

실제 PostgreSQL에서 양쪽 lane의 복구/재claim 후 새 token, 옛 중간/최종 쓰기 차단, 새 결과의 정상 반영을 검증한다. archive/delete/generation/version/manual/status 변경은 item/outbox 불변 취소이며, lane별 3회·30분 한도는 한 번만 version을 올린다. 동시 reconciler 두 개는 같은 실행에 recovery event를 한 번만 만들고, 연속 recovery의 task_name도 고유하다.

실제 item/job 잠금을 보유하면 복구가 대기 없이 건너뛰며, 이후 lease/token 갱신을 존중한다. 후보 조회와 잠금 사이 token이 교체돼도 이번 스캔에서는 건너뛴다. 만료 후보 조회를 멈춘 뒤 같은 token의 lease를 갱신하고 정상 finish를 완료한다. 복구를 재개하면 현재 완료 상태를 재검증해 건너뛰므로 중복 version/outbox가 없다. 실제 settled 124 microusd와 RESERVED 496 microusd 및 reservation/window row를 그대로 유지한다.

관련 33개 테스트가 통과했고, 전체 `./gradlew test`는 4분 5초에 성공했다. 130개 중 129개 통과, 실패·오류 0개, 실제 외부 URL pilot 1개 opt-in skip이다. JDK 17·Colima를 테스트 process에 지정했다. 읽기 전용 코드 검토에서 production 결함은 없었고 finish 경합 테스트가 실제 만료 후보를 보지 않는다는 지적을 반영했다. production 변경 없이 이 테스트를 보완한 뒤 복구 테스트 10개를 다시 실행해 모두 통과했다.

## 후속

다음은 Task 8의 IO 경계·제한된 DB pool·runtime 자원 종료다. B0 전체 완료가 아니며 PR·push는 진행하지 않았다.
