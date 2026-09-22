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
- 모든 Worker는 같은 `AnalysisJob` generation·lifecycle·owner를 다시 확인해 늦거나 중복된 결과를 반영하지 않는다.

## 추출과 AI 분류

일반 Worker는 URL 정규화·SSRF 검증·HTTP fetch·JSON-LD·OpenGraph·HTML metadata·heuristic parser를 순서대로 사용한다. 결과가 없거나 품질이 낮을 때만 browser Queue에 한 번 작업을 넣어 Playwright를 실행한다. browser 단계도 실패하면 무한 재시도하지 않고 `PARTIAL`과 사용자 직접 보완으로 끝낸다.

추출 metadata와 허용 taxonomy, 최근 활성 목적 10개 이하를 넣어 `gpt-5.6-luna`에 Responses API Structured Output 호출을 한 번 수행한다. `reasoning.effort`는 `none`이다. 응답은 category `ASSIGNED | ABSTAINED`, purpose `ASSIGNED | UNASSIGNED`로 검증한다. 허용되지 않은 ID·refusal·content filter는 `PARTIAL`, 일시적 네트워크·429·5xx는 기존 3회·30분 retry 정책을 따른다.

입력은 4,096 token, 출력은 256 token으로 제한하며 raw prompt·response는 저장하지 않는다. `store: false`, canonical URL query 제거, Secret Manager의 OpenAI key 사용을 적용한다.

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

holdout 통과 기준은 category 정확도 85% 이상, purpose 오연결 5% 이하, 의도적 애매 사례 abstain 80% 이상, schema 검증 실패 0건, OpenAI latency p95 15초 이하, 월 20,000건 가정에서 LLM 월 10,000원 hard cap 충족이다.

## 출시 전 검증

다음 부하 시험을 fake 외부 서비스와 실제 외부 서비스로 각각 수행한다.

1. 일반 상품 20개 burst: 완료 p95 5분, duplicate·retry·backlog 확인
2. JS-rendered 상품 4개 동시: browser Worker 2개와 Queue 격리 확인
3. 일반·JS 상품 30분 혼합: backlog가 지속 증가하지 않고 failure·retry가 비정상 증가하지 않는지 확인

이 결과와 AI holdout 평가가 모두 기준을 통과하면 initial resource·timeout·AI 모델 설정을 그대로 출시한다. 어느 하나라도 실패하면 해당 원인만 조정하고 같은 고정 평가·부하 시험으로 다시 검증한다.

## 구현 순서

1. Gradle/Ktor 모듈과 Flyway migration, local Docker·emulator/fake test harness를 만든다.
2. API, Outbox·Cloud Tasks, 일반 Worker와 idempotent AnalysisJob 상태 전이를 구현한다.
3. OpenAI Structured Output adapter·budget guard·evaluation harness를 구현한다.
4. browser Worker·browser Queue와 Playwright fallback을 구현한다.
5. production IAM·secret·CI/CD migration/deploy workflow와 observability를 구성한다.
6. AI holdout·부하 시험을 실행하고 결과로 resource·prompt를 조정한다.
