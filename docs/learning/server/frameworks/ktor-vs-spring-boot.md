# Ktor와 Spring Boot: 처음 서버를 구축할 때의 비교

> 상태: **학습 노트** · 최종 선택 상태: **Ktor 채택** · 최종 갱신: 2026-08-28

## 먼저 결론

이 프로젝트의 MVP에는 **Kotlin + Ktor를 채택**했다. **Kotlin + Spring Boot**는 가장 강한 재검토 대안이다. 둘 다 AWS의 컨테이너 실행 환경에 배포할 수 있으므로, AWS 사용 여부만으로 둘 중 하나를 고를 필요는 없다.

Ktor는 작은 API와 Worker를 필요한 만큼 명시적으로 조립하는 방식이고, Spring Boot는 자주 필요한 서버 기능을 관례와 자동 설정으로 먼저 제공하는 방식이다.

## 비유

- **Ktor**는 필요한 조리 도구를 골라 배치하는 작은 주방에 가깝다. 구조가 눈에 잘 보이고 가볍지만, 어떤 도구를 어디에 둘지 직접 결정해야 한다.
- **Spring Boot**는 업소용 표준 주방에 가깝다. 오븐, 환기, 안전장치가 잘 갖춰져 있지만, 사용법과 규칙을 익혀야 한다.

둘 다 Kotlin으로 코드를 작성하고, HTTP API와 background Worker를 만들 수 있다.

## 이 앱에서 서버가 실제로 해야 하는 일

```text
POST /wishlist
  → 로그인한 사용자인지 확인
  → WishlistItem을 PROCESSING으로 DB에 저장
  → queue에 추출 작업 등록
  → 즉시 JSON 응답

Worker
  → queue에서 작업 수신
  → 안전하게 상품 URL fetch
  → metadata 추출 및 AI 분류
  → DB 상태 갱신
```

두 프레임워크 모두 위 흐름을 구현할 수 있다. database, queue, LLM API는 프레임워크가 바뀌어도 별도로 선택·연결해야 한다.

## 처음 서버를 만드는 사람의 관점 비교

| 관점 | Ktor | Spring Boot |
| --- | --- | --- |
| 시작 방식 | routing, JSON serialization, 인증, 오류 처리 등 필요한 plugin을 선택해 추가 | `starter` 의존성과 auto-configuration이 일반적인 기본 구성을 제공 |
| 코드 읽기 | 요청 경로와 처리 흐름이 비교적 직접 보임 | annotation, dependency injection, Bean, auto-configuration 규칙을 함께 이해해야 함 |
| 처음 만드는 MVP | 필요한 것만 넣어 작게 시작하기 쉬움 | 관례대로 시작하면 견고한 기반을 빨리 얻지만 처음 보는 구성 요소가 더 많음 |
| DB·transaction | 라이브러리와 transaction 경계를 명시적으로 선택·구성 | Spring의 data/transaction 관례와 통합 선택지가 매우 풍부 |
| 인증·보안 | 구현 및 라이브러리 선택을 더 직접적으로 해야 함 | Spring Security라는 강력한 생태계가 있으나 학습 난이도도 높음 |
| health·metrics | 필요한 endpoint와 metrics 구성을 설계해서 넣음 | Actuator가 health, metrics 등 운영 endpoint를 제공 |
| 문제 해결 자료 | Kotlin 서버 문서와 예제가 충분하지만 Spring보다 선택지가 적음 | 오랜 기간 축적된 자료·사례·라이브러리가 매우 많음 |
| 장기 복잡도 | 규칙을 스스로 잘 정하지 않으면 프로젝트마다 구조가 달라질 수 있음 | 프로젝트가 커질수록 표준 관례와 생태계의 이점이 커짐 |

## AWS 배포에서는 무엇이 달라지는가

실제 AWS 흐름은 프레임워크와 무관하게 거의 같다.

```text
Ktor 또는 Spring Boot 코드
  → Gradle build
  → Docker image 생성
  → Amazon ECR에 image push
  → App Runner 또는 ECS/Fargate가 image 실행
```

