# QA-CLI-013: 화면마다 Presenter owner를 두려면 lifecycle-viewmodel-compose 없이 어떻게 하는가?

> 확인 날짜: 2026-10-10 · C4 Task 7 · 구현: [Android 구조](../../../architecture/client/android.md)

**질문:** 같은 `ItemDetailRoute`가 스택에 여러 번 쌓일 수 있고 화면이 빠지면 Presenter가 닫혀야 한다. 새 의존성(lifecycle-viewmodel-compose)을 더하지 않고 화면(칸)마다 ViewModel 수명을 어떻게 주는가?

**답변:** Activity의 ViewModelStore에 두면 화면이 빠져도 owner가 Activity 끝까지 남는다. 그래서 칸 id마다 별도 `ViewModelStore`를 쓴다.

- `WLEntryViewModelStores`(Activity ViewModelStore 안의 ViewModel)가 id별 `ViewModelStore`를 들고 구성 변경 동안 유지한다. `WLNavHost`가 칸마다 자체 `LocalWLEntryViewModelStoreOwner`로 제공한다. Compose의 `LocalViewModelStoreOwner`·`viewModel()`은 lifecycle-viewmodel-compose가 있어야 하므로 쓰지 않고, 화면은 `remember(owner) { ViewModelProvider(owner, factory)[...] }`로 만든다. `ViewModelProvider`는 lifecycle-viewmodel 본체에 있다.
- 칸이 pop·`replaceTop`·계정 범위 정리로 빠지면 `WLNavigator.drainRemoved()`가 id를 한 번씩 내보내고, 전환 모션이 끝난 뒤 `clearRemoved`가 그 store를 지워 `onCleared()`에서 Presenter가 닫힌다. 모션 동안에는 ViewModel이 살아 있어 떠나는 화면이 비지 않는다.
- 이 목록은 저장하지 않는다. 프로세스가 죽으면 store도 사라지므로 복원된 스택의 칸은 새 owner를 만든다.

대안(`navigation3`·`lifecycle-viewmodel-compose`)은 새 의존성이라 이번 단계의 "새 의존성은 Coil 3만"(D5) 승인 범위를 넘는다.
