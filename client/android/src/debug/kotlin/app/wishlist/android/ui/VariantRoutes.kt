package app.wishlist.android.ui

import androidx.compose.runtime.Composable
import app.wishlist.android.navigation.*
import app.wishlist.android.feature.demo.*

internal object VariantRoutes : WLRouteCodec {
    override fun encode(route: WLRoute): List<String>? = when (route) {
        is DemoRoute.Product -> listOf("demo", "product", route.itemId)
        is DemoRoute.CategoryList -> listOf("demo", "list", route.top, route.type)
        is DemoRoute.Purpose -> listOf("demo", "purpose", route.purposeId)
        else -> null
    }

    override fun decode(tokens: List<String>): WLRoute? {
        if (tokens.firstOrNull() != "demo") return null
        return when {
            tokens.size == 3 && tokens[1] == "product" -> DemoRoute.Product(tokens[2])
            tokens.size == 4 && tokens[1] == "list" -> DemoRoute.CategoryList(tokens[2], tokens[3])
            tokens.size == 3 && tokens[1] == "purpose" -> DemoRoute.Purpose(tokens[2])
            else -> null
        }
    }

    @Composable
    fun Content(route: WLRoute, sourceKey: String?): Boolean {
        when (route) {
            is WLRoute.TabRoot -> when (route.tab) {
                // 홈 탭은 C3부터 실제 HomeScreen(main AppRoute)이다.
                WLTab.Home -> return false
                WLTab.Category -> DemoCategoryScreen()
                WLTab.Purpose -> DemoPurposeScreen()
            }
            is DemoRoute.Product -> DemoProductDetailScreen(route, sourceKey)
            is DemoRoute.CategoryList -> DemoCategoryListScreen(route, sourceKey)
            is DemoRoute.Purpose -> DemoPurposeDetailScreen(route, sourceKey)
            else -> return false
        }
        return true
    }
}
