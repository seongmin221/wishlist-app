# 기술 설계 재개 체크포인트 (2026-09-15)

> 상태: **진행 중** · 날짜: 2026-09-15 · 영역: **서버·인프라**

다른 기기나 새 세션에서 인프라 기술 설계 논의를 정확히 이어갈 수 있도록 확정 사항, 검토한 대안과 미결정 질문을 기록한다. 이 문서는 [2026-09-13 기술 설계 체크포인트](TECHNICAL-DESIGN-CHECKPOINT-2026-09-13.md)를 대체한다.

## 현재 구현 상태

- 제품·아키텍처·결정·학습 문서만 존재한다.
- 앱과 서버 구현은 아직 시작하지 않았다.
- 서버는 Kotlin/JVM + Ktor, 클라이언트 UI는 SwiftUI와 Jetpack Compose, 공통 domain/data 계층은 KMP를 사용한다.
- `WishlistItem` 상태 모델과 API 계약까지 확정했고, 현재는 인프라 공급자와 운용 경계를 설계 중이다.

## 이번 논의에서 확정한 기준

### 출시 목표와 규모

- 학습용 배포보다 **실제 공개 출시**를 우선한다.
- 초기 공개 출시 후 첫 3개월은 **MAU 1,000명 이하**를 기준으로 설계한다.
- 용량 산정을 위한 작업 가정은 사용자당 월 20개 저장, 전체 월 약 20,000건의 상품 분석이다. 이는 제품 제한이 아니라 비용과 용량을 비교하기 위한 초기 추정치다.
- 주요 사용자는 한국 거주자로 가정한다.

### 비용과 응답 정책

- 기본 월 인프라 예산 상한은 **30,000원**이다.
- 사용자 요청을 받는 Ktor API는 유휴 상태에서도 바로 응답할 수 있게 유지한다.
- 상품 분석 Worker는 유휴 시 중단되거나 시작이 지연돼도 허용한다. URL 저장 요청은 즉시 끝나고 실제 metadata 추출·AI 분류가 뒤에서 진행되는 기존 구조를 유지한다.
- 30,000원에 외부 LLM API 사용료까지 포함하는지는 아직 결정하지 않았다.

### PostgreSQL과 Auth

- PostgreSQL은 **Neon**, 인증은 **Firebase Authentication**을 사용한다.
- 첫 출시 로그인 수단은 **Apple + Google**이다.
- 모바일 앱은 Firebase에서 로그인하고 ID token을 Ktor API에 전달한다. Ktor는 Firebase Admin Java SDK로 token을 검증하고 Firebase UID를 내부 사용자 ID에 연결한다.
- Ktor만 Neon에 직접 연결한다. 모바일 앱은 Neon에 직접 접근하지 않는다.

```text
iOS / Android
  → Apple 또는 Google 로그인
  → Firebase Authentication이 ID token 발급
  → Ktor API가 token 검증 및 사용자 식별
  → Neon PostgreSQL에서 사용자 데이터 조회·변경
```

## Neon + Firebase Auth를 선택한 이유

- Firebase Authentication은 iOS와 Android에서 Apple·Google 로그인을 공식 지원하고, Ktor가 사용하는 JVM용 Admin SDK로 ID token을 검증할 수 있다.
- Neon은 기존 설계가 요구하는 PostgreSQL transaction, unique constraint와 relational model을 그대로 사용할 수 있다.
- 두 서비스 모두 초기 규모를 수용할 무료 구간이 있어 API·Worker·Queue 호스팅에 예산을 남길 수 있다.
- Auth와 DB가 분리되어도 역할이 명확하다. Firebase UID를 애플리케이션 `users` 테이블에 연결하고, 데이터 접근 권한은 Ktor application layer에서 검사한다.
- Neon은 유휴 compute를 scale-to-zero로 전환했다가 요청 시 자동 재개하므로, 낮은 사용량에서 비용을 줄일 수 있다. 무료 한도와 DB 재개 지연은 출시 전 부하 테스트와 사용량 알림으로 확인한다.

## 검토했지만 선택하지 않은 조합

### Supabase PostgreSQL + Supabase Auth

DB와 Auth를 한 프로젝트에서 운영할 수 있고 Apple·Google 로그인을 지원해 가장 단순한 조합이다. 그러나 Free 프로젝트는 활동이 충분하지 않으면 일시 정지될 수 있고, Pro는 월 $25부터 시작해 현재 전체 예산을 넘는다. 사용자 활동이 적다는 이유로 Auth와 DB가 함께 정지될 가능성을 초기 공개 출시에 받아들이지 않았다.

### Neon PostgreSQL + AWS Cognito

Cognito는 Apple·Google 소셜 로그인을 지원하고 초기 MAU에서 비용이 낮다. 다만 IAM, User Pool, App Client와 provider callback 등 설정 범위가 넓다. 이번 프로젝트는 AWS 학습보다 출시 속도를 우선하므로 선택하지 않았다.

### 제외한 다른 방향

