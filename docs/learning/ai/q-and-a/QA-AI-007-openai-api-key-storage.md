# OpenAI API 키는 어디에 저장하는가

## 질문

AI 입력 token을 측정하거나 서버에서 분류를 호출할 때 OpenAI API 키를 어디에 보관해야 하는가?

## 답변

API 키는 비밀번호처럼 취급한다. Git에 들어가는 코드·설정·문서, 채팅, 테스트 결과·로그, iOS/Android 앱에 넣지 않는다. OpenAI도 서버 애플리케이션에는 환경변수나 secret 관리 서비스를 사용하도록 안내한다.

- **로컬 개발:** 본인 기기의 비공개 저장소(예: 비밀번호 관리자)에 키를 보관하고, 실행할 때 해당 터미널 세션의 `OPENAI_API_KEY` 환경변수로 전달한다. `export OPENAI_API_KEY='...'`를 터미널에 직접 입력하면 shell 기록에 남을 수 있으므로 주의한다. 프로젝트의 추적 파일에는 저장하지 않는다. 값 확인 시 키 자체를 출력하지 말고 설정 여부만 확인한다.
- **배포 환경:** 결정된 서버 설계대로 Google Cloud Secret Manager에 저장하고, 필요한 Cloud Run 서비스에만 환경변수로 주입한다. GitHub Actions나 클라이언트 앱에 키 원문을 넣지 않는다. 개발용·운영용 키는 분리하고 권한·지출 한도를 각각 관리한다.

현재 `OpenAiResponsesGateway`는 `OpenAiConfig`로 키를 전달받지만 환경변수나 Secret Manager에서 읽어 조립하는 실행 구성이 아직 없다. 로컬 터미널에 환경변수를 설정하는 것만으로 현재 서버가 자동 연결되는 것은 아니다. 토큰 측정 도구 또는 서버 실행 구성을 만들 때 키를 읽어 adapter에 전달해야 한다.

키가 노출되었다고 의심되면 해당 키를 폐기하고 새 키로 교체한다. 토큰 측정 결과에는 키나 사용자 원문 데이터를 기록하지 않는다.

## 관련 문서

- [서버 운영 구조](../../../architecture/server/overview.md)
- [OpenAI 운영 모범 사례](https://developers.openai.com/api/docs/guides/production-best-practices)
