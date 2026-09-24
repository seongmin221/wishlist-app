# AI 구조

> 상태: **확정된 원칙 + 제안된 구현**

## 확정된 원칙

- MVP에서는 자체 모델 학습, GPU 운영, embedding/vector DB/RAG를 하지 않는다.
- MVP의 분류·목적 연결은 OpenAI API를 사용하며, 기본 모델 후보는 `gpt-5.6-luna`다. release에는 실제 model snapshot ID를 고정한다.
- OpenAI API 호출은 최소화하고, 기본 요청은 `reasoning.effort`를 `none`으로 설정하며 Structured Outputs를 사용한다.
- 모바일 on-device와 서버 자체 호스팅 LLM은 MVP에서 제외한다.
- 코드로 확실히 처리할 수 있는 URL 정규화·HTML 구조 parsing은 AI에 맡기지 않는다.
- [추출 pipeline](../server/extraction-pipeline.md)의 deterministic parser는 AI의 입력 metadata를 만들며, AI는 taxonomy 분류·목적 연결 보조 수단으로만 사용한다.
- 카테고리·목적의 사용자 정책, AI 제안·확정·재판단 조건은 [구매 후보 정리의 AI 분류와 목적 연결](../../product/organize-candidates.md#5-ai-분류와-목적-연결)을 따른다.
- AI 구현은 요청마다 허용된 category·purpose ID를 구조화해 전달하고, 응답 ID를 서버 schema로 검증한 뒤 predicted 값과 model/prompt version을 기록한다.
- 출시 공용 taxonomy의 11개 상위 그룹·87개 세부 유형은 [버전 데이터](../../../ai/taxonomy/v1.json)로 고정하며 [제품 문서](../../product/references/product-taxonomy.md)와 일치하는지 테스트한다. AI 후보 snapshot에는 ID뿐 아니라 사람에게 읽히는 그룹·세부 유형 이름도 보존한다.

## 처리 흐름

```text
정규화·절단된 title / brand / merchant / description
  + 공용 taxonomy 및 검증된 사용자 전용 세부 카테고리
  + 최근 활성 목적 최대 10개
  → LLM inference adapter를 통해 OpenAI Responses API **한 번** 호출
  → schema 검증된 category ID 또는 ABSTAINED
  + schema 검증된 purpose ID 또는 목적 미지정
  → predicted 값과 model/prompt version 저장
  → 사용자 수정 시 final 값 갱신
```

`Product` cache는 canonical URL의 추출 metadata만 재사용한다. cache 적중은 사용자별 category·purpose 판단을 생략하지 않으며, MVP는 별도 LLM 결과 cache를 두지 않는다. 재판단은 제품 정책에서 허용한 변경만 입력으로 사용하고, 주기적 재분류 작업을 예약하지 않는다.

사용자 전용 카테고리 입력은 신뢰하지 않는 입력으로 다루고, [구매 후보 정리의 카테고리 선택과 사용자 전용 세부 카테고리](../../product/organize-candidates.md#1-카테고리-선택과-사용자-전용-세부-카테고리)의 검증 결과를 통과한 값만 AI 요청에 넣는다.

명령문 형태의 prompt injection, URL·코드·스크립트 중심 입력, AI 입력에 부적절한 유해 텍스트는 해당 카테고리를 변경하거나 삭제하지 않은 채 AI 후보에서만 제외한다. 사용자는 이 카테고리를 계속 보고 직접 지정할 수 있다. AI 후보 제외 여부는 MVP 화면에 노출하지 않고 내부 상태로 관리한다.

모델에는 허용된 값을 데이터 필드로 구조화해 전달하며, 사용자 텍스트를 모델 지시문으로 해석하거나 공용 taxonomy에 반영하지 않는다.

## 출력·실패·동시성 제어

단일 Structured Output은 `category { id | null, decision: ASSIGNED | ABSTAINED }`와 `purpose { id | null, decision: ASSIGNED | UNASSIGNED }`를 포함한다. `category`가 `ABSTAINED`이면 근거 없는 분류를 만들지 않고 `AI_ABSTAINED` 상태로 남긴다. 목적 미지정은 정상 결과다.

timeout·네트워크 오류·429·5xx만 기존 재시도 정책을 적용한다. refusal, content filter, schema-invalid ID는 재시도하지 않고 안전한 공개 코드의 `PARTIAL`로 끝낸다. 400·401·403, 출력 토큰 한도로 인한 incomplete와 요청 구성 오류는 `FAILED_TERMINAL`과 운영 알림 대상으로 처리한다. Structured Outputs라도 refusal과 incomplete를 별도로 검사한다.

요청 시 `AnalysisJob`에 허용 후보 ID, model/prompt/taxonomy version과 metadata fingerprint를 기록한다. 반영 transaction은 job generation, owner, `ACTIVE` lifecycle, 후보 ID의 현재 유효성을 모두 다시 검증한다. 어느 하나라도 달라진 결과는 반영하지 않고 취소하며, 현재 입력으로 자동 분석이 필요한 항목만 새 작업을 만든다.

## 비용·개인정보·평가 경계

- 시스템 절대 상한은 입력 4,096·출력 256 토큰이다. 개인 사용 단계의 구현 요청 상한은 입력 2,000·출력 80 토큰이다. 월 20,000건은 당장의 예상 사용량이 아닌 확대 단계의 재평가 가정이며, 그 규모의 출시는 별도 비용·품질 평가를 통과해야 한다. 목적 후보는 최대 10개로 제한한다.
- 일별 1,000원·월별 10,000원을 외부 LLM hard cap으로 두고, 각 한도의 80%에서 운영 알림을 낸다. 호출 전에 DB 일·월 budget window에 최대 요청 비용을 조건부 원자 reservation한다. request UUID·job generation·가격표 version·상태를 가진 reservation은 전송 직전에 `IN_FLIGHT`가 되며 120초 lease를 갖는다. 응답 시 실제 비용으로 정산하고 차액을 해제한다. 1분 reconciler는 만료 `RESERVED`만 해제하고 만료 `IN_FLIGHT`는 최대 비용으로 정산한다. reservation을 얻지 못하면 OpenAI 호출·재시도 대신 `PARTIAL`과 `AI_BUDGET_EXCEEDED`로 끝낸다. 가격표 version 변경은 비용 평가와 ceiling 갱신을 거친 release에서만 허용한다.
- 현재 구현된 DB 예산 서비스는 일·월 window를 고정 순서로 잠그고 같은 transaction에서 예약하며, 미전송 만료 건은 해제하고 전송 가능성이 있는 만료 건은 최대 비용으로 정산한다. 예산 사용량이 80%에 도달하면 같은 transaction에 일·월별 알림 사건을 각각 한 번만 기록하고, 발행기가 실패한 전달을 재시도할 수 있다. Responses adapter는 후보 ID를 서버에서 다시 검사하고 alias 대신 snapshot 설정을 요구한다. 일반·browser Worker의 처리 콜백은 추출 뒤 분류 결과를 받아야 `READY`로 끝나며, 예산 소진·무효 응답은 `PARTIAL`로 끝난다. 추출·분류 결과는 우선 `analysis_jobs`의 임시 필드에 보관하고, Worker가 claim과 generation을 확인한 뒤 상품의 결과와 최종 상태를 같은 DB transaction에서 반영한다. 첫 분류 시 후보 ID 목록도 작업에 봉인하여 재시도가 같은 후보를 사용한다. 일반 Worker runtime은 전체 v1 taxonomy를 공급하고 카테고리 분류를 수행한다. 사용자 목적 후보 공급, browser Worker runtime, 예약 reconciler 주기 실행, 알림 채널 연결, 승인된 model snapshot 검증은 남아 있어 아직 출시 가능한 보호로 간주하지 않는다.
- 예산 복구는 `server/`의 `./gradlew runBudgetMaintenance`로 1회 실행할 수 있다. `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`를 요구하며, 예약 만료 정산 뒤 미전달 알림을 발행한다. 현 notifier는 구조화된 경고를 stderr에 남기는 경계까지만 구현되어 있다. Cloud Scheduler의 1분 주기 실행과 Cloud Logging 경고 채널은 배포 설정에서 별도로 연결해야 한다.
- Responses adapter는 87개 taxonomy를 그룹형 `그룹명[ID:유형명,...]`으로 압축한다. `responses/input_tokens`에 동일한 model·input·출력 schema를 보내 실제 입력 토큰 수를 확인하고, 2,000 초과면 분류 호출을 보내지 않는다. 토큰 계산 API가 받지 않는 `store`·`max_output_tokens`·`reasoning` 옵션은 계산 요청에서 제외한다. 2,000/80 기준 최대 $0.000496를 호출 전 예약하고 응답 usage로 정산한다. 현재는 사전 검사 실패처럼 Responses 호출이 발생하지 않은 건도 보수적으로 최대 예약액을 정산하므로, 사용량 차감 정밀화가 남아 있다. 로컬 실행에서만 모델 별칭을 명시적으로 허용하며 production은 snapshot ID를 요구한다. 토큰 계산 호출의 과금 여부와 추가 지연은 아직 운영 검증이 필요하다. 목적 저장·조회 테이블이 없어 현재 runtime의 목적 후보는 비어 있다.
- OpenAI 요청은 `store: false`를 사용하고 query를 제거한 canonical URL 또는 필요한 metadata만 보낸다. raw prompt/response는 애플리케이션 로그·DB에 보관하지 않으며, 개인정보 처리 고지에 외부 API 전송을 명시한다. abuse monitoring 로그의 보존 정책은 `store: false`와 별개다.
- 평가는 최초 출시와 model snapshot·prompt·taxonomy 변경 전에 수행한다. 120개 개발 사례와 독립 evaluator가 출시 통과/실패만 한 번 반환하는 60개 blind holdout을 포함한 180개 대표 corpus로 category 정확도, purpose 오연결·연결률, abstain 품질, 저품질 metadata와 prompt injection 사례를 평가한다. holdout 출시 기준은 category 정확도 85% 이상, purpose 오연결 5% 이하·연결률 70% 이상, 의도적 애매 사례 abstain 80% 이상, 허용되지 않은 ID·schema 검증 실패 0건, OpenAI latency p95 15초 이하와 월 10,000원 LLM hard cap 충족이다. 실패한 holdout은 retired로 봉인해 개발·다음 평가에 쓰지 않으며, 새 평가용 holdout은 독립 evaluator가 새로 선정·라벨·봉인한다.

## 기록할 데이터

- `predicted_category_id`
- `final_category_id` 또는 사용자 override
- `predicted_purpose_id` 또는 목적 미지정
- `final_purpose_id` 또는 사용자 override
- model provider/model alias와 실제 snapshot identifier
- prompt/taxonomy version
- confidence 또는 후보 목록(지원될 경우)
- 분류 시각 및 실패 사유
- 요청 입력 fingerprint·후보 snapshot version·입력/출력 토큰 사용량

이 데이터는 이후 정확도 측정과 taxonomy 개선의 근거가 된다. semantic search, similar product, recommendation이 실제 제품 요구가 되었을 때 embedding 도입을 별도 결정한다.

LLM 비용 범위와 provider/model 변경 재검토 조건은 [ADR-006](../../history/architecture/ai/ADR-006-llm-cost-and-execution-strategy.md), OpenAI API 기본 전략은 [ADR-010](../../history/architecture/ai/ADR-010-openai-low-cost-model-strategy.md), 호출·상태·보호 정책은 [ADR-011](../../history/architecture/ai/ADR-011-ai-classification-control-policy.md), 출시 평가는 [ADR-025](../../history/architecture/ai/ADR-025-openai-classification-release-evaluation.md)를 따른다.
