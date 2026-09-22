# MVP 서버 운영과 AI 평가 설계

> 상태: **검토 요청** · 날짜: 2026-09-22

## 목표와 범위

이 설계는 Wishlist MVP의 상품 저장·분석 서버를 구현하기 전, Cloud Run 운영 경계, DB migration, 비용 제어, JS-rendered fallback과 OpenAI 출시 평가 방법을 고정한다. 제품 정책과 WishlistItem API 상태 모델은 이미 확정되어 있으며, 이 문서는 이를 바꾸지 않는다.

목표는 한국 사용자를 대상으로 월 약 20,000건의 분석을 처리하면서, 저장부터 분석 완료까지 p95 5분, 기본 인프라 평균 월 30,000원, 외부 LLM 월 10,000원 hard cap을 만족하는 것이다.

## 실행 구조

```text
모바일 앱
  → API Cloud Run
  → Neon transaction: WishlistItem + AnalysisJob + OutboxEvent
  → Cloud Tasks
     ├─ 일반 Queue → 일반 Worker
     └─ browser Queue → browser Worker (필요한 경우만)
  → Neon에 결과 반영
```

- API는 1 vCPU·1 GiB·concurrency 20·minimum instance 1·maximum instance 3이다.
- 일반 Worker는 1 vCPU·1 GiB·concurrency 1·minimum instance 0·maximum instance 5이며 일반 Queue는 초당 1 dispatch·동시 5개다.
- browser Worker는 2 vCPU·2 GiB·concurrency 1·minimum instance 0·maximum instance 2이며 browser Queue는 초당 1 dispatch·동시 2개다.
- Worker timeout은 90초, Cloud Tasks deadline은 105초다.
- 일반 Worker가 browser fallback을 결정하면 같은 Neon transaction에서 `AnalysisJob` 단계를 `BROWSER_PENDING`으로 바꾸고 `browserAttempted=true` 및 browser용 `OutboxEvent`를 함께 기록한다. 따라서 task 생성 전후 프로세스가 중단돼도 dispatcher가 browser task를 복구한다.
- browser Worker는 task를 받으면 generation·owner·lifecycle뿐 아니라 `BROWSER_PENDING` 단계 claim을 원자적으로 `BROWSER_RUNNING`으로 바꾼다. 이미 claim·취소·삭제·새 generation인 task는 2xx로 종료하며 결과를 반영하지 않는다.

## 추출과 AI 분류

일반 Worker는 URL 정규화·SSRF 검증·HTTP fetch·JSON-LD·OpenGraph·HTML metadata·heuristic parser를 순서대로 사용한다. 결과가 없거나 품질이 낮고 `browserAttempted=false`일 때만 위 transaction으로 browser Queue 작업을 요청한다. browser 단계에서 대상 사이트 차단·navigation timeout·대상 DNS/연결 오류·추출 품질 부족이 나면 즉시 `PARTIAL`을 저장하고 2xx로 끝낸다. DB commit 실패, Worker runtime 장애처럼 terminal 상태를 저장하지 못한 Worker 인프라 오류만 전역 3회·30분 정책으로 재시도한다. 어느 경우도 두 번째 browser fallback은 만들지 않는다.

추출 metadata와 허용 taxonomy, 최근 활성 목적 10개 이하를 넣어 release에서 고정한 `gpt-5.6-luna` snapshot에 Responses API Structured Output 호출을 한 번 수행한다. `reasoning.effort`는 `none`이다. 응답은 category `ASSIGNED | ABSTAINED`, purpose `ASSIGNED | UNASSIGNED`로 검증한다. 허용되지 않은 ID·refusal·content filter는 `PARTIAL`, 일시적 네트워크·429·5xx는 기존 3회·30분 retry 정책을 따른다. alias는 후보 선택에만 쓰며 snapshot 변경은 새 model 변경으로 간주해 재평가한다.

월 20,000건과 월 10,000원 cap을 함께 만족시키기 위해 release 기본 요청은 입력 최대 1,000 token·출력 최대 80 token으로 제한한다. 4,096/256은 시스템 절대 상한이며, 이 값을 사용하는 release는 별도 비용 평가 없이는 허용하지 않는다. raw prompt·response는 저장하지 않는다. `store: false`, canonical URL query 제거, Secret Manager의 OpenAI key 사용을 적용한다.

