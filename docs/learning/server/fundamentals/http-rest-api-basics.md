# HTTP와 REST API 기초

> 상태: **학습 노트** · 단계: **Ktor 학습 1단계** · 최종 갱신: 2026-08-28

## 서버와 앱이 대화하는 방법

HTTP는 앱과 서버가 요청(request)과 응답(response)을 주고받는 공통 규칙이다. 모바일 앱은 서버의 주소와 원하는 작업을 담아 요청하고, 서버는 결과와 상태를 응답한다.

```text
Mobile app
  → POST /wishlist + JSON body
  → Server
  → 201 Created + JSON response
```

이 프로젝트에서 앱은 상품 URL을 저장할 때 서버에 아래 의미의 요청을 보낸다.

```text
"이 URL을 내 위시리스트에 저장해줘."
```

서버는 URL을 저장한 직후, 분석이 아직 진행 중이라는 상태와 함께 응답한다. 상품 분석 완료를 기다리지 않는다.

## 요청의 구성

| 요소 | 설명 | 위시리스트 예시 |
| --- | --- | --- |
| Method | 요청이 하려는 작업 종류 | `POST`: 새 항목 생성 |
| Path | 서버 기능의 주소 | `/wishlist` |
| Header | 부가 정보 | `Authorization`, `Content-Type: application/json` |
| Body | 요청에 담는 실제 데이터 | `{ "url": "https://…" }` |

`POST`는 새 데이터를 만들 때, `GET`은 데이터를 읽을 때, `PATCH`는 일부 값을 수정할 때, `DELETE`는 삭제할 때 주로 사용한다.

## 응답의 구성

| 요소 | 설명 | 위시리스트 예시 |
| --- | --- | --- |
| Status code | 요청 처리 결과를 짧은 숫자로 표현 | `201 Created` |
| Header | 응답 형식, cache 정책 등 | `Content-Type: application/json` |
| Body | 앱이 사용할 결과 데이터 | 생성된 item ID와 상태 |

예시 응답:

```json
{
  "itemId": "wi_123",
  "status": "PROCESSING"
}
```

`PROCESSING`은 “저장에 실패했다”가 아니라 “URL은 저장됐고 상품 정보 분석이 뒤에서 진행 중이다”라는 뜻이다.

## 상태 코드의 최소 규칙

| 상태 | 의미 | 이 프로젝트에서의 예 |
| --- | --- | --- |
| `200 OK` | 조회·수정 등이 성공 | 목록 조회 성공 |
| `201 Created` | 새 리소스 생성 성공 | 위시리스트 항목 생성 |
| `400 Bad Request` | 요청 형식 또는 입력값이 잘못됨 | URL이 비어 있거나 HTTP(S)가 아님 |
| `401 Unauthorized` | 인증 정보가 없거나 유효하지 않음 | 로그인 토큰 없음/만료 |
| `403 Forbidden` | 로그인했지만 접근 권한이 없음 | 다른 사용자의 항목 수정 시도 |
| `404 Not Found` | 요청한 항목이 없음 | 존재하지 않는 item ID |
| `409 Conflict` | 현재 상태와 충돌 | 정책상 중복 저장을 막는 경우 |
| `500 Internal Server Error` | 서버의 예상하지 못한 오류 | DB 연결 등 내부 장애 |

초기에는 모든 가능한 오류를 미리 만들 필요가 없다. 하지만 mobile이 안정적으로 처리할 수 있게 status code와 오류 body의 형식은 일관되게 유지해야 한다.

## REST는 무엇인가

REST는 HTTP 주소를 “동사”보다 “관리하는 대상(resource)” 중심으로 설계하는 관례다.

```text
좋은 예: POST /wishlist             → 위시리스트 항목 생성
좋은 예: GET /wishlist              → 내 위시리스트 목록 조회
좋은 예: PATCH /wishlist/{itemId}   → 항목의 category 등 일부 변경
좋은 예: DELETE /wishlist/{itemId}  → 항목 삭제
```

`/saveWishlistItem`처럼 동사를 path에 넣는 방식도 동작하지만, REST 관례에서는 HTTP method가 동사의 역할을 맡고 path는 대상을 나타낸다.

REST는 엄격한 규칙집이라기보다, mobile·server·문서가 같은 API를 이해하기 쉽게 만드는 약속이다. 이 프로젝트에서는 일관성과 명확성이 “REST스럽게 보이는 것”보다 중요하다.

## 비동기 처리와 API 응답

상품 분석은 다음과 같이 API 요청과 분리된다.

```text
POST /wishlist
  → DB에 항목 저장: 성공
  → Queue에 작업 등록: 성공
  → 201 + PROCESSING 응답

Worker
  → 상품 페이지 분석
  → AI category 분류
  → 항목을 READY/PARTIAL/FAILED로 갱신
```

따라서 `201 Created`은 “상품 metadata 분석이 완료됐다”가 아니라 “사용자의 위시리스트 저장 요청이 성공적으로 접수됐다”를 의미한다.

## 다음 단계와 연결

다음 단계에서는 Gradle과 Kotlin/JVM으로 이 HTTP 요청을 실제로 받아 처리할 수 있는 Ktor 서버 프로젝트가 어떻게 구성되는지 배운다. 아직 DB, Auth, Queue는 연결하지 않는다.
