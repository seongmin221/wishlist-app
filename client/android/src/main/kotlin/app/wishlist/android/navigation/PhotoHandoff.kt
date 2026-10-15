package app.wishlist.android.navigation

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 전환 중 사진 보유(C5 spec §4). 메모리 cache는 축출될 수 있어 key만으로는 전환 중 사진이 남는다는 보장이 없다.
 * 그래서 사진 공유 요소 전환 동안 그리는 이미지 객체 자체를 셸이 잠깐 든다(메모리 전용, 저장하지 않는다).
 *
 * - **정방향(push):** 카드를 누르는 순간 카드가 그리고 있는 이미지와 그 memory cache key를 새 칸 id로 [hold]한다.
 *   상세 사진 자리는 이것 → cache 자리표시(`placeholderMemoryCacheKey`) → 기본 placeholder 순으로 쓴다.
 *   상세 크기 사진이 그려지고([detailDrawn]) push 모션이 끝나면([motionEnded]) 놓는다.
 * - **역방향(pop·끌어서 뒤로):** Compose 공유 요소는 뒤로 갈 때 카드 쪽 내용을 overlay에 그린다. 상세가 떠나는 동안
 *   ([isLeaving]) 자기 현재 이미지를 [beginReverse]하고, 카드 사진 칸은 [reverseFor]가 있으면 그것을 먼저 그린다.
 *   끌기를 취소해 상세로 돌아오면 [endReverse].
 * - 칸이 스택에서 빠지면(pop 모션 뒤, 계정 떠남) [drop]이 양쪽을 놓는다. 프로세스 복원에는 handoff가 없다(일반 로드).
 *
 * snapshot 상태라 읽는 composable이 바뀔 때 다시 그린다. 이미지·key 종류는 앱이 정한다(앱은 `coil3.Image`·`MemoryCache.Key`).
 */
class PhotoHandoff<I : Any, K : Any> {
    data class Held<I, K>(val image: I, val memoryCacheKey: K?)

    private data class Forward<I, K>(val held: Held<I, K>, val drawn: Boolean = false, val motionEnded: Boolean = false)
    private data class Reverse<I>(val sourceKey: String, val image: I)

    private val forward = mutableStateMapOf<Long, Forward<I, K>>()
    private val reverse = mutableStateMapOf<Long, Reverse<I>>()

    fun hold(entryId: Long, image: I, memoryCacheKey: K?) {
        forward[entryId] = Forward(Held(image, memoryCacheKey))
    }

    fun forward(entryId: Long): Held<I, K>? = forward[entryId]?.held

    fun detailDrawn(entryId: Long) = updateForward(entryId) { it.copy(drawn = true) }

    fun motionEnded(entryId: Long) = updateForward(entryId) { it.copy(motionEnded = true) }

    private fun updateForward(entryId: Long, change: (Forward<I, K>) -> Forward<I, K>) {
        val next = forward[entryId]?.let(change) ?: return
        if (next.drawn && next.motionEnded) forward.remove(entryId) else forward[entryId] = next
    }

    fun beginReverse(entryId: Long, sourceKey: String, image: I) {
        reverse[entryId] = Reverse(sourceKey, image)
    }

    fun reverseFor(sourceKey: String): I? = reverse.values.firstOrNull { it.sourceKey == sourceKey }?.image

    fun endReverse(entryId: Long) {
        reverse.remove(entryId)
    }

    fun drop(entryIds: Collection<Long>) {
        entryIds.forEach {
            forward.remove(it)
            reverse.remove(it)
        }
    }

    companion object {
        /**
         * 칸 [entryId]가 지금 뒤로 떠나는 중인지. 끌어서 뒤로는 스택이 아직 그대로라 이 칸이 맨 위이고, pop은 이미
         * 스택에서 빠져 떠나는 칸으로 그려진다.
         */
        internal fun isLeaving(transition: WLNavTransition?, tab: WLTab, entryId: Long, stack: List<Long>): Boolean = when {
            transition == null || transition.tab != tab -> false
            transition is WLNavTransition.BackGesture -> stack.lastOrNull() == entryId
            transition is WLNavTransition.Pop -> entryId !in stack
            else -> false
        }
    }
}

/** 셸이 하나 두는 사진 handoff(`WLNavHost`가 넣는다). */
typealias AppPhotoHandoff = PhotoHandoff<coil3.Image, coil3.memory.MemoryCache.Key>

val LocalPhotoHandoff = staticCompositionLocalOf<AppPhotoHandoff> { error("WLNavHost 밖에서 LocalPhotoHandoff를 읽었다") }

/**
 * DEBUG 기기 확인용(C5 spec §4 "메모리 cache를 비운 상태에서 push·pop·끌어서 뒤로 취소"): 켜져 있으면 사진 전환이 시작될
 * 때(카드를 누를 때, 상세가 뒤로 떠나기 시작할 때) 이미지 memory cache를 비운다. DEBUG launch extra만 켜고 release는 늘 꺼져 있다.
 */
object PhotoTransitionDebug {
    @Volatile var clearImageMemory = false

    fun onPhotoTransitionStart(context: android.content.Context) {
        if (clearImageMemory) coil3.SingletonImageLoader.get(context).memoryCache?.clear()
    }
}
