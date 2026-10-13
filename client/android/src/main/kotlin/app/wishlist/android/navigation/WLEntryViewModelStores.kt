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
 *
 * 지운 id는 은퇴시킨다(navigator id는 다시 쓰이지 않는다). 빠진 칸이 떠나는 모션 동안 다시 store를 달라고 하면 정식 store를
 * 되살리지 않고 임시 store를 주며, 그 칸이 composition에서 사라질 때(`releaseStray`) 또는 Activity가 끝날 때 닫는다.
 */
internal class WLEntryViewModelStores : ViewModel() {
    private val stores = mutableMapOf<Long, ViewModelStore>()
    private val retired = mutableSetOf<Long>()
    private val strays = mutableMapOf<Long, ViewModelStore>()

    fun storeFor(entryId: Long): ViewModelStore =
        if (entryId in retired) strays.getOrPut(entryId) { ViewModelStore() }
        else stores.getOrPut(entryId) { ViewModelStore() }

    /** 매번 [storeFor]를 읽는 owner. 칸이 빠진 뒤 읽으면 임시 store가 나온다. */
    fun ownerFor(entryId: Long): ViewModelStoreOwner = object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore get() = storeFor(entryId)
    }

    /** 없는 id면 아무 일도 하지 않는다(같은 id를 두 번 지워도 된다). 이후 이 id의 정식 store는 다시 만들지 않는다. */
    fun clear(entryId: Long) {
        retired += entryId
        stores.remove(entryId)?.clear()
    }

    /** 빠진 칸이 화면에서 사라질 때: 그 사이 만든 임시 store를 닫는다. 살아 있는 칸이면 아무 일도 하지 않는다. */
    fun releaseStray(entryId: Long) {
        strays.remove(entryId)?.clear()
    }

    /** 복원 뒤: 스택에 없는 id의 store를 지운다(빠진 id는 저장되지 않아 `drainRemoved`로 오지 않는다). */
    fun retainOnly(liveIds: Set<Long>) {
        (stores.keys - liveIds).forEach(::clear)
    }

    /** 전환이 끝났을 때만 빠진 칸의 store를 지운다. 전환 중이면 떠나는 화면이 아직 그려지므로 다음 기회로 미룬다. */
    fun clearRemoved(navigator: WLNavigator) {
        if (navigator.isTransitioning) return
        navigator.drainRemoved().forEach(::clear)
    }

    override fun onCleared() {
        (stores.values + strays.values).forEach { it.clear() }
        stores.clear()
        strays.clear()
    }
}

/**
 * 스택 칸 하나의 ViewModelStoreOwner. `WLNavHost`가 칸마다 제공한다. lifecycle-viewmodel-compose(`LocalViewModelStoreOwner`)가
 * 의존성에 없어 직접 둔다. 화면은 `remember(owner) { ViewModelProvider(owner, factory)[…] }`로 owner를 한 번만 만든다.
 */
val LocalWLEntryViewModelStoreOwner = staticCompositionLocalOf<ViewModelStoreOwner> {
    error("WLNavHost 밖에서 LocalWLEntryViewModelStoreOwner를 읽었다")
}
