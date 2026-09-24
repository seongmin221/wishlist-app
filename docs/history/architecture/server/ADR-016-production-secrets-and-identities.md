# ADR-016: production secret과 service identity를 역할별 최소 권한으로 분리한다

> 상태: **확정** · 날짜: 2026-09-20 · 영역: **server·security**

## 맥락

production에는 API Cloud Run, 분석 Worker Cloud Run, Cloud Tasks, Cloud Scheduler와 CI/CD가 있다. 이들은 모두 DB, OpenAI 또는 다른 Cloud Run service에 접근하지만, 동일한 broad 권한이나 장기 service account key를 공유할 필요는 없다.

## 결정

### Secret 보관과 주입

- Neon production connection URL·credential과 OpenAI API key는 production GCP project의 **Secret Manager**에 보관한다.
- API는 Neon secret만, Worker는 Neon·OpenAI secret만 각각 특정 version의 환경변수로 주입받는다.
- Firebase Admin SDK 인증에는 Cloud Run runtime service identity의 Application Default Credentials를 사용한다. Firebase private key 파일이나 `GOOGLE_APPLICATION_CREDENTIALS` 환경변수는 배포하지 않는다.
- secret 값은 source code, Git repository, container image, 일반 환경변수 설정과 CI log에 저장하지 않는다. 값을 회전하면 새 secret version을 지정해 새 Cloud Run revision을 배포한다.

### Runtime identity

- **API runtime service account**는 Firebase token 검증, 자신에게 허용된 Neon secret read, Cloud Tasks task 생성과 task-invoker service account의 `actAs` 권한만 가진다.
- **Worker runtime service account**는 자신에게 허용된 Neon·OpenAI secret read만 가진다. task 생성·서비스 배포·다른 service 호출 권한은 주지 않는다.
- **Cloud Tasks 호출 service account**는 Worker service에 대한 `roles/run.invoker`만 가진다. DB·OpenAI secret에는 접근하지 않는다.
- **Cloud Scheduler 호출 service account**는 API service의 outbox 복구 endpoint 호출을 위해 API service에 대한 `roles/run.invoker`만 가진다. 애플리케이션은 이 identity의 요청을 복구 endpoint로만 제한한다.
- Cloud Tasks와 Cloud Scheduler는 각 호출 service account로 OIDC token을 만들어 private Cloud Run target을 호출한다.

### Deployment identity

- **CI/CD service account**는 GitHub Actions OIDC workload identity federation으로 짧은 수명의 credential을 받아 사용한다. 장기 service account JSON key는 만들지 않는다.
- CI/CD에는 Artifact Registry image push, Cloud Run deploy와 runtime service account attach에 필요한 최소 권한만 준다. 실행 중인 API·Worker의 secret 값 read 권한은 주지 않는다.

## 이유와 trade-off

API·Worker·자동 호출자·배포 주체가 하나의 credential을 공유하지 않으면, 한 구성요소의 잘못된 설정이나 침해가 다른 역할의 권한으로 확대되는 범위를 줄인다. Cloud Run service identity와 Secret Manager를 쓰면 key 파일을 container·repository에 배포하지 않아도 된다.

대신 IAM binding과 배포 설정이 늘고, API가 task를 만들기 위해 task-invoker identity를 `actAs`할 수 있도록 제한된 권한을 추가로 관리해야 한다. 권한 이름과 binding은 IaC 또는 배포 설정으로 재현 가능하게 관리한다.

## 후속 결정

- production DB migration의 실행 주체·순서·failure와 rollback 절차를 정한다.
- 월 평균 30,000원 비용 상한의 alert threshold와 관측 지표·운영 대응을 정한다.
- CI/CD workflow와 IAM binding을 실제 배포 방식에 맞춰 구현 계획으로 구체화한다.

## 근거

- [Cloud Run Secret Manager 설정](https://cloud.google.com/run/docs/configuring/services/secrets)
- [Cloud Run service identity](https://cloud.google.com/run/docs/configuring/services/service-identity)
- [Cloud Tasks HTTP target OIDC](https://cloud.google.com/tasks/docs/creating-http-target-tasks)
- [Cloud Scheduler HTTP target 인증](https://cloud.google.com/scheduler/docs/http-target-auth)
