# ADR-007: API와 Worker 호스팅으로 Cloud Run을 채택한다

> 상태: **확정** · 날짜: 2026-09-19 · 영역: **server·infrastructure**

## 맥락

초기 공개 출시 후 첫 3개월의 MAU는 1,000명 이하, 상품 분석은 월 약 20,000건으로 가정한다. 사용자 요청을 받는 Ktor API는 유휴 상태에서도 바로 응답해야 하지만, 비동기 상품 분석 Worker는 작업이 없을 때 중단되고 다음 작업에서 시작이 지연돼도 된다. 기본 인프라 월 예산은 30,000원이며 Neon PostgreSQL은 Singapore 리전을 우선한다.

이 조건으로 Google Cloud Run, Railway와 Render를 검토했다.

## 결정

- Ktor API와 상품 분석 Worker의 호스팅 공급자로 **Google Cloud Run**을 채택한다.
- 기본 배포 리전은 Neon과 가까운 **Singapore (`asia-southeast1`)**로 한다.
- API는 Cloud Run service로 배포하고 request-based billing과 minimum instance 1을 초기 기준으로 삼는다.
- Worker는 API와 별도 배포 단위로 두고 유휴 시 scale-to-zero를 허용한다.
- Worker 실행 형태와 Queue는 후속 [ADR-008](ADR-008-cloud-tasks-outbox-worker.md)에서 인증된 HTTP Cloud Run service와 Cloud Tasks로 확정했다.
- API와 Worker의 정확한 CPU, memory, concurrency와 최대 instance는 부하·비용 검증 후 확정한다.

## 이유와 trade-off

- API는 minimum instance로 cold start를 줄이고 Worker는 작업 기반 실행으로 유휴 비용을 줄일 수 있어 서로 다른 응답 정책을 한 공급자에서 표현할 수 있다.
- Singapore 리전을 지원하므로 Neon과의 반복 DB round trip을 같은 지역권에 둘 수 있다.
- Firebase Authentication과 같은 Google Cloud 계정·권한 체계를 활용할 수 있다.
- container 기반이므로 Ktor API와 Worker 코드를 같은 repository와 build 체계로 유지할 수 있다.

대신 Cloud Run만 고르는 것으로 비동기 처리의 신뢰성이 완성되지는 않는다. Queue, IAM, retry, dead-letter, transactional outbox와 비용 알림을 별도로 설계해야 한다. minimum instance는 유휴 중에도 비용이 발생하므로 실제 Ktor memory 사용량과 billing 설정을 배포 전에 측정한다.

## 선택하지 않은 대안

- **Railway**: 배포 경험은 단순하지만 API를 계속 실행하고 Worker만 안전하게 sleep시키는 조건과 JVM resource 비용의 예측성을 추가 검증해야 한다.
- **Render**: web service와 background worker 구분은 명확하지만 서비스별 compute 비용이 기본 예산을 더 빠르게 사용한다.

두 후보를 기술적으로 사용할 수 없다고 판단한 것은 아니다. 초기 요구사항과 Firebase 연계, Worker scale-to-zero 및 장기 운영 경계를 종합해 Cloud Run을 우선 선택했다.

## 재검토 조건

- API minimum instance와 Worker·Queue를 포함한 실제 월 비용이 30,000원 상한을 지속적으로 넘는다.
- Ktor cold start 또는 memory 사용량이 선택한 resource 범위에서 허용 수준을 충족하지 못한다.
- Cloud Run과 Neon Singapore 사이의 실제 지연이나 연결 안정성이 제품 요구를 충족하지 못한다.
- Queue와 Worker 구성의 운영 복잡도가 Railway 또는 Render보다 현저히 커진다.
