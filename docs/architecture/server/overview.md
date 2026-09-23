# Server 구조

> 상태: **부분 확정** — MVP 서버는 Kotlin/JVM + Ktor, Neon PostgreSQL, Firebase Authentication과 Google Cloud Run을 사용한다. 분석 작업은 transactional outbox와 Cloud Tasks로 전달한다.

## 논리 모듈

- **API**: 인증된 요청의 생성·조회·수정, 짧은 응답
- **Application**: wishlist/product/category use case와 transaction 경계
- **Extraction**: URL 검증, fetch, HTML parser, canonicalization
- **Classification**: taxonomy 입력 구성, 실행 방식과 공급자에 독립적인 LLM inference adapter 호출, 결과 검증
- **Worker**: queue 소비, retry, 중복 전달에도 안전한 idempotent 상태 갱신
- **Persistence**: PostgreSQL repository와 migration
- **Integration**: Auth, queue, LLM, browser rendering adapter

Ktor의 HTTP routing과 plugin은 adapter 계층에 둔다. domain/application 로직이 Ktor, AWS, DB driver에 직접 의존하지 않게 해 향후 테스트와 기술 교체의 비용을 낮춘다.

## 관리형 PostgreSQL과 Auth

- PostgreSQL 제공자는 Neon을 사용한다.
- Auth는 Firebase Authentication을 사용하고 첫 출시에는 Apple·Google 로그인을 제공한다.
- 모바일 앱은 Firebase ID token을 Ktor API에 전달한다. Ktor는 Firebase Admin Java SDK로 token을 검증하고 Firebase UID를 내부 사용자와 연결한다.
- 모바일 앱은 Neon에 직접 접근하지 않는다. 사용자 데이터 접근 권한과 transaction 경계는 Ktor application layer가 소유한다.
- 상품 생성 API는 Firebase Admin SDK로 Bearer ID token을 검증한 UID만 소유자 식별에 사용한다. 내부 `owner_id` UUID는 `firebase:<projectId>:<uid>`의 name-based UUID로 결정적으로 계산해 같은 Firebase 계정의 재전송이 같은 소유자 범위에 속하도록 한다. 요청의 사용자 ID 헤더는 신뢰하지 않는다.
- 초기 주요 사용자는 한국으로 가정하고 Neon과 Ktor를 Singapore에 함께 배치한다.

선택 배경, 비용 가정과 정확한 후속 논의 지점은 [2026-09-19 기술 설계 체크포인트](../../history/architecture/server/technical-design-checkpoint-2026-09-19.md)를 따른다.

## 환경 경계

MVP 초기에는 local과 production만 운용한다. local은 개발·단위·통합 테스트를 위한 환경이며, production만 Cloud Run, Firebase Authentication, Neon PostgreSQL과 Cloud Tasks의 실제 계정을 사용한다. 별도 cloud development·staging은 production 통합 검증 위험이 반복될 때 추가한다.

local은 Docker PostgreSQL로 migration·repository를 통합 테스트하고 Firebase Auth Emulator로 인증 경계를 검증한다. Cloud Tasks·OpenAI adapter는 in-memory fake, extraction은 fixture HTTP server로 대체한다. 실제 Cloud Tasks IAM은 production smoke test, OpenAI 품질·latency는 대표 URL 평가로 검증한다.

production의 Neon credential과 OpenAI API key는 Secret Manager에 두고, API·Worker에 필요한 secret version만 환경변수로 주입한다. Firebase Admin SDK는 Cloud Run service identity의 Application Default Credentials를 사용하며 private key 파일을 배포하지 않는다. Cloud Tasks와 Cloud Scheduler는 전용 service account의 OIDC token으로 private Worker·API endpoint를 호출하고, CI/CD는 GitHub Actions OIDC federation으로 배포한다.

DB schema는 Flyway의 versioned SQL migration 파일로 Git에서 관리한다. local Docker PostgreSQL의 빈 DB에서 Gradle task로 전체 migration과 통합 테스트를 실행하고, CI/CD의 전용 단계가 production Neon에 `validate`·`migrate`를 한 번 적용한 뒤 API·Worker를 배포한다. runtime 서비스는 migration을 실행하지 않으며 destructive change는 expand → migrate → contract로 나눈다.

production migration은 API·Worker와 별도의 Neon DB role·credential을 사용한다. 일반 변경 전에는 Neon restore history를 확인하고, destructive·대량 data 변경 전에는 Neon branch 또는 snapshot을 생성한다. migration 실패 시 deployment를 중단하고, 자동 rollback 대신 forward migration 또는 복구 branch 검증을 거쳐 수동으로 대응한다.

## 비용과 관측

기본 인프라는 평균 월 30,000원 상한으로 관리한다. GCP production project에는 월 20,000원 budget alert를 두고 50%·80%·100%에서 알림을 받으며, Neon은 월 10,000원 목표 비용으로 별도 확인한다. 자동 billing·서비스 중단은 하지 않는다. API·Worker latency·오류·instance, Worker 실행 시간·retry, Queue backlog와 저장부터 분석 완료까지의 p95 시간을 비용과 함께 관측한다.

