import SwiftUI
import WebKit

/// FWebView(원본 링크, spec §4·결정 2026-10-02, Android `WebViewScreen`): 위쪽 바(닫기 · 자물쇠(https) + 도메인 / 페이지
/// 제목) + 2px 진행 선, 웹뷰, 아래쪽 바(뒤로·앞으로 | 새로고침↔중지·공유). 뒤로(바·VoiceOver escape)는 기록을 먼저
/// 되돌리고 기록이 없으면 닫는다. 왼쪽 가장자리 끌기는 기록이 있으면 WKWebView의 뒤로(`allowsBackForwardNavigationGestures`),
/// 없을 때만 셸 pop이다(`WLNavMotion.setEdgeBackBlocked`). 탐색 판정·외부 앱은 `WebViewModel`, 사용자 탭 없는 외부 앱은
/// FWebViewExternal 확인창(열기 먹색).
///
/// owner(`WebViewModel`)는 칸 id로 처음 나타날 때 한 번만 받는다(은퇴한 id는 요청마다 닫힌 임시 owner를 만든다).
struct WebViewScreen: View {
    let page: WebPageURL

    @Environment(\.wlEntryOwners) private var owners
    @Environment(\.wlEntryID) private var entryID
    @Environment(\.wlColors) private var c
    @State private var model: WebViewModel?

    var body: some View {
        ZStack {
            if let model, let entryID {
                WebViewContent(model: model, entryID: entryID, opened: page.url)
            } else {
                c.background.ignoresSafeArea()
            }
        }
        .onAppear {
            if model == nil, let entryID { model = owners.web(entryID, url: page.url) }
        }
    }
}

/// 도메인 14/700, 제목 12/500 보조색.
private let hostText = WLTextStyle.bodyBold
private let titleText = WLTextStyle.label

/// 아래쪽 바 아래 여백: 보드 32(홈 표시줄 포함). 홈 표시줄이 더 높으면 그 위 8(Android와 같은 구현 기본값).
private func bottomBarBottomPadding(safeBottom: CGFloat) -> CGFloat {
    max(32, safeBottom + WishlistTokens.Space.s8)
}

private struct WebViewContent: View {
    let model: WebViewModel
    let entryID: Int
    let opened: URL

    @Environment(\.wlNavigator) private var nav
    @Environment(\.wlNavMotion) private var motion
    @Environment(\.overlayHostState) private var overlay
    @Environment(\.wlColors) private var c
    @State private var notice: BriefNotice?
    @State private var copies = 0

    var body: some View {
        let page = model.page
        GeometryReader { geo in
            ZStack(alignment: .top) {
                // 밀기 동안 아래 화면이 비치지 않게 자기 바탕을 칠한다.
                c.background.ignoresSafeArea()
                VStack(spacing: 0) {
                    WLTopBar(background: c.background) {
                        WLCircleButton(.close, label: String(localized: "webview.close")) { close() }
                    } center: {
                        WebPageTitle(page: page)
                    } trailing: {
                        // 닫기와 같은 폭: 도메인·제목이 화면 가운데에 온다.
                        Color.clear
                            .frame(width: WishlistTokens.Space.minTouch, height: WishlistTokens.Space.minTouch)
                            .accessibilityHidden(true)
                    }
                    WebProgressLine(page: page)
                    ZStack {
                        WebViewHost(webView: model.webView, onTouch: { [model] in model.noteTouch() })
                        if page.failed {
                            c.background
                            DetailStatusBlock(
                                message: String(localized: "webview.load.failed"),
                                buttonText: String(localized: "detail.retry")
                            ) { model.retry() }
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    WebBottomBar(
                        page: page,
                        bottomPadding: bottomBarBottomPadding(safeBottom: geo.safeAreaInsets.bottom),
                        onBack: back,
                        onForward: { model.goForward() },
                        onReloadOrStop: { if page.showsStop { model.stop() } else { model.reload() } },
                        onShare: openShare
                    )
                }
                .ignoresSafeArea(.container, edges: .bottom)
                DetailNoticeLine(notice: notice)
                    .padding(.top, DetailLayout.noticeTop)
            }
        }
        // The page scrolls its focused field above the keyboard itself; the bars stay where they are.
        .ignoresSafeArea(.keyboard)
        .accessibilityAction(.escape) { back() }
        .onAppear {
            installPrompt()
            model.start()
            // onChange(initial:) runs only once; a view that disappeared and came back blocks again here.
            motion.setEdgeBackBlocked(entryID, model.page.canGoBack)
        }
        .onChange(of: page.canGoBack, initial: true) { _, canGoBack in
            motion.setEdgeBackBlocked(entryID, canGoBack)
        }
        .onDisappear { motion.setEdgeBackBlocked(entryID, false) }
    }

    private func close() {
        nav.pop()
    }

    private func back() {
        if model.page.canGoBack { model.goBack() } else { close() }
    }

    private func openShare() {
        guard let overlay else { return }
        let url = model.page.shareURL(fallback: opened)
        overlay.showSheet(title: String(localized: "webview.share")) {
            WebShareSheetContent(
                onOpenBrowser: {
                    overlay.dismiss()
                    WebShare.openInBrowser(url)
                },
                onCopy: {
                    overlay.dismiss()
                    WebShare.copy(url)
                    copies += 1
                    notice = BriefNotice(serial: copies, text: String(localized: "webview.link.copied"))
                },
                onShareOther: {
                    overlay.dismiss()
                    WebShare.shareToOtherApp(url)
                }
            )
        }
    }

    /// FWebViewExternal: 48 바깥 앱 상태색 타일 · "외부 앱을 열까요?" · 두 줄 · 취소 / 열기(먹색, 결정 2026-10-04).
    private func installPrompt() {
        let overlay = overlay
        // Weak: the model holds these closures.
        model.presentPrompt = { [weak model] prompt in
            guard let overlay else { return false }
            var confirmed = false
            let spec = WLDialogSpec(
                title: String(localized: "webview.external.title"),
                bullets: [String(localized: "webview.external.line.app"), String(localized: "webview.external.line.tap")],
                cancelText: String(localized: "dialog.cancel"),
                confirmText: String(localized: "webview.external.open"),
                confirmKind: .primary,
                icon: AnyView(ExternalAppTile()),
                onDismissed: {
                    model?.dismissPrompt = nil
                    prompt.closed(confirmed)
                },
                onDropped: {
                    model?.dismissPrompt = nil
                    prompt.notShown()
                },
                onConfirm: {
                    confirmed = true
                    prompt.open()
                }
            )
            guard overlay.showDialog(spec) else { return false }
            model?.dismissPrompt = { [weak overlay] in overlay?.dismissDialog(spec) }
            return true
        }
    }
}

/// 확인창 머리 타일: 48 모서리 14, 오프라인(바깥) 상태색 면 + phone 아이콘.
private struct ExternalAppTile: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let dark = scheme == .dark
        WLIconTile(
            size: 48,
            color: dark ? WishlistTokens.Status.Dark.offlineSurface : WishlistTokens.Status.Light.offlineSurface
        ) {
            WLIcon(.phone, color: dark ? WishlistTokens.Status.Dark.offlineIcon : WishlistTokens.Status.Light.offlineIcon)
        }
        .accessibilityHidden(true)
    }
}

