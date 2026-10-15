package app.wishlist.android.ui

import app.wishlist.android.feature.category.CategoryListRoute
import app.wishlist.android.feature.web.WebViewRoute
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

/**
 * 저장된 상품 상세(서버 항목). 탭 바 없음, 계정 범위. 홈 행에서는 가로 밀기, 카테고리 목록 카드에서는 카드 사진이 상세
 * 사진 자리로 커지는 공유 요소 전환(`viaPhoto`, C5 PR B)이다.
 */
internal data class ItemDetailRoute(val itemId: String, val viaPhoto: Boolean = false) : WLRoute {
    override val showsTabBar = false
    override val pushStyle = if (viaPhoto) WLPushStyle.Photo else WLPushStyle.Slide
    override val accountScoped = true
}

/** 아직 서버에 가지 않은 로컬 대기 항목의 상세. 서버 항목으로 옮겨지면 `replaceTop(ItemDetailRoute)`로 바뀐다. 계정 범위. */
internal data class LocalSubmissionRoute(val submissionId: String) : WLRoute {
    override val showsTabBar = false
    override val pushStyle = WLPushStyle.Slide
    override val accountScoped = true
}

/** C3·C4 production routes (`web/<base64url(url)>` re-validates on decode). Tokens never collide with the debug `demo` namespace. */
internal object ProductionRouteCodec : WLRouteCodec {
    override fun encode(route: WLRoute): List<String>? = when (route) {
        SettingsRoute -> listOf("settings")
        LoginRoute -> listOf("login")
        is ItemDetailRoute -> listOfNotNull("item", route.itemId, "photo".takeIf { route.viaPhoto })
        is LocalSubmissionRoute -> listOf("local", route.submissionId)
        is WebViewRoute -> listOf("web", WebViewRoute.encodeToken(route.url))
        is CategoryListRoute -> CategoryListRoute.encode(route)
        else -> null
    }

    override fun decode(tokens: List<String>): WLRoute? = when {
        tokens == listOf("settings") -> SettingsRoute
        tokens == listOf("login") -> LoginRoute
        tokens.size == 2 && tokens[0] == "item" -> ItemDetailRoute(tokens[1])
        tokens.size == 3 && tokens[0] == "item" && tokens[2] == "photo" -> ItemDetailRoute(tokens[1], viaPhoto = true)
        tokens.size == 2 && tokens[0] == "local" -> LocalSubmissionRoute(tokens[1])
        tokens.size == 2 && tokens[0] == "web" -> WebViewRoute.decodeToken(tokens[1])
        tokens.firstOrNull() == "category" -> CategoryListRoute.decode(tokens)
        else -> null
    }
}
