# ADR-030: 공유 수신은 플랫폼별로 나누고 iOS 확장은 app group inbox에 기록만 한다

> 상태: **확정**(C3에서 확장 전송은 끔) · 날짜: 2026-10-07 · 영역: **client** · 관련: [ADR-027](ADR-027-client-implementation-strategy.md), [C3 계획 C3-D1](../../../superpowers/plans/2026-10-07-client-c3-share-save.md#선택한-결정), [QA-CLI-002](../../../learning/client/q-and-a/QA-CLI-002-share-receipt-feedback.md), [QA-CLI-012](../../../learning/client/q-and-a/QA-CLI-012-ios-share-starts-analysis.md)

## 맥락

- 제품 규칙은 "공유한 링크를 잃지 않는다"이다. 공유하면 서버 상품과 구분되는 `LocalSubmission`을 먼저 만들고, 같은 로컬 공유는 같은 UUID key로 재전송한다([클라이언트 구조](../../../architecture/client/README.md#공통-저장-상태)).
- 공유 카드의 로그인 상태 문구 "정보를 가져오는 중이에요"는 공유 순간 서버 요청이 나간다는 전제다. 로드맵은 확장이 직접 서버에 보낼지를 C3 시작 전 결정 사항으로 남겼다.
- Android 공유 Activity는 앱과 같은 프로세스라 KMP runtime과 SQLite를 그대로 쓴다. iOS Share Extension은 별도 프로세스이고 메모리·실행 시간이 짧으며, 끝나면 시스템이 바로 정리한다.
- C3 시점에는 Apple Developer 가입이 없다. 실제 Firebase 인증도 없다(C3-D2 fake 인증).

## 선택지

| 안 | 내용 | 장점 | 비용 |
| --- | --- | --- | --- |
| A | iOS 확장이 Shared.framework와 Firebase를 링크해 직접 SQLite에 쓰고 ITEM-01을 보낸다 | 공유 순간 분석이 시작된다. 문구가 두 플랫폼에서 같다 | 앱과 확장 두 프로세스가 같은 SQLite를 쓴다(잠금·migration 경합). 확장 크기·메모리가 커지고 확장이 끝나면 요청이 끊길 수 있다. token 전달에 Keychain 공유(개발자 팀)가 필요하다 |
| B | 확장은 로컬 기록만 남기고 전송은 앱이 맡는다 | 가장 단순하다. 팀·Keychain이 필요 없다 | 공유만으로는 분석이 시작되지 않는다. 사용자가 앱을 열어야 보낸다 |
| C | B처럼 app group `inbox/`에 공유 1건 = JSON 파일 1개를 쓰고, 추가로 background URLSession에 같은 key의 POST를 맡긴다. 앱은 실행·foreground 때 inbox를 가져와 같은 key로 다시 보내 결과를 받는다 | 확장이 끝나도 시스템이 POST를 마친다. 앱의 재전송은 서버 멱등 replay라 중복 항목이 생기지 않는다. 다중 프로세스 DB가 없다 | 확장 전송에는 Keychain 공유(개발자 팀)와 Firebase ID token 만료(1시간) 처리가 필요하다 |

## 결정

- **C안을 택하고, C3에서는 확장 전송 자리만 두고 끈다.** C3의 iOS 동작은 사실상 B안이며, 가입 뒤 "인증 연결" 단계에서 확장 전송만 켜면 C안이 된다.
- iOS 확장은 Shared.framework를 링크하지 않는다. 링크를 추출해 `inbox/<clientSubmissionId>.json`(형식 v1)을 원자적으로 쓰고 카드를 보인 뒤 닫는다. `ShareDirectSender` 자리는 `DisabledShareDirectSender`(아무것도 하지 않음)다.
- 본 앱이 scene `.active`마다 inbox를 읽어 KMP `SubmissionCoordinator.importInbox`로 SQLite에 넣고, 저장이 확인된 파일만 지운다. 그 뒤 전송은 Android와 같은 coordinator가 한다.
- Android 공유 Activity는 같은 프로세스의 runtime으로 받아 즉시 저장하고, 로그인·온라인이면 바로 전송을 요청한다.
- 확장은 공유 순간의 계정만 app group defaults의 미러(`wl.session.accountBinding`)로 안다. 자격 증명은 공유하지 않는다.

## 결과

- **카드 문구:** iOS 로그인 상태는 "위시리스트에 저장했어요 / 앱을 열면 정보를 가져와요"(`SAVED_OPEN_APP`, Swift 전용 enum). Android 로그인+온라인은 "정보를 가져오는 중이에요"(SAVED), 오프라인은 "다음에 앱을 열면 보내요"(OFFLINE)다(C3-D9). 확장 전송을 켜면 iOS 문구를 다시 정한다.
- **app group은 B에도 필요하다.** 확장과 앱이 파일을 주고받는 유일한 통로다. app group id는 `group.app.wishlist`.
- **다중 프로세스 DB 공유 없음.** SQLite는 앱 프로세스만 연다. inbox import는 같은 key·같은 URL이면 no-op이라, import 뒤·파일 삭제 전에 앱이 죽어도 다음 import가 안전하다(Review Focus 2).
- **binding:** inbox 레코드는 공유 순간 binding을 그대로 가진다. 앱은 그 binding을 바꾸지 않으므로 A 계정으로 공유한 항목을 B 계정이 보내지 않는다.

## 구현 중 발견

### 시뮬레이터 서명과 app group (C3 Task 0, Ruling 4)

- 시뮬레이터의 entitlements는 서명 단계에서 바이너리 `__TEXT,__entitlements` section으로 들어간다. `CODE_SIGNING_ALLOWED=NO` 빌드에는 이 section이 없어 `containerURL(forSecurityApplicationGroupIdentifier:)`가 nil이고 `simctl get_app_container … groups`도 비어 있다.
- 프로젝트 기본 서명("Sign to Run Locally", 팀 없음)으로 빌드하면 앱·확장 모두 app group이 붙는다. 사용자가 Safari에서 수동 공유해 확장이 쓴 파일이 앱이 보는 같은 group 폴더에 생긴 것을 확인했다(2026-10-07).
- 그래서 단위 테스트·CI는 `CODE_SIGNING_ALLOWED=NO`를 유지하고, app group 코드는 디렉터리·defaults를 주입받아 임시 폴더로 테스트한다. 공유 확인용 빌드만 기본 서명으로 설치한다. 실행 중 container가 nil이면 inbox만 꺼진다(crash 없음).
- 실기기·배포에는 개발자 팀과 app group 등록이 필요하다(인증 연결 단계). 지금 프로젝트에는 `DEVELOPMENT_TEAM`이 없다.

### iOS 26 확장 표시 (C3 Task 6·7, Ruling 17)

- 보드는 "원래 앱 위에 뜨는 카드"다. iOS 26.5 시뮬레이터에서는 iOS가 확장 window 안에 불투명 page sheet(`UIDropShadowView`, `systemBackgroundColor`)를 그리고 그 뒤 앱을 어둡게 한다. `modalPresentationStyle = .overFullScreen`, `preferredContentSize`는 효과가 없고 `sheetPresentationController`는 nil이다.
- 시스템 view들의 배경을 지우면 카드만 뜨지만 UIKit 내부 계층에 기대므로 넣지 않았다.
- 카드 대비를 보드와 같게 하려고 시트 안에 보드의 "다른 앱" 바탕색(라이트 #E9E9E9, 다크 #2A2A2A)을 칠했다. 카드는 시트 아래쪽에서 보드 motion대로 오르내리고, 그림자는 없다. A(현재안)·B(다른 대비)·C(내부 계층 수정) 중 권장안 A를 적용했고 사용자가 확정했다(2026-10-09). 보드 차이는 [디자인 결정](../../../design/decisions.md)에 기록했다.
- `viewDidLoad`에서 부른 `completeRequest`는 표시 전이라 무시되어 시트가 닫히지 않았다. 카드 등장은 `viewDidAppear` 뒤, `completeRequest`는 내려가는 모션이 끝난 뒤에만 부른다.

## 재검토 조건

- 개발자 팀이 생기면("인증 연결") 확장 전송을 켠다. 조건: Keychain 공유 access group으로 token 전달, 만료된 token이면 보내지 않고 앱 전송에 맡김, 401이어도 inbox 파일을 지우지 않음, iOS 로그인 카드 문구 재결정.
- iOS가 공유 확장의 투명 표시를 공개 API로 허용하면 시트 바탕색을 없앤다.
- 확장에서 공유 외 입력(이미지 등)을 받아야 하면 inbox 형식 버전을 올린다(`v`가 1이 아닌 파일은 앱이 건드리지 않는다).

## 보완 (2026-10-09, PR #12 3차 리뷰)

Android 즉시 저장은 runtime ready를 1500ms까지만 기다려, 느린 콜드 스타트에서는 링크가 어디에도 남지 않았다. 사용자 결정으로 그때(또는 저장 실패 때) Android도 iOS처럼 공유 1건을 앱 파일 inbox(`filesDir/share-inbox`)에 미귀속 record로 남기고 "위시리스트에 저장했어요 / 앱을 열면 정보를 가져와요" 카드를 보인다. 가져오기는 iOS와 같은 `SubmissionCoordinator.importInbox`다. 평소 경로(1500ms 안 저장·즉시 전송)는 바뀌지 않는다. iOS reader는 확장이 쓰기와 이름 바꾸기 사이에 끝나 남긴 임시 파일을 60초 뒤 복구한다.

## 관련 문서

- [iOS 구조 C3 절](../../../architecture/client/ios.md#공유-확장inbox로그인홈설정-c3), [Android 구조 C3 절](../../../architecture/client/android.md#공유-수신로그인홈설정-c3)
- [KMP 공유 수신·전송 조정기](../../../architecture/client/kmp.md#공유-수신전송-조정기submissioncoordinator)
- [C3 검증 기록](c3-verification-2026-10-07.md)
