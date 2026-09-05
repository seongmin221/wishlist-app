# iOS 구조

> 상태: **확정** — Swift + SwiftUI + Share Extension

## 역할

- SwiftUI로 화면과 navigation을 구성한다.
- 상품 링크 웹뷰를 SwiftUI navigation 안에서 열고, 공통 웹뷰 탐색·외부 앱·쿠키 정책을 구현한다.
- Share Extension에서 공유 대상의 URL을 추출한다.
- 공유 확장은 가능한 한 짧게 URL을 app group 저장소에 전달하고 종료한다.
- 본 앱이 그 데이터를 읽어 KMP의 저장 UseCase를 호출한다.

## 주의점

- Share Extension은 메모리·실행 시간이 제한되므로 상품 분석이나 긴 네트워크 요청을 실행하지 않는다.
- 공유 대상에는 URL 외의 텍스트·이미지가 올 수 있으므로 MVP에서는 URL만 명확히 지원하고, 지원하지 않는 입력은 안내한다.
- 인증 토큰과 공유용 임시 데이터의 저장 경계를 별도로 설계한다.
- 웹뷰 닫기와 외부 앱 복귀를 구분하고, 외부 앱 복귀 시 기존 웹뷰 상태를 유지한다.
