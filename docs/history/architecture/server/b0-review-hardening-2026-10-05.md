# B0 외부 리뷰 보완

> 2026-10-05 · `server/b0-foundation` · 기준 `70ced87`

## 확인과 수정

| 피드백 | 코드 확인과 반영 |
| --- | --- |
| 1. 실행 시간/dispatch deadline/lease 불일치 | 기존 최대 외부 호출 합계가 120초를 넘을 수 있었다. 처리 예산 80초·전체 Worker 응답 90초·Cloud Tasks 105초·분석 lease 120초를 공통 상수와 관계 테스트로 고정했다. 일반/browser는 같은 bounded executor 경계를 사용하고 redirect/token/LLM/browser는 남은 시간을 공유한다. |
| 1. 중복 ACK 후 PENDING 정체 | 유효 Retryable 전환에 새 outbox를 같은 transaction으로 저장한다. 이전 task가 제거돼도 발행할 작업은 남으며 늦은 실행은 claim guard로 검증한다. Scheduler 발행 연결은 B5다. |
| 2. 생성 발행 직렬화/무제한 RPC | 작업 수를 짧은 잠금에서 집계하고 네트워크는 잠금 밖에서 실행한다. stop은 admission을 닫고 이미 허용한 작업을 기다린다. createTask는 재시도 없이 총 5초 RPC 제한을 적용했다. |
| 3. 500 관측 누락/요청 오류의 500 변환 | 예상 밖 오류는 requestId·예외 타입·stack frame을 ERROR로 남긴다. 메시지에는 credential/SQL/입력이 포함될 수 있어 기록하지 않는다. Ktor의 요청 오류는 400/404/413/415로 분리하고 취소는 재전파한다. |
| 4. 오래된 PENDING 복구 | B5 설계와 통과 조건에 queue retry 소진/유실, 미발행 fallback, 살아 있는 task/backlog 구분, 동시 재예약 제약을 추가했다. 신규 event 지정 발행과 backlog 발행도 구분한다. 구현은 B5다. |
| 5. 최종 SQL 유지보수 | 컬럼명과 값을 같은 map에 두고 parameter 번호를 순서대로 계산한다. guard가 잠근 job을 반환해 중복 잠금 조회를 제거했고 상품 조회는 명시적 컬럼을 사용한다. |
| 6. 무제한 복구/후보 오류 전파 | 기본 100개, 허용 1~1000개로 discovery를 제한한다. 후보별 transaction의 일반 오류는 기록 후 다음 후보로 진행하고 취소/interrupt는 재전파한다. |
| 7. 예산 시각 불일치 | 예약·IN_FLIGHT lease·기본 만료 조회에 DB clock_timestamp를 사용한다. 예약 createdAt도 window 선택 시각과 맞춰 정산이 같은 일/월 window를 사용한다. 명시적 복구 시각은 기존 테스트/관리 호출 호환을 위해 유지한다. |
| 8. 문서의 영문 붙여쓰기 | 완료 기록의 판단 29건을 한국어 표로 정리했고 계획에서 링크한다. 기존 판단의 근거와 부담을 보존했다. 직렬화 처리량 비용 누락도 정정했다. |
| 9. DNS별 연결 재사용/삭제 PENDING | 주소 pin 검증을 유지하고 HTTP 유휴 connection 수를 0으로 줄였다. 같은 generation/lane의 삭제 PENDING은 item→job 잠금 안에서 CANCELLED로 바꾸고 상품/version/attempt/outbox를 변경하지 않는다. |

## 시간 제한과 비용의 실제 범위

WorkerExecution은 claim·처리·finish와 executor queue 대기를 포함한 요청 실행에 90초 상한을 둔다. timeout은 RETRY를 반환하고 작업 interrupt를 시도한다. 진행 중 작업 수와 queue 크기도 제한한다. 외부 호출은 단조 시계의 남은 80초 예산으로 timeout을 줄이고, 예산을 초과한 처리 결과는 성공으로 반영하지 않는다.

interrupt가 모든 외부 SDK/JDBC 호출을 즉시 중단시키는 것은 아니다. 반환하지 않는 실행은 bounded slot을 계속 점유하며 RUNNING lease 복구 대상이다. 늦게 종료되는 retry는 새 outbox를 원자 저장한다. 이미 발생한 AI 비용은 응답 usage 또는 기존 보수적 만료 정산으로 처리한다. heartbeat로 작업 시간을 무한 연장하지 않는다.

Cloud Tasks가 마감 이후 Worker 응답을 듣지 않는 동작은 [공식 Task 문서](https://docs.cloud.google.com/java/docs/reference/google-cloud-tasks/latest/com.google.cloud.tasks.v2.Task)를 확인했다. createTask 설정은 [공식 Java retry/timeout 설정](https://docs.cloud.google.com/java/docs/client-retries)을 따르며 SDK 기본값에 맡기지 않는다.

## 검증 기록

- 발행 동시성·Ktor 4xx·중복 delivery 후 durable retry 테스트: 기존 코드에서 3개 실패 확인 후 관련 40개 회귀 통과.
- 실행 시간 제한/남은 예산·createTask 제한·복구 batch/오류 격리 테스트: 미구현 interface 실패 확인 후 대상 회귀 통과.
- DB 시각 차이와 삭제 PENDING 취소 테스트: 기존 코드에서 2개 실패 확인. 일반/browser deadline 통합 테스트는 새 처리 경계에서 통과.
- SQL 정리는 기존 양 lane의 최종 상태·사용자 값·stale·race 테스트로 회귀 확인한다.
- 독립 코드 리뷰: Critical 0, Important 0, Minor 1. 시간 제한·durable retry·발행 동시성·안전한 오류 기록·DB 시각·SQL 변경을 확인했다.
- 보류한 Minor: 복구 첫 batch가 지속적으로 실패하거나 잠기면 뒤 후보가 검사되지 않을 수 있다. 현재 maintenance runtime은 B5 범위이므로 공유 cursor/다음 검사 시각과 진행 보장 회귀를 B5 설계에 명시했다. 후보 1개의 오류 뒤 같은 batch의 다음 후보는 현재 코드에서 계속 처리한다.
- 최종 전체 회귀: `RUN_REAL_URL_PILOT=0 ./gradlew test` **BUILD SUCCESSFUL, 4분 41초 · 165개 중 164개 통과 · 실패/오류 0 · opt-in RealUrlPilot 1개 skip**. 실제 PostgreSQL migration/경합/시각/예산 테스트는 모두 실행했다. JDK 17.0.18·Docker 28.4.0·Gradle 9.7.1을 사용했다.
- `git diff --check`와 변경 문서의 로컬 링크 검증을 통과했다. V1~V9 migration 파일 변경은 없다.

## B5 인계

[오래된 PENDING 복구 설계](../../../architecture/server/analysis-pending-recovery.md)에 따라 maintenance runtime·Scheduler·신규 event 지정 발행을 구현한다. 이번 변경은 queue 최대 retry 소진이나 유실을 직접 조회/재예약하는 운영 경로까지 구현하지 않았다. lane별 3회/30분 정책은 유지하며 generation 전체 예산은 B5에서 연결한다.

운영 배포·실제 Firebase/Cloud Tasks/AI/browser 호출은 수행하지 않았다. 기존 migration V1~V9는 변경하지 않았다.
