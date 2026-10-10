# Product metadata extraction pipeline

> 상태: **B5 구현** · Product cache·중복 판단은 후속 · 배포 egress firewall은 B11

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

## 상품 정보 필드 (B5)

[B5 결정](../../history/product-planning/mvp/decisions/b5-analysis-runtime-policy-2026-10-09.md)을 따른다. `ProductMetadataParser`가 일반 HTTP와 browser 렌더 결과를 같은 규칙으로 읽는다.

- brand·가격·판매처는 JSON-LD `Product` → OpenGraph `product:*`·`og:site_name` 순서로만 읽는다. heuristic이나 AI로 만들지 않는다.
- 가격은 단일 Offer(또는 값이 모두 같은 Offer 배열)의 금액과 ISO 4217 통화가 모두 유효할 때만 저장한다. AggregateOffer·offer별 상이·`12,900`·지수·음수·소수 5자리 이상은 null이다.
- JSON `null`은 값이 없는 것으로 본다. 이름·brand·판매처·통화는 JSON 문자열만, 가격은 문자열이나 숫자를 받는다. 이름 없는 Product 노드보다 이름 있는 노드를 대표로 쓴다.
- 이름이 다른 Product가 둘 이상이면 brand·가격은 null이다. brand·merchant가 200자를 넘으면 null이다.
- merchant는 `offers.seller.name` → `og:site_name`, 둘 다 없으면 null이다.
- canonical은 `link[rel=canonical]` → `og:url`이 최종 URL과 같은 등록 도메인(OkHttp `topPrivateDomain`)이고 엄격한 URI 문법·http(s)·userinfo 없음·기본 port일 때만 채택한다. https 최종 URL에서 http canonical로 낮추는 것은 채택하지 않는다. 그 밖에는 최종 URL을 쓴다. 정규화는 scheme/host 소문자·fragment 제거·tracking query(`utm_*`, `fbclid`, `gclid`, `msclkid`, `igshid`, `mc_cid`, `mc_eid`) 제거이며 다른 query는 보존한다.

## URL 안전성

- HTTP와 HTTPS만 허용한다.
- localhost, loopback, private, link-local, cloud metadata endpoint를 차단한다.
- DNS 결과와 실제 연결 대상 IP를 검증한다.
- 일반 HTTP fetch는 검사한 DNS 주소를 실제 연결에도 사용하고 자동 redirect를 끈다. 각 redirect 목적지는 다시 검사하며, 읽고 파싱하는 응답 body는 첫 512 KiB로 제한한다. 더 큰 HTML도 상품 JSON-LD 또는 OpenGraph 제목이 제한 범위 안에 있으면 추출하되, 잘린 문서의 일반 `<title>`만으로는 상품을 확정하지 않고 browser 후보로 보낸다.
- 모든 redirect destination도 같은 검사를 다시 한다.
- connect/read timeout, 최대 redirect 수, response body 최대 크기, 허용 MIME type을 둔다.
- DNS 해석은 `BoundedResolver`가 남은 처리 시간(최대 5초) 안에서만 기다리고, 포화되면 즉시 거부한다. 해석 실패·시간 초과·포화는 `DnsLookupFailed`로 일반 lane Retryable, browser lane PARTIAL이다. 차단 주소·scheme·port·userinfo·잘못된 URL은 `UnsafeUrlException`으로 `BLOCKED_ADDRESS`와 함께 FAILED_TERMINAL이다.

## Playwright 사용 기준

일반 fetch에서 추출 결과가 없거나 품질이 낮은 JS-rendered 사이트에만 **한 번** 제한적으로 사용한다. Playwright는 비용·지연·bot detection 위험이 있어 모든 URL·모든 retry에 적용하지 않는다. browser rendering도 충분한 metadata를 얻지 못하면 `PARTIAL`과 직접 보완 흐름으로 넘긴다. Playwright는 일반 Worker가 아닌 scale-to-zero browser Worker에서 실행하며 browser resource는 후속 설계에서 정한다.

