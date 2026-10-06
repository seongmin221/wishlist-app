package app.wishlist.android.ui

import androidx.compose.runtime.Composable
import app.wishlist.android.navigation.*
import app.wishlist.android.feature.demo.*

internal object VariantRoutes : WLRouteCodec {
    override fun encode(route: WLRoute): List<String>? = (route as? DemoRoute.Detail)?.let {
        listOf("demo", it.id, it.hasPhoto.toString())
    }
    override fun decode(tokens: List<String>): WLRoute? =
        if (tokens.size == 3 && tokens[0] == "demo") DemoRoute.Detail(tokens[1], tokens[2].toBooleanStrict()) else null

    @Composable
    fun Content(route: WLRoute, sourceKey: String?): Boolean {
        when (route) {
            is WLRoute.TabRoot -> when (route.tab) {
                WLTab.Home -> DemoHomeScreen()
                WLTab.Category -> DemoCategoryScreen()
                WLTab.Purpose -> DemoPurposeScreen()
            }
            is DemoRoute.Detail -> DemoDetailScreen(route, sourceKey)
            else -> return false
        }
        return true
    }
}
