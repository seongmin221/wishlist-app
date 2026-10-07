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
