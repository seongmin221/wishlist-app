# ADR-014: local은 실제 PostgreSQL과 service adapter의 emulator·fake를 조합한다

> 상태: **확정** · 날짜: 2026-09-20 · 영역: **server·testing**

## 맥락

MVP는 local과 production만 운용한다. local에서도 migration, repository, 인증 경계, queue 발행, LLM 결과 검증과 metadata extraction을 검증해야 하지만, 실제 production Neon·Firebase·Cloud Tasks·OpenAI에 의존하면 비용, 속도와 테스트 격리가 나빠진다.

각 의존성은 실제 동작에 가까운 검증 가치와 local에서의 재현 비용이 다르다.

## 결정

- PostgreSQL은 Docker container로 실행해 실제 migration과 repository 통합 테스트를 수행한다.
- Firebase Authentication은 Firebase Auth Emulator로 ID token 검증 경계를 테스트한다.
- Cloud Tasks는 application이 정의한 queue adapter의 in-memory fake로 테스트한다. production Cloud Tasks HTTP 전달·IAM 자체는 배포 전 작은 production smoke test로 별도 확인한다.
- OpenAI는 고정된 Structured Output·오류 응답을 돌려주는 LLM adapter fake로 테스트한다. 실제 모델 품질·token·latency 평가는 별도 대표 URL 평가에서 수행한다.
- URL fetch와 extraction은 로컬 fixture HTTP server와 저장된 HTML·redirect 사례로 검증한다. 외부 쇼핑몰을 자동 테스트 대상으로 사용하지 않는다.

## 이유와 trade-off

PostgreSQL schema와 migration은 database-specific 동작을 가질 수 있으므로 실제 PostgreSQL container를 쓴다. 반면 Queue와 LLM은 adapter 경계를 통해 fake로 대체하면 재시도·idempotency·결과 검증을 빠르고 결정적으로 검증할 수 있다.

Firebase Emulator와 Docker는 local 실행 준비가 필요하다. 또한 fake만으로 Cloud Tasks IAM 또는 OpenAI의 실제 응답 품질을 보장하지는 않는다. 이 차이는 제한된 production smoke test와 출시 전 대표 URL 평가로 보완한다.

## 후속 결정

- Docker Compose의 service 구성, fixture 관리, test data reset과 CI에서의 실행 방법을 구현 계획에서 정한다.
- production GCP/Firebase project, Neon production DB·branch, secret·service account·CI 권한 경계를 정한다.
- production smoke test의 범위·실행 주체와 DB migration 적용 절차를 정한다.
