# B0 Task 6 — Worker 최종 결과와 실패 경로 보호

> 날짜: 2026-10-05 · 브랜치: `server/b0-foundation` · 기준: `6bea64f`

## 구현

AnalysisResultRepository.finish로 일반·browser의 최종 반영을 통합했다. 같은 guard transaction에서 item 상태·출처·검토·누락 사유·진단, job stage와 token 해제를 반영한다. 정상 최종 반영의 version은 +1이며 재시도와 fallback은 version을 유지한다. stale는 ACK하며 item/job/outbox를 변경하지 않는다. Main의 기존 method reference는 변경 없이 새 Worker 조립과 컴파일된다.

USER 출처 또는 override로 보호한 값과 null을 유지한다. 목적 USER+null도 보존한다. 부분·실패는 기존 nonnull metadata를 유지하고 빈 필드만 채운다. Complete라도 usable category가 없으면 PARTIAL과 AI_INVALID_CANDIDATE를 기록한다. 보호된 category 누락 사유는 유지한다.

CONFIRMED/DEFERRED는 다시 열지 않는다. 이름·category가 있고 실제 미확정 AI category 또는 purpose 연결이 생긴 경우에만 PENDING이다. 리뷰에서 USER category에 AI 목적만 새로 연결되는 경우가 누락됨을 발견했다. 상태 명세에서 목적도 검토 대상임을 확인하고 양쪽 lane과 네 검토 상태의 실패 테스트를 추가한 뒤 수정했다.

일반 NeedsBrowser의 flag/stage/token 해제/outbox를 원자적으로 저장한다. infrastructure exception은 양쪽 Worker에서 guarded Retryable로 처리해 현재 실행만 RETRY/503, stale와 완료는 ACK/204다. 기존 browser의 예외 전파·reconciler 대기 테스트를 계획의 즉시 재시도 정책에 맞게 바꿨다. cancellation은 Worker와 HTTP parsing에서 다시 던진다.

## 검증

초기 결과별 테스트 20개 중 token 해제와 사용자 값 보호 4개 실패를 확인했다. 추가 3개 테스트에서 metadata 보존, category 없는 Complete, browser infrastructure 재시도 실패를 확인한 뒤 구현했다. 관련 23개가 통과했다. 목적만 AI로 연결되는 리뷰 회귀도 기존 구현에서 assertion 실패를 확인했다.

실제 PostgreSQL에서 양쪽 lane의 결과별 처리를 멈추고 generation·archive·delete·수동 완료·version·lease·token을 변경한 뒤 재개해 전체 item/job/outbox 불변을 검증한다. 사용자 출처와 override를 각각 검증하며 CONFIRMED/DEFERRED 및 목적 해제를 보존한다. 정상 결과의 version/token, fallback 중복 방지와 한도 소진도 확인한다.

두 connection의 실제 잠금 경합에서는 사용자 편집이 item lock을 보유한 동안 finish가 대기하는 것을 pg_stat_activity로 확인한다. 편집이 job 취소까지 commit하면 finish는 deadlock 없이 ACK하고 이미 반영된 사용자 version과 값을 유지한다. 실제 HTTP 통합에서 양쪽 lane의 현재 fault 503, stale fault 204, 완료 204를 확인한다.

최종 `./gradlew test`는 3분 40초에 성공했다. 120개 중 119개 통과, 실패·오류 0개, 실제 외부 URL pilot 1개 opt-in skip이다. JDK 17·Colima를 테스트 process에 지정했다. Task 6 관련 일반 13개·browser 10개·HTTP 3개는 모두 통과했다. 수정 후 읽기 전용 코드 재검토에서 추가 지적은 없었다.

## 후속

다음은 Task 7의 lease 기반 reconciler 복구다. B0 전체 완료가 아니며 PR·push는 진행하지 않았다.
