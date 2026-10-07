import SwiftUI

/// 앱 루트: 테마 → overlay(시트·확인창·메뉴) → 탭 셸, 그 위에 첫 실행 로그인 안내(전체 화면, 탭 바 없음).
/// 안내는 복원이 끝난 뒤 로그인 전이고 아직 보지 않았을 때만 뜨고(`showFirstRunLogin`), 로그인하거나 "나중에 하기"를
/// 누르면 사라진다(나타남 모션 없음, 사라짐 opacity 260 `accelerate`). 뜨는 동안 아래 탭 셸은 입력·접근성에서 가려진다.
struct ContentView: View {
    @State private var navigator: WLNavigator
    @State private var motion: WLNavMotion
    @State private var overlay = OverlayHostState()

    @Environment(AccountPresenterOwner.self) private var account
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init() {
        let navigator = WLNavigator()
        _navigator = State(initialValue: navigator)
        _motion = State(initialValue: WLNavMotion(navigator: navigator))
    }

    var body: some View {
        let firstRun = account.state.showFirstRunLogin
        WLTheme {
            ZStack {
                OverlayHost(state: overlay) {
                    WLNavHost(navigator: navigator, motion: motion) { route in AppRoute(route: route) }
                }
                .environment(\.wlNavigatorStorage, navigator)
                .environment(\.wlNavMotionStorage, motion)
                .wlAccessibilityCovered(firstRun)
                // The fade is scoped to this layer: the same update also changes the tab shell (home
                // logged out → in, accessibility cover), and those changes must not pick up the 260ms fade.
                ZStack {
                    if firstRun {
                        LoginScreen(mode: .firstRun)
                            .transition(.asymmetric(insertion: .identity, removal: .opacity))
                    }
                }
                .animation(reduceMotion ? nil : WishlistTokens.Curve.accelerate.animation(ms: WishlistTokens.Motion.sheetClose), value: firstRun)
                .zIndex(1)
            }
        }
    }
}

/// 앱의 단일 route renderer: 실제 기능 화면(debug·release 공통)을 먼저 고르고, 카테고리·목적 탭은 debug 데모(release는 이름만).
private struct AppRoute: View {
    let route: WLRoute

    var body: some View {
        if let tab = route.rootTab {
            switch tab {
            case .home:
                HomeScreen()
            case .category, .purpose:
                #if DEBUG
                DemoTabRoot(tab: tab)
                #else
                PlainTabRoot(tab: tab)
                #endif
            }
        }
        if let destination = route.destination.base as? AppDestination {
            switch destination {
            case .settings: SettingsScreen()
            case .login: LoginScreen(mode: .pushed)
            }
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
        case .home: EmptyView() // unreachable: the home tab is the real HomeScreen (C3)
        case .category: DemoCategoryScreen()
        case .purpose: DemoPurposeScreen()
        }
    }
}
#endif

/// release 빌드의 카테고리·목적 탭 첫 화면(아직 기능 화면이 없다).
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