OpenAI 가격과 보수 환율 1 USD=1,600원을 기준으로 월 USD 6.00·일 USD 0.60을 내부 budget ceiling으로 둔다. 호출 전 `LlmBudgetWindow`의 일·월 reservation을 조건부 원자 update로 확보한다. reservation은 해당 요청의 최대 1,000 입력·80 출력 token 비용이며, 동시 Worker 중 어느 하나라도 ceiling을 넘기면 reservation을 얻지 못한다.

각 `LlmBudgetReservation`은 request UUID·`AnalysisJob` generation·가격표 version·최대 비용·실제 비용·`RESERVED | IN_FLIGHT | SETTLED | RELEASED` 상태와 120초 lease를 기록한다. 120초는 Worker timeout 90초와 Task deadline 105초보다 길다. OpenAI 전송 직전에 reservation을 `IN_FLIGHT`로 바꾸고 lease를 갱신한다. 응답을 받으면 실제 token 비용으로 `SETTLED`하고 남은 reservation을 해제한다. 1분 reconciler는 만료 `RESERVED`를 `RELEASED`로 해제하고, 만료 `IN_FLIGHT`는 OpenAI가 이미 처리했을 가능성을 보수적으로 인정해 최대 비용으로 `SETTLED`한다. 따라서 Worker가 호출 뒤 죽거나 응답을 잃어도 cap을 초과하지 않으며, 호출 전 중단만 예산을 되돌린다. 80%에서 알림을 보내며 reservation 실패 시 OpenAI를 호출·재시도하지 않고 `PARTIAL`과 `AI_BUDGET_EXCEEDED`로 끝낸다. 모델 가격표 version 변경은 새 release의 비용 평가와 ceiling 갱신 없이는 production에 적용하지 않는다.

Cloud Tasks는 `maxAttempts=3`, `minBackoff=10s`, `maxBackoff=600s`, `maxRetryDuration=1800s`로 설정한다. Worker는 실행 전에 `AnalysisJob`의 attempt count와 최초 시도 기준 30분 deadline을 검사한다. 한도 소진 전 retryable 오류만 non-2xx로 반환하며, 3회 또는 30분 한도를 넘으면 `FAILED_RETRYABLE`을 저장하고 2xx로 task 재시도를 끝낸다.

## 환경·권한·DB

- 환경은 local과 production만 둔다. staging은 production 통합 검증 위험이 반복될 때 만든다.
- local은 Docker PostgreSQL, Firebase Auth Emulator, Cloud Tasks·OpenAI fake와 fixture HTTP server를 쓴다.
- production은 하나의 전용 GCP/Firebase project와 별도 Neon production project·database를 쓴다.
- API, Worker, Cloud Tasks 호출자, Scheduler 호출자, CI/CD는 별도 service identity를 쓰며 필요한 권한만 부여한다.
- Neon credential·OpenAI API key는 Secret Manager에 보관한다. Firebase Admin은 Cloud Run service identity의 ADC를 사용하며 private key 파일을 배포하지 않는다.
- CI/CD는 GitHub Actions OIDC federation을 사용한다.

Flyway versioned SQL migration은 Git에 보관한다. local과 CI의 빈 PostgreSQL에서 전부 적용·검증한 뒤, CI/CD migration 전용 Neon role이 `validate → migrate → deploy` 순서로 production에 한 번 적용한다. runtime API·Worker는 schema 변경 권한이나 startup migration을 갖지 않는다. destructive migration은 expand → migrate → contract로 나누고, branch·snapshot을 먼저 만들며 실패 시 앱 배포를 중단한다.

## 비용과 관측

- GCP는 월 20,000원 budget alert를 50%·80%·100%에 둔다.
- Neon은 월 10,000원 목표 비용으로 확인한다.
- 합계는 평균 월 30,000원 상한으로 관리하고, billing·서비스 자동 중단은 하지 않는다.
- API latency·error·instance, Worker 실행 시간·retry·resource, Queue backlog, 저장부터 완료까지 p95, GCP·Neon 비용을 관측한다.
- 80% alert에서는 resource 상향과 비용 증가 배포를 보류하고 원인을 분석한다. 100%에서는 capacity를 낮출지 비용 초과를 승인할지 명시적으로 결정한다.

## AI 출시 평가

사람이 정답을 라벨한 180개 metadata snapshot을 사용한다. 120개는 개선용이며, 첫 model·prompt 실험 전에 고정한 60개 holdout은 최종 출시 판정에만 쓴다. corpus에는 모든 상위 taxonomy, 목적 연결·미지정, 다중 후보, 저품질·abstain, JS-rendered와 비정상 입력 사례가 포함된다.