- **Amazon ECR**은 Docker image를 보관하는 AWS의 private registry다.
- **App Runner**는 image를 받아 실행·확장·load balancing을 관리하는 단순한 web service 옵션이다.
- **ECS/Fargate**는 container task를 더 세밀하게 구성할 수 있는 옵션이다. Fargate를 쓰면 EC2 서버 자체를 운영하지 않는다.

Ktor는 Gradle plugin으로 fat JAR와 Docker image를 만들 수 있고, Spring Boot도 Docker container packaging을 지원한다. App Runner는 ECR의 container image를 실행하며 시작·실행·확장·load balancing을 관리한다. 즉 MVP 배포 경로에서는 프레임워크 사이의 본질적 차이가 없다.

Spring Boot의 운영상 장점은 Actuator다. 예를 들어 `/actuator/health` 같은 health endpoint를 기본 관례로 제공해 AWS load balancer나 monitoring 도구가 서비스가 살아 있는지 확인하기 편하다. Ktor에서도 health endpoint와 metrics를 만들 수 있지만, 필요한 범위와 노출 정책을 더 직접 결정한다.

## 이 프로젝트에 Ktor를 채택한 이유

- Android와 KMP에서 이미 쓰는 Kotlin 및 coroutine 사고방식을 서버에서도 이어갈 수 있다.
- MVP는 native mobile API와 하나의 Product Worker가 중심이므로, 큰 enterprise 서버 기능을 처음부터 많이 필요로 하지 않는다.
- URL fetch와 LLM 호출은 대기 시간이 큰 I/O 작업이라 coroutine 기반 처리와 잘 맞는다.
- 작은 팀이 필요 없는 추상화와 서비스 분리를 피하기 쉽다.

중요: client와 server를 억지로 한 모듈로 합친다는 뜻은 아니다. server는 Kotlin/JVM의 독립 모듈로 유지한다. KMP와는 API contract나 순수 model을 제한적으로 공유할 수 있지만, DB/인증/Worker 코드를 mobile shared module에 넣지 않는다.

## Spring Boot를 선택해야 할 신호

아래 중 여러 항목이 맞으면 Spring Boot를 더 진지하게 선택한다.

- 서버 개발자와 협업하거나, 나중에 서버 인력을 채용할 가능성이 높다.
- 인증·권한·감사·운영 지표를 빠르게 표준 방식으로 갖추고 싶다.
- 복잡한 DB transaction, 많은 외부 연동, back-office API가 빠르게 늘어날 가능성이 크다.
- Ktor의 자유도보다 Spring의 강한 관례와 방대한 사례를 선호한다.

Spring Boot가 “대규모 서비스에서만 가능한 도구”인 것은 아니다. 작은 서비스에도 잘 쓸 수 있다. 다만 이 MVP에서 처음 접해야 할 서버 개념의 양이 Ktor보다 많을 수 있다.

## 최종 선택 전에 해 볼 최소 검증

Ktor를 선택했지만, 아래 최소 skeleton으로 운영 적합성을 검증한다.

1. `POST /wishlist`가 JSON 요청을 받아 임시 응답을 반환한다.
2. `GET /health`가 서비스 상태를 반환한다.
3. background Worker가 dummy job을 처리한다.
4. Docker image를 만들고 AWS ECR → App Runner 또는 ECS/Fargate에 배포한다.
5. 로컬과 cloud에서 logging, 환경변수, 오류 처리를 확인한다.

이 검증에서 인증·DB·운영 기능의 구성이 지나치게 어렵거나 불명확하다면 Spring Boot로 전환한다. 반대로 구조가 이해 가능하고 반복 개발이 빠르면 Ktor를 채택한다.

## 공식 자료

- [Ktor deployment](https://ktor.io/docs/server-deployment.html)
- [Ktor Docker support](https://ktor.io/docs/docker.html)
- [Spring Boot packaging](https://docs.spring.io/spring-boot/reference/packaging/index.html)
- [Spring Boot Actuator endpoints](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html)
- [AWS App Runner: container image service](https://docs.aws.amazon.com/apprunner/latest/dg/service-source-image.html)
- [AWS ECS/Fargate](https://docs.aws.amazon.com/AmazonECS/latest/APIReference/Welcome.html)
