# B0 Task 5 — 중간 결과와 AI 예산에 실행 claim 연결

> 날짜: 2026-10-05 · 브랜치: `server/b0-foundation` · 기준: `abcfdfa`

## 구현과 연결

일반 추출·browser rendering·AI 분류의 입력과 Worker 콜백을 AnalysisClaim으로 변경했다. Main과 HTTP 통합 경로의 기존 method reference는 새 타입으로 컴파일된다. job ID만 받는 processor/classifier/reserve overload는 남기지 않았다. 예산 유지보수·알림 테스트도 실제 유효 claim을 사용한다.

AnalysisPendingResultRepository는 source URL 조회, 임시 metadata·assignment·failure 저장, 후보 snapshot 읽기·최초 저장을 guard transaction에서 수행한다. 후보 공급은 DB/local 조회로 한정하며 원격 호출은 잠금 밖에서 한다. snapshot은 같은 generation의 재claim에도 유지된다. guard 실패 시 저장하지 않고 processor/classifier는 Stale를 반환한다. nullable browser rendering은 무효 claim에서 null을 반환하며 Worker의 guard가 정상 partial과 구분한다.

예산 예약도 item→job→기존 고정 순서의 예산 window를 잠근다. 기존 request ID 조회보다 먼저 claim을 검증하므로 만료 claim이 이전 reservation으로 우회할 수 없다. 다른 job/generation의 request ID 재사용은 거부한다. 새 reservation을 생성하기 전에 window 잠금 대기 후 claim을 재검증한다. gateway 직전 claim을 다시 검사하며 무효 RESERVED reservation은 기존 lease reconciliation에서 해제한다.

실제 gateway 응답의 유효 usage는 claim 무효화와 무관하게 정산한다. usage 누락·범위 초과에서는 기존 maximum settlement 또는 retry IN_FLIGHT lease reconciliation을 유지한다. 결과 저장은 별도 guard로 차단한다. 외부 호출 직전 검사와 이후 무효화 사이의 좁은 race는 결과 저장 보호로 처리한다.

## 다음 Task와의 경계

유효한 claim을 processor에 전달하려면 Worker의 시작도 Task 4 repository를 사용해야 하므로 함께 연결했다. 기존 최종 저장 transaction 입구에도 guard를 적용해 중간 경로의 Stale나 교체된 token이 최종 상태를 바꾸지 못하게 했다. 이는 Task 5 연결을 위한 선행 보완이다. 최종 category/source/review 정책, 결과 repository 통합과 token 해제는 Task 6에서 완료한다. 일반 Worker는 cancellation을 다시 던져 오래된 실행으로 오해하지 않게 한다.

## 검증

claim 입력과 Stale 타입이 없는 상태에서 새 stale 테스트가 컴파일 실패하는 것을 확인한 뒤 구현했다. 관련 20개 테스트가 통과했다. 추가로 usage 누락 시 stale maximum/reconciliation과 실제 재claim 뒤 sealed candidate 유지까지 검증한다.

실제 PostgreSQL에서 external callback을 latch로 멈춘 뒤 token·version·generation·수동 완료·lease를 변경해, 재개 후 metadata·assignment·failure·candidate snapshot이 그대로인지 확인한다. 오래된 rendering은 pending metadata를 저장하지 않으며 이미 만료된 classification은 후보 공급·예산 예약·gateway를 실행하지 않는다. 무효 claim의 직접 pending 저장도 모두 false/null이다.

AI assigned/abstained/unusable/terminal/retry 응답이 stale여도 500/20 token의 실제 124 microusd 정산은 유지한다. usage 없는 assigned는 maximum 496 microusd, usage 없는 retry는 기존 IN_FLIGHT reconciliation에서 maximum으로 처리한다. reservation 생성 전에 만료된 claim은 기존 request ID가 있어도 Stale이고 예약량을 늘리지 않는다.

최종 `./gradlew test compileKotlin compileTestKotlin`는 108개 중 107개 통과, 실패·오류 0개, 실제 외부 URL pilot 1개 opt-in skip이었다. JDK 17·Colima를 테스트 process에 지정했다. runtime·test caller 검색에서 job ID만 받는 분석/예약 overload가 없음을 확인했다. 코드 검토의 동작상 지적은 없었고 예산 예약 경로의 문서 표현 1건을 보완했다.

## 후속

Task 6에서 일반/browser의 최종 결과를 AnalysisResultRepository로 모으고 상태·출처·review와 사용자 수정 보호를 일관되게 반영한다. B0 전체 완료 기록이 아니다.
