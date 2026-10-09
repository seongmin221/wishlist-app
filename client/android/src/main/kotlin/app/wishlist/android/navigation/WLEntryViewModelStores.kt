package app.wishlist.android.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * Activity-scoped holder (a ViewModel) of one ViewModelStore per back-stack entry id.
 *
 * Activity의 ViewModelStore에 두어 구성 변경 동안 유지된다(복원된 navigator가 같은 id를 쓰므로 같은 store를 다시 받는다).
 * 칸이 스택에서 빠지면 `WLNavHost`가 전환이 끝난 뒤 `clearRemoved`로, 계정을 떠나면 셸이 `dropAccountScoped`의 id로
 * 바로 `clear`해 그 화면의 ViewModel(Presenter owner)을 닫는다. Activity가 끝나면 남은 store를 모두 닫는다.
 */
internal class WLEntryViewModelStores : ViewModel() {
    private val stores = mutableMapOf<Long, ViewModelStore>()

    fun storeFor(entryId: Long): ViewModelStore = stores.getOrPut(entryId) { ViewModelStore() }

    /** 없는 id면 아무 일도 하지 않는다(같은 id를 두 번 지워도 된다). */
    fun clear(entryId: Long) {
        stores.remove(entryId)?.clear()
    }

    /** 전환이 끝났을 때만 빠진 칸의 store를 지운다. 전환 중이면 떠나는 화면이 아직 그려지므로 다음 기회로 미룬다. */
    fun clearRemoved(navigator: WLNavigator) {
        if (navigator.isTransitioning) return
        navigator.drainRemoved().forEach(::clear)
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

/**
 * 스택 칸 하나의 ViewModelStoreOwner. `WLNavHost`가 칸마다 제공한다. lifecycle-viewmodel-compose(`LocalViewModelStoreOwner`)가
 * 의존성에 없어 직접 둔다. 화면은 `ViewModelProvider(LocalWLEntryViewModelStoreOwner.current, factory)[…]`로 owner를 만든다.
 */
val LocalWLEntryViewModelStoreOwner = staticCompositionLocalOf<ViewModelStoreOwner> {
    error("WLNavHost 밖에서 LocalWLEntryViewModelStoreOwner를 읽었다")
}
