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

/** 저장된 상품 상세(서버 항목). 홈 행에서 가로 밀기로 열린다. 탭 바 없음, 계정 범위. */
internal data class ItemDetailRoute(val itemId: String) : WLRoute {
    override val showsTabBar = false
    override val pushStyle = WLPushStyle.Slide
    override val accountScoped = true
}

/** 아직 서버에 가지 않은 로컬 대기 항목의 상세. 서버 항목으로 옮겨지면 `replaceTop(ItemDetailRoute)`로 바뀐다. 계정 범위. */
internal data class LocalSubmissionRoute(val submissionId: String) : WLRoute {
    override val showsTabBar = false
    override val pushStyle = WLPushStyle.Slide
    override val accountScoped = true
}

/** C3·C4 production routes. Tokens never collide with the debug `demo` namespace. */
internal object ProductionRouteCodec : WLRouteCodec {
    override fun encode(route: WLRoute): List<String>? = when (route) {
        SettingsRoute -> listOf("settings")
        LoginRoute -> listOf("login")
        is ItemDetailRoute -> listOf("item", route.itemId)
        is LocalSubmissionRoute -> listOf("local", route.submissionId)
        else -> null
    }

    override fun decode(tokens: List<String>): WLRoute? = when {
        tokens == listOf("settings") -> SettingsRoute
        tokens == listOf("login") -> LoginRoute
        tokens.size == 2 && tokens[0] == "item" -> ItemDetailRoute(tokens[1])
        tokens.size == 2 && tokens[0] == "local" -> LocalSubmissionRoute(tokens[1])
        else -> null
    }
}
