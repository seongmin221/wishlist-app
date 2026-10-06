package app.wishlist.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import app.wishlist.android.designsystem.*
import app.wishlist.android.navigation.*

@Composable
internal fun AppRoute(route: WLRoute, sourceKey: String?) {
    if (!VariantRoutes.Content(route, sourceKey)) {
        when (route) {
            is WLRoute.TabRoot -> PlainTabRoot(route.tab)
            else -> error("No screen registered for $route")
        }
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

/** App-owned codec composition point; future production feature codecs are registered here. */
internal object AppRouteCodec : WLRouteCodec {
    private val featureCodecs: List<WLRouteCodec> = emptyList()

    override fun encode(route: WLRoute): List<String>? =
        featureCodecs.firstNotNullOfOrNull { it.encode(route) } ?: VariantRoutes.encode(route)

    override fun decode(tokens: List<String>): WLRoute? =
        featureCodecs.firstNotNullOfOrNull { it.decode(tokens) } ?: VariantRoutes.decode(tokens)
}
