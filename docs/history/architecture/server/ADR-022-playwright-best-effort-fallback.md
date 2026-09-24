# ADR-022: JS-rendered 상품 페이지는 Playwright를 제한적 보조 경로로 지원한다

> 상태: **확정** · 날짜: 2026-09-20 · 영역: **server·extraction**

## 맥락

일부 쇼핑몰은 일반 HTTP fetch의 HTML에 상품명·가격·이미지 등의 metadata를 넣지 않고, 브라우저에서 JavaScript를 실행한 뒤에만 보여 준다. 이 경우 일반 parser만으로는 상품을 충분히 추출할 수 없다. 반면 Playwright browser rendering은 실행 시간·memory·bot detection 위험을 늘리고 Worker timeout·평균 월 비용에 영향을 준다.

## 결정

- MVP는 JS-rendered 쇼핑몰을 **가능하면 지원하되 실패는 허용**한다.
- 일반 HTTP fetch와 JSON-LD·OpenGraph·HTML metadata·heuristic parser를 먼저 실행한다.
- 일반 경로가 실패했거나 상품 metadata 품질이 낮을 때만 Playwright rendering을 **한 번** 보조 경로로 시도한다.
- Playwright도 충분한 metadata를 얻지 못하거나 timeout·대상 차단이 발생하면 분석을 무한 재시도하지 않고 기존 `PARTIAL`·직접 보완 흐름으로 넘긴다.
- Playwright는 기본 수집 경로가 아니며, 모든 URL·모든 retry에 적용하지 않는다.

## 이유와 trade-off

이 방식은 일반 HTML 쇼핑몰을 빠르고 저렴하게 처리하면서도, JS-rendered 사이트를 처음부터 포기하지 않는다. 브라우저 실행 실패가 전체 저장 의도를 막지 않고 사용자는 제목·카테고리 등을 직접 보완할 수 있다.

지원되는 JS 사이트의 범위는 best-effort다. bot protection 우회, 로그인·결제 페이지 처리나 모든 JS 사이트의 성공은 MVP 범위가 아니다. Playwright 실행 위치와 browser 전용 resource 설정은 별도 결정이 필요하다.

## 후속 결정

- Playwright browser Worker의 resource·queue limit과 비용 영향을 정한다.
- Playwright 실행 시간·memory limit, metadata 품질 판정과 domain별 allow/deny 정책을 정한다.
- 대표 JS-rendered URL로 Worker 90초·task deadline 105초와 비용 영향을 부하 시험으로 검증한다.