Worker request timeout은 90초, Cloud Tasks task deadline은 105초로 시작한다. 대표 URL 부하 시험에서 전체 완료 p95 5분, timeout·retry 비율과 queue backlog를 확인해 timeout과 capacity를 함께 조정한다.

production은 전용 Google Cloud project 하나에 Firebase Authentication, Cloud Run, Cloud Tasks, Cloud Scheduler와 Secret Manager를 함께 둔다. Neon은 별도의 production 전용 project·database를 사용한다. staging을 추가할 때는 production 자원을 공유하지 않는다.

## Cloud Run 호스팅

- Ktor API와 상품 분석 Worker는 Cloud Run의 Singapore 리전에 배포한다.
- API는 Cloud Run service와 minimum instance 1을 초기 기준으로 사용한다.
- Worker는 API와 별도 배포하고 유휴 시 scale-to-zero를 허용한다.
- 일반 Worker는 Cloud Tasks의 인증된 HTTP 요청을 받는 Cloud Run service로 구성한다. Playwright는 별도의 scale-to-zero browser Worker service와 browser 전용 Queue에서만 실행한다.
- 초기 API는 request-based billing, 1 vCPU·1 GiB memory·concurrency 20·minimum instances 1·maximum instances 3으로 둔다.
- 초기 Worker는 request-based billing, 1 vCPU·1 GiB memory·concurrency 1·minimum instances 0·maximum instances 5로 둔다.
- Cloud Tasks는 초당 최대 1 dispatch, 최대 5개 동시 dispatch로 시작한다. 실제 부하·비용 측정으로 조정한다.
- browser Worker는 request-based billing, 2 vCPU·2 GiB memory·concurrency 1·minimum instances 0·maximum instances 2로 둔다. browser Queue는 초당 최대 1 dispatch, 최대 2개 동시 dispatch로 시작한다.

선택 근거와 재검토 조건은 [ADR-007](../../history/architecture/server/ADR-007-cloud-run-hosting.md)을 따른다.
Cloud Tasks와 transactional outbox 선택은 [ADR-008](../../history/architecture/server/ADR-008-cloud-tasks-outbox-worker.md)을 따른다.

## 비동기 등록 흐름

1. API가 URL, 사용자 ID를 검증한다.
2. canonical candidate로 재사용 가능한 `Product` 캐시를 찾는다.
3. `WishlistItem`을 만들고, 재사용 가능한 캐시가 있으면 그 시점의 metadata를 항목에 복사한다.
4. 추가 추출이 필요하면 같은 Neon transaction에서 대상 항목을 `PROCESSING`으로 만들고 `AnalysisJob`과 `OutboxEvent`를 등록한다.
   같은 사용자와 `clientSubmissionId`의 동시 저장은 DB unique constraint와 `ON CONFLICT DO NOTHING`으로 한 요청만 생성하고, 다른 요청은 기존 항목을 조회해 재전송 응답을 만든다.
5. API는 처리 완료를 기다리지 않고 item ID와 상태를 응답한다.
6. Outbox dispatcher는 미발행 event를 Cloud Tasks task로 만들고 발행 완료를 기록한다.
7. Cloud Tasks는 OIDC로 인증된 HTTP 요청을 scale-to-zero Worker service에 전달한다.
8. Worker는 새 저장 요청의 대상 상품만 추출한다. 일반 추출이 부족하면 같은 DB transaction으로 `BROWSER_PENDING` 단계·browser `OutboxEvent`를 기록해 browser Worker에 한 번만 넘긴다. 최종 단계는 category·purpose를 OpenAI API 한 번의 구조화 호출로 판단해 해당 `WishlistItem`의 독립된 snapshot을 완성한다. 추출 metadata만으로 `READY`를 기록하지 않으며, 분류 결과가 안전하게 반영된 경우에만 완료한다.
9. 추출 결과는 이후 새 항목 생성에 재사용할 수 있도록 `Product` 캐시에 저장할 수 있지만 기존 `WishlistItem`에는 전파하지 않는다.
10. 성공 시 새 항목 상태를 `READY` 또는 `PARTIAL`로 바꾼다. 실패는 `FAILED_RETRYABLE`과 `FAILED_TERMINAL`로 구분하고 안전한 공개 오류 코드와 내부 진단 정보를 분리한다.

## Product 경계

공용 `Product`는 canonical URL과 추출 metadata를 보관하는 재사용 캐시다. `WishlistItem`은 사용자별 원본 URL, 표시용 상품 정보, 카테고리, 목적과 수동 수정값을 자체 보관하는 독립 snapshot이다. 이 경계는 tracking/affiliate URL이 다른 사용자 데이터에 섞이는 것을 줄인다.

새 항목 생성 시 `Product`의 metadata를 `WishlistItem`에 한 번 복사할 수 있지만 그 뒤에는 어느 방향으로도 동기화하지 않는다. `WishlistItem`은 화면 조회 시 `Product`를 실시간 원본으로 사용하지 않는다. `Product` 정보가 나중에 보완돼도 기존 항목은 바뀌지 않고, 사용자의 항목 수정도 `Product`나 다른 사용자의 항목에 반영되지 않는다.

