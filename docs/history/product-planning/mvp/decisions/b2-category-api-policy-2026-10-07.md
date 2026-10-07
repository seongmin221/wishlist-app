# B2 카테고리 API 정책 확정

2026-10-07 사용자가 제안 세 항목 모두 진행하도록 확인했다.

- 생성 receipt는 계정 데이터가 유지되는 동안 보존하며 시간 만료하지 않는다.
- 진행 중 미확정 실행의 후보가 낡으면 최신 후보로 새 generation/job/outbox를 예약한다. 완료한 상품 전체 재분석과 사용자 CONFIRMED/DEFERRED·USER 연결 초기화는 하지 않는다. 기존 시도 횟수·30분 예산을 승계하며 최대 3회 일반 실행 뒤 FAILED_RETRYABLE로 종료한다.
- owner별 직전 60초 신규 생성 성공 최대 5건이다. 정상 replay는 제외하고 실패는 receipt를 남기지 않는다. 삭제된 category의 성공 receipt도 세며 삭제 후 replay는 409 CATEGORY_NOT_AVAILABLE다.

추가 피드백에 대해 빈/공백뿐인 예시는 422 거절로 확인했다. AI 가격표 2,000/80은 유지하고, custom 정보 축약 후에도 입력을 초과하면 해당 호출에서 custom을 제외하고 공용 taxonomy로 분류하는 제안도 확인했다. 저장된 custom·수동 지정·AI 적합성은 유지한다.

같은 parent의 공백·대소문자 normalized 중복은 이번 작업의 사용자 원지시에 명시되어 있다. NFC도 비교 key에 적용하고 저장 원문은 보존한다. 이름40/설명200/예시5×60 길이는 Unicode code point 단위이며 설명 LF/CRLF를 허용한다. 클라이언트 counter 정합성은 후속 client 구현에서 맞춘다.

상품 초안 취소 뒤 생성 category 수명은 별도 제품 확인 대상이다. B2 생성은 상품 연결과 독립적이며 자동 취소·삭제 API를 추가하지 않는다.

구체적인 HTTP 오류·replay·순서·토큰 tier·잠금 계약은 [B2 서버 계약](../../../../architecture/server/category-management-api.md)을 따른다.

후속 피드백의 공용-only owner도 전체 입력 초과 시 metadata160자의 공용 최소 단계를 시도하도록 반영했다. Unicode filler/방향 표시 문자의 거절 범위는 새로 확대하지 않고 현재 허용 범위를 계약에 명시했다. 이는 기존 저장 원문 보존 규칙을 유지한다.
