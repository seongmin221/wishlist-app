import SwiftUI

/// 앱 루트: 테마 → overlay(시트·확인창·메뉴) → 탭 셸.
struct ContentView: View {
    @State private var navigator: WLNavigator
    @State private var motion: WLNavMotion

    init() {
        let navigator = WLNavigator()
        _navigator = State(initialValue: navigator)
        _motion = State(initialValue: WLNavMotion(navigator: navigator))
    }

    @State private var overlay = OverlayHostState()

    var body: some View {
        WLTheme {
            OverlayHost(state: overlay) {
                WLNavHost(navigator: navigator, motion: motion) { route in AppRoute(route: route) }
            }
            .environment(\.wlNavigatorStorage, navigator)
            .environment(\.wlNavMotionStorage, motion)
        }
    }
}

private struct AppRoute: View {
    let route: WLRoute

    var body: some View {
        if let tab = route.rootTab {
            #if DEBUG
            DemoTabRoot(tab: tab)
            #else
            PlainTabRoot(tab: tab)
            #endif
        }
        #if DEBUG
        if let destination = route.destination.base as? DemoDestination {
            DemoDetailScreen(destination: destination)
        }
        #endif
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
