# AI 구조

> 상태: **확정된 원칙 + 제안된 구현**

## 확정된 원칙

- MVP에서는 자체 모델 학습, GPU 운영, embedding/vector DB/RAG를 하지 않는다.
- 외부 LLM API를 사용한다.
- 코드로 확실히 처리할 수 있는 URL 정규화·HTML 구조 parsing은 AI에 맡기지 않는다.
- 모델은 공용 taxonomy를 새로 만들지 않는다. 분류 결과는 공용 taxonomy ID 또는 검증을 통과한 해당 사용자의 세부 카테고리 ID 중에서만 선택한다.
- 사용자가 수정한 category가 최종값이다.
- 모델은 사용자가 이미 만든 목적에만 새 항목을 연결할 수 있다. 새 목적을 생성하지 않으며, 연결 확신이 낮으면 목적 미지정으로 남긴다.

## 처리 흐름

```text
추출된 title / brand / merchant / description
  + 공용 taxonomy 및 검증된 사용자 전용 세부 카테고리
  → LLM 카테고리 분류 요청
  → schema 검증된 category ID 응답
  + 사용자가 만든 목적과 최근 활성 위시리스트 문맥
  → LLM 기존 목적 연결 요청
  → schema 검증된 purpose ID 또는 목적 미지정 응답
  → predicted 값과 model/prompt version 저장
  → 사용자 수정 시 final 값 갱신
```

사용자 전용 카테고리의 이름·설명·예시는 신뢰하지 않는 입력으로 다룬다. 서버는 길이·형식·생성량을 제한하고, 유해·어뷰징 또는 AI 입력에 부적합한 값은 해당 카테고리를 변경하지 않은 채 AI 후보에서 제외할 수 있다. 모델에는 허용된 값을 데이터 필드로 구조화해 전달하며, 이 텍스트를 모델 지시문으로 해석하거나 공용 taxonomy에 반영하지 않는다.

## 기록할 데이터

- `predicted_category_id`
- `final_category_id` 또는 사용자 override
- `predicted_purpose_id` 또는 목적 미지정
- `final_purpose_id` 또는 사용자 override
- model provider/model identifier
- prompt/taxonomy version
- confidence 또는 후보 목록(지원될 경우)
- 분류 시각 및 실패 사유

이 데이터는 이후 정확도 측정과 taxonomy 개선의 근거가 된다. semantic search, similar product, recommendation이 실제 제품 요구가 되었을 때 embedding 도입을 별도 결정한다.
