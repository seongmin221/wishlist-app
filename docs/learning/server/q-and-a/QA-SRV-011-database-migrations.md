# QA-SRV-011: DB migration은 무엇이며 왜 별도로 관리하는가

> 상태: **학습 Q&A** · 날짜: 2026-09-20

## 질문

DB migration은 무엇이고 Wishlist 서버에는 왜 필요한가? 무엇을 조심해야 하며 어떤 방식으로 진행하는 것이 좋은가?

## 짧은 답변

**DB migration은 데이터베이스 표 구조를 바꾸는 버전 관리 파일**이다. 앱 코드는 Git으로 버전을 관리하듯, DB도 “언제 어떤 표·컬럼·인덱스를 추가하거나 바꿨는지” 순서대로 기록해야 한다.

예를 들어 처음에는 위시 상품 표가 이렇게 시작할 수 있다.

```text
WishlistItem
- id
- owner_id
- source_url
- title
```

나중에 분석 상태를 표시하려면 다음 정보가 필요하다.

```text
+ analysis_status
+ review_status
+ created_at
```

이 변경을 직접 production DB 화면에서 손으로 하면, 어느 환경에 무엇을 바꿨는지 잊거나 다른 개발자가 같은 변경을 다시 적용할 수 있다. migration 파일 `001_create_wishlist_item.sql`, `002_add_analysis_status.sql`처럼 순서대로 남기면 모든 DB가 같은 설계에 도달한다.

## 왜 필요한가

1. **앱 코드와 DB 구조를 맞춘다.** 새 코드가 `analysis_status`를 읽는데 DB에 컬럼이 없으면 앱이 실패한다.
2. **변경 이력을 재현한다.** 새 개발자·새 production DB도 첫 migration부터 실행해 같은 표 구조를 만들 수 있다.
3. **안전하게 검토한다.** production DB를 직접 만지는 대신, 변경 내용을 코드 리뷰·local 테스트에서 먼저 확인한다.
4. **실패 원인을 추적한다.** 어떤 DB가 어디까지 바뀌었는지 migration history table로 확인할 수 있다.

## 이 프로젝트에서 권하는 흐름

```text
1. 개발자가 migration 파일을 Git에 추가
       ↓
2. local Docker PostgreSQL을 빈 상태에서 시작
       ↓
3. 모든 migration을 순서대로 적용하고 테스트 실행
       ↓
4. CI/CD가 production Neon에 migration을 한 번 적용
       ↓
5. 성공한 뒤 API·Worker 새 버전 배포
```

앱이 시작될 때 API나 Worker가 migration을 실행하지 않는 것이 중요하다. API와 Worker가 동시에 여러 인스턴스로 시작될 수 있어, 실행 시점의 schema 변경은 경쟁·장애 원인을 만들 수 있다. migration은 배포 흐름의 별도 한 단계가 한 번만 실행한다.

## 특히 조심할 점

### 1. 기존 데이터를 깨는 변경

아래처럼 한 번에 컬럼을 삭제하거나 이름을 바꾸면, 아직 이전 컬럼을 쓰는 구버전 API·Worker가 즉시 실패할 수 있다.

```sql
-- 위험: 기존 앱이 title을 아직 읽고 있을 수 있다.
ALTER TABLE wishlist_item DROP COLUMN title;
```

대신 다음처럼 나눈다.

```text
1. 새 컬럼 추가: product_title_new
2. 새·기존 코드가 모두 동작하도록 데이터 복사 또는 이중 읽기
3. 앱을 새 컬럼 사용 버전으로 배포
4. 이전 앱이 더는 실행되지 않는 것을 확인
5. 다음 migration에서 기존 title 제거
```

이를 **expand → migrate → contract** 방식이라고 한다. 초기 MVP에서도 삭제·이름 변경·NOT NULL 추가처럼 기존 행을 깨뜨릴 수 있는 변경에는 이 순서를 적용한다.

### 2. 큰 표에 오래 걸리는 작업

행이 많아지면 index 생성, 모든 행 update, `NOT NULL` 강제 같은 작업이 DB를 오래 잠글 수 있다. MVP 초기는 데이터가 작아 단순하게 시작하되, 실행 전 SQL의 lock·실행 시간을 확인하고 production 실행 시간을 기록한다.

### 3. rollback을 자동으로 믿지 않기

DB 변경은 삭제·변환처럼 되돌릴 수 없는 경우가 많다. 실패 시 이전 migration을 억지로 역실행하기보다, 안전한 **새 forward migration**으로 수정하는 방식을 기본으로 한다. 배포 전에는 Neon backup·복구 기능과 migration 실행 전 확인 절차를 마련한다.

### 4. migration과 앱 배포 순서

새 컬럼을 추가하는 migration은 먼저 적용해도 구버전 앱이 보통 계속 동작한다. 반대로 새 코드가 필요한 컬럼을 읽기 시작하기 전에 migration이 적용되어 있어야 한다. 그래서 기본 순서는 **호환 가능한 migration → 앱 배포 → 나중에 오래된 구조 제거**다.

## MVP에서의 구체적 기준

- migration 파일은 Git에서 수정하지 않고, 이미 적용된 파일을 바꿔야 하면 새 migration을 추가한다.
- local Docker PostgreSQL에서 빈 DB부터 전체 migration 적용을 CI가 검증한다.
- production Neon migration은 CI/CD의 전용 단계가 한 번 실행하며, API·Worker runtime은 migration 권한을 갖지 않는다.
- destructive migration은 backup 확인, 실행 계획 검토, expand → migrate → contract 순서를 요구한다.
- 실패하면 배포를 멈추고, 원인을 고친 새 migration 또는 호환되는 앱 버전으로 복구한다.

## 다음 결정

이 원칙을 채택한 뒤에는 migration 도구(Flyway, Liquibase 또는 Gradle 기반 도구), production migration 실행 권한과 backup·실패 대응 절차를 결정한다.

## 관련 문서

- [WishlistItem 상태와 API 계약](../../../architecture/wishlist-item-state-api.md)
- [production project와 database 경계](../../../history/architecture/server/ADR-015-production-project-and-database-boundaries.md)
- [local 통합 테스트 의존성](../../../history/architecture/server/ADR-014-local-integration-test-dependencies.md)
