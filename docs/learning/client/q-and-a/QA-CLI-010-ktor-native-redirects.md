# QA-CLI-010: Ktor의 redirect 차단은 MockEngine만으로 확인할 수 있는가?

> 확인 날짜: 2026-10-07 · C2 계획 리뷰

**질문:** Ktor의 `followRedirects=false`가 OkHttp/Darwin의 native redirect까지 막는가? MockEngine 테스트로 확인할 수 있는가?

**답변:** MockEngine은 실제 engine을 실행하지 않으므로 native redirect 동작을 검증하지 못한다. C2에서 선택한 Ktor 3.4.3의 [OkHttpConfig](https://github.com/ktorio/ktor/blob/3.4.3/ktor-client/ktor-client-okhttp/jvm/src/io/ktor/client/engine/okhttp/OkHttpConfig.kt)는 `followRedirects`와 `followSslRedirects`를 이미 false로 두며, [Darwin 기본 delegate](https://github.com/ktorio/ktor/blob/3.4.3/ktor-client/ktor-client-darwin/darwin/src/io/ktor/client/engine/darwin/KtorNSURLSessionDelegate.kt)는 redirect callback에 `completionHandler(null)`을 호출해 embedded redirect를 거부한다.

따라서 기본 engine 구성을 유지하고 [Ktor 자체 redirect](https://ktor.io/docs/client-redirect.html)도 끄면 된다. Darwin에 별도 Boolean 설정이 있다고 가정하거나 custom delegate를 새로 만들 필요는 없다. preconfigured client/session 또는 다른 버전을 채택하면 해당 구성과 소스를 다시 확인한다.

C2 Task 6a는 Android host의 MockWebServer 두 대와 실제 OkHttp로 redirect target 요청 수가 0인지 확인할 계획이다. Darwin은 채택 버전의 delegate 소스 근거를 기록하고, 호스트 서버 실행·포트 전달을 포함한 실제 simulator 검증은 C12에서 구체화한다. 현재는 소스 확인만 수행했으며 실제 engine 실행 결과는 없다.

OkHttp의 `retryOnConnectionFailure`는 기본값 true를 유지한다. 연결 복구는 Ktor의 HTTP 응답 이후 application retry와 별개이며, C2에서 막는 것은 네트워크/5xx 오류 뒤 명시적인 POST 자동 재시도다.
