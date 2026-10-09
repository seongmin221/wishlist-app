# QA-SRV-014: 조회 인덱스의 동시 생성·중단 복구·checksum 보존

> 학습 Q&A · 날짜: 2026-10-09

## 질문

쓰기 중단을 줄이면서 인덱스를 만들고 부분 실패 후 재시도하려면 무엇이 필요한가? 공유됐을 수 있는 V15, Java migration checksum과 배포 경로는 어떻게 관리하는가?

## 답변

일반 CREATE INDEX는 빌드 동안 쓰기를 차단한다. CONCURRENTLY는 쓰기를 허용하지만 transaction block 밖에서 실행해야 하며, 중단하면 INVALID 인덱스가 남을 수 있다. IF NOT EXISTS는 이름만 확인하므로 validity와 정의를 별도로 검사해야 한다. [PostgreSQL CREATE INDEX](https://www.postgresql.org/docs/current/sql-createindex.html)

사용자가 공유 적용 여부 불명확으로 원본 V15 보존을 선택했다. ed496fc SQL 바이트/checksum을 유지하고 임시 비트랜잭션 설정을 제거했다. V14 이하 DB의 최초 V15 적용에는 일반 인덱스 생성 잠금이 남는다. V16이 이를 소급해서 없애지 않으므로 그 단계는 유지보수 창에서 검증해야 한다.

## V16 변경 감지와 인덱스 복구

V16은 canExecuteInTransaction=false인 Java migration이다. Java migration은 기본 checksum이 없으므로 getChecksum을 구현한다. 고정 revision 숫자는 실행 helper의 변경을 감지하지 못한다. Gradle processResources가 V16 클래스 소스와 전용 ReadIndexRollout 소스를 `/migration-checksums/V16/`에 묶고, getChecksum은 두 원본 소스 바이트의 CRC32를 계산한다. 컴파일러 bytecode 차이에 의존하지 않는다. 두 실행 소스는 적용 후 변경하지 않고 후속 migration을 추가한다. 새 실행 의존성이 생기면 checksum 입력에서도 빠뜨리지 않아야 한다. [Flyway Java migrations](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/java-based-migrations)

V16은 pg_index의 valid/ready/live, 대상 table, 정렬 컬럼과 ACTIVE predicate를 확인한다. 기대 정의와 일치하는 정상 인덱스는 유지한다. 동일 table의 INVALID 또는 다른 정의는 DROP INDEX CONCURRENTLY IF EXISTS 후 재생성하며, 다른 table의 동명 인덱스는 변경하지 않는다. 누락 인덱스는 CREATE INDEX CONCURRENTLY IF NOT EXISTS 후 재검증한다. 이전 짧은 인덱스도 table을 확인한 뒤 제거한다.

## 별도 배포 job과 실패 이력 재시도

API/Worker의 Main은 migration을 호출하지 않는다. 인덱스 빌드는 서비스 시작 전에 별도 배포 단계에서 수행하며 그 단계의 timeout은 실제 빌드 시간을 수용해야 한다. DB 하나에 job 하나만 실행한다. 기존 DATABASE_URL/DATABASE_USER/DATABASE_PASSWORD를 배포 secret으로 전달하고 `server/`에서 실행한다.

```sh
./gradlew runDatabaseMigrations
```

DatabaseMigrationJob은 validate → 필요한 V16 실패 이력 복구 → migrate → validate 순서로 실행한다. repair는 실패 이력 삭제 외에도 checksum/description/type 재정렬과 누락 migration 삭제 처리를 할 수 있으므로 무조건 실행하지 않는다. [Flyway repair](https://documentation.red-gate.com/flyway/reference/commands/repair)

재시도는 validation 오류가 실패한 V16 하나에만 해당하고, info의 V16이 실제 resolved 상태인 FAILED이며 JDBC type·description·applied/resolved checksum이 현재 V16 artifact와 일치할 때만 허용한다. MISSING_FAILED/FUTURE_FAILED도 같은 validation 오류 코드를 낼 수 있어 오류 코드만 검사하지 않는다. Flyway 11.20의 checksum matching helper는 null을 허용하므로 실제 checksum 두 값을 비교한다. 이 조건을 통과하면 repair 후 validate하고 V16을 재실행한다. 다른 버전 실패·checksum 불일치·누락 migration은 중단한다. 일반 Flyway CLI의 repair는 이 보호 경로를 대신하지 않는다.

## 공통 설정

server/src/main/resources/flyway.conf 한 곳에 postgresql.transactional.lock=false와 classpath:db/migration을 둔다. DatabaseFactory.migrationConfiguration은 같은 설정을 읽어 job/test의 migrate·validate에 사용하며 코드에서 locations를 다시 지정하지 않는다. session advisory lock은 동시 빌드가 Flyway 자신의 transaction 종료를 기다리는 문제를 피한다. V16은 설정이 누락되면 DDL 전에 실패한다. [Flyway PostgreSQL lock](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-postgresql-namespace/flyway-postgresql-transactional-lock-setting)

외부 CLI는 이 conf와 Java migration·Kotlin runtime·checksum 소스가 포함된 서버 artifact를 모두 로드해야 한다. SQL 디렉터리만 로드하면 V16이 빠진다. 배포에는 위 전용 job을 사용한다.

## 검증 경계

부분 실패 재실행·정상 인덱스 유지·다른 table 거절·잘못된 정의 복구·checksum과 retry guard는 직접 단위/component 테스트로 확인했다. 권한 전환 후 전체 회귀에서 실제 PostgreSQL의 V15→V16 upgrade/validate·INVALID 복구·failed V16 재시도와 Gradle packaging을 확인했다. 별도 임시 DB에서도 배포 job의 정상 적용·예상 실패·재시도·OID 유지 재실행을 검증했다. production rollout은 수행하지 않았다. [B4 구현 이력](../../../history/architecture/server/b4-read-api-implementation-2026-10-07.md#권한-전환-후-전체-회귀와-배포-job-검증)에 실행 결과를 구분한다.
