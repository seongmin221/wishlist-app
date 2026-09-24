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
- 일반 HTTP fetch는 검사한 DNS 주소를 실제 연결에도 사용하고 자동 redirect를 끈다. 각 redirect 목적지는 다시 검사하며, 읽고 파싱하는 응답 body는 첫 512 KiB로 제한한다. 더 큰 HTML도 상품 JSON-LD 또는 OpenGraph 제목이 제한 범위 안에 있으면 추출하되, 잘린 문서의 일반 `<title>`만으로는 상품을 확정하지 않고 browser 후보로 보낸다.
- 모든 redirect destination도 같은 검사를 다시 한다.
- connect/read timeout, 최대 redirect 수, response body 최대 크기, 허용 MIME type을 둔다.

## Playwright 사용 기준

일반 fetch에서 추출 결과가 없거나 품질이 낮은 JS-rendered 사이트에만 **한 번** 제한적으로 사용한다. Playwright는 비용·지연·bot detection 위험이 있어 모든 URL·모든 retry에 적용하지 않는다. browser rendering도 충분한 metadata를 얻지 못하면 `PARTIAL`과 직접 보완 흐름으로 넘긴다. Playwright는 일반 Worker가 아닌 scale-to-zero browser Worker에서 실행하며 browser resource는 후속 설계에서 정한다.

일반 Worker는 `browserAttempted=false`일 때에만 같은 transaction에서 `BROWSER_PENDING`, `browserAttempted=true`, browser outbox event를 기록한다. browser Worker는 `BROWSER_PENDING → BROWSER_RUNNING`을 claim하고 성공 metadata를 해당 `WishlistItem`에 기록한다. 사이트 차단·navigation timeout·정보 부족은 `PARTIAL`로 끝나며, Worker 자체 중단으로 120초 이상 `BROWSER_RUNNING`이 남으면 reconciler가 browser outbox를 다시 만든다. browser 대상 페이지의 모든 요청은 URL 안전성 검사를 거치며, 실제 배포에는 browser service의 사설 주소 egress 차단도 적용해야 한다.

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
