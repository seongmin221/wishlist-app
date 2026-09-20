# ADR-009: Outbox 발행과 분석 재시도·장기 실패 복구 정책을 확정한다

> 상태: **확정** · 날짜: 2026-09-19 · 영역: **server·infrastructure**

## 맥락

Transactional outbox와 Cloud Tasks를 선택한 뒤에도 누가 outbox를 발행할지, 일시적 실패를 얼마나 재시도할지, Cloud Tasks retry가 끝난 작업을 어떻게 복구할지 정해야 한다. 빠른 분석 시작과 작업 유실 방지를 함께 만족하면서 초기 운영 비용과 구성 요소를 최소화하는 정책이 필요하다.

## 결정

### Outbox dispatcher

- **API의 즉시 best-effort 발행 + Cloud Scheduler의 주기적 복구**를 결합한 하이브리드 방식을 사용한다.
- API는 Neon transaction commit 직후 Cloud Tasks 생성을 한 번 시도한다. 실패해도 사용자 저장 요청을 실패로 되돌리지 않고 outbox를 미발행 상태로 남긴다.
- Cloud Scheduler는 1분마다 OIDC로 인증된 dispatcher endpoint를 호출해 남은 outbox를 발행한다.
- dispatcher는 중복 호출에 안전해야 한다. outbox claim/lease와 고유 task 이름을 사용하고, Cloud Tasks 생성 성공 후 발행 완료를 기록한다.

### 분석 재시도

- 하나의 `AnalysisJob` generation에서 실제 Worker 분석 실행은 최초 시도를 포함해 **최대 3회**다.
- retry 간격은 10초부터 시작하는 exponential backoff를 사용하고 최대 간격은 10분이다.
- 첫 Worker 분석 시도부터 전체 retry 기간은 **30분**이다.
- 최대 3회와 30분 중 하나라도 먼저 도달하면 추가 분석 실행을 막는다.
- 일시적 실패는 내부 진단과 시도 횟수를 저장하고 non-2xx로 응답해 Cloud Tasks 재시도를 요청한다.
- terminal 실패는 `FAILED_TERMINAL`을 저장하고 2xx로 응답해 재시도를 끝낸다.
- 자동 재시도 한도를 소진한 일시적 실패는 `FAILED_RETRYABLE`로 저장하고 2xx로 응답한다. 이후 사용자가 요청하면 generation을 올린 새 작업을 만든다.

Cloud Tasks의 `maxAttempts`와 `maxRetryDuration`은 두 조건이 모두 충족될 때까지 재시도할 수 있다. 따라서 Queue에도 `maxAttempts=3`, `minBackoff=10s`, `maxBackoff=600s`, `maxRetryDuration=1800s`를 설정하되, 두 제한을 독립적인 상한으로 정확히 지키는 책임은 `AnalysisJob` attempt count·deadline과 Worker의 사전 검증이 가진다.

### 장기 실패 복구

- Cloud Tasks가 task를 삭제해도 Neon의 `AnalysisJob` 시도·오류 기록은 보존한다.
- reconciler가 오래 queue 대기 또는 실행 중인 job을 찾아 현재 generation과 lifecycle을 검증한다.
- 제한 안에서 안전하게 재실행할 수 있으면 새 dispatch attempt로 다시 Queue에 넣는다.
- 3회 또는 30분 한도를 넘은 retryable 오류는 `FAILED_RETRYABLE`, 반복해도 성공할 수 없는 오류는 `FAILED_TERMINAL`로 전환한다.
- Cloud Tasks queue depth와 응답 코드별 task attempt에 alert를 둔다.

## 이유와 trade-off

- 즉시 발행은 정상 상황의 지연을 줄이고 Scheduler는 일시적 Cloud Tasks 장애와 API 중단에서 미발행 작업을 복구한다.
- 3회·30분 제한은 외부 사이트 장애 때 비용과 부하가 무한히 커지는 것을 막는다.
- `AnalysisJob`을 논리적 dead-letter 기록으로 사용하면 native DLQ가 없는 Cloud Tasks에서도 사용자 재분석과 운영 복구가 가능하다.

대신 API와 Scheduler가 동시에 같은 outbox를 볼 수 있으므로 claim과 task 생성의 idempotency가 필수다. Cloud Tasks 설정만으로 3회와 30분을 각각 hard limit로 보장할 수 없어 애플리케이션 상태 검증도 필요하다.

## 재검토 조건

- 실제 일시적 장애의 대부분이 3회·30분 안에 회복되지 않는다.
- Scheduler 1분 주기가 사용자 체감 지연이나 DB 부하 문제를 만든다.
- 장기 실패 작업의 운영량이 커져 native DLQ와 replay가 필요해진다.
- Queue·Worker 관측 결과 다른 concurrency 또는 retry 정책이 필요하다.
