# QA-CLI-011: ObjCName을 붙였는데 Swift getter 이름이 바뀌지 않는 이유는?

> 확인 날짜: 2026-10-07 · Kotlin 2.3.21 / SKIE 0.10.12

**질문:** 공유 Purpose의 description이 NSObject.description과 충돌했다. 생성자 val에 ObjCName을 붙여도 Swift getter가 description_로 남는 이유는?

**답변:** 이 저장소에서는 use-site target을 생략한 annotation이 생성자 매개변수에 적용됐다. [ObjCName](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.native/-obj-c-name/)은 property와 value parameter 모두에 적용할 수 있으므로, getter를 바꾸려면 [annotation target](https://kotlinlang.org/docs/annotations.html#annotation-use-site-targets)을 명시한다.

```kotlin
@file:OptIn(kotlin.experimental.ExperimentalObjCName::class)

import kotlin.native.ObjCName

// 실제 Purpose와 ArchivePurposeSnapshot에도 같은 property target을 적용한다.
data class ExamplePurpose(
    @property:ObjCName(name = "purposeDescription", swiftName = "purposeDescription")
    val description: String?,
)
```

Kotlin 필드명은 description, Swift getter는 purposeDescription을 사용한다. C2 Task 2b에서 변경 전 Swift typecheck 실패와 변경 후 성공, generated Shared.h의 getter 이름을 실제 확인했다. annotation 컴파일 성공만으로 getter 이름이 바뀌었다고 판단하지 않는다. 확정 모델 경계는 [KMP 구조](../../../architecture/client/kmp.md)를 따른다.
