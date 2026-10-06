# B0 Task 3 — 상태·분석 실행 스키마와 사용자 조회

> 날짜: 2026-10-05 · 브랜치: `server/b0-foundation` · 기준 커밋: `ad7d104`

## 구현 판단

기존 V1~V7은 수정하지 않고 V8 상태·출처 필드와 V9 실행 token·lease·claimed version을 추가했다. current generation은 기존 job의 최댓값으로 backfill하고 job이 없는 상품은 1로 둔다. READY의 category만 실제 지정으로 승격하며 기존 predicted purpose는 자원 부재로 진단에 남긴다. 실제 스키마·backfill 규칙은 [상품 상태 저장 기반](../../../architecture/server/wishlist-state-persistence.md)에 기록했다.

legacy RUNNING job은 migration 시점의 만료 lease와 null token/version으로 보관한다. 복구 시 새 claim이 필요하다. 현재 쓰기 경로를 유효한 claim으로 바꾸는 작업은 Task 4~7에 이어진다.

사용자별 조회 SQL에 owner와 item ID 조건을 함께 두고 상태·nullable 출처·수동 완료 시각·덮어쓰기 집합을 읽는다. 내부 조회는 archived/deleted를 보존한다. 공개 GET을 추가한 변경은 아니다.

신규 생성은 current generation 1과 같은 generation의 job·outbox를 기존 transaction에서 저장한다. 분석 전 category 누락 사유를 명시적으로 EXTRACTION_UNRESOLVED로 기록한다.

## 검증

타입 부재로 컴파일 실패를 확인한 후 repository를 구현했다. V8/V9가 없는 상태에서 migration version, 신규 column, 상태 constraint 테스트가 실패하는 것을 확인한 뒤 migration을 추가했다.

실제 PostgreSQL에서 빈 DB와 V7 데이터의 업그레이드를 검증했다. READY/PARTIAL/실패/PROCESSING/DELETED, 여러 generation과 job 없는 상품, GENERAL/BROWSER RUNNING, 예산 reservation/window/alert·outbox를 포함한다. 신규 필드를 제외한 원본 row JSON 전체를 전후 비교해 기존 데이터 보존을 확인한다. 잘못된 enum·0 version/generation·category 조합·AI purpose·금지 override와 null override를 차단하고 USER+null 목적 해제는 허용한다. 다른 owner·없는 ID는 null이고 내부 archived/deleted 조회와 새 상품/job 초기 상태도 검증했다.

최초 RED 실행 중 기존 `queue publication failure after commit does not lose accepted item()`의 컨테이너 시작이 한 번 시간 초과했다. 이후 관련 10개 테스트 전체를 같은 JDK 17·Colima 설정에서 다시 실행해 모두 통과했다. fixture나 시스템 Docker 설정을 추가 변경하지 않았다. Flyway validate와 재실행도 통과했다.

최종 전체 회귀 `./gradlew test`는 95개 중 94개 통과, 실패·오류 0개, 외부 실제 URL pilot 1개 opt-in skip이었다. 별도 코드 검토에서 구체적인 지적 사항은 없었다.

## 다음 작업

Task 4의 원자 claim과 쓰기 보호를 구현한다. 이번 기록은 B0 전체 완료나 production migration 적용 기록이 아니다.
