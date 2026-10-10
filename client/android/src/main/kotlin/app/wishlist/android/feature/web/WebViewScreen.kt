package app.wishlist.android.feature.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.wishlist.android.R
import app.wishlist.android.designsystem.LocalWLColors
import app.wishlist.android.designsystem.LocalWLDark
import app.wishlist.android.designsystem.WLButtonKind
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
import app.wishlist.android.feature.detail.DetailNoticeLine
import app.wishlist.android.feature.detail.DetailStatusBlock
import app.wishlist.android.feature.detail.rememberBriefNotice
import app.wishlist.android.navigation.LocalWLEntryViewModelStoreOwner
import app.wishlist.android.navigation.LocalWLNavigator
import kotlinx.coroutines.channels.Channel

/** An external-app navigation the screen still has to carry out: at once, or after FWebViewExternal. */
internal data class ExternalRequest(val target: ExternalTarget.Launch, val confirm: Boolean)

/**
 * Lifetime owner of one route's [WebView] (spec §4 수명). Kept in the entry's ViewModelStore so the page and its
 * history survive configuration changes and trips to other apps (D15); [onCleared] (pop, account change)
 * destroys it. After process death a new holder opens [initialUrl] only (D15).
 *
 * The WebView is built on a [MutableContextWrapper] of the application context: while shown, its base is the
 * Activity (page dialogs, `<select>` pickers); when the view leaves composition it goes back to the
 * application, so a recreated Activity is never held. It uses the default WebView profile (no data directory
 * suffix), the same cookie/Web Storage/cache store that settings "웹뷰 데이터 삭제" (`WebViewDataCleaner`) clears.
 */
@SuppressLint("StaticFieldLeak") // only the application context is held between attachments; see above
internal class WebViewHolder(private val app: Context, initialUrl: String) : ViewModel() {
    private val context = MutableContextWrapper(app)
    private var webView: WebView? = null

    var page by mutableStateOf(WebPageState(url = initialUrl))
        private set

    /** Bumped when the renderer died and the WebView was dropped, so the screen builds a new view. */
    var generation by mutableIntStateOf(0)
        private set

    private var pendingLoad: String? = initialUrl

    val externalRequests = Channel<ExternalRequest>(Channel.BUFFERED)

    /** For `AndroidView.factory`: the kept WebView, re-parented and re-based on [activity]. */
    fun attach(activity: Context): WebView {
        val view = webView ?: create().also { webView = it }
        (view.parent as? ViewGroup)?.removeView(view)
        context.baseContext = activity
        pendingLoad?.let {
            pendingLoad = null
            view.loadUrl(it)
        }
        return view
    }

    /** For `AndroidView.onRelease`: the view stays alive in this holder, holding only the application context. */
    fun detach(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
        if (view === webView) context.baseContext = app
    }

    fun load(url: String) {
        webView?.loadUrl(url)
    }

    /** Follows the screen's lifecycle: a paused page stops animations, media and geolocation work. */
    fun resumeWebView(resumed: Boolean) {
        val view = webView ?: return
        if (resumed) view.onResume() else view.onPause()
    }

    fun goBack() {
        webView?.takeIf { it.canGoBack() }?.goBack()
    }

    fun goForward() {
        webView?.takeIf { it.canGoForward() }?.goForward()
    }

    fun reload() {
        webView?.reload()
    }

    fun stop() {
        webView?.stopLoading()
        page = page.copy(loading = false)
    }

    /** "다시 시도": reload the failed page, or open its URL in the fresh view built after a renderer crash. */
    fun retry() {
        page = page.copy(failed = false)
        val view = webView
        if (view == null || view.url == null) {
            if (view == null) pendingLoad = page.url else view.loadUrl(page.url)
        } else {
            view.reload()
        }
    }

    private fun syncHistory(view: WebView) {
        page = page.copy(
            url = view.url?.takeIf { it.isNotEmpty() } ?: page.url,
            canGoBack = view.canGoBack(),
            canGoForward = view.canGoForward(),
        )
    }

