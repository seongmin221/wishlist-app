package app.wishlist.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.wishlist.android.BuildConfig
import app.wishlist.android.R
import app.wishlist.android.designsystem.DISABLED_ALPHA
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLButton
import app.wishlist.android.designsystem.WLButtonKind
import app.wishlist.android.designsystem.WLCard
import app.wishlist.android.designsystem.WLCircleButton
import app.wishlist.android.designsystem.WLIcon
import app.wishlist.android.designsystem.WLIconTile
import app.wishlist.android.designsystem.WLLineIcon
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLTopBar
import app.wishlist.android.designsystem.WLType
import app.wishlist.android.designsystem.WishlistTokens
import app.wishlist.android.designsystem.overlay.LocalOverlayHostState
import app.wishlist.android.designsystem.overlay.WLDialogSpec
import app.wishlist.android.feature.session.LocalAccountOwner
import app.wishlist.android.navigation.LocalWLNavigator
import app.wishlist.android.platform.WebViewDataCleaner
import app.wishlist.android.ui.LoginRoute
import app.wishlist.shared.core.AuthAccount
import app.wishlist.shared.core.AuthProvider

private val SectionLabel = WLType.label.copy(fontSize = 13.sp)
private val RowTitle = WLType.body.copy(fontSize = 16.sp)
private val RowSubtitle = WLType.body.copy(fontSize = 13.sp)
private val Email = WLType.bodyStrong.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold)
private val Hint = WLType.body.copy(fontSize = 15.sp, lineHeight = 1.5f.em)
private val Version = WLType.price.copy(fontSize = 14.sp)

/**
 * FSettings·FSettingsLoggedOut(+ 로그아웃·웹뷰 데이터 삭제 확인창). 줄을 누르면 다음 화면은 가로 밀기다. 로그아웃은 먹색,
 * 웹뷰 데이터 삭제는 빨강 확인. "방금 삭제했어요"는 이 화면 수명 동안만(C3-D5 h). 오픈소스 라이선스 줄은 C12까지 숨긴다.
 */
@Composable
fun SettingsScreen() {
    val c = LocalWLColors.current
    val nav = LocalWLNavigator.current
    val overlay = LocalOverlayHostState.current
    val owner = LocalAccountOwner.current
    val context = LocalContext.current
    val state by owner.state.collectAsState()
    val idle = state.signingIn == null
    var webViewCleared by rememberSaveable { mutableStateOf(false) }

    val logoutDialog = WLDialogSpec(
        title = stringResource(R.string.logout_title),
        bullets = listOf(
            stringResource(R.string.logout_line_device),
            stringResource(R.string.logout_line_kept),
            stringResource(R.string.logout_line_resume),
        ),
        cancelText = stringResource(R.string.dialog_cancel),
        confirmText = stringResource(R.string.settings_logout),
        confirmKind = WLButtonKind.Primary,
        onConfirm = { owner.signOut() },
    )
    val webViewDialog = WLDialogSpec(
        title = stringResource(R.string.webview_clear_title),
        bullets = listOf(
            stringResource(R.string.webview_clear_line_login),
            stringResource(R.string.webview_clear_line_cookies),
            stringResource(R.string.webview_clear_line_kept),
            stringResource(R.string.webview_clear_line_irreversible),
        ),
        cancelText = stringResource(R.string.dialog_cancel),
        confirmText = stringResource(R.string.webview_clear_confirm),
        confirmKind = WLButtonKind.Danger,
        onConfirm = { if (WebViewDataCleaner.clear(context)) webViewCleared = true },
    )

    Column(Modifier.fillMaxSize().background(c.background)) {
        WLTopBar(
            background = c.background,
            leading = { WLCircleButton(WLLineIcon.Back, stringResource(R.string.settings_back), onClick = { nav.pop() }) },
            title = { WLText(stringResource(R.string.settings_title), WLType.title, maxLines = 1) },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = WishlistTokens.Space.screenMargin, end = WishlistTokens.Space.screenMargin, top = WishlistTokens.Space.s8),
            verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s24),
        ) {
            SettingsSection(stringResource(R.string.settings_account)) {
                val account = state.account
                if (account != null) {
                    AccountRow(account)
                    SettingsRow(stringResource(R.string.settings_logout), enabled = idle, onClick = { overlay.showDialog(logoutDialog) }) {
                        WLIcon(WLLineIcon.ChevronRight, size = 16.dp)
                    }
                } else {
                    Column(
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        WLText(stringResource(R.string.settings_login_hint), Hint)
                        WLButton(
                            stringResource(R.string.settings_login), WLButtonKind.Primary,
                            onClick = { nav.push(LoginRoute, "settings/login") },
                            modifier = Modifier.fillMaxWidth(), enabled = idle,
                        )
                    }
                }
            }
            SettingsSection(stringResource(R.string.settings_original_links)) {
                SettingsRow(
                    stringResource(R.string.settings_webview_clear),
                    subtitle = stringResource(if (webViewCleared) R.string.settings_webview_cleared else R.string.settings_webview_clear_hint),
                    onClick = { overlay.showDialog(webViewDialog) },
                ) { WLIcon(WLLineIcon.ChevronRight, size = 16.dp) }
            }
            SettingsSection(stringResource(R.string.settings_app_info)) {
                SettingsRow(stringResource(R.string.settings_version), onClick = null) {
                    WLText(BuildConfig.VERSION_NAME, Version, color = c.textSecondary, maxLines = 1)
                }
            }
            Spacer(Modifier.height(60.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))
        }
    }
}

/** 섹션: 13/500 이름(좌우 4) + 묶음 카드(모서리 28, 위아래 4). */
@Composable
private fun SettingsSection(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s8)) {
        WLText(label, SectionLabel, Modifier.padding(horizontal = 4.dp), color = LocalWLColors.current.textSecondary)
        WLCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp), content = content)
        }
    }
}

/** 계정 줄(높이 72 이상): 원형 아바타 · 이메일 16/700 · "○○로 로그인됨" 12/500. */
@Composable
private fun AccountRow(account: AuthAccount) {
    val c = LocalWLColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WLIconTile(radius = 22.dp) { WLIcon(WLLineIcon.Person) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            WLText(account.email, Email, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val provider = when (account.provider) {
                AuthProvider.GOOGLE -> R.string.settings_signed_in_google
                AuthProvider.APPLE -> R.string.settings_signed_in_apple
            }
            WLText(stringResource(provider), WLType.label, color = c.textSecondary, maxLines = 1)
        }
    }
}

/** 설정 줄(높이 56 이상, 좌 16·우 14): 제목 16 + 보조 13, 오른쪽 화살표나 값. `onClick`이 없으면 누를 수 없다. */
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)?,
    trailing: @Composable RowScope.() -> Unit,
) {
    val c = LocalWLColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(WishlistTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            WLText(title, RowTitle)
            if (subtitle != null) WLText(subtitle, RowSubtitle, color = c.textSecondary)
        }
        trailing()
    }
}
