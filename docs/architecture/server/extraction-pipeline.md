# Product metadata extraction pipeline

> 상태: **부분 구현**

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
- 일반 HTTP fetch는 검사한 DNS 주소를 실제 연결에도 사용하고 자동 redirect를 끈다. 각 redirect 목적지는 다시 검사하며, 응답 body는 512 KiB로 제한한다.
- 모든 redirect destination도 같은 검사를 다시 한다.
- connect/read timeout, 최대 redirect 수, response body 최대 크기, 허용 MIME type을 둔다.

## Playwright 사용 기준

일반 fetch에서 추출 결과가 없거나 품질이 낮은 JS-rendered 사이트에만 **한 번** 제한적으로 사용한다. Playwright는 비용·지연·bot detection 위험이 있어 모든 URL·모든 retry에 적용하지 않는다. browser rendering도 충분한 metadata를 얻지 못하면 `PARTIAL`과 직접 보완 흐름으로 넘긴다. Playwright는 일반 Worker가 아닌 scale-to-zero browser Worker에서 실행하며 browser resource는 후속 설계에서 정한다.

일반 Worker는 `browserAttempted=false`일 때에만 같은 transaction에서 `BROWSER_PENDING`, `browserAttempted=true`, browser outbox event를 기록한다. browser Worker는 `BROWSER_PENDING → BROWSER_RUNNING`을 claim하고 성공 metadata를 해당 `WishlistItem`에 기록한다. 사이트 차단·navigation timeout·정보 부족은 `PARTIAL`로 끝나며, Worker 자체 중단으로 120초 이상 `BROWSER_RUNNING`이 남으면 reconciler가 browser outbox를 다시 만든다. browser 대상 페이지의 모든 요청은 URL 안전성 검사를 거치며, 실제 배포에는 browser service의 사설 주소 egress 차단도 적용해야 한다.