같은 사용자의 여러 `WishlistItem`이 같은 `Product` 캐시에서 만들어지는 것을 허용한다. 캐시 식별자를 출처나 중복 탐지에 사용할 수는 있지만 항목 데이터 동기화의 근거로 사용하지 않는다. 서버는 활성 중복 후보를 찾아 검토 정보로 제공하지만 자동으로 저장을 거부하거나 항목을 병합하지 않는다. 추출 후 canonical URL이 같다고 밝혀진 경우에도 새 항목과 기존 항목을 함께 반환해 사용자가 어느 항목을 삭제할지 또는 모두 유지할지 결정하게 한다.

아카이브에만 있거나 삭제된 과거 항목은 새 저장을 막지 않는다. 새 저장은 별도 활성 `WishlistItem`을 만들고 기존 아카이브 기록은 변경하지 않는다.

## Archive 경계

아카이브의 대상·스냅샷 내용과 복원·삭제 정책은 [구매 결정 종료](../../product/finish-a-purchase-decision.md)가 소유한다. 서버는 그 아카이브 묶음의 생성·복원·삭제를 하나의 원자적 작업으로 영속화한다.

## 신뢰성 원칙

- DB 기록과 Cloud Tasks 등록의 불일치를 막기 위해 [transactional outbox](../../learning/server/q-and-a/QA-SRV-007-transactional-outbox.md)를 사용한다.
- API가 commit 직후 task 발행을 시도하고 Cloud Scheduler가 1분마다 미발행 outbox를 복구한다.
- dispatcher는 outbox 행을 `FOR UPDATE SKIP LOCKED`로 claim하고 120초 발행 lease를 기록한다. Cloud Tasks 생성에 실패하면 lease를 풀어 다시 시도할 수 있게 하며, task 이름을 고정해 생성 성공 직후 dispatcher가 죽어도 중복 발행을 동일 task로 수렴시킨다.
- 하나의 generation은 최대 3회, 10초부터 최대 10분의 exponential backoff로 전체 30분 동안 재시도한다. 3회 또는 30분 중 하나라도 먼저 도달하면 추가 분석을 막는다.
- Cloud Tasks queue도 `maxAttempts=3`, `minBackoff=10s`, `maxBackoff=600s`, `maxRetryDuration=1800s`로 고정한다. Worker가 DB attempt count와 최초 시도 기준 deadline을 사전 검증하며 소진 시 `FAILED_RETRYABLE`을 기록하고 2xx로 끝낸다.
- Worker는 같은 작업이 중복 전달·실행되어도 상태 전이와 `WishlistItem` 결과가 한 번 처리한 경우와 같은 최종 결과가 되도록 idempotent해야 한다.
- 일반 Worker는 `GENERAL_PENDING → GENERAL_RUNNING`을 DB에서 claim하며 첫 시도부터 30분과 최대 3회를 사전에 검증한다. 실행 중 종료되어 120초 넘게 `GENERAL_RUNNING`에 남은 job은 reconciler가 다시 대기 상태와 새 outbox event로 복구한다. 한도를 소진한 작업은 `FAILED_RETRYABLE`로 기록한다.
- browser Worker도 generation·owner·lifecycle과 `BROWSER_PENDING` 단계 claim을 원자적으로 검증한다. 중복·stale browser task는 결과를 쓰지 않고 2xx로 끝낸다. 대상 사이트 차단·navigation timeout·대상 DNS/연결 오류·추출 부족은 `PARTIAL`을 저장하고 2xx로 끝내며, DB commit 실패·Worker runtime 장애처럼 terminal 상태를 쓰지 못한 인프라 오류만 재시도한다.
- retry 횟수, backoff, 장기 실패와 추출 성공률을 관측 가능하게 만든다. Cloud Tasks retry 소진 후 task가 삭제돼도 `AnalysisJob`의 실패 기록은 보존한다.
- Worker는 AI 요청 후보 snapshot을 기록하고 결과 반영 때 generation·lifecycle·후보 유효성을 재검증한다. stale 결과는 반영하지 않는다.
- runtime은 명시적으로 지원하지 않는 Worker 역할을 설정하면 기동을 거부한다. production API는 DB·Firebase·Cloud Tasks 필수 설정이 없으면 health-only로 조용히 기동하지 않는다. Worker 역할 조립과 private endpoint 배포가 끝나기 전에는 Worker 서비스를 출시하지 않는다.
- [추출 pipeline](extraction-pipeline.md)은 서버 구현의 보안 경계다.
- WishlistItem의 독립 상태 축, idempotency, anchor window와 경쟁 상황은 [WishlistItem 상태 모델과 API 계약](../wishlist-item-state-api.md)을 따른다.

## 기술 미결정 사항

- Worker request timeout, Cloud Tasks task deadline, 비용 alert의 정확한 기준
- 실제 부하·비용 측정에 따른 초기 resource와 queue 용량 조정
- browser Worker의 CPU·memory·concurrency·maximum instance, browser Queue dispatch rate와 비용
- OpenAI API의 실제 token 사용량·품질·latency가 정한 cap과 평가 기준을 충족하는지
