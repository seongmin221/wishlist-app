# ADR-025: OpenAI 분류는 고정 holdout 평가와 출시 기준을 통과해야 한다

> 상태: **확정** · 날짜: 2026-09-22 · 영역: **AI·quality**

## 결정

- 출시 전과 model·prompt·taxonomy 변경 전에는 사람의 정답 라벨이 있는 **180개 대표 상품 평가 corpus**를 사용한다.
- 120개는 prompt·taxonomy 개선과 원인 분석에 사용하고, **60개 holdout**은 개선 과정에서 정답을 보거나 기준을 맞추는 데 사용하지 않는 최종 출시 시험으로 고정한다.
- 각 사례에는 입력 metadata, 정답 category 또는 `ABSTAINED`, 허용 purpose 또는 `UNASSIGNED`, 연결하면 안 되는 purpose, metadata 품질, JS-rendered 여부와 prompt injection·비정상 입력 여부를 기록한다.
- 180개 문제집은 11개 상위 taxonomy마다 최소 12개를 포함하고, purpose 연결·미지정 사례는 각각 최소 60개, 여러 purpose 후보가 있는 사례와 정보 부족·abstain 사례는 각각 최소 30개, JS-rendered와 비정상 입력 사례는 각각 최소 20개를 포함한다. 사례는 여러 기준에 함께 포함될 수 있다.
- 실제 페이지가 바뀌어 평가가 흔들리지 않게, AI 평가는 당시 추출한 정규화 metadata snapshot을 고정 입력으로 사용한다. 실제 URL fetch·Playwright 성능은 별도 부하 시험에서 평가한다.
- 60개 holdout은 첫 model·prompt 실험 전에 strata를 유지해 분리·고정하며, `ABSTAINED` 정답 사례 10개 이상과 purpose `ASSIGNED` 정답 사례 20개 이상을 포함한다.
- category exact accuracy의 분모는 정답이 category ID인 사례 전체이며, 예상 ID와 정확히 같을 때만 정답이다. `ABSTAINED` 정답은 이 분모에서 빼고 abstain 지표로 따로 채점한다.
- purpose 오연결률의 분모는 AI가 `ASSIGNED`를 낸 사례이고, 분자는 사람이 허용한 purpose ID 집합 밖의 ID를 낸 사례다. 정답 `UNASSIGNED` 사례의 `ASSIGNED`도 오연결이다. 복수 purpose가 허용되면 그 집합의 어느 ID나 정답으로 한다. purpose `ASSIGNED` 정답 사례의 허용 ID 연결률도 별도로 계산한다.
- holdout 출시 기준은 category exact accuracy **85% 이상**, `ASSIGNED` purpose 오연결 **5% 이하**, purpose 허용 ID 연결률 **70% 이상**, 의도적으로 애매하거나 근거가 부족한 사례 category `ABSTAINED` **80% 이상**, 허용되지 않은 ID·schema 검증 실패 **0건**, OpenAI 호출 latency p95 **15초 이하**, 월 20,000건 분석 가정에서 외부 LLM 월 **10,000원 hard cap** 충족이다.
- 사람이 정한 final 값, LLM predicted 값, abstain·unassigned, alias와 실제 model snapshot ID, prompt/taxonomy version, input·output token과 latency를 결과별로 기록한다. raw prompt·response는 보관하지 않는다.
- 최종 후보는 120개 개발 사례만으로 선택한다. 독립 evaluator는 holdout 사례별 결과·점수·집계값을 공개하지 않고 출시 통과/실패만 한 번 반환한다. 실패한 holdout은 읽기 전용 retired holdout으로 봉인하고 개발 사례·학습 데이터·다음 평가에 쓰지 않는다. 개발 후보 선택이 끝난 뒤, 라벨에 접근하지 않는 개발팀과 분리된 evaluator가 strata를 유지해 새 60개를 선정·라벨·봉인한다. 그 새 holdout으로만 다음 최종 검증을 한다.

## 이유와 trade-off

고정 holdout은 개발 사례에서만 좋아 보이는 과적합을 줄이고 변경 전후 품질을 비교하게 한다. category는 사용자가 검토·수정할 수 있으므로 정확도를 중시하되, purpose는 잘못된 비교 묶음을 만들 수 있어 오연결 기준을 더 엄격히 둔다. 애매한 입력에서 abstain하는 것은 근거 없는 category를 만드는 것보다 낫다.

180개 수작업 라벨은 초기 준비 비용이 있지만, 87개 taxonomy, 사용자별 목적과 저품질 URL을 포함한 실제 제품 정책을 평가에 반영한다.

## 재검토 조건

- 실제 사용자 수정·오연결 패턴이 corpus의 대표성을 부정한다.
- 20,000건 월 사용 가정 또는 LLM hard cap이 바뀐다.
- 실제 token 비용·latency 또는 품질이 기준을 지속해서 벗어난다.

## 근거

- [OpenAI 모델 선택·평가 가이드](https://developers.openai.com/api/docs/guides/model-selection)
- [GPT-5.6 Luna 모델 문서](https://developers.openai.com/api/docs/models/gpt-5.6-luna)