일반 Worker는 `browserAttempted=false`일 때에만 같은 transaction에서 `BROWSER_PENDING`, `browserAttempted=true`, browser outbox event를 기록한다. browser Worker는 `BROWSER_PENDING → BROWSER_RUNNING`을 claim하고 성공 metadata를 해당 `WishlistItem`에 기록한다. 사이트 차단·navigation timeout·정보 부족은 `PARTIAL`로 끝난다. Playwright 시작·browser launch·context 생성 실패는 Worker 인프라 장애라 Retryable이며, `PARTIAL`로 바꾸는 Playwright 예외는 대상 페이지 navigation·내용 읽기 구간만이다. Worker 자체 중단으로 120초 이상 `BROWSER_RUNNING`이 남으면 reconciler가 browser outbox를 다시 만든다. browser 대상 페이지의 모든 요청은 URL 안전성 검사를 거치며, 실제 배포에는 browser service의 사설 주소 egress 차단도 적용해야 한다.

B5의 browser Worker는 Chromium을 프로세스 내 loopback `EgressProxy`로만 연결한다(`--proxy-server`, `--proxy-bypass-list=<-loopback>`, QUIC·비proxy WebRTC UDP 끔, service worker·다운로드 차단). proxy는 CONNECT·절대 URI 요청의 host를 `UrlSafetyPolicy`로 검증한 주소에만 연결하고 다시 해석하지 않으므로 DNS rebinding으로 사설 주소에 닿을 수 없다. 80/443 외 port, IPv6 literal 대상, 검증 실패는 403이다. proxy는 render마다 새로 만들고 render가 끝나면 닫는다. 한 render 안에서 검증한 host는 그 주소로 고정해 재사용한다. 이후 rebinding 응답은 쓰지 않는다. 검증된 주소 중 하나에 연결하지 못하면 다음 주소를 순서대로 시도한다. 절대 URI(평문 HTTP) 응답은 `Connection: close`로 바꿔, Chromium이 같은 proxy 연결로 다른 origin을 요청해 처음 host의 주소로 전달되는 일을 막는다. route 검사는 그 render의 proxy 검증 결과를 함께 써서 origin마다 한 번만 해석한다. IPv6 차단 대역은 loopback·link-local 외에 fc00::/7(ULA)·2002::/16·2001::/32(Teredo)·64:ff9b::/96·64:ff9b:1::/48·::ffff:0:0:0/96(SIIT)·IPv4 호환 주소를 포함한다. IPv4-mapped(::ffff:0:0/96) 주소는 내장 IPv4로 다시 검사한다. native DNS는 mapped AAAA 응답을 `Inet6Address`로 남기는데, JDK의 loopback·사설 판정은 이 주소를 놓치기 때문이다. fallback 첫 실행도 generation 합산 3회 예산을 쓴다.

## 실제 URL 8건 로컬 파일럿 (2026-09-24)

사용자 확정 카테고리 8건을 상품 생성 HTTP → 일반 Worker HTTP → 실제 URL fetch → OpenAI → DB 상태까지 실행했다. 기존 구현에서는 EQL·Goodrunner·Kaptain Sunshine 3건만 `READY`였다. Mizuno(약 783 KB)·8Division(약 673 KB)은 정상 HTML이지만 512 KiB 초과 시 전체를 버리는 처리로 `PARTIAL`이 됐다. 앞부분만 파싱하도록 변경한 뒤 두 건도 `READY`와 기대 카테고리로 완료됐다. KREAM 3건은 초기 두 실행에서 연결 시간 초과였고 같은 환경의 브라우저 요청도 한 차례 HTTP 500·빈 문서였으나, 이후 세 차례 전체 재실행에서는 정상 HTML이 반환되어 8건 모두 첫 Worker 시도에 `READY`·기대 카테고리로 완료됐다. 따라서 8/8은 특정 시점의 경로 검증 결과이며 KREAM의 지속적 접근성이나 출시 정확도를 보증하지 않는다.

| 사례 | 기대 카테고리 | 최종 재실행 상태 |
| --- | --- | --- |
| EQL 팬츠 | C003 | READY / C003 |
| Goodrunner 캡 | C011 | READY / C011 |
| Mizuno 러닝화 | C006 | READY / C006 |
| KREAM 포켓몬 TCG | C068 | READY / C068 |
| KREAM 카고 팬츠 | C003 | READY / C003 |
| KREAM 데님 재킷 | C001 | READY / C001 |
| 8Division 트라우저 | C003 | READY / C003 |
| Kaptain Sunshine 백팩 | C007 | READY / C007 |

이 파일럿은 로컬 DB·실제 사이트·실제 OpenAI를 사용했지만 Firebase 인증, Cloud Tasks, 배포된 모바일 앱 경로를 대체해 검증하지는 않는다. 테스트는 `RUN_REAL_URL_PILOT=1` opt-in이며 외부 사이트 상태에 따라 실패할 수 있다.
