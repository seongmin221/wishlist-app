# QA-CLI-009: SKIE는 Kotlin 2.3.21을 지원하는가?

> 확인 날짜: 2026-10-07 · C2 계획 준비

**질문:** 프로젝트의 Kotlin 2.3.21에서 SKIE를 사용할 수 있는가?

**답변:** [SKIE 0.10.12 공식 변경 기록](https://skie.touchlab.co/changelog/0.10.12)에 Kotlin 2.3.21 지원이 명시돼 있다. 이는 upstream 지원 확인이며, 이 저장소의 AGP 9.0·Gradle 9.3·static Shared.framework·Xcode 통합 실행 성공을 뜻하지 않는다.

C2 첫 구현 task에서 Swift의 StateFlow 수집, suspend 호출, 수집 취소와 scope 종료를 실제 simulator XCTest로 검증한다. Swift가 비suspend token callback interface를 구현하고 Kotlin이 호출하는 방향도 성공/오류 각 1건으로 확인한다. 이 ABI 검증과 실제 token adapter의 취소·늦은/중복 완료 의미론(commonTest)은 별도 task가 소유한다. 다운로드·JDK·Xcode 오류는 compiler plugin 미지원과 구분한다. 실제 미지원이면 KMP-NativeCoroutines의 해당 Kotlin 지원 release와 Swift package 버전을 맞춰 동일한 probe로 비교한다. stack 선택 대기 중에는 SharedRuntime.ready와 Presenter의 Swift Flow/suspend 연결을 보류하고, 일반 가격 함수와 callback ABI는 ObjC framework로 계속 검증한다.

**현재 검증 수준:** 공식 문서만 확인했다. plugin 적용·framework 빌드·Swift 실행은 계획 승인 후 수행한다. [C2 계획](../../../superpowers/plans/2026-10-07-client-c2-kmp-core.md)의 Task 1이 실행 기준이다.
