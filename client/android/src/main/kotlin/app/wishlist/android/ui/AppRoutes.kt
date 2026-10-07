package app.wishlist.android.ui

import app.wishlist.android.navigation.WLPushStyle
import app.wishlist.android.navigation.WLRoute
import app.wishlist.android.navigation.WLRouteCodec

/** 설정(FSettings*): 홈 오른쪽 위 원형 버튼에서 가로 밀기로 열린다. 탭 바 없음. */
internal data object SettingsRoute : WLRoute {
    override val showsTabBar = false
    override val pushStyle = WLPushStyle.Slide
}

/** 로그인(FLogin)을 홈 로그인 카드·설정 "로그인"에서 push로 연 경우. 첫 실행 안내는 route가 아니라 앱 루트 위 레이어다. */
internal data object LoginRoute : WLRoute {
    override val showsTabBar = false
    override val pushStyle = WLPushStyle.Slide
}

/** C3 production routes. Tokens never collide with the debug `demo` namespace. */
internal object ProductionRouteCodec : WLRouteCodec {
    override fun encode(route: WLRoute): List<String>? = when (route) {
        SettingsRoute -> listOf("settings")
        LoginRoute -> listOf("login")
        else -> null
    }

    override fun decode(tokens: List<String>): WLRoute? = when (tokens) {
        listOf("settings") -> SettingsRoute
        listOf("login") -> LoginRoute
        else -> null
    }
}
