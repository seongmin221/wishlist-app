package app.wishlist.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.wishlist.android.BuildConfig
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLTheme
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.designsystem.overlay.OverlayHost
import app.wishlist.android.designsystem.overlay.rememberOverlayHostState
import app.wishlist.android.feature.demo.DemoCategoryScreen
import app.wishlist.android.feature.demo.DemoDetailScreen
import app.wishlist.android.feature.demo.DemoHomeScreen
import app.wishlist.android.feature.demo.DemoPurposeScreen
import app.wishlist.android.navigation.WLNavHost
import app.wishlist.android.navigation.WLRoute
import app.wishlist.android.navigation.WLTab
import app.wishlist.android.navigation.label

/** 앱 루트: 테마 → overlay(시트·확인창·메뉴) → 탭 셸. */
@Composable
fun WishlistApp() {
    WLTheme {
        OverlayHost(rememberOverlayHostState()) {
            WLNavHost { route, sourceKey -> AppRoute(route, sourceKey) }
        }
    }
}

@Composable
private fun AppRoute(route: WLRoute, sourceKey: String?) {
    when (route) {
        is WLRoute.TabRoot -> if (BuildConfig.DEBUG) DemoTabRoot(route.tab) else PlainTabRoot(route.tab)
        // 데모 상세는 debug 빌드의 데모 첫 화면에서만 열린다.
        is WLRoute.DemoDetail -> DemoDetailScreen(route, sourceKey)
    }
}

@Composable
private fun DemoTabRoot(tab: WLTab) {
    when (tab) {
        WLTab.Home -> DemoHomeScreen()
        WLTab.Category -> DemoCategoryScreen()
        WLTab.Purpose -> DemoPurposeScreen()
    }
}

/** release 빌드의 탭 첫 화면(C1에는 기능 화면이 없다). */
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
