import SwiftUI

/// 앱 루트: 테마 → overlay(시트·확인창·메뉴) → 탭 셸.
struct ContentView: View {
    @State private var overlay = OverlayHostState()

    var body: some View {
        WLTheme {
            OverlayHost(state: overlay) {
                WLNavHost { route in AppRoute(route: route) }
            }
        }
    }
}

private struct AppRoute: View {
    let route: WLRoute

    var body: some View {
        switch route {
        case .tabRoot(let tab):
            #if DEBUG
            DemoTabRoot(tab: tab)
            #else
            PlainTabRoot(tab: tab)
            #endif
        case .demoDetail(let id, let hasPhoto):
            // 데모 상세는 debug 빌드의 데모 첫 화면에서만 열린다.
            DemoDetailScreen(id: id, hasPhoto: hasPhoto)
        }
    }
}

#if DEBUG
private struct DemoTabRoot: View {
    let tab: WLTab

    var body: some View {
        switch tab {
        case .home: DemoHomeScreen()
        case .category: DemoCategoryScreen()
        case .purpose: DemoPurposeScreen()
        }
    }
}
#endif

/// release 빌드의 탭 첫 화면(C1에는 기능 화면이 없다).
private struct PlainTabRoot: View {
    let tab: WLTab

    var body: some View {
        WLText(tab.label, .display28)
            .accessibilityAddTraits(.isHeader)
            .padding(.horizontal, WishlistTokens.Space.screenMargin)
            .padding(.vertical, WishlistTokens.Space.s24)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}
