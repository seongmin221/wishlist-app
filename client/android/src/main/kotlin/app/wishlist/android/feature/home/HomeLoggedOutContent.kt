package app.wishlist.android.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.wishlist.android.R
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLButton
import app.wishlist.android.designsystem.WLButtonKind
import app.wishlist.android.designsystem.WLCard
import app.wishlist.android.designsystem.WLIcon
import app.wishlist.android.designsystem.WLIconTile
import app.wishlist.android.designsystem.WLLineIcon
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.feature.session.LocalAccountOwner
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.android.ui.LoginRoute
import app.wishlist.shared.presentation.HomeState
import androidx.compose.runtime.collectAsState

/**
 * FHomeLoggedOut: 로그인 카드 + 할 일 "분석 대기"(이 기기에만 있는 링크, 오래된 순). 대기가 없으면 할 일 섹션을 숨긴다.
 * 줄을 누르면 로컬 대기 화면(C4), 오른쪽 "원본"은 시스템 브라우저다(PR A).
 */
@Composable
internal fun HomeLoggedOutContent(state: HomeState.LoggedOut) {
    Column(Modifier.padding(top = WishlistTokens.Space.s32)) { LoginCard() }
    if (state.pending.isNotEmpty()) {
        HomeSection(stringResource(R.string.home_todo)) {
            var expanded by rememberExpanded("home/pending")
            HomeTodoCard(
                icon = WLLineIcon.Clock,
                title = stringResource(R.string.home_pending_title),
                subtitle = stringResource(R.string.home_pending_subtitle),
                count = state.pending.size,
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                state.pending.forEach { row -> key(row.key) { HomeLinkRow(row, WLLineIcon.Clock, tileSize = 44.dp, showsOriginal = true) } }
            }
        }
    }
}

/** 로그인 카드: 아이콘 타일 + 17/700 제목, 14/1.6 글머리표 두 줄, 로그인(먹색 주 버튼 → FLogin push). */
@Composable
private fun LoginCard() {
    val c = LocalWLColors.current
    val nav = LocalWLNavigator.current
    val account by LocalAccountOwner.current.state.collectAsState()
    WLCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(WishlistTokens.Space.s20), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12), verticalAlignment = Alignment.CenterVertically) {
                WLIconTile { WLIcon(WLLineIcon.Person) }
                WLText(stringResource(R.string.home_login_card_title), HomeStyles.cardTitle)
            }
            Column(Modifier.padding(start = 6.dp)) {
                listOf(R.string.home_login_card_fill, R.string.home_login_card_devices).forEach { res ->
                    Row(horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8)) {
                        WLText("•", HomeStyles.bullet, color = c.textSecondary)
                        WLText(stringResource(res), HomeStyles.bullet, Modifier.weight(1f), color = c.textSecondary)
                    }
                }
            }
            WLButton(
                stringResource(R.string.home_login_button),
                WLButtonKind.Primary,
                onClick = { nav.push(LoginRoute, "home/login") },
                modifier = Modifier.fillMaxWidth(),
                enabled = account.signingIn == null,
            )
        }
    }
}
