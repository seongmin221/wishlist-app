# ADR-015: production은 전용 GCP/Firebase project와 전용 Neon database를 사용한다

> 상태: **확정** · 날짜: 2026-09-20 · 영역: **server·infrastructure**

## 맥락

MVP는 local과 production만 운용한다. production에는 Cloud Run API·Worker, Cloud Tasks, Cloud Scheduler, Firebase Authentication, secret 관리와 Neon PostgreSQL이 필요하다. 이 서비스들의 billing·IAM·감사 경계를 명확히 하면서 local 개발이 production 자원에 직접 의존하지 않게 해야 한다.

## 결정

- production용 **전용 Google Cloud project 하나**를 사용한다.
- Firebase Authentication은 같은 Google Cloud project에 연결한다.
- Cloud Run API·Worker, Cloud Tasks, Cloud Scheduler, Secret Manager와 billing·IAM은 이 production project에서 관리한다.
- Neon은 production 전용 Neon project와 database 하나를 사용한다. local Docker PostgreSQL은 이 database에 접근하지 않는다.
- staging이 추가되면 production과 별도의 GCP/Firebase project 및 Neon database를 사용한다. production project·database를 staging과 공유하지 않는다.

## 이유와 trade-off

GCP와 Firebase를 하나의 production project에 두면 Firebase service account, Cloud Run service identity, Queue와 secret의 IAM 관계를 한 billing·감사 경계에서 관리할 수 있다. Neon은 독립 SaaS 경계이므로 전용 production project·database를 명확히 분리한다.

프로젝트 하나는 MVP 초기 관리 비용을 낮추지만, 내부적으로도 역할별 service account·secret 접근 권한을 분리해야 한다. 이 세부 권한은 후속 설계에서 정한다.

## 후속 결정

- API, Worker, outbox dispatcher·Scheduler, CI/CD가 사용할 service account와 최소 IAM 역할을 정한다.
- Neon connection URL, Firebase Admin credential, OpenAI API key 등 secret의 저장·주입·회전 기준을 정한다.
- production migration의 실행 주체, 실행 순서와 failure·rollback 절차를 정한다.
