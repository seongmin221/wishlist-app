package app.wishlist.android.navigation

/** 하단 탭 3개. 순서가 탭 바 칸 순서다. */
enum class WLTab { Home, Category, Purpose }

/** 앱/feature가 경로의 화면 정책을 제공한다. */
interface WLRoute {
    val showsTabBar: Boolean
    val pushStyle: WLPushStyle

    data class TabRoot(val tab: WLTab) : WLRoute {
        override val showsTabBar = true
        override val pushStyle = WLPushStyle.Surface
    }
}

enum class WLPushStyle { Photo, Surface }
