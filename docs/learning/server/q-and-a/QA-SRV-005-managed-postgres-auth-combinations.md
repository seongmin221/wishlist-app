# QA-SRV-005: 관리형 PostgreSQL과 Auth를 어떻게 조합하는가

> 상태: **학습 Q&A** · 날짜: 2026-09-15

## 질문

Neon + Firebase Auth, Supabase PostgreSQL + Supabase Auth와 Neon + AWS Cognito는 각각 어떻게 동작하며, 왜 인프라 후보로 선정했는가?

## 짧은 답변

Auth는 사용자가 누구인지 확인하고 token을 발급한다. PostgreSQL은 확인된 사용자의 위시리스트와 상품·분류·작업 상태를 저장한다. 모바일 앱이 Auth token을 Ktor에 보내면 Ktor가 token을 검증하고 사용자 ID에 해당하는 PostgreSQL 데이터만 조회·변경한다.

```text
Apple / Google 로그인
  → Auth가 사용자 확인 및 token 발급
  → 모바일 앱이 token과 함께 Ktor API 호출
  → Ktor가 token 검증 및 사용자 권한 확인
  → PostgreSQL에서 애플리케이션 데이터 조회·변경
```

이 프로젝트는 PostgreSQL 관계 모델을 유지하면서 Apple·Google 로그인을 iOS와 Android에 제공하고, 초기 MAU 1,000명과 월 30,000원 이하에서 운영할 수 있어야 한다. 이를 기준으로 통합형, 역할 분리형과 AWS 중심형을 대표하는 세 조합을 비교했다.

## 세 조합의 차이

| 조합 | 이해하기 쉬운 비유 | 장점 | 핵심 trade-off |
| --- | --- | --- | --- |
| Neon + Firebase Auth | 모바일 출입증은 Firebase, 데이터 창고는 Neon | 모바일 SDK와 JVM token 검증이 성숙하고 두 역할이 분명함 | 두 공급자를 연결하고 사용자 생명주기를 동기화해야 함 |
| Supabase DB + Auth | 출입증 발급소와 창고가 한 건물에 있음 | 설정·계정·DB 관리가 한곳에 모임 | Free 프로젝트 정지 위험, 유료 시작 가격이 현재 전체 예산보다 큼 |
| Neon + Cognito | 대형 서비스용 출입 통제를 작은 앱에 적용 | AWS 표준 인증과 장기 확장성 | IAM·User Pool·App Client 등 초기 설정과 학습 범위가 큼 |

## 왜 Neon + Firebase Auth를 선택했는가

- Firebase Authentication이 Swift/iOS와 Kotlin/Android에서 Apple·Google 로그인을 공식 지원한다.
- Firebase Admin Java SDK를 사용하면 Ktor가 ID token을 검증하고 Firebase UID를 안정적인 사용자 식별자로 받을 수 있다.
- Neon은 일반 PostgreSQL이므로 확정된 transaction, unique constraint, optimistic concurrency와 outbox 설계를 유지할 수 있다.
- 초기 규모에서 DB와 Auth의 무료 구간을 활용해 API·Worker·Queue 예산을 남길 수 있다.
- Auth 공급자와 DB 공급자가 달라도 Ktor가 경계를 소유하므로 모바일 앱이 DB에 직접 의존하지 않는다.

Ktor의 애플리케이션 DB에는 Firebase UID를 연결하는 내부 사용자 행을 둔다. 사용자 삭제, 계정 연결과 탈퇴 시 데이터 삭제는 Firebase와 Neon에 걸친 별도 application use case로 다뤄야 한다. Apple의 비공개 이메일과 Google 계정이 서로 다른 계정으로 생성될 수 있으므로 교차 공급자 계정 연결 정책도 인증 상세 설계에서 확정한다.

## 후보 선정 원칙

- 직접 비밀번호와 token 발급 체계를 구현하지 않는다.
- 모바일 양 플랫폼에서 Apple·Google 로그인을 지원한다.
- Ktor가 표준 token 또는 공식 JVM SDK로 인증을 검증할 수 있어야 한다.
- PostgreSQL을 유지해 기존 상태·transaction 설계를 바꾸지 않는다.
- 실제 출시를 위해 관리형 서비스를 사용하고 self-hosting 부담을 피한다.
- 공급자 가격과 무료 한도는 구현 시점의 공식 자료로 다시 확인한다.

## 관련 설계

- [2026-09-15 기술 설계 체크포인트](../../../history/architecture/server/technical-design-checkpoint-2026-09-15.md)
- [Server 구조](../../../architecture/server/overview.md)
- [WishlistItem 상태 모델과 API 계약](../../../architecture/wishlist-item-state-api.md)
