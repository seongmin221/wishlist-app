# AI 구조

> 상태: **확정된 원칙 + 제안된 구현**

## 확정된 원칙

- MVP에서는 자체 모델 학습, GPU 운영, embedding/vector DB/RAG를 하지 않는다.
- 외부 LLM API를 사용한다.
- 코드로 확실히 처리할 수 있는 URL 정규화·HTML 구조 parsing은 AI에 맡기지 않는다.
- 카테고리는 모델이 새로 만들지 않으며, 사전 정의 taxonomy ID 중에서만 선택한다.
- 사용자가 수정한 category가 최종값이다.

## 처리 흐름

```text
추출된 title / brand / merchant / description
  + taxonomy 및 버전
  → LLM 분류 요청
  → schema 검증된 category ID 응답
  → predicted 값과 model/prompt version 저장
  → 사용자 수정 시 final 값 갱신
```

## 기록할 데이터

- `predicted_category_id`
- `final_category_id` 또는 사용자 override
- model provider/model identifier
- prompt/taxonomy version
- confidence 또는 후보 목록(지원될 경우)
- 분류 시각 및 실패 사유

이 데이터는 이후 정확도 측정과 taxonomy 개선의 근거가 된다. semantic search, similar product, recommendation이 실제 제품 요구가 되었을 때 embedding 도입을 별도 결정한다.