    private fun create(): WebView = WebView(context).apply {
        applyWishlistDefaults()
        webViewClient = Client()
        webChromeClient = Chrome()
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            val decision = WebNavigationPolicy.decide(schemeOf(url), request.isForMainFrame, request.hasGesture(), isAboutBlank(url))
            return when (decision) {
                WebDecision.LOAD_INSIDE -> false
                WebDecision.BLOCK -> true
                WebDecision.OPEN_EXTERNAL, WebDecision.CONFIRM_EXTERNAL -> {
                    when (val target = WebExternalApps.targetOf(url)) {
                        is ExternalTarget.Launch ->
                            externalRequests.trySend(ExternalRequest(target, confirm = decision == WebDecision.CONFIRM_EXTERNAL))
                        is ExternalTarget.LoadInside -> view.post { if (view === webView) view.loadUrl(target.url) }
                        ExternalTarget.None -> Unit
                    }
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            page = page.copy(url = url ?: page.url, title = null, loading = true, failed = false)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            page = page.copy(loading = false, title = view.title)
            syncHistory(view)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            syncHistory(view)
        }

        // D13: only the main frame's network / bad-address errors cover the page; sub-resource errors are ignored.
        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) page = page.copy(failed = true)
        }

        // A renderer crash or kill must not take the app down: drop this view and offer "다시 시도".
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view === webView) {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                webView = null
                page = page.copy(loading = false, failed = true)
                generation++
            }
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            page = page.copy(progress = newProgress)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            page = page.copy(title = title)
        }
    }

    override fun onCleared() {
        externalRequests.close()
        webView?.apply {
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
    }

    companion object {
        fun factory(app: Context, url: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { WebViewHolder(app.applicationContext, url) }
        }
    }
}

private val HostText = WLType.bodyStrong.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold)
private val TitleText = WLType.label

/**
 * FWebView(원본 링크, spec §4·결정 2026-10-02): 위쪽 바(닫기 · 자물쇠(https) + 도메인 / 페이지 제목) + 2px 진행 선,
 * 웹뷰, 아래쪽 바(뒤로·앞으로 | 새로고침↔중지·공유). 뒤로(바·시스템)는 기록을 먼저 되돌리고 기록이 없으면 닫는다.
 * 탐색 판정은 [WebNavigationPolicy], `intent:`는 [IntentSanitizer]. 사용자 탭 없는 외부 앱은 FWebViewExternal 확인창(열기 먹색).
 */
@Composable
internal fun WebViewScreen(route: WebViewRoute) {
    val c = LocalWLColors.current
    val nav = LocalWLNavigator.current
    val overlay = LocalOverlayHostState.current
    val context = LocalContext.current
    val storeOwner = LocalWLEntryViewModelStoreOwner.current
    val holder = remember(storeOwner) {
        ViewModelProvider(storeOwner, WebViewHolder.factory(context, route.url))[WebViewHolder::class.java]
    }
    val page = holder.page
    val close = { nav.pop(); Unit }

    BackHandler(enabled = page.canGoBack && !overlay.isShowing) { holder.goBack() }
    LifecycleResumeEffect(holder, holder.generation) {
        // attach() runs first (AndroidView factory), so the view exists by the time the screen resumes.
        holder.resumeWebView(true)
        onPauseOrDispose { holder.resumeWebView(false) }
    }

    val externalDialog by rememberUpdatedState(externalDialogSpec())
    val currentContext by rememberUpdatedState(context)
    LaunchedEffect(holder) {
        for (request in holder.externalRequests) {
            if (request.confirm) {
                overlay.showDialog(externalDialog.copy(onConfirm = { WebExternalApps.launch(currentContext, request.target, holder::load) }))
            } else {
                WebExternalApps.launch(currentContext, request.target, holder::load)
            }
        }
    }

    var copies by remember { mutableIntStateOf(0) }
    val notice = rememberBriefNotice(copies.takeIf { it > 0 }, stringResource(R.string.webview_link_copied))
    val shareTitle = stringResource(R.string.webview_share)
    val openShare = {
        val url = page.url.takeIf(WebUrl::isWeb) ?: route.url
        overlay.showSheet(title = shareTitle) {
            WebShareSheetContent(
                onOpenBrowser = { overlay.dismiss(); WebShare.openInBrowser(context, url) },
                onCopy = { overlay.dismiss(); if (WebShare.copy(context, url)) copies++ },
                onShareOther = { overlay.dismiss(); WebShare.shareToOtherApp(context, url) },
            )
        }
        Unit
    }

    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            WLTopBar(
                background = c.background,
                leading = { WLCircleButton(WLLineIcon.Close, stringResource(R.string.webview_close), onClick = close) },
                // Same width as the close button, so the domain and title sit in the middle of the screen.
                trailing = { Spacer(Modifier.size(WishlistTokens.Space.minTouch)) },
                title = { PageTitle(page) },
            )
            ProgressLine(page)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                key(holder.generation) {
                    AndroidView(
                        factory = { holder.attach(it) },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { holder.detach(it) },
                    )
                }
                if (page.failed) {
                    Box(Modifier.fillMaxSize().background(c.background)) {
                        DetailStatusBlock(stringResource(R.string.webview_load_failed), stringResource(R.string.detail_retry), holder::retry)
                    }
                }
            }
            BottomBar(
                page = page,
                onBack = { if (page.canGoBack) holder.goBack() else close() },
                onForward = holder::goForward,
                onReloadOrStop = { if (page.showsStop) holder.stop() else holder.reload() },
                onShare = openShare,
            )
        }
        DetailNoticeLine(notice, Modifier.align(Alignment.TopCenter))
    }
}

