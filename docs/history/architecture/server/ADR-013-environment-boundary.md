# ADR-013: MVP는 local과 production 환경만 운용한다

> 상태: **확정** · 날짜: 2026-09-20 · 영역: **server·infrastructure**

## 맥락

MVP는 Cloud Run API·Worker, Cloud Tasks, Firebase Authentication, Neon PostgreSQL과 외부 OpenAI API를 사용한다. 별도 cloud staging 환경은 production과 유사한 통합 검증을 돕지만, 프로젝트·DB·인증 설정·secret·비용을 중복 관리해야 한다.

초기 목표는 월 평균 30,000원 기본 인프라 비용 안에서 제품을 구현·검증하는 것이다. 아직 구현이 시작되지 않았고, staging이 필요한 실제 장애 유형도 확인되지 않았다.

## 결정

- MVP 초기 운용 환경은 **local**과 **production** 두 개로 한정한다.
- 개발·단위·통합 테스트는 local에서 수행하고, Cloud Run·Firebase·Neon·Cloud Tasks의 실제 production 배포는 명시적 배포 절차를 통해서만 수행한다.
- 별도 cloud development 또는 staging 프로젝트·DB·Firebase 앱은 만들지 않는다.
- 다음 조건 중 하나가 반복해 나타나면 staging 추가를 재검토한다.
  - 인증, Queue, DB migration의 통합 검증을 production에서 수행하는 것이 데이터·가용성 위험을 만든다.
  - production 배포 전 실제 Google Cloud 통합을 재현해야만 잡을 수 있는 오류가 반복된다.
  - 여러 기여자·배포 흐름 때문에 production 직접 검증의 권한 분리가 필요해진다.

## 이유와 trade-off

두 환경은 초기 secret·계정·비용 경계를 최소화하고 MVP의 운영 복잡도를 낮춘다. local 테스트에서 실제 managed service 차이로 인한 문제가 남지만, 초기에는 명시적 production 배포·관측과 작은 변경 단위로 관리한다.

staging을 늦추는 결정은 staging을 영구적으로 배제하지 않는다. 실제 통합 위험이 비용과 관리 부담보다 커졌다는 증거가 생기면 같은 production 구조를 복제하는 방식으로 추가한다.

## 후속 결정

- local PostgreSQL, Firebase Authentication, Cloud Tasks와 OpenAI adapter를 어떤 emulator·fake·test double로 구성할지 정한다.
- production GCP/Firebase 프로젝트, Neon production DB·branch, secret 보관과 CI/CD 계정의 분리 기준을 정한다.
- DB migration의 local 검증과 production 적용·rollback 경계를 정한다.
