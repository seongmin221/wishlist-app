# 카테고리 AI 후보와 stale 보호

카테고리 공개 API는 [API 계약](category-management-api.md), DB 경계는
[상태 영속성과 잠금](wishlist-state-persistence.md)에서 관리한다.
정책 결정 경위는 [B2 제품 결정](../../history/product-planning/mvp/decisions/b2-category-api-policy-2026-10-07.md)에 있다.

## 후보 공급과 snapshot

CategoryCandidateSupply는 현재 transaction의 connection과 claim을 받는다.
CategoryCandidateProvider는 공용 taxonomy 전체와 해당 owner의 미삭제·AI 허용 custom만 공급한다.
후보 저장 transaction을 마친 다음 connection을 반환하고 외부 AI를 호출한다.
TaxonomyCatalog v1은 process에서 한 번 파싱한 catalog를 공유한다.

CandidateSnapshotCodec이 저장과 재사용의 JSON 경계를 담당한다. snapshot을 한 번 읽고
한 번 해석한 결과를 재사용 검증에도 전달한다. 최종 finish는 새 transaction에서 다시 읽어
최신 owner·활성·AI 자격·version을 확인한다. CategoryRef는 알려진 공용 ID와 정규 UUID
custom을 구분하며 알 수 없는 비UUID ID는 공용 후보로 신뢰하지 않는다.

| 저장 형식 | 검증과 재사용 |
| --- | --- |
| schema v1 | 공용 registry 후보만 허용; claim owner·job 관계로 보호 |
| schema v2 | owner_id 일치, custom ID 집합 일치, 관련 version 확인 |
| 잘못된 구조·알 수 없는 ID | 유료 호출·예산 예약 없이 finish의 stale 처리로 전달 |

v2는 categories/purposes ID 배열과 category_labels/purpose_labels object를 유지한다.
custom_categories는 ID를 key로 하는 version/name/parent_id/description/examples object다.
이 snapshot은 내부 표현이며 공개 DTO에 AI 적합성이나 제외 사유를 노출하지 않는다.

## 입력 안전성

저장 원문을 임의로 수정하지 않는다. 로컬 adapter는 지시 무시·system prompt,
URL/HTML/실행 코드와 일부 직접적 위해 지시 패턴을 보수적으로 제외한다.
이는 의미 기반 moderation이 아니며 전체 문맥의 안전성을 판정하지 못한다.
제외된 custom도 상세·목록·수동 지정에 사용할 수 있다.

AI 요청의 developer 메시지에는 고정 지시문만 넣는다. user 메시지는 JSON encoder로
생성한 데이터 문자열이다. custom_categories는 id/parent_id/name/description/examples
객체 배열이므로 이름의 ], ;, :, , 또는 줄바꿈이 데이터 구조를 깨지 않는다.
공용 registry만 compact 문자열을 사용한다.

## Stale 처리

검증 대상은 선택한 custom뿐 아니라 공급했던 모든 custom이다. 후보 편집은 version을
올리지만 동일 내용 PATCH는 version을 유지한다. snapshot 이후 신규 custom 생성은
이미 저장된 snapshot을 무효화하지 않는다. 완료한 상품 전체를 다시 분석하지 않는다.

| 상황 | 동작 |
| --- | --- |
| 후보가 유효함 | 기존 실행 fence를 통과한 결과만 반영 |
| 후보가 stale이고 사용자 미확정 실행임 | 기존 job CANCELLED, 최신 후보용 새 generation/job/outbox를 같은 transaction에 저장 |
| stale 재예약의 일반 실행 예산이 소진됨 | job FAILED, item FAILED_RETRYABLE; 새 job/outbox 없음 |
| 후보 stale로 Partial 또는 Complete를 반환했고 CONFIRMED/DEFERRED 또는 USER/override category가 있음 | AI assignment를 버리고 기존 category 기준 READY/PARTIAL 계산; 연결·review 유지 |
| 유효한 AI 결과가 기존 확정 category·purpose와 다름 | 기존 연결 유지 |
| 목적 결과 | B3부터 [AI 목적 후보](purpose-ai-candidates.md)의 판단/판단 없음 규칙을 따른다. CONFIRMED/DEFERRED·USER·override 상품의 빈 목적은 채우지 않는다 |
| generation/token/lease/lifecycle 또는 실행 identity가 바뀜 | 기존 Worker guard를 따라 반영 차단 |

replacement는 일반·browser 시도 횟수와 최초 시각을 승계한다. generation 합산 3회 또는
가장 이른 최초 시도 후 30분을 넘기면 재예약하지 않고, 읽은 metadata를 반영해 FAILED_RETRYABLE로 끝낸다. category 편집으로 예산을 초기화하지 않는다.
실제 AI 비용 정산은 결과 폐기와 독립적이다. general/browser는 같은 finish repository를
사용하며 Scheduler와 browser runtime 조립은 B5에서 연결했다.

## 토큰 단계와 공용 fallback

유료 입력 상한은 B3부터 2,500(B2 당시 2,000)·출력 80 토큰이며 가격표는 유지한다. 목적 후보를 포함한 단계는 [B3 설계](../../superpowers/specs/2026-10-07-b3-purpose-management-design.md#토큰-단계와-최악-크기)를 따른다. custom 단계의 순서와 의미는 바뀌지 않는다. custom의 가용 예산은 공용 후보,
상품, 출력 schema를 포함한 입력의 실측 상한(B3부터 2,500 토큰)에서 남은 공간이다.
각 단계는 token-count endpoint에서 같은 요청 형태를 검사한다. 단계를 통과한 요청만
유료 호출을 한 번 보내고, 실제 전송한 후보 ID로 응답을 검증한다.

| custom 후보 | 시도 순서 | 상품 metadata 상한 | custom 입력 |
| --- | --- | --- | --- |
| 있음 | 전체 | 2,400 code point | 이름·설명·예시 전체 |
| 있음 | 이름 | 800 code point | 이름만; 설명·예시 제외 |
| 있음 | 최소 | 160 code point | 이름 12 code point; 설명·예시 제외 |
| 있음 | 공용 fallback | 160 code point | 해당 호출에서 custom 제외 |
| 없음 | 전체 → 공용 최소 | 2,400 → 160 code point | 공용 taxonomy만 사용 |

세 custom 단계는 같은 후보 ID 집합을 사용한다. 공용 fallback은 그 호출의 custom만
제외하며 저장된 custom, 수동 지정 및 AI 적합성은 바꾸지 않는다. public 최소도 초과하면
유료 호출 없이 input_too_large로 종료하고 기존 AI_UNUSABLE_RESPONSE/PARTIAL 경로를 따른다.
단계들은 기존 Worker 남은 처리 시간을 공유한다. token-count HTTP 오류의 retry 정책은 유지한다.

검증과 리뷰 결과는 [B2 구현 이력](../../history/architecture/server/b2-category-implementation-2026-10-07.md)과
[후속 리뷰](../../history/architecture/server/b2-review-followup-2026-10-07.md)에 보존한다.
