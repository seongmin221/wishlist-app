# B0 후속 리뷰: 예산·마감·claim·최종 복구

> 2026-10-06 · `server/b0-foundation` · 기준 `95713e4`

## 1차 리뷰: 원인과 반영

- 예산 lease는 DB 시각인데 두 만료 테스트가 JVM `Instant.now()+121초`를 넘겼다. 호스트보다 Docker VM이 빠르면 실제 lease가 만료되지 않는다. 테스트에서 DB `clock_timestamp()-1초`로 lease를 만료시키고 기본 `reconcileExpired()`를 호출한다. 기존 통과 기록은 당시 환경의 실행 결과이며 시계 차이에 대한 이식성을 입증한 결과는 아니다.
- `markInFlight` 뒤 요청 구성의 `WorkerExecution.remaining()`이 예외를 던지면 전송하지 않은 요청도 최대 비용 정산 대상이 됐다. 토큰 검사와 요청 구성은 RESERVED에서 하고 유료 요청 전송 직전 callback에서 IN_FLIGHT로 전환한다. 전송하지 않은 예약은 finally에서 즉시 RELEASED로 해제한다. 전송 이후 사용량 누락·통신 실패의 최대 비용 정책은 유지한다.
- SDK의 밀리초 제한으로 바뀌기 전 남은 예산이 1ms 미만이면 `ProcessingDeadlineExceeded`를 던진다. Playwright launch/navigation과 OkHttp가 공유하는 `remaining()` 경계에서 0으로 절삭되는 무제한 timeout을 막는다.
- 만료 실행의 claimed version 불일치는 job만 CANCELLED로 만들었다. 현재 generation의 ACTIVE·PROCESSING·수동 미완료 상품이면 FAILED_RETRYABLE과 version +1도 같은 transaction에서 저장한다. 사용자 편집 필드·outbox·예산은 보존하고 삭제·보관·다른 generation·수동 완료 상품은 변경하지 않는다.
- AI Retryable 분기의 연속된 `isCurrent()` 검사를 하나 제거했다. 유료 호출 전 검사와 응답 뒤 검사는 유지하고 최종 반영은 기존 `finish()` guard로 검증한다. 역할별 pool 기본값은 `RuntimeRole.defaultPoolSize` 한 곳에 모았다.

## 구현 순서와 남은 설계

- 신규 event 지정 발행은 B5에서 B1 생성 응답 작업으로 앞당긴다. 503 재전달과 retry outbox의 중복 전달은 원자 claim으로 동시 분석을 막지만 다음 PENDING에서 추가 attempt를 소비할 수 있다. 지금 503을 제거하면 maintenance가 없는 runtime의 진행 보장이 약해지므로 유지한다. B5에서 generation 전체 3회·30분 한도·queue 소진·발행 관측을 함께 연결한다.
- CONFIRMED/DEFERRED를 유지하면서 새 AI category/purpose를 반영하는 동작은 기존 코드와 `assertPurposeOnlyReview` 테스트에 명시돼 있다. 현재 검토 상태는 이전 값에 대한 사용자 결정이고 새로운 값까지 검토했다는 증거가 될 수 없다. B7 재분석 전에 값 보존 또는 검토 의미의 계약을 확정해야 한다.
- 1차 리뷰에서는 browser 중복 저장과 중복 isCurrent 검사 정리를 보류했다. 아래 2차 리뷰에서 저장 책임과 경합 테스트를 함께 바꿔 단일 저장으로 정리했다. 공용 transaction 추출은 여전히 별도 작업이다.
- legacy RUNNING 복구 전 구 Worker drain/중지 → V8/V9 별도 적용 → 보호 코드 배포 → queue 재개 순서는 유지한다. migration 파일은 변경하지 않는다.

## 1차 검증

추가한 전송 전 마감·1ms 미만 예산·version 불일치 복구 테스트는 기준 코드에서 각각 실패했다. 대상 35개 실행에서 이 3개 실패를 확인했다. 사용자 보고의 기존 시계 혼용 테스트 2개는 이 실행에서는 통과했으므로 환경에 따른 재현 차이를 기록한다.

수정 후 대상 회귀는 BUILD SUCCESSFUL이었다. 전송 전 마감·1ms 미만 예산·version 불일치 테스트가 통과했고, 토큰 검사 시 RESERVED 유지·과대 입력 예약 해제·유료 HTTP 요청 직전 IN_FLIGHT callback 순서도 검증했다.

최종 전체 회귀는 `RUN_REAL_URL_PILOT=0 ./gradlew test`로 **BUILD SUCCESSFUL, 5분 37초 · 169개 중 168개 통과 · 실패/오류 0 · RealUrlPilot 1개 skip**이었다. JDK 17과 Colima Docker의 PostgreSQL 컨테이너에서 migration·DB 경합 테스트를 실제 실행했다. 외부 AI/실제 URL pilot·운영 배포는 실행하지 않았다.

