# Repository 구조

> 상태: **확정** · 최종 갱신: 2026-08-28

## 권장 최상위 구조

```text
repo/
  docs/                 # 기획, 아키텍처, 학습, 결정 이력
  client/
    ios/                # SwiftUI app, Share Extension, Xcode project
    android/            # Compose app, ACTION_SEND receiver
    shared/             # KMP domain/data/API/local cache/sync
  server/               # Kotlin/JVM + Ktor API와 Product Worker
  ai/                   # taxonomy, prompt, evaluation 자산
```

이 구조는 mobile, server, AI의 배포 주기와 책임이 다른 monorepo에 적합하다. 한 repository에서 문서·계약·변경 이력을 함께 보면서도, 각 영역의 build와 runtime을 불필요하게 결합하지 않는다.

## client

`client/ios`, `client/android`, `client/shared` 분리는 KMP 사용 방식과 잘 맞는다.

- `ios/`: SwiftUI UI, Share Extension, iOS 앱 생명주기와 Keychain 같은 platform 코드
- `android/`: Compose UI, Share Receiver, Android 앱 생명주기와 Android 보안 저장소
- `shared/`: domain model, repository, use case, API client, local DB/cache, sync, URL normalization

`shared/`는 UI나 server DB 구현을 알지 않아야 한다. iOS/Android가 공유하는 mobile business/data 계층이다.

## server

MVP의 `server/`는 Ktor API와 Product Worker를 같은 Kotlin/JVM codebase에 둔다. 이는 microservice가 아니라 하나의 modular monolith다.

처음에는 별도 Gradle submodule을 많이 만들지 않고 단일 server application으로 시작한다. API와 Worker는 package와 실행 entry point를 논리적으로 나누며, 실제 운영 요구가 생길 때만 deployment를 분리한다.

```text
server/
  src/main/kotlin/      # API, Worker, domain/application/adapter
  src/test/kotlin/
  src/main/resources/   # 기본 config가 필요할 경우
  build.gradle.kts
```

Ktor server module과 KMP shared module은 서로 다른 Gradle module이어야 한다. 같은 Kotlin 언어를 사용해도 Ktor plugin과 Kotlin Multiplatform plugin을 같은 module에 함께 적용하지 않는다.

## ai

MVP의 `ai/`는 Python 모델 서버나 GPU workload를 의미하지 않는다. 실제 LLM 호출과 classification 실행 로직은 Product Worker가 있는 `server/`에 둔다.

root `ai/`에는 코드와 분리해 versioning할 가치가 있는 AI 자산을 둔다.

```text
ai/
  taxonomy/             # category tree와 taxonomy version
  prompts/              # structured classification prompt template
  evaluations/          # 익명·비민감 sample과 평가 기준
  README.md             # 자산의 소유자·갱신 규칙
```

이 경계는 taxonomy와 prompt를 제품 자산으로 관리하면서도, 실제 runtime을 별도 AI microservice로 과설계하지 않게 한다. 자체 모델 학습 또는 embedding pipeline이 실제 요구가 되면 그때 `ai/`에 별도 실행 코드나 service를 검토한다.

## Gradle build 경계

초기에는 repository 전체를 하나의 거대한 Gradle build로 합치기보다, client와 server의 build를 독립적으로 유지하는 방식을 권장한다.

```text
client/                 # KMP/Android 중심 Gradle build + iOS project
server/                 # Kotlin/JVM + Ktor 중심 Gradle build
```

장점:

- mobile build와 server build의 dependency·plugin·배포 주기를 분리한다.
- KMP와 Ktor의 Gradle 구성을 서로 복잡하게 만들지 않는다.
- server CI와 mobile CI를 필요한 변경에서만 실행하기 쉽다.

trade-off:

- Kotlin dependency version이나 API DTO source를 무조건 공유할 수 없다.
- API contract는 server가 소유하고, mobile은 OpenAPI 또는 명시적인 DTO contract를 기준으로 맞춘다. 소스 코드 공유는 꼭 필요하다고 확인될 때만 검토한다.

## 아직 만들지 않는 최상위 폴더

- `infra/`: Docker, AWS IaC, deployment pipeline을 실제 도입할 때 추가한다.
- `web/`: 공유 위시리스트 웹페이지가 MVP 범위를 벗어나므로 지금 만들지 않는다.
- 별도 `worker/` 또는 `services/`: MVP에서는 server 내부의 logical role로 충분하다.
