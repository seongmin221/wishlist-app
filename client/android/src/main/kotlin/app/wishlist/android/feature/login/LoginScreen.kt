package app.wishlist.android.feature.login

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.wishlist.android.R
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLButton
import app.wishlist.android.designsystem.WLButtonKind
import app.wishlist.android.designsystem.WLIcon
import app.wishlist.android.designsystem.WLIconTile
import app.wishlist.android.designsystem.WLLineIcon
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.feature.session.LocalAccountOwner
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.shared.core.AuthProvider
import kotlinx.coroutines.flow.first

/** 첫 실행 안내(앱 루트 위 레이어)인지, 홈 로그인 카드·설정 "로그인"에서 push로 연 화면인지. */
enum class LoginMode { FirstRun, Pushed }

private val PointStyle = WLType.body.copy(fontSize = 15.sp, lineHeight = 1.5f.em)
private val LaterStyle = WLType.body.copy(fontSize = 15.sp)

/**
 * FLogin. 로그인 중(`signingIn`)에는 세 버튼을 모두 막는다. 실패는 C3에서 화면에 남기지 않는다(fake는 RELEASE에서만
 * 실패하고, 그때 버튼은 아무 일도 하지 않는다 — 인증 연결 단계까지).
 * - FirstRun: "나중에 하기" = 다시 자동으로 띄우지 않음. 로그인하면 루트가 레이어를 내린다.
 * - Pushed: "나중에 하기" = 뒤로. 로그인 전 → 로그인으로 바뀌면 이 화면을 닫는다.
 */
@Composable
fun LoginScreen(mode: LoginMode) {
    val c = LocalWLColors.current
    val owner = LocalAccountOwner.current
    val nav = LocalWLNavigator.current
    val state by owner.state.collectAsState()
    val idle = state.signingIn == null

    if (mode == LoginMode.Pushed) {
        val signedOutWhenOpened = remember { owner.state.value.account == null }
        LaunchedEffect(Unit) {
            if (!signedOutWhenOpened) return@LaunchedEffect
            owner.state.first { it.account != null }
            // 여는 전환이 아직 끝나지 않았으면 끝난 뒤 닫는다(전환 중 pop은 무시된다).
            snapshotFlow { nav.isTransitioning }.first { !it }
            nav.pop()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.background)
            // 첫 실행 레이어 아래 탭 셸로 누름이 새지 않게 한다.
            .pointerInput(Unit) { detectTapGestures { } }
            .safeDrawingPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 40.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s24, Alignment.CenterVertically),
            ) {
                WLIconTile(size = 64.dp, radius = WishlistTokens.Radius.m, color = WishlistTokens.Purpose.coral) {
                    WLIcon(WLLineIcon.Heart, size = 30.dp, color = WishlistTokens.Purpose.onPurpose)
                }
                WLText(stringResource(R.string.login_title), WLType.display28TwoLine)
                Column(verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12)) {
                    listOf(R.string.login_point_share, R.string.login_point_fetch, R.string.login_point_devices).forEach { res ->
                        Row(horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12), verticalAlignment = Alignment.CenterVertically) {
                            WLIconTile(size = 32.dp, radius = WishlistTokens.Radius.xs, color = WishlistTokens.Purpose.coral) {
                                WLIcon(WLLineIcon.Check, color = WishlistTokens.Purpose.onPurpose)
                            }
                            WLText(stringResource(res), PointStyle, Modifier.weight(1f))
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WLButton(
                    stringResource(R.string.login_apple), WLButtonKind.Primary,
                    onClick = { owner.signIn(AuthProvider.APPLE) },
                    modifier = Modifier.fillMaxWidth().height(56.dp), enabled = idle,
                )
                WLButton(
                    stringResource(R.string.login_google), WLButtonKind.Secondary,
                    onClick = { owner.signIn(AuthProvider.GOOGLE) },
                    modifier = Modifier.fillMaxWidth().height(56.dp), enabled = idle,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(WishlistTokens.Radius.pill))
                        .clickable(enabled = idle, role = Role.Button) {
                            when (mode) {
                                LoginMode.FirstRun -> owner.skipFirstRunLogin()
                                LoginMode.Pushed -> nav.pop()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    WLText(stringResource(R.string.login_later), LaterStyle, color = c.textSecondary, maxLines = 1)
                }
            }
        }
    }
}
