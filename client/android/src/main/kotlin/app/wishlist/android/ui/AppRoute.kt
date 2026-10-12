package app.wishlist.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import app.wishlist.android.designsystem.*
import app.wishlist.android.feature.detail.ItemDetailScreen
import app.wishlist.android.feature.detail.LocalSubmissionScreen
import app.wishlist.android.feature.home.HomeScreen
import app.wishlist.android.feature.login.LoginMode
import app.wishlist.android.feature.login.LoginScreen
import app.wishlist.android.feature.settings.SettingsScreen
import app.wishlist.android.navigation.*

/** 앱의 단일 route renderer: 실제 기능 화면(debug·release 공통)을 먼저 고르고, 나머지는 variant(debug 데모)에 맡긴다. */
@Composable
internal fun AppRoute(route: WLRoute, sourceKey: String?) {
    when (route) {
        WLRoute.TabRoot(WLTab.Home) -> HomeScreen()
        SettingsRoute -> SettingsScreen()
        LoginRoute -> LoginScreen(LoginMode.Pushed)
        is ItemDetailRoute -> ItemDetailScreen(route.itemId)
        is LocalSubmissionRoute -> LocalSubmissionScreen(route.submissionId)
        else -> if (!VariantRoutes.Content(route, sourceKey)) {
            when (route) {
                is WLRoute.TabRoot -> PlainTabRoot(route.tab)
                else -> error("No screen registered for $route")
            }
        }
    }
}

/** release 빌드의 카테고리·목적 탭 첫 화면(아직 기능 화면이 없다). */
@Composable
private fun PlainTabRoot(tab: WLTab) {
    Column(
        Modifier
            .fillMaxSize()
            .background(LocalWLColors.current.background)
            .statusBarsPadding()
            .padding(horizontal = WishlistTokens.Space.screenMargin, vertical = WishlistTokens.Space.s24),
    ) {
        WLText(tab.label, WLType.display28)
    }
}

/** App-owned codec composition point; production feature codecs first, then the variant's (debug demo). */
internal object AppRouteCodec : WLRouteCodec {
    private val featureCodecs: List<WLRouteCodec> = listOf(ProductionRouteCodec)

    override fun encode(route: WLRoute): List<String>? =
        featureCodecs.firstNotNullOfOrNull { it.encode(route) } ?: VariantRoutes.encode(route)

    override fun decode(tokens: List<String>): WLRoute? =
        featureCodecs.firstNotNullOfOrNull { it.decode(tokens) } ?: VariantRoutes.decode(tokens)
}
