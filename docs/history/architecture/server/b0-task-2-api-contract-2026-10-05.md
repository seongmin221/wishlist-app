# B0 Task 2 — 공통 API 응답과 공개 오류

> 날짜: 2026-10-05 · 브랜치: `server/b0-foundation` · 범위: DTO·오류 기반

## 구현과 계약

`installApiHttpSupport`는 요청마다 서버 UUID를 발급하고 성공·오류 응답에 `X-Request-ID`를 넣는다. 입력 헤더는 신뢰하지 않는다. 공개 오류는 `error.code`, 동일한 `requestId`, 최소 `details`로 구성한다. 상세 값은 `JsonElement`여서 계약의 `currentVersion: 8`을 숫자로 보존한다. 상세 값이 없으면 `{}`다.

기존 생성 API의 인증 오류 401, 잘못된 Idempotency-Key 400, 잘못된 URL 422, 키 재사용 409의 코드와 상태를 유지하면서 공통 helper를 적용했다. 생성 201·Location과 재전송 200·Idempotency-Replayed도 유지한다. 미처리 예외는 원본 메시지를 노출하지 않는 `500 INTERNAL_ERROR`로 변환하며 coroutine 취소는 다시 던진다. 요청 body 파싱도 취소를 삼키지 않는다.

상품·metadata·category·purpose·analysis DTO와 상태 enum serializer를 추가했다. 공통 JSON 설정의 `encodeDefaults`로 null과 기본 출처/행동 필드를 유지한다. DTO의 UUID·시간은 문자열이다. 실제 entity mapper와 상세 조회 연결은 B1에서 구현한다.

가격은 nullable BigDecimal과 JSON 숫자 serializer를 사용한다. JsonPrimitive(Number)를 사용한 첫 구현은 큰 소수 금액을 Double로 변환하는 테스트 실패가 있어 교체했다. `123456789012345.67`을 JSON 숫자로 정확하게 보존한다. 금액 단위와 통화 validation은 이후 metadata 계약에서 확정한다.

## 검증과 실행 판단

새 타입/helper가 없을 때 컴파일 실패를 확인한 뒤 구현했다. 오류 상태·코드, 요청 ID 일치·입력 무시, 예외 메시지 비노출, 숫자 details, explicit null·enum roundtrip, 소수 정밀도를 검증했다. 기존 생성·재전송·충돌 HTTP 테스트와 Firebase owner 테스트도 함께 실행했다.

취소 테스트는 Ktor test engine이 미처리 CancellationException을 기본 500 진단 응답으로 표현하는 점을 반영한다. 예외가 엔진까지 전달되고 API의 `INTERNAL_ERROR` JSON으로 바뀌지 않는지 확인한다. 테스트 엔진의 진단 응답은 production 공개 오류 형식이 아니다.

기존 DB 회귀를 막던 호스트 forwarding 준비 문제는 선행 테스트 fixture 수정으로 해결했다. 상세 기록은 [DB 테스트 호스트 준비 대기](db-test-host-readiness-2026-10-05.md)를 따른다.

최종 검증은 JDK 17과 Colima Docker를 테스트 process에 지정한 `./gradlew test`다. 91개 중 90개 통과, 실패·오류 0개, 실제 외부 URL pilot 1개 opt-in skip이다. 관련 12개 대상 테스트도 통과했다. 별도 코드 검토에서 요구사항과의 구체적인 결함은 발견되지 않았다.

## 남은 범위

B0 전체 완료 기록이 아니다. 다음은 Task 3의 상태·generation·lease DB migration과 owner 범위를 보장하는 repository다. 이후 claim·Worker 쓰기 보호가 이어진다.