- Neon Auth는 확인한 공식 안내가 React, Next.js와 TanStack 등 웹 생태계에 집중되어 있어 SwiftUI·Android/KMP 첫 출시에 채택하지 않았다.
- Firestore는 PostgreSQL이 아니므로 확정된 transaction·constraint·outbox 설계와 맞지 않는다.
- 직접 구축한 Auth와 self-hosted PostgreSQL은 보안·백업·장애 대응 부담이 커서 검토 대상에서 제외했다.

자세한 개념 비교는 [QA-SRV-005: 관리형 PostgreSQL과 Auth 조합](../learning/server/q-and-a/QA-SRV-005-managed-postgres-auth-combinations.md)을 참고한다.

## 리전과 호스팅 검토 상태

- Neon의 현재 아시아 리전은 Singapore가 가장 적합한 후보다.
- Ktor를 Seoul에 두고 Neon을 Singapore에 두면 한 API 요청 안의 여러 DB round trip이 국가 간 왕복을 반복한다.
- 따라서 **Ktor API와 Worker도 Neon과 같은 Singapore에 배치하는 방향을 우선 검토**한다. 이는 아직 호스팅 공급자와 함께 최종 확정하지 않았다.

현재 공식 자료를 확인한 호스팅 후보는 다음과 같다.

| 후보 | 검토 방향 | 아직 확인할 점 |
| --- | --- | --- |
| Google Cloud Run | API는 request-based billing + minimum instance 1, Worker는 scale-to-zero. Singapore 지원, Firebase와 같은 Google Cloud 계정 사용 | 실제 Ktor idle 비용, queue 결합과 예산 상한 |
| Railway Hobby | Singapore에서 API와 Worker를 별도 service로 배포하기 쉬움 | JVM 두 service의 RAM·CPU 실사용 비용과 예산 예측성 |
| Render | Singapore의 paid web service와 background worker 또는 workflow 사용 | Worker 실행 방식과 두 compute의 월 비용 |

Cloud Run은 현재 조건에서 우선 추천 후보지만 아직 사용자 승인을 받지 않았다.

## 정확한 중단 지점

호스팅 조합을 고르기 전에 다음 질문을 확인하려던 시점에서 논의를 멈췄다.

> 월 30,000원 예산에 외부 LLM API 사용료도 포함되는가, 아니면 Neon·Firebase·API·Worker·Queue 같은 기본 인프라 비용만 포함되는가?

이 답에 따라 Cloud Run minimum instance와 Worker 실행 비용에 배정할 수 있는 금액이 달라진다.

## 다음 논의 순서

1. 월 30,000원의 포함 범위를 확정한다.
2. Google Cloud Run, Railway와 Render의 API·Worker 구성을 같은 예상 사용량으로 비교하고 하나를 선택한다.
3. Queue와 retry/dead-letter 정책, transactional outbox 또는 동등한 작업 전달 보장을 확정한다.
4. 로컬·개발·운영 환경, secret, Firebase service account와 DB migration 운용 경계를 확정한다.
5. JS-rendered 사이트에 Playwright를 적용할 조건과 실행 위치를 확정한다.
6. 인프라 설계 문서를 최종 검토한 뒤 구현 계획을 작성한다.

가격과 무료 한도는 바뀔 수 있으므로 공급자 선택 또는 구현 직전에 공식 자료를 다시 확인한다.

## 다른 기기에서 사용할 재개 문구

아래 요청을 그대로 사용한다.

> `docs/history/TECHNICAL-DESIGN-CHECKPOINT-2026-09-15.md`를 기준으로 현재 상태를 확인하고 인프라 설계 논의를 재개하자. 먼저 월 3만 원 예산에 LLM API 비용이 포함되는지 질문한 뒤, 답을 반영해 Ktor API와 Worker 호스팅 후보를 비교해줘. 이미 확정된 Neon PostgreSQL + Firebase Auth와 Apple + Google 로그인 결정은 변경하지 말고, 가격과 제약은 현재 공식 자료로 다시 확인해줘.

## 확인한 공식 자료

- [Neon pricing](https://neon.com/pricing)
- [Neon region status](https://neon.com/docs/introduction/status)
- [Firebase Authentication](https://firebase.google.com/docs/auth/)
- [Firebase pricing](https://firebase.google.com/pricing)
- [Firebase ID token verification](https://firebase.google.com/docs/auth/admin/verify-id-tokens)
- [Supabase pricing](https://supabase.com/pricing)
- [Supabase Free project pausing](https://supabase.com/docs/guides/platform/free-project-pausing)
- [Amazon Cognito pricing](https://aws.amazon.com/cognito/pricing/)
- [Google Cloud Run pricing](https://cloud.google.com/run/pricing)
- [Railway pricing](https://docs.railway.com/pricing/plans)
- [Railway regions](https://docs.railway.com/deployments/regions)
- [Render pricing](https://render.com/pricing)
- [Render regions](https://render.com/docs/regions)

## 관련 프로젝트 문서

- [제품 기능 및 스펙](../product-spec.md)
- [전체 서비스 구조](../architecture/README.md)
- [Server 구조](../architecture/server/overview.md)
- [WishlistItem 상태 모델과 API 계약](../architecture/wishlist-item-state-api.md)
- [ADR-005: WishlistItem 상태와 API 경계](ADR-005-wishlist-item-state-api.md)