/// 가운데 두 줄: 자물쇠 13(https만) + 도메인 14/700, 제목 12/500 보조색. 제목이 없으면 도메인만(D13).
private struct WebPageTitle: View {
    let page: WebPageState

    @Environment(\.wlColors) private var c

    var body: some View {
        VStack(spacing: 2) {
            HStack(spacing: 4) {
                if page.secure { WLIcon(.lock, size: 13) }
                WLText(page.host, hostText, color: c.text, maxLines: 1)
            }
            if let title = page.titleLine {
                WLText(title, titleText, color: c.textSecondary, alignment: .center, maxLines: 1)
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

/// 위쪽 바 아래 2px: 선 색 바탕 위 글자색 진행분. 로드가 끝나면 사라진다(자리는 남는다).
private struct WebProgressLine: View {
    let page: WebPageState

    @Environment(\.wlColors) private var c

    var body: some View {
        GeometryReader { geo in
            if page.progressVisible {
                ZStack(alignment: .leading) {
                    c.line
                    c.text.frame(width: geo.size.width * min(1, max(0, page.progress)))
                }
            }
        }
        .frame(height: 2)
        .accessibilityHidden(true)
    }
}

/// 아래쪽 바(위 8·좌우 12): 왼쪽 뒤로·앞으로, 오른쪽 새로고침(로딩 중 중지)·공유. 44 버튼, 간격 4. 앞으로는 기록이 없으면 흐린 색.
private struct WebBottomBar: View {
    let page: WebPageState
    let bottomPadding: CGFloat
    let onBack: () -> Void
    let onForward: () -> Void
    let onReloadOrStop: () -> Void
    let onShare: () -> Void

    @Environment(\.wlColors) private var c

    var body: some View {
        HStack(spacing: 0) {
            HStack(spacing: 4) {
                WebBarButton(icon: .back, label: String(localized: "webview.back"), action: onBack)
                WebBarButton(icon: .chevronRight, label: String(localized: "webview.forward"), enabled: page.canGoForward, action: onForward)
            }
            Spacer(minLength: 0)
            HStack(spacing: 4) {
                if page.showsStop {
                    WebBarButton(icon: .close, label: String(localized: "webview.stop"), action: onReloadOrStop)
                } else {
                    WebBarButton(icon: .reload, label: String(localized: "webview.reload"), action: onReloadOrStop)
                }
                WebBarButton(icon: .share, label: String(localized: "webview.share"), action: onShare)
            }
        }
        .padding(.horizontal, WishlistTokens.Space.s12)
        .padding(.top, WishlistTokens.Space.s8)
        .padding(.bottom, bottomPadding)
        .background(c.background)
    }
}

private struct WebBarButton: View {
    let icon: WLLineIcon
    let label: String
    var enabled = true
    let action: () -> Void

    @Environment(\.wlColors) private var c

    var body: some View {
        Button(action: action) {
            WLIcon(icon, color: enabled ? c.text : c.underline)
                .frame(width: WishlistTokens.Space.minTouch, height: WishlistTokens.Space.minTouch)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(label)
    }
}

/// The owner's `WKWebView` in a container: a recreated representable re-parents it, and dismantling only takes it
/// out of its own container (another container may already hold it).
/// A `WebTouchStampRecognizer` on the container sees every touch that lands in the web view (D16 user tap).
private struct WebViewHost: UIViewRepresentable {
    let webView: WKWebView
    let onTouch: () -> Void

    func makeUIView(context: Context) -> UIView {
        let container = UIView()
        container.clipsToBounds = true
        container.addGestureRecognizer(WebTouchStampRecognizer(onTouch: onTouch))
        attach(to: container)
        return container
    }

    func updateUIView(_ container: UIView, context: Context) {
        if webView.superview !== container { attach(to: container) }
    }

    static func dismantleUIView(_ container: UIView, coordinator: ()) {
        for view in container.subviews { view.removeFromSuperview() }
    }

    private func attach(to container: UIView) {
        webView.removeFromSuperview()
        webView.frame = container.bounds
        webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        container.addSubview(webView)
    }
}
