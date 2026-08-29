# Hilt

> 상태: **검토 예정**

Hilt는 Android에서 API client, repository, database처럼 객체 생성 규칙을 한 곳에서 관리하도록 돕는 의존성 주입 도구다. 화면이 직접 구현체를 만들지 않게 해 테스트와 교체를 쉽게 만든다.

이 프로젝트에서는 Android UI와 KMP shared UseCase를 연결하는 지점에서 검토한다. 대안은 수동 생성(composition root)과 Koin 등이다. 앱 규모가 작다면 수동 구성도 가능하지만, 화면과 의존성이 늘어나면 Hilt가 일관성을 제공한다.
