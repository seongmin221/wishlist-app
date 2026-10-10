# QA-CLI-015: 원본 링크 웹뷰는 웹 페이지가 앱을 마음대로 움직이지 못하게 무엇을 막는가?

> 확인 날짜: 2026-10-10 · C4 Task 13~16 · Ruling 11·12 · 구현: [Android 웹뷰](../../../architecture/client/android.md#원본-링크-웹뷰-c4-pr-b), [iOS 웹뷰](../../../architecture/client/ios.md#원본-링크-웹뷰-c4-pr-b) · 규칙: [C4 설계](../../../superpowers/specs/2026-10-09-client-c4-product-detail-design.md#4-웹뷰) §4·D16

**질문:** 쇼핑몰 페이지와 그 안의 광고는 우리가 만든 코드가 아니다. 이런 페이지가 앱 안의 화면을 실행하거나, 피싱 페이지로 바꿔치기하거나, 사용자 몰래 다른 앱을 여는 것을 어떻게 막는가?

**답변:** 판정을 순수 함수(`WebNavigationPolicy`)에 모으고, "어떤 scheme인가 · main frame인가 iframe인가 · 사용자가 정말 눌렀는가"의 세 값으로 안에서 열기 / 막기 / 외부 바로 / 확인 후를 정한다. 네 가지 규칙이 핵심이다.

- **Android `intent://` 정화:** `intent:` URL은 패키지·클래스 이름(component)과 selector를 담아 기기 안의 공개되지 않은 Activity까지 겨눌 수 있다(intent scheme 공격). `IntentSanitizer`가 component·selector를 지우고, launch flag를 모두 지우고(`FLAG_GRANT_*`로 파일 권한을 넘기는 것 포함), `CATEGORY_BROWSABLE`을 붙인 intent만 보낸다. `parseUri`가 실패하거나 data가 `file:`·`content:`·`javascript:`이면 보내지 않는다. `intent:`가 아닌 scheme(`market:`·`tel:` 등)은 `parseUri`를 거치지 않고 `ACTION_VIEW` + BROWSABLE이다.
- **최상위 `data:`·`blob:` 차단:** 주소창이 없는 웹뷰에서 main frame이 `data:text/html,…`로 바뀌면 진짜 로그인 화면처럼 꾸민 피싱 페이지를 출처 없이 보여줄 수 있다. 그래서 main frame은 `http`/`https`/`about:blank`만 연다(Chrome과 같은 규칙). iframe에서도 `data:`·`blob:` 문서 이동은 막고 `about:blank`·`about:srcdoc`만 둔다(Ruling 11, 설계 §4가 "하위 리소스(iframe 아님)"에만 허용).
- **iframe 이동 구분:** 광고 iframe이 main frame을 바꾸거나 외부 앱을 열려 하면 사용자는 누가 시켰는지 알 수 없다. iframe에서 온 외부 앱 요청도 탭이 없으면 확인창이고, Android iframe `intent:`의 web fallback을 main frame에 여는 것은 탭이 있을 때만이다. 확인창을 취소하면 main frame이 다른 URL로 갈 때까지 탭 없는 요청을 버려 확인창 반복 공격을 막는다(`ExternalPromptGate`).
- **꾸밀 수 있는 gesture:** "사용자 탭이면 바로 연다"는 규칙은 탭 판정이 꾸며지면 무너진다. iOS `navigationType == .linkActivated`는 스크립트의 `a.click()`에도 붙어서, 그것만 믿으면 페이지가 확인창 없이 결제 앱·전화를 열 수 있었다(시뮬레이터에서 재현). 그래서 iOS는 `.linkActivated` **그리고** 최근 1초 안에 웹뷰에 실제 손가락이 닿았을 때만 탭으로 본다(Ruling 12). Android `WebResourceRequest.hasGesture()`는 Chromium의 user activation이라 스크립트만으로는 생기지 않는다(탭 직후의 JS 이동은 탭으로 인정된다).

**함께 두는 설정:** `addJavascriptInterface`는 쓰지 않는다(페이지 JS가 앱 코드를 부르는 통로). `allowFileAccess`·`allowContentAccess` false, mixed content `NEVER_ALLOW`. 쇼핑몰 로그인 때문에 JavaScript·DOM storage·third-party 쿠키는 켠다.

**대가:** 국내 결제처럼 탭 뒤 JS로 다시 이동하는 흐름은 iOS에서 결제마다 확인창이 뜰 수 있다(D16, 실기기 확인 항목).
