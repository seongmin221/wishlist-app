# Product metadata extraction pipeline

> 상태: **제안**

```text
입력 URL
  → 정규화
  → SSRF 검증
  → HTTP fetch (redirect마다 재검증)
  → JSON-LD / schema.org Product
  → OpenGraph
  → HTML metadata
  → heuristic parser
  → 필요 시 Playwright rendering
  → canonical URL·metadata 병합
```

## 우선순위

구조화되고 재현 가능한 정보를 먼저 신뢰한다. AI가 HTML 전체에서 상품을 추측하는 것은 기본 경로가 아니다.

1. JSON-LD `Product` 및 schema.org
2. OpenGraph metadata
3. title, meta description, image 등 HTML metadata
4. 도메인별 또는 일반 heuristic
5. JavaScript 렌더링 결과

## URL 안전성

- HTTP와 HTTPS만 허용한다.
- localhost, loopback, private, link-local, cloud metadata endpoint를 차단한다.
- DNS 결과와 실제 연결 대상 IP를 검증한다.
- 모든 redirect destination도 같은 검사를 다시 한다.
- connect/read timeout, 최대 redirect 수, response body 최대 크기, 허용 MIME type을 둔다.

## Playwright 사용 기준

일반 fetch에서 추출 결과가 없거나 품질이 낮은 JS-rendered 사이트에만 제한적으로 사용한다. Playwright는 비용·지연·bot detection 위험이 있어 모든 URL에 적용하지 않는다.
