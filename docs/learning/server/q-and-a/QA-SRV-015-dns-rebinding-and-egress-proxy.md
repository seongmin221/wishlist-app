# QA-SRV-015: browser Worker는 왜 URL 검사만으로 사설 주소 접근을 막지 못하고 proxy를 거치는가

> 상태: **학습 Q&A** · 날짜: 2026-10-10 · 관련: [추출 파이프라인](../../../architecture/server/extraction-pipeline.md), [B5 spec](../../../superpowers/specs/2026-10-09-b5-analysis-runtime-recovery-design.md)

## 질문

일반 Worker처럼 URL을 먼저 검사하면 browser(Chromium)도 안전하지 않은가? 왜 별도 proxy가 필요한가?

## 짧은 답변

검사와 연결이 서로 다른 DNS 조회를 쓰기 때문이다. 서버가 `shop.example`을 조회해 공인 IP를 확인해도, Chromium은 연결할 때 다시 조회한다. 공격자가 DNS 응답을 짧은 TTL로 바꾸면 두 번째 조회는 `127.0.0.1`이나 클라우드 metadata 주소가 될 수 있다(DNS rebinding).

일반 HTTP fetch는 검사한 주소를 OkHttp에 고정해 이 문제가 없다. Chromium은 그런 고정 기능이 없으므로 모든 연결을 프로세스 안의 loopback proxy로 보내고, proxy가 host를 검증한 뒤 **검증한 주소에만** TCP 연결한다. proxy는 다시 조회하지 않는다.

## 이 프로젝트의 적용

- Chromium 인자: `--proxy-server=http://127.0.0.1:{port}`, `--proxy-bypass-list=<-loopback>`(기본 loopback 우회 제거), `--disable-quic`, `--force-webrtc-ip-handling-policy=disable_non_proxied_udp`. QUIC과 WebRTC UDP는 HTTP proxy를 거치지 않아 우회 경로가 되기 때문이다.
- service worker와 다운로드를 막는다. Playwright route 검사는 빠른 거부용으로만 남긴다.
- proxy는 CONNECT와 절대 URI 요청만 받고 80/443 외 port, 검증 실패, 사설 주소는 403으로 거부한다.
- loopback에는 같은 컨테이너의 다른 프로세스도 접근할 수 있으므로, 배포 단계(B11)에서 browser 컨테이너에 다른 workload를 두지 않고 네트워크 egress firewall을 추가 방어로 둔다.

## 테스트로 확인한 것

`EgressProxyTest`는 resolver가 첫 응답은 공인 IP, 두 번째 응답은 `127.0.0.1`을 내는 rebinding 상황에서, proxy가 첫 검증 주소로만 연결하고 다음 연결은 거부함을 확인한다. `PlaywrightRealBrowserTest`(opt-in)는 실제 Chromium이 proxy 없이는 해석할 수 없는 host를 proxy를 거쳐 렌더링하고 사설 subresource에 연결하지 않음을 확인한다.
