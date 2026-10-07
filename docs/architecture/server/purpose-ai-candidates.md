# AI 목적 후보와 반영 보호

공개 API는 [목적 API 계약](purpose-management-api.md), DB 경계는 [상태 영속성](wishlist-state-persistence.md#b3-목적-schema와-잠금)에서 관리한다.
category 후보는 [카테고리 AI 후보](category-ai-candidates.md)를 따르며, 이 문서는 같은 경로에 목적을 더한 부분을 다룬다.
정책 경위는 [B3 제품 결정](../../history/product-planning/mvp/decisions/b3-purpose-api-policy-2026-10-07.md)에 있다.

## 후보 공급과 snapshot v3

`CategoryCandidateProvider`는 같은 connection에서 `PurposeCandidateReader`를 호출한다. 후보 저장 transaction을 마친 뒤에 외부 AI를 호출한다.

- 목적: owner의 ACTIVE 목적을 `activity_at DESC, id DESC` 순서로 최대 10개 고른다. 빈 목적도 포함한다. 이름·설명 원문을 담는다.
- 상품명: 목적별 ACTIVE 후보 상품명 최대 2개를 서버 `created_at DESC, id DESC` 순서로 담는다. 분석 대상 상품과 빈 이름은 빼고, 각 20 code point로 자른다.
- 목적 텍스트에는 사용자 category의 AI 후보 안전 제외를 적용하지 않는다. 결정 근거는 B3 제품 결정에 있다.

`CandidateSnapshotCodec` schema v3는 `purposes`를 **활동 순위 순서 그대로의 배열**로 저장한다. 각 원소는 `{id, name, description, item_names}`다.

- v3에는 `purpose_labels`가 없다. codec은 ID 중복, 정규 UUID, 최대 10개를 검사한다.
- decode는 순서를 보존한다. 재시도는 저장된 snapshot을 그대로 쓰므로 첫 시도와 같은 alias·축소 대상을 만든다.
- v1/v2의 `purposes`·`purpose_labels`는 목적 테이블이 생기기 전의 문자열 ID다. gateway는 이 값을 보내지 않고, 그 결과의 목적은 판단 없음으로 처리한다.

## 요청 형식

목적은 JSON object 배열 `{"id":"P01","n":이름,"d":설명,"i":[상품명]}`으로 user 데이터 메시지에 넣는다.

- 목적 ID는 snapshot 순서대로 `P01`~`P10` alias로 보낸다. 출력 80 token 안에 custom UUID와 목적 UUID가 함께 들어가지 않게 하기 위해서다. 응답 alias는 실제 ID로 되돌린다.
- developer 지시문은 고정 문장이다. 목적 key 범례는 목적을 보낼 때만 붙여, 공용-only 요청의 크기를 바꾸지 않는다.
- 응답은 실제 전송한 alias 집합으로만 검증한다. 보내지 않은 alias나 실제 UUID는 Unusable이다.

## 토큰 단계

유료 입력 상한은 2,500, 출력은 80이다(`PriceTable.MAX_INPUT_TOKENS/MAX_OUTPUT_TOKENS`).

- 호출 1회 최대 예약액은 596 micro USD다.
- 일·월 ceiling 721,000·7,210,000 micro USD는 2,000 상한 때와 같은 최대 처리 건수를 유지한다.
- 단계는 내용을 빼기만 한다. 흔한 입력은 앞 단계에서 들어가므로 앞 3단계를 순서대로 검사하고(T0이 넘고 T1이 들어가면 2회), 그 뒤 단계는 상품 길이·custom·목적 절반처럼 큰 덩어리를 빼서 token 수가 확실히 줄어드는 구간이라 이분 탐색한다. 들어가는 가장 앞 단계를 고르며 token-count는 최대 6회, 같은 본문인 단계는 하나로 합친다. 통과한 단계 하나만 유료 호출한다.

| 단계 | 상품 | custom | 목적 |
| --- | --- | --- | --- |
| T0 | 2,400 | 전체 | 10개 이름+설명+상품명 |
| T1 | 2,400 | 전체 | 10개 이름+설명 |
| T2 | 2,400 | 전체 | 10개 이름만 |
| T3 | 800 | 이름만 | 10개 이름만 |
| T4 | 160 | 이름 12 | 10개 이름만 |
| T5 | 160 | 제외 | 10개 이름만 |
| T6 | 160 | 제외 | 활동순 5개 이름만 |
| T7 | 160 | 제외 | 제외(공용 최소) |

입력 형태별 실행 단계:

| 입력 | 실행 단계 | 비고 |
| --- | --- | --- |
| custom·목적 모두 없음 | T0 → T7 | B2 공용-only와 같다 |
| custom만 있음 | T0 → T3 → T4 → T7 | B2 tier 0~3과 1:1이다 |
| 목적만 있음 | T0 → T1 → T2 → T5 → T6 → T7 | T3/T4는 custom이 있을 때만 쓴다. 목적이 5개 이하이면 T6을 생략한다 |
| 둘 다 있음 | T0~T7 전체 | token-count는 최대 6회이며 Worker의 남은 처리 시간을 공유한다 |

목적 근거를 먼저 줄인다. 그래서 custom 설명·예시는 B2보다 일찍 빠지지 않는다.

추정 크기(보수적 가정: 한글 1자=1 token, ASCII 2.5자=1 token, 고정분 ≈947)는 [B3 설계](../../superpowers/specs/2026-10-07-b3-purpose-management-design.md#토큰-단계와-최악-크기)에 있다.
- 최악 입력도 T4에서 목적 10개 이름과 custom 이름이 남는다.
- 보통 입력의 상품명 근거는 목적이 5개 안팎일 때 전달되고, 10개면 T1로 내려가 빠진다.
- 실제 값은 runtime token-count가 결정한다.

## 반영과 보호

목적 결과는 판단과 판단 없음으로 나눈다. 판단 여부는 `analysis_jobs.pending_purpose_judged`에 저장하며, claim 때마다 false로 초기화한다.

| 결과 | 분류 | 상품 반영 |
| --- | --- | --- |
| 유효 v3 snapshot에서 고른 목적이 같은 owner·ACTIVE이고 이름·설명이 snapshot과 같음 | 판단 | 연결 가능 |
| 목적 후보 전부를 보낸 단계(T0~T5)의 목적 미지정이고, 현재 연결이 없거나 그 목적이 snapshot에 원문 그대로 있음 | 판단 | 기존 AI 연결 해제 가능 |
| 현재 연결 목적이 snapshot 밖(활동순 10위 밖 등)이거나 그 뒤 이름·설명이 바뀐 상태의 목적 미지정 | 판단 없음 | 기존 연결 유지 |
| 고른 목적의 이름·설명 변경, 비활성, 다른 owner, snapshot 밖 ID | 판단 없음 | 기존 연결 유지 |
| v1/v2 snapshot, T6/T7의 목적 미지정 | 판단 없음 | 기존 연결 유지 |
| 색·아이콘만 바뀐 목적 | 판단 | 연결 가능 |

**판단을 반영하지 않는 경우.** 판단이라도 USER 출처, PURPOSE override, CONFIRMED/DEFERRED 상품에는 목적을 쓰지 않는다. 확정·보류 상품의 빈 목적도 채우지 않는다. 이전 B0/B2의 빈 목적 슬롯 채우기는 B3에서 바꿨다.

**연결이 바뀔 때.** 같은 transaction에서 새 목적의 membershipVersion +1과 activity(CANDIDATE_ADDED)를 기록하고, 이전 목적은 membershipVersion만 +1한다.

**재예약하지 않는다.** `CategoryCandidateGuard.valid`는 목적 변경을 보지 않는다. 목적 변경은 snapshot 재사용·최종 반영 어느 쪽에서도 replacement(새 generation/job)를 만들지 않는다. category stale만 B2 규칙(예산 승계 재예약)을 따른다. 목적 생성·편집은 job을 만들지 않는다.

**잠금.** finish는 owner → item → job을 잠근 뒤 목적 row를 갱신한다. 모든 목적 쓰기는 owner 잠금을 먼저 잡으므로 순환 대기가 없다. 목적 검증도 owner 잠금 아래에서 읽는다.

## 관측

Worker는 유료 호출을 보낸 뒤 성공·재시도·실패와 관계없이 `AI classification tier jobId={} tier={} customSent={} purposeSent={}`를 INFO 로그로 남긴다. 사용자 텍스트는 기록하지 않는다. T5~T7 빈도는 이 로그로 확인하며, cloud metric 연결은 B11에서 다룬다.
