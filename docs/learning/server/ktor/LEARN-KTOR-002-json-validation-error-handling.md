---
id: LEARN-KTOR-002
type: learning
area: server
topic: ktor
status: active
summary: HTTP JSON을 DTO로 변환하고, 입력·도메인·내부 오류를 모바일이 처리 가능한 API 오류로 변환하는 방법.
related:
  - LEARN-KTOR-001
  - QA-SRV-001
---

# JSON serialization, validation, 오류 처리

> 단계: **Ktor 학습 4단계** · 최종 갱신: 2026-08-28

## 왜 이 단계가 필요한가

앱이 보내는 JSON은 신뢰할 수 없는 외부 입력이다. server는 JSON의 형식을 읽을 수 있어야 하고, 값이 제품 규칙에 맞는지 검사해야 하며, 문제가 있을 때 Android/iOS가 일관되게 처리할 수 있는 오류 응답을 보내야 한다.

```text
HTTP JSON request
  → deserialize
  → request validation
  → UseCase / domain validation
  → success response 또는 stable error response
```

## serialization과 deserialization

- **Deserialization**: JSON request를 Kotlin DTO로 변환하는 과정
- **Serialization**: Kotlin response DTO를 JSON으로 변환하는 과정

Ktor의 `ContentNegotiation` plugin은 `Content-Type`과 `Accept` header를 사용해 media type을 협상하고, 등록된 converter를 통해 객체와 JSON을 변환한다. MVP에서는 Kotlin ecosystem과의 일관성을 위해 `kotlinx.serialization`을 우선 검토한다. 최종 library 채택은 서버 dependency 선택 단계에서 기록한다.

## DTO는 domain model이나 DB entity가 아니다

```text
CreateWishlistRequestDto
  → CreateWishlistCommand
  → WishlistItem domain model
  → CreateWishlistResponseDto
```

DTO(Data Transfer Object)는 API 경계를 통과하는 데이터 모양이다. DB column이나 내부 domain 객체를 그대로 앱에 노출하지 않는다.

이 분리는 다음을 가능하게 한다.

- DB schema를 바꿔도 API contract를 필요한 경우에만 변경
- 앱이 보내면 안 되는 필드(`userId`, internal status 등) 차단
- API 응답을 mobile UX에 맞게 유지
- Ktor에서 Spring Boot로 transport를 교체해도 core model을 보존

## validation은 세 단계로 생각한다

| 단계 | 질문 | 예: URL 저장 |
| --- | --- | --- |
| HTTP/형식 검증 | 요청이 JSON API 규약을 따르는가? | `Content-Type`이 JSON인지, JSON 문법이 맞는지 |
| Request 검증 | 입력값이 API가 허용하는 모양인가? | URL이 비어 있지 않은지, 길이가 과도하지 않은지, HTTP(S)인지 |
| Domain 검증 | 현재 제품 상태에서 이 작업이 가능한가? | 항목이 사용자의 소유인지, 중복 저장 정책과 충돌하는지 |

mobile의 입력 검증은 빠른 UX를 위해 필요하지만, 보안과 데이터 정합성의 최종 책임은 server에 있다.

SSRF 방어처럼 URL을 실제로 fetch할 때 필요한 검증은 Product Worker의 안전한 fetch policy에서 한 번 더 수행한다. `POST /wishlist`에서 HTTP(S) 여부를 확인했다고 Worker의 보안 검증을 생략하면 안 된다.

## 오류를 HTTP 응답으로 번역하기

handler마다 `try/catch`를 반복하는 대신, Ktor `StatusPages` plugin에 공통 오류 변환 규칙을 둔다. 이 plugin은 예외나 status code를 기준으로 응답을 구성할 수 있다.

초기 오류 응답 shape의 예시는 아래와 같다. API specification 단계에서 최종 확정한다.

```json
{
  "code": "INVALID_URL",
  "message": "HTTP 또는 HTTPS URL만 저장할 수 있습니다.",
  "details": [
    {
      "field": "url",
      "reason": "unsupported_scheme"
    }
  ],
  "requestId": "req_..."
}
```

- `code`: mobile이 안정적으로 분기할 수 있는 기계 친화적 값
- `message`: 사용자에게 보여 줄 수 있는 안전한 설명
- `details`: field 수준 오류가 필요할 때만 제공
- `requestId`: log에서 동일 요청을 찾기 위한 식별자

stack trace, DB host, access token, 외부 provider 응답 같은 내부 정보는 error body에 포함하지 않는다.

## 상태 코드의 기준

| 상황 | 응답 예시 | 의미 |
| --- | --- | --- |
| JSON이 아니거나 JSON 문법이 잘못됨 | `400 Bad Request` 또는 `415 Unsupported Media Type` | API 형식을 해석할 수 없음 |
| URL field가 비었거나 HTTP(S)가 아님 | `400 Bad Request` | 호출자가 고칠 수 있는 입력 오류 |
| 로그인 token이 없거나 만료됨 | `401 Unauthorized` | 다시 로그인 또는 token 갱신 필요 |
| 다른 사용자의 item 수정 시도 | `403 Forbidden` | 인증됐지만 권한 없음 |
| item이 존재하지 않음 | `404 Not Found` | 대상 없음 |
| 중복 저장을 막는 정책과 충돌 | `409 Conflict` | 현재 상태 충돌 |
| 예상하지 못한 예외 | `500 Internal Server Error` | 내부 log와 alert로 조사할 오류 |

상품 metadata extraction이 나중에 실패하는 것은 `POST /wishlist`의 `500` 사유가 아니다. 저장과 queue 등록이 성공했다면 API는 `201 Created`와 `PROCESSING`을 반환하고, Worker가 item 상태를 이후 `FAILED`로 갱신한다.

## 테스트에서 확인할 것

- 올바른 URL JSON은 `201`과 `PROCESSING`을 반환하는가
- body가 없거나 JSON이 깨지면 내부 stack trace 없이 규약된 `4xx` 오류를 반환하는가
- 지원하지 않는 URL scheme을 거절하는가
- 인증·권한·not found 오류가 서로 다른 status/code로 구분되는가
- 예상하지 못한 예외가 안전한 `500` body와 request ID로 변환되는가

## 다음 단계

다음에는 configuration과 secret을 배운다. 환경마다 다른 DB 주소·Queue endpoint·LLM API key를 코드 밖에서 주입하고, Git과 log에 비밀이 남지 않게 하는 방법이다.

## 공식 자료

- [Ktor ContentNegotiation과 serialization](https://ktor.io/docs/server-serialization.html)
- [Ktor StatusPages](https://ktor.io/docs/server-status-pages.html)
