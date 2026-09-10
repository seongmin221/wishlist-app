# Server 의사결정

## 초기 결정

- **확정**: 상품 저장 API는 비동기 분석 완료를 기다리지 않고 `PROCESSING` 상태를 즉시 응답한다.
- **확정**: Product metadata extraction과 AI classification은 background worker에서 처리한다.
- **확정**: Product와 사용자별 WishlistItem을 분리한다.
- **제안**: 초기 백엔드는 modular monolith로 시작하고 API와 Worker는 논리적 역할만 분리한다.
- **확정**: MVP 서버 프레임워크는 Ktor를 사용한다. Spring Boot는 재검토 가능한 대안이다.
- **검토 예정**: DB/Auth/Queue/Hosting 공급자.

- [ADR-002: MVP 서버 구현 언어는 Kotlin을 우선한다](ADR-002-kotlin-server-language.md)
- [ADR-003: MVP 서버 프레임워크로 Ktor를 채택한다](ADR-003-select-ktor.md)

추가 결정은 `ADR-번호-제목.md` 형식으로 이 폴더에 기록한다.