/** FWebViewExternal: 48 상태(바깥 앱) 타일 · "외부 앱을 열까요?" · 두 줄 · 취소 / 열기(먹색, 결정 2026-10-04). */
@Composable
private fun externalDialogSpec(): WLDialogSpec {
    val dark = LocalWLDark.current
    val (surface, icon) = if (dark) {
        WishlistTokens.Status.Dark.offlineSurface to WishlistTokens.Status.Dark.offlineIcon
    } else {
        WishlistTokens.Status.Light.offlineSurface to WishlistTokens.Status.Light.offlineIcon
    }
    return WLDialogSpec(
        title = stringResource(R.string.webview_external_title),
        bullets = listOf(stringResource(R.string.webview_external_line_app), stringResource(R.string.webview_external_line_tap)),
        cancelText = stringResource(R.string.dialog_cancel),
        confirmText = stringResource(R.string.webview_external_open),
        confirmKind = WLButtonKind.Primary,
        icon = { WLIconTile(size = 48.dp, color = surface) { WLIcon(WLLineIcon.Phone, color = icon) } },
        onConfirm = {},
    )
}

/** 가운데 두 줄: 자물쇠 13(https만) + 도메인 14/700, 제목 12/500 보조색. 제목이 없으면 도메인만(D13). */
@Composable
private fun PageTitle(page: WebPageState) {
    val c = LocalWLColors.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page.secure) WLIcon(WLLineIcon.Lock, size = 13.dp)
            WLText(page.host, HostText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        page.titleLine?.let { WLText(it, TitleText, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** 위쪽 바 아래 2px: 선 색 바탕 위 글자색 진행분. 로드가 100에 이르면 사라진다(자리는 남는다). */
@Composable
private fun ProgressLine(page: WebPageState) {
    val c = LocalWLColors.current
    Box(Modifier.fillMaxWidth().height(2.dp)) {
        if (page.progressVisible) {
            Box(Modifier.fillMaxSize().background(c.line))
            Box(Modifier.fillMaxHeight().fillMaxWidth(page.progress.coerceIn(0, 100) / 100f).background(c.text))
        }
    }
}

/** 아래쪽 바 아래 여백: 보드 32(홈 표시줄 포함). 내비게이션 막대가 더 높으면 그 위 8(구현 기본값). */
@Composable
private fun bottomBarBottomPadding(): Dp =
    max(32.dp, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + WishlistTokens.Space.s8)

/** 아래쪽 바(위 8·좌우 12): 왼쪽 뒤로·앞으로, 오른쪽 새로고침(로딩 중 중지)·공유. 44 버튼, 간격 4. 앞으로는 기록이 없으면 흐린 색. */
@Composable
private fun BottomBar(page: WebPageState, onBack: () -> Unit, onForward: () -> Unit, onReloadOrStop: () -> Unit, onShare: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(LocalWLColors.current.background)
            .padding(start = 12.dp, end = 12.dp, top = WishlistTokens.Space.s8, bottom = bottomBarBottomPadding()),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BarButton(WLLineIcon.Back, stringResource(R.string.webview_back), onClick = onBack)
            BarButton(WLLineIcon.ChevronRight, stringResource(R.string.webview_forward), enabled = page.canGoForward, onClick = onForward)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (page.showsStop) {
                BarButton(WLLineIcon.Close, stringResource(R.string.webview_stop), onClick = onReloadOrStop)
            } else {
                BarButton(WLLineIcon.Reload, stringResource(R.string.webview_reload), onClick = onReloadOrStop)
            }
            BarButton(WLLineIcon.Share, stringResource(R.string.webview_share), onClick = onShare)
        }
    }
}

@Composable
private fun BarButton(icon: WLLineIcon, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalWLColors.current
    Box(
        Modifier
            .size(WishlistTokens.Space.minTouch)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { WLIcon(icon, color = if (enabled) c.text else c.underline) }
}