category 정확도는 정답이 category ID인 holdout 사례 전체를 분모로 하며, 예상 ID와 정확히 같을 때만 정답이다. `ABSTAINED` 정답 사례는 이 분모에서 빼고 abstain 지표로 따로 채점한다. purpose 오연결률은 AI가 `ASSIGNED`를 낸 사례를 분모로 하며, 사람이 허용한 purpose ID 집합에 없는 ID를 낸 사례를 분자로 한다. 복수 purpose가 허용되면 그 집합의 어느 ID나 정답이며, 정답이 `UNASSIGNED`인 사례에서의 `ASSIGNED`는 오연결이다. purpose `ASSIGNED` 정답 사례에 대한 허용 ID 연결률도 70% 이상이어야 한다.

holdout에는 `ABSTAINED` 정답 사례 10개 이상, purpose `ASSIGNED` 정답 사례 20개 이상을 strata로 보장한다. 출시 기준은 category 정확도 85% 이상, purpose 오연결 5% 이하, purpose 허용 ID 연결률 70% 이상, 의도적 애매 사례 abstain 80% 이상, schema 검증 실패 0건, OpenAI latency p95 15초 이하, 월 20,000건 가정에서 LLM 월 10,000원 hard cap 충족이다.

최종 후보는 120개 개발 사례만으로 선택한다. 독립 evaluator는 holdout의 사례별 결과·점수·집계값을 공개하지 않고 출시 통과/실패만 한 번 반환한다. holdout 실패 뒤 기존 60개는 읽기 전용 retired holdout으로 봉인해 개발 사례·학습 데이터·다음 평가에 쓰지 않는다. 새 holdout은 개발 후보 선택이 끝난 뒤, 라벨에 접근하지 않는 개발팀과 분리된 evaluator가 같은 strata를 충족하도록 선정·라벨하고 봉인한다. 그 새 60개로만 다음 최종 검증을 한다.

## 출시 전 검증

다음 부하 시험을 fake 외부 서비스와 실제 외부 서비스로 각각 수행한다.

완료 시간은 API가 저장을 수락한 시각부터 `READY` 또는 `PARTIAL`이 된 시각까지로 정의한다. `FAILED_RETRYABLE`·`FAILED_TERMINAL`은 완료 p95에서 제외하되 성공률 지표에는 실패로 포함한다.

1. 일반 상품 20개 burst를 Queue가 회복될 때까지 10회 반복한다. 총 200개 중 `READY|PARTIAL` 성공률 98% 이상, 성공 항목 완료 p95 5분 이하, 중복 반영 0건, 마지막 입력 뒤 Queue backlog 회복 2분 이하다.
2. JS-rendered 상품 4개 동시 burst를 Queue 회복 뒤 5회 반복한다. 총 20개에서 browser Worker 최대 2개·동시 dispatch 2개를 넘지 않고, 중복 반영 0건, 성공 항목 완료 p95 5분 이하를 확인한다.
3. 30분 혼합 시험은 30초마다 1개(총 60개)를 넣되 일반 80%·JS 20%로 구성한다. `READY|PARTIAL` 성공률 95% 이상, 자동 retry가 전체 시도의 5% 이하, 마지막 입력 뒤 Queue backlog 회복 2분 이하를 기준으로 한다.

이 결과와 AI holdout 평가가 모두 기준을 통과하면 initial resource·timeout·AI snapshot 설정을 그대로 출시한다. 부하 시험이 실패하면 해당 원인만 조정하고 같은 시나리오로 다시 검증한다. AI holdout이 실패하면 새 holdout을 고정해 최종 검증한다.

## 구현 순서

1. Gradle/Ktor 모듈과 Flyway migration, local Docker·emulator/fake test harness를 만든다.
2. API, Outbox·Cloud Tasks, 일반 Worker와 idempotent AnalysisJob 상태 전이를 구현한다.
3. OpenAI Structured Output adapter·budget guard·evaluation harness를 구현한다.
4. browser Worker·browser Queue와 Playwright fallback을 구현한다.
5. production IAM·secret·CI/CD migration/deploy workflow와 observability를 구성한다.
6. AI holdout·부하 시험을 실행하고 결과로 resource·prompt를 조정한다.
