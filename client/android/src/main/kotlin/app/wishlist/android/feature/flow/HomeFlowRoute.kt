package app.wishlist.android.feature.flow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.wishlist.android.R
import app.wishlist.android.WishlistApplication
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.feature.detail.DetailStatusBlock
import app.wishlist.android.feature.detail.DetailText
import app.wishlist.android.navigation.LocalWLEntryViewModelStoreOwner
import app.wishlist.android.navigation.WLPushStyle
import app.wishlist.android.navigation.WLRoute
import app.wishlist.shared.model.HomeActionGroup
import app.wishlist.shared.presentation.HomeFlowPhase
import kotlinx.coroutines.flow.first
import java.util.Base64

/**
 * 홈 연속 처리(FHomeReviewFlow · FHomeFillFlow). [startAnchor]: the carousel preview's HOME-02 anchor
 * (null = from the first card). Account scoped, slide, no tab bar. Codec `["flow", group, b64url(anchor) | ""]`.
 */
internal data class HomeFlowRoute(val group: HomeActionGroup, val startAnchor: String?) : WLRoute {
    override val showsTabBar = false
    override val pushStyle = WLPushStyle.Slide
    override val accountScoped = true

    companion object {
        private val FLOW_GROUPS = setOf(HomeActionGroup.CLASSIFICATION_REVIEW, HomeActionGroup.INFORMATION_COMPLETION)

        fun encode(route: HomeFlowRoute): List<String> = listOf(
            "flow", route.group.name,
            route.startAnchor?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8)) }.orEmpty(),
        )

        fun decode(tokens: List<String>): HomeFlowRoute? {
            if (tokens.size != 3 || tokens[0] != "flow") return null
            val group = HomeActionGroup.entries.firstOrNull { it.name == tokens[1] }?.takeIf { it in FLOW_GROUPS } ?: return null
            if (tokens[2].isEmpty()) return HomeFlowRoute(group, null)
            return try {
                HomeFlowRoute(group, String(Base64.getUrlDecoder().decode(tokens[2]), Charsets.UTF_8).takeIf { it.isNotEmpty() })
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

/**
 * The flow screen: starts the Presenter once (entry store), and on a later resume (app foreground while
 * this route is shown) asks for the window around the current card. The group picks the board.
 */
@Composable
internal fun HomeFlowScreen(route: HomeFlowRoute) {
    val store = LocalWLEntryViewModelStoreOwner.current
    val runtime = (LocalContext.current.applicationContext as WishlistApplication).runtime
    val owner = remember(store) { HomeFlowOwner.of(store) { runtime.homeFlowPresenter() } }
    val presenter = owner.presenter
    val state by presenter.state.collectAsState()
    LaunchedEffect(owner) {
        runtime.ready.first { it }
        owner.startOnce(route.group, route.startAnchor)
    }
    // C6 pattern: the first resume is the screen's own entry; later ones are the app coming back.
    var resumes by remember(owner) { mutableIntStateOf(0) }
    LifecycleResumeEffect(owner) {
        if (resumes++ > 0 && runtime.ready.value) presenter.foreground()
        onPauseOrDispose { }
    }
    val c = LocalWLColors.current
    Column(Modifier.fillMaxSize().background(c.background)) {
        val title = stringResource(if (route.group == HomeActionGroup.CLASSIFICATION_REVIEW) R.string.home_group_review_title else R.string.home_group_fill_title)
        val error = state.error
        when {
            state.phase == HomeFlowPhase.ERROR && error != null -> {
                FlowHeader(title, position = null)
                DetailStatusBlock(stringResource(DetailText.errorText(error)), stringResource(R.string.detail_retry), presenter::retry)
            }
            route.group == HomeActionGroup.CLASSIFICATION_REVIEW -> ReviewFlowScreen(title, state, presenter)
            else -> FillFlowScreen(title, state, presenter)
        }
    }
}
