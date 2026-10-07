# C2 전반부 코드 리뷰 후속 보완

> 2026-10-07 · `client/c2-kmp-core` · Task 1~5 리뷰의 우선 수정 3건 반영

## 변경과 근거

- **재분석 결과 병합:** Fake `completeAnalysis`가 이름·카테고리·검토 상태를 분석 결과로 무조건 덮어써, "편집 → 재분석 → 완료"에서 사용자 값이 사라지고 검토가 다시 열렸다. 서버 `AnalysisResultRepository`의 USER 출처 보호, CONFIRMED/DEFERRED 유지, READY만 AI 값 교체, 이름·category가 모두 있을 때만 PENDING 규칙을 Fake에 옮겼다. category 없는 READY는 VALIDATION으로 거절하고, 이유 없는 category 누락은 EXTRACTION_UNRESOLVED로 채운다. debug 기본 backend가 Fake라 C3/C4 화면이 서버와 다른 동작 위에 만들어지는 것을 막는다.
- **ID 정규화:** 시드는 받은 ID를 그대로 저장하고 `get`·`create`만 UUID를 소문자로 정규화해, iOS `UUID().uuidString` 같은 대문자 ID로 시드하면 조회가 NOT_FOUND였다. 저장소의 모든 상품 조회를 같은 정규화 helper로 모으고, 시드는 쓰기 전에 ID를 정규화·검증한다. `BoardSeeds`도 생성 ID를 정규화해 카드 fixture의 item ID가 저장된 ID와 일치한다. UUID가 아닌 ID는 개발용 제어에서도 `get`과 같이 `VALIDATION INVALID_WISHLIST_ITEM_ID`다.
- **정책 평가의 되먹임 제거:** `evaluateItem`이 자신이 이전에 계산해 저장한 `requiredAction`/`allowedActions`를 다시 읽어, 한 번 UNKNOWN이 되면 상태가 바뀌어도 UNKNOWN에 머물렀다. 서버 정책처럼 상태 축만 읽는 함수로 바꾸고, `sanitizeAllowedActions`는 Remote mapper 전용으로 남겼다.

## 후속으로 남긴 리뷰 항목

시드 상품의 같은 `createdAt`으로 인한 목록 순서 무작위, 모델 생성자의 `require`(Swift 생성·6b mapper crash 위험), Fake 생성의 URL 검증 생략, 카테고리 존재 검사 생략, 보관 상품 삭제, 세션 거절 요청의 장애 주입 소모는 별도 작업으로 남겼다.

## 검증

수정 전 새 회귀 8개(정책 2, Fake 6)가 실패함을 확인했다. 수정 후 공통 테스트를 Android host와 iOS simulator에서 각각 실행해 **88개 통과, 실패·오류·skip 0**이다. 플랫폼 앱 테스트와 framework link는 이번 변경 범위(공유 Fake·정책 내부)에 영향이 없어 다시 실행하지 않았다.
