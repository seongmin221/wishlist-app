# ADR-018: Flyway와 versioned SQL 파일로 PostgreSQL migration을 관리한다

> 상태: **확정** · 날짜: 2026-09-20 · 영역: **server·database**

## 맥락

ADR-017은 DB schema를 Git migration 파일과 CI/CD의 별도 적용 단계로 관리하도록 정했지만, Kotlin/Ktor·Gradle 프로젝트에서 migration 파일의 형식과 실행 도구는 정하지 않았다. MVP는 Neon PostgreSQL 하나를 사용하며, 팀이 SQL과 DB 구조 변경을 쉽게 검토할 수 있어야 한다.

## 결정

- PostgreSQL migration 도구로 **Flyway**를 사용한다.
- migration은 database-native **SQL 파일**로 작성하고 `V1__create_initial_tables.sql`, `V2__add_analysis_status.sql`처럼 Flyway의 versioned naming을 사용한다.
- migration 파일은 application repository의 전용 경로에 Git으로 관리한다.
- Gradle task가 local Docker PostgreSQL과 CI의 빈 test DB에 전체 migration을 실행한다.
- production Neon에는 CI/CD의 migration 단계만 Flyway `validate`와 `migrate`를 실행한다. API·Worker application startup은 Flyway를 실행하지 않는다.
- 적용된 versioned SQL 파일은 수정하지 않으며, 수정은 새 migration 파일로 추가한다.

## 이유와 trade-off

Flyway는 PostgreSQL과 Gradle에 연결할 수 있고, SQL을 그대로 migration으로 사용한다. SQL 파일은 실제 DB에 적용되는 변경을 숨기지 않아 schema를 학습·검토하기 쉽다. Liquibase의 XML/YAML/JSON changelog와 rollback·diff 기능은 더 복잡한 database 운영에는 유용하지만, 초기 MVP에는 새 추상 형식과 운영 규칙을 추가한다.

Flyway의 단순성은 destructive migration의 안전을 자동으로 보장하지 않는다. 기존 data를 깨는 변경은 ADR-017의 expand → migrate → contract 원칙과 backup·실행 검토 절차로 다뤄야 한다.

## 후속 결정

- production migration 실행 전 Neon backup 확인, 실행 identity와 failure runbook을 정한다.
- Gradle module·directory 구조와 CI/CD workflow는 구현 계획에서 구체화한다.

## 근거

- [Flyway PostgreSQL 지원](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database)
- [Flyway 문서](https://documentation.red-gate.com/fd)
