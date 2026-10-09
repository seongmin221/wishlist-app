# QA-CLI-012: iOS는 공유만으로 분석이 시작되지 않는가, C안의 개발자 팀 설정은 복잡한가?

> 상태: **학습 Q&A** · 날짜: 2026-10-07 · 결정 기록: [ADR-030](../../../history/architecture/client/ADR-030-share-receipt-mode.md)

## 질문

1. "iOS는 단순 공유만으로 분석 시작이 안 된다는 거야?"
2. "C 방법의 개발자 팀 설정은 복잡한가?"

## 짧은 답변

**1. 방식에 따라 다르다.**

- **B안(확장은 로컬 기록만):** 맞다. 공유 확장은 app group 폴더에 링크만 남기고 바로 끝난다. 서버 요청(분석 시작)은 사용자가 앱을 열거나 앱이 foreground가 될 때 앱이 보낸다. C3의 iOS가 지금 이 상태이고, 그래서 로그인 상태 카드 문구가 "앱을 열면 정보를 가져와요"다.
- **C안(B + background URLSession):** 공유만으로 시작된다. 확장이 같은 기록을 남긴 뒤 같은 key의 POST를 background `URLSession`에 맡긴다. 확장 프로세스가 끝나도 시스템이 그 요청을 끝까지 보낸다. 나중에 앱을 열면 inbox를 가져와 **같은 key로 다시 보낸다.** 서버는 같은 `Idempotency-Key`를 멱등 replay로 처리해 이미 만든 항목을 돌려주므로 중복 항목이 생기지 않고, 앱은 그 응답으로 결과를 받는다.
- Android는 공유 Activity가 앱과 같은 프로세스라 어느 안이든 공유 즉시 보낸다.

**2. 설정 자체는 어렵지 않지만 가입과 정책 두 가지 비용이 있다.**

- **Keychain 공유에는 유료 Apple Developer 팀이 필요하다.** 확장이 직접 보내려면 로그인 token을 앱과 확장이 함께 읽어야 하고, 그 통로가 Keychain 공유 access group이다. access group은 팀 ID에 묶이므로 팀 없이 쓸 수 없다. 실기기 설치·배포 서명도 같은 팀이 필요하다.
- **Firebase ID token은 1시간 뒤 만료된다.** 확장은 token을 새로 받지 못할 수 있으므로, 만료된 token이면 보내지 않고 앱 전송에 맡기는 규칙이 필요하다. 401이 와도 inbox 파일은 지우지 않는다.
- **app group은 B안에도 필요하다.** 확장과 앱이 파일을 주고받는 유일한 통로라서다. 다만 시뮬레이터에서는 팀이 없어도 된다. 프로젝트 기본 "Sign to Run Locally" 서명으로 빌드하면 app group entitlement가 붙는다. `CODE_SIGNING_ALLOWED=NO`로 빌드하면 entitlement가 빠져 container가 nil이 된다(C3 Task 0에서 확인).

## 이 프로젝트의 결정

C3는 C안을 택하되 확장 전송 자리(`ShareDirectSender`)만 두고 끈다. 그래서 지금 iOS 동작은 B안과 같다. 가입 뒤 "인증 연결" 단계에서 Keychain 공유와 token 만료 정책을 갖춰 켠다.

## 관련 문서

- [QA-CLI-002: 공유 직후 저장 확인 카드와 그 한계](QA-CLI-002-share-receipt-feedback.md)
- [iOS 구조 C3 절](../../../architecture/client/ios.md#공유-확장inbox로그인홈설정-c3)
- [서버 연동 상태의 인증 연결 인계](../../../architecture/client/server-integration-status.md#인계-인증-연결-단계)
