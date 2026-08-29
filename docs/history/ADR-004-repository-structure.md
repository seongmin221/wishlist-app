# ADR-004: client, server, AI 자산을 분리한 monorepo를 사용한다

> 상태: **확정** · 날짜: 2026-08-28 · 영역: **프로젝트 공통**

## 맥락

Wishlist 앱은 native iOS/Android UI와 KMP shared 계층, Kotlin/Ktor server, taxonomy와 prompt 같은 AI 자산을 함께 관리한다. 각 영역은 서로 다른 runtime·build·배포 주기를 가지지만, API contract와 제품 문서를 같은 Git 이력에서 관리할 필요가 있다.

## 결정

다음 최상위 구조의 monorepo를 사용한다.

```text
docs/
client/
  ios/
  android/
  shared/
server/
ai/
```

`client/`와 `server/`는 독립 Gradle build로 유지한다. `client/shared`는 KMP module이고, `server`는 독립 Kotlin/JVM + Ktor application이다.

MVP의 `ai/`는 독립 model-serving service가 아니라 taxonomy, prompt, evaluation 같은 versioned AI 자산의 위치다. 실제 LLM 호출과 classification runtime은 `server`의 Product Worker가 담당한다.

## 이유와 trade-off

- mobile, server, AI 자산의 책임과 build/deployment를 분리한다.
- 제품 문서와 API contract를 같은 repository에서 추적한다.
- KMP와 Ktor의 Gradle/plugin 구성을 분리해 불필요한 build 결합을 피한다.
- server의 API와 Worker를 처음부터 microservice로 나누지 않는다.

대신 client와 server가 Kotlin source를 무조건 공유하지 않는다. API contract는 server가 소유하고, source sharing은 명확한 중복 비용이 확인될 때만 검토한다.

## 보류한 구조

- `web/`: 공유 위시리스트 페이지는 MVP 밖이다.
- `infra/`: Docker/AWS IaC를 실제 도입할 때 추가한다.
- 별도 `worker/`, `services/`, AI model server: MVP에서는 과설계다.
