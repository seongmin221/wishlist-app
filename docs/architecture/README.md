# 전체 서비스 구조

> 상태: **제안**

초기 구조는 **modular monolith + 비동기 Product Worker**다. 여러 독립 마이크로서비스를 운영하지 않고, 하나의 백엔드 코드베이스에서 API와 Worker 역할을 분리한다.

```text
iOS / Android native UI
  └─ KMP shared domain·data·sync
             │
             ▼
          API 서버
             │  (빠른 저장 응답)
             ▼
        DB + 작업 등록
             │
             ▼
          Queue
             │
             ▼
       Product Worker
       ├─ 안전한 URL fetch
       ├─ metadata extraction
       ├─ AI 분류·정규화
       └─ DB 상태 갱신
```

## 컴포넌트 책임

| 컴포넌트 | 책임 | 포함하지 않는 책임 |
| --- | --- | --- |
| Mobile | 공유 URL 수신, 표시, 오프라인 재시도 | 웹 페이지 scraping, AI 호출 |
| API | 인증, 사용자 데이터 접근, 빠른 생성·조회·수정 | 긴 HTML 분석 작업 |
| Queue | 느린 작업의 전달과 재시도 경계 | 상품 데이터를 영구 보관하는 원본 저장소 |
| Worker | 추출 pipeline, 상태 변경, AI 호출 | 사용자 UI 상태 관리 |
| PostgreSQL | 사용자·상품·분류·작업 상태의 신뢰 가능한 저장 | 대용량 HTML 캐시 또는 vector search |

## 설계 원칙

- 요청 경로와 느린/실패 가능한 작업을 분리한다.
- 코드베이스와 도메인 모델은 가능한 한 하나로 유지한다.
- 외부 서비스는 관리형 서비스를 우선 검토하되, 데이터와 도메인 규칙은 애플리케이션에 둔다.
- 미래 기능을 위한 확장 지점은 남기되, MVP에서 가격 추적·추천 인프라는 만들지 않는다.

세부 구조는 [client](client/README.md), [server](server/overview.md), [AI](ai/overview.md), [repository 구조](repository-structure.md)를 참고한다.
