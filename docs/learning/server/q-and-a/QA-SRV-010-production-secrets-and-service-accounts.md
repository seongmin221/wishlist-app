# QA-SRV-010: production secret과 service account를 왜 나누는가

> 상태: **학습 Q&A** · 날짜: 2026-09-20

## 질문

Cloud Run API·Worker, Cloud Tasks와 Cloud Scheduler를 쓰는 Wishlist 서버에서 secret과 service account를 왜 역할별로 나누며, 지금 무엇을 결정하는가?

## 짧은 답변

지금 정하는 것은 앱 기능이 아니라 **서버의 출입증과 열쇠 관리**다.

- **secret**은 Neon DB 비밀번호·OpenAI API key처럼 외부에 노출되면 안 되는 값이다.
- **service account**는 Cloud Run API, Worker, Scheduler 같은 서버 구성요소가 Google Cloud에서 자신을 증명하는 전용 신분증이다.

각 구성요소에 필요한 권한만 주면, Worker가 API 전용 작업을 하거나 Scheduler가 DB·OpenAI key를 읽는 일을 막을 수 있다. 하나가 잘못 구성되거나 침해돼도 영향 범위가 작아진다.

```text
모바일 앱
   ↓ Firebase 로그인 토큰
API service account
   ├─ Secret Manager에서 DB·Firebase·OpenAI secret 읽기
   └─ Cloud Tasks에 분석 작업 추가
                    ↓ OIDC 신분증
Worker service account
   └─ DB·OpenAI secret만 읽고 분석 실행

Scheduler service account
   └─ outbox 복구 API만 호출
```

## 이번에 정할 내용

1. 어떤 secret을 Secret Manager에 보관할지
   - Neon connection URL 또는 DB credential
   - OpenAI API key
   - Firebase Admin은 Cloud Run service identity를 사용하므로 private key 파일을 보관하지 않음

2. API·Worker·Scheduler·CI/CD가 각각 무엇을 할 수 있는지
   - API는 분석 task를 만들 수 있다.
   - Worker는 분석 결과를 저장하지만 task를 만들 필요는 없다.
   - Scheduler는 복구 endpoint만 호출한다.
   - CI/CD는 배포만 하고, 앱 실행용 secret을 읽지 않는다.

3. secret을 어떻게 전달할지
   - source code, Git repository, Docker image에 secret을 넣지 않는다.
   - production에서는 Secret Manager가 Cloud Run 시작 시 secret을 환경변수로 전달한다.
   - secret 값을 바꾸면 새 Cloud Run revision을 배포해 적용한다.

로컬 개발에서는 production secret을 복사하지 않고 `.env` 같은 로컬 전용 설정과 emulator·fake를 사용한다.

## 비유

식당으로 보면 API는 주문을 받고 주방 티켓을 발행하는 직원, Worker는 실제 조리사, Scheduler는 밀린 티켓을 확인하는 관리자다. 조리사에게 계산대 금고 열쇠까지 줄 필요가 없고, 관리자가 식재료 창고에 자유롭게 들어갈 필요도 없다. 각자 맡은 일에 필요한 열쇠만 받는다.

## 이 프로젝트에 제안하는 실제 형태

처음에는 하나의 production Google Cloud project 안에 네 개의 전용 신분증을 만든다. 사람의 Google 계정이나 공용 관리자 계정을 서버 실행에 사용하지 않는다.

```text
                    Secret Manager
             ┌───────┴───────┐
             │               │
        DB secret  OpenAI key
             │       │
             ▼       ▼
API service account          Worker service account
  - DB 읽기·쓰기                - DB 읽기·쓰기
  - 분석 task 생성              - OpenAI 호출
  - Firebase Admin 사용         - 분석 결과 저장
             │
             ▼
Cloud Tasks ── OIDC 호출 ──> Worker Cloud Run

Scheduler service account ── OIDC 호출 ──> API의 outbox 복구 endpoint

CI/CD service account ──> 배포만 수행
```

핵심은 다음과 같다.

- **API 신분증**은 사용자 요청을 처리하고 분석 작업을 Queue에 넣는다. Firebase token 검증과 DB 접근이 필요하므로 그 secret을 읽는다.
- **Worker 신분증**은 Queue가 보낸 분석만 실행한다. OpenAI 호출과 분석 결과 저장에 필요한 DB·OpenAI secret만 읽는다. 새 task를 만들거나 사용자 API를 관리하지 않는다.
- **Cloud Tasks용 신분증**은 Worker를 호출하는 권한만 가진다. DB나 OpenAI key를 읽지 못한다.
- **Scheduler용 신분증**은 API의 `outbox 복구` endpoint만 호출한다. DB나 OpenAI key를 읽지 못한다.
- **CI/CD 신분증**은 새 컨테이너를 배포할 수 있지만 실행 중인 앱의 secret 값을 읽지 못한다. GitHub Actions는 장기 비밀번호 파일 대신 OIDC로 짧게 유효한 인증을 받는다.

이 구조는 처음부터 서비스 수를 많이 늘리는 방식이 아니다. 실행 중인 앱은 여전히 API Cloud Run 하나와 Worker Cloud Run 하나다. service account는 서버를 추가하는 것이 아니라, 같은 두 서버와 두 자동 호출자가 서로의 권한을 넘겨받지 않도록 하는 잠금장치다.

## 현재 결정과 다음 단계

현재까지는 production 전용 GCP/Firebase project와 별도 Neon production database를 쓰기로 정했다. 다음으로는 위 역할별 최소 권한, secret 보관 방식과 CI/CD 인증 방식을 확정한다. 실제 권한 이름·배포 절차는 구현 계획에서 구체화한다.

## 공식 자료

- [Cloud Run Secret Manager 설정](https://cloud.google.com/run/docs/configuring/services/secrets)
- [Cloud Run service identity](https://cloud.google.com/run/docs/configuring/services/service-identity)
- [Cloud Tasks HTTP target OIDC](https://cloud.google.com/tasks/docs/creating-http-target-tasks)
- [Cloud Scheduler HTTP target 인증](https://cloud.google.com/scheduler/docs/http-target-auth)

## 관련 문서

- [ADR-015: production project와 database 경계](../../../history/architecture/server/ADR-015-production-project-and-database-boundaries.md)
- [Outbox 발행·재시도·장기 실패 운영](QA-SRV-009-outbox-dispatch-retry-recovery.md)