`git diff --check`와 변경 문서 6개의 로컬 링크 검증을 통과했다. B1 구현은 착수하지 않았고 신규 event 지정 발행의 구현 순서만 변경했다.

## 2차 리뷰: 원인과 반영

| 피드백 | 최종 반영 |
| --- | --- |
| queue에서 마감된 전달의 attempt 소진 | executor에서 꺼낼 때와 양 Worker claim 전에 마감을 확인해 RETRY만 반환한다. claim의 connection/행 잠금 대기로 마감될 때도 attempt 또는 한도 실패 처리 전에 확인하고 rollback한다. |
| markInFlight DB 대기를 제외한 HTTP timeout | callback 뒤 timeout을 계산한다. 그때 마감이면 client.send에 진입하지 않고 LlmRequestNotSent를 통해 IN_FLIGHT 예약을 해제한다. 실제 전송 여부가 불명확한 commit/통신 실패는 보수적 정산을 유지한다. |
| finally 해제 오류가 원래 예외를 덮음 | 원래 Throwable을 보존하고 cleanup 오류를 suppressed로 추가한다. commit 성공 뒤 오류가 발생해 IN_FLIGHT가 남으면 이를 RESERVED처럼 해제하지 않고 원래 원인을 전달한다. |
| callback 없는 usage 응답의 무료 성공 | usage 또는 Assigned/Abstained 응답을 계약 위반으로 판정한다. 최대 비용 정산 후 결과 저장 전에 실패시킨다. |
| 보호된 빈 category를 AI 실패로 표시 | AI가 유효 category를 반환했어도 사용자 CATEGORY 보호로 연결할 수 없으면 PARTIAL·기존 사용자 누락 사유를 유지한다. AI_INVALID_CANDIDATE는 남기지 않으며 유효 predicted category는 진단으로 저장한다. |
| outbox의 JVM/DB 시각 혼용 | 발견과 120초 lease 저장을 모두 DB clock_timestamp로 통일한다. DB clock을 호스트보다 30초 앞당긴 회귀를 추가했다. |

version 불일치의 즉시 처리는 B5까지 미루지 않았다. finish가 item→job 잠금에서 owner·job 관계·generation·lane·token·claimed version으로 같은 실행을 확인한다. 현재 ACTIVE·PROCESSING·수동 미완료 상품의 version만 달라졌으면 Stale outcome에서도 job CANCELLED·상품 FAILED_RETRYABLE·version +1을 같은 transaction에서 저장한다. 사용자 값·진단·outbox는 보존하고 중복 finish는 무변경이다. 다른 token, 새 generation, 삭제·보관·수동 완료 상품은 기존대로 무변경 ACK다. 종료되지 않는 실행이나 일반 lease 정체를 스캔하는 maintenance runtime은 여전히 B5 범위다.

중복 왕복은 다음처럼 정리했다.

- AI usage 정산 뒤 Assigned/실패의 guarded write 전에 하던 isCurrent transaction을 제거했다. 각 write의 guard가 stale를 판정한다. DB 쓰기 없는 Retryable과 유료 호출 전 검사는 유지한다.
- 일반 추출의 DB 쓰기 없는 Partial/NeedsBrowser/Terminal은 잠정 outcome이며 최종 finish가 판정한다. metadata 저장과 AI 호출 전 보호는 유지한다.
- BrowserRenderProcessor는 source URL guard 후 데이터만 반환한다. BrowserWorkerService가 metadata를 한 번 guarded 저장하고 분류한다. direct render의 반환값은 저장 완료를 뜻하지 않는다.
- lane별 3회/30분 판정과 job identity 해제, claim/recovery의 FAILED_RETRYABLE 갱신을 AnalysisJobTransitions로 모았다. 최종 결과의 metadata 병합 SQL은 finish에 유지한다.

재현 테스트 7개는 수정 전 모두 실패했다. 수정 후 이 7개를 포함한 대상 테스트 20개가 통과했고, 추가 browser 조합 경로 테스트는 기존 이중 저장에서 실패했다.

최종 `RUN_REAL_URL_PILOT=0 ./gradlew test --rerun-tasks`는 **BUILD SUCCESSFUL, 11분 15초 · 177개 중 176개 통과 · 실패/오류 0 · RealUrlPilot 1개 skip**이었다. 컴파일과 test task를 모두 강제 실행했고 PostgreSQL migration·DB 경합·양 lane의 queue/finish 보호·예산 정산·단일 browser 저장을 실제 검증했다. 외부 AI·실 URL pilot·운영 배포는 실행하지 않았다.

`git diff --check`와 변경 문서 7개의 로컬 링크 검증도 통과했다. B1 구현이나 B5 runtime 연결은 착수하지 않았다.
