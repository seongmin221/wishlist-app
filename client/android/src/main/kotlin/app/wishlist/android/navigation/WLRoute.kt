package app.wishlist.android.navigation

/** 하단 탭 3개(디자인 결정 2026-10-01). 순서가 탭 바 칸 순서다. */
enum class WLTab { Home, Category, Purpose }

/** 탭 스택에 쌓이는 화면. C1에서는 탭 첫 화면과 데모 상세만 있다. */
sealed interface WLRoute {
    /** 이 화면이 맨 위일 때 탭 바를 보이는지. */
    val showsTabBar: Boolean

    data class TabRoot(val tab: WLTab) : WLRoute {
        override val showsTabBar: Boolean get() = true
    }

    /**
     * 데모 상세(debug 빌드에서만 진입). `hasPhoto`면 사진 공유 요소로, 아니면 누른 면(자리 표시)이 커지며 열린다
     * (motion.md 2절의 두 방식).
     */
    data class DemoDetail(val id: String, val hasPhoto: Boolean) : WLRoute {
        override val showsTabBar: Boolean get() = false
    }
}

/** 화면 이동 방식. 경로가 정한다(motion.md 2절). */
internal enum class WLPushStyle { Photo, Surface }

internal val WLRoute.pushStyle: WLPushStyle
    get() = when (this) {
        is WLRoute.DemoDetail -> if (hasPhoto) WLPushStyle.Photo else WLPushStyle.Surface
        is WLRoute.TabRoot -> WLPushStyle.Surface
    }
