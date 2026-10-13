package app.wishlist.android.feature.web

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wishlist.android.R
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.WLIcon
import app.wishlist.android.designsystem.WLIconTile
import app.wishlist.android.designsystem.WLLineIcon
import app.wishlist.android.designsystem.WLText
import app.wishlist.android.designsystem.WLType

/** FWebViewShare actions on the page's current URL (spec §4, D6). The web view stays open for all three. */
internal object WebShare {
    /** Android 13+ shows its own clipboard confirmation, so the app's "링크를 복사했어요" is only for older versions (D6). */
    fun showsCopyNotice(sdkInt: Int): Boolean = sdkInt < Build.VERSION_CODES.TIRAMISU

    @Suppress("UseKtx") // Uri.parse: core-ktx is only a transitive dependency of this module.
    fun openInBrowser(context: Context, url: String) {
        WebExternalApps.startSafely(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    }

    /** true when the app should show its own notice (copied and below Android 13). */
    fun copy(context: Context, url: String): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText(url, url))
        return showsCopyNotice(Build.VERSION.SDK_INT)
    }

    fun shareToOtherApp(context: Context, url: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        WebExternalApps.startSafely(context, Intent.createChooser(send, null))
    }
}

private val RowText = WLType.body.copy(fontSize = 16.sp)

/** 시트 내용(보드 FWebViewShare): 줄 최소 60, 40 타일(모서리 14, 시트 칸 색) + 16 글자, 간격 14. 누르면 시트가 닫힌 뒤 동작한다. */
@Composable
internal fun WebShareSheetContent(onOpenBrowser: () -> Unit, onCopy: () -> Unit, onShareOther: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ShareRow(WLLineIcon.Globe, stringResource(R.string.webview_open_browser), onOpenBrowser)
        ShareRow(WLLineIcon.Copy, stringResource(R.string.webview_copy_link), onCopy)
        ShareRow(WLLineIcon.Apps, stringResource(R.string.webview_share_other), onShareOther)
    }
}

@Composable
private fun ShareRow(icon: WLLineIcon, label: String, onClick: () -> Unit) {
    val c = LocalWLColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WLIconTile(size = 40.dp, color = c.sheetField) { WLIcon(icon) }
        WLText(label, RowText, Modifier.weight(1f), color = c.text)
    }
}
