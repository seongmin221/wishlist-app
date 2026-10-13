package app.wishlist.android.navigation

/** 하단 탭 3개. 순서가 탭 바 칸 순서다. */
enum class WLTab { Home, Category, Purpose }

/** 앱/feature가 경로의 화면 정책을 제공한다. */
interface WLRoute {
    val showsTabBar: Boolean
    val pushStyle: WLPushStyle

    /** 로그인한 계정의 데이터를 보이는 화면. 계정을 떠나면(로그아웃·다른 계정) 모든 탭에서 이 칸과 그 위가 닫힌다. */
    val accountScoped: Boolean get() = false

    data class TabRoot(val tab: WLTab) : WLRoute {
        override val showsTabBar = true
        override val pushStyle = WLPushStyle.Slide
    }
}

/** 화면 이동 방식: 사진이 커지는 공유 요소(Photo) 또는 가로 밀기(Slide, 사진 없는 이동). */
enum class WLPushStyle { Photo, Slide }
