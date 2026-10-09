# QA-SRV-013: 읽기 cursor 검증과 SQL/policy 일치

> 학습 Q&A · 2026-10-07 · B4 구현

## 질문

owner/filter cursor를 거절하려면 HMAC 서명이 필요한가? 홈 count SQL과 Kotlin policy는 어떻게 같은 판정을 유지하는가?

## 짧은 답변

B4의 cursor는 권한 증명 없이 읽을 위치만 지정한다. 구조·owner digest·endpoint·scope·용도·시각/UUID 범위를 검증하되 SQL은 항상 인증 owner/scope를 다시 제한한다. 자기 범위의 유효 위치 변경은 다른 사람의 데이터 접근으로 이어지지 않는다. HMAC은 내용 변경을 탐지할 수 있지만 secret 운용·키 교체에 따른 기존 cursor 무효화 비용을 추가한다. 변경 불가 위치라는 별도 요구가 생기면 도입을 재검토한다. Base64와 digest를 서명이나 암호화로 설명하지 않는다.

SQL은 공통 requiredAction CASE와 group 표현을 한 곳에 정의하고, JDK Char.isWhitespace와 같은 28문자 상수를 bind해 btrim으로 판정한다. NBSP·U+2007·U+202F·U+2028/2029는 blank, U+0085는 blank가 아니다. category는 public/custom coalesce를 함께 평가한다. 모든 BMP 문자와 상수를 대조하고 실제 FK/CHECK를 유지한10,080개 상태·이름 조합에서 각 행의 SQL 결과와 Kotlin evaluate를 비교한다. FK가 허용하지 않는 blank category는 제약을 풀지 않고 SELECT-derived row로 표현을 검증한다.

홈 count와 preview SQL이 같아도 별도 transaction이면 Worker commit 사이에 서로 다른 상태를 읽을 수 있다. 같은 read-only repeatable-read connection에서 count/key·batch projection·목적 summary를 읽고, 첫 SELECT 후 다른 connection commit을 latch로 삽입하는 테스트로 snapshot 일치를 검증한다.

## 구현 근거

- [B4 조회 계약](../../../architecture/server/wishlist-item-read-api.md#b4-공통-조회-계약)
- [B4 구현 이력](../../../history/architecture/server/b4-read-api-implementation-2026-10-07.md)
- [공통 SQL 판정 테스트](../../../../server/src/test/kotlin/app/wishlist/WishlistReadPolicyParityTest.kt)
- [동시 snapshot 테스트](../../../../server/src/test/kotlin/app/home/HomeReadSnapshotTest.kt)

## 빈 페이지와 반복 비용 보완 (2026-10-09)

요청 사이 삭제·이동·처리로 다음/이전 page가 비어도 반대쪽에 남은 항목을 가리키는 cursor를 반환해야 한다. 일반 배타적 cursor로 그 생존 항목을 기준 삼으면 그 항목 자체를 건너뛰므로, 복귀 cursor 내부에 inclusive 용도를 담는다. 다른 owner/scope/endpoint 거절과 카드 ANCHOR 용도 분리는 유지한다. 완전히 빈 scope에서만 두 방향 cursor가 null이다.

owner digest는 응답당 한 번 만들고 카드와 앞뒤 cursor에 공유한다. 페이지 token의 방향은 한 번 decode해서 읽는다. HOME-02의 count와 key 쿼리는 materialized 분류를 공유하는 한 SQL로 합쳐 CASE 중복 계산을 제거한다. 정확한 최신 totalCount를 유지하는 한 owner ACTIVE 전체 판정 비용 자체는 남는다. 별도 API 계약 변경 없이 합계를 생략하거나 오래된 캐시로 바꾸지 않는다.

## 공통 window SQL과 materialization의 경계

ITEM-02/HOME-02의 같은 window 동작을 유지하려면 fallback·방향·inclusive 선택을 하나의 reader가 구성하게 한다. 일반 scope의 eligible은 NOT MATERIALIZED로 두어 cursor 조건이 table/index로 내려갈 수 있게 한다. 홈은 동일 우선순위 조건에서 group CASE를 직접 생성하고 단일 MATERIALIZED classified에 저장한다. actions와 classified를 각각 저장하거나 inline action CASE를 group 분기마다 반복하지 않는다. HOME-02 eligible도 NOT MATERIALIZED로 둔다. [PostgreSQL CTE materialization](https://www.postgresql.org/docs/current/queries-with.html#QUERIES-WITH-CTE-MATERIALIZATION)

이 선택은 설계 근거이며 실제 성능 측정을 대신하지 않는다. 실제 service의 SQL·bind를 capture해서 EXPLAIN해야 한다. 모든 조회는 placeholder 순서의 값 List를 bindParameters로 전달하며 SQL의 물음표를 임의로 치환하지 않는다. 첫 페이지 navigation은 Boolean bind로 EXISTS를 건너뛰므로 navigation SQL 형태를 별도로 분기하지 않는다.

## 목적 cursor의 시각 범위

범용 microsecond 변환과 endpoint 범위 검증을 나눈다. 목적 목록은 PostgreSQL MIN_TIMESTAMP를 Unix epoch로 바꾼 -210866803200000000 이상·signed Long 범위를 encode/decode 모두에 적용한다. 1970년 이전·year10000도 저장 가능한 시각이면 왕복한다. PostgreSQL 밖의 음수 시각은 SQL에 bind하기 전에 cursor 오류로 거절한다. 상품 cursor는 승인된 1970~9999 범위를 별도로 유지한다. PostgreSQL의 실제 상수/epoch 기준은 [timestamp.h](https://github.com/postgres/postgres/blob/master/src/include/datatype/timestamp.h)에 있다.

## 같은 snapshot에서 상세 row가 빠지는 경우

HOME-01은 최대4개 preview만 상세 조회하므로 누락 row 수로 전체 count를 정확히 다시 계산할 수 없다. 같은 repeatable-read snapshot에서 선택한 key의 상세 row가 없으면 invariant 위반으로 실패시킨다. count=1/previews=[]를 조용히 성공 응답으로 내보내지 않는다.
