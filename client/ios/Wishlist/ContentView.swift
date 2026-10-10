import Shared
import SwiftUI

/// 앱 루트: 테마 → overlay(시트·확인창·메뉴) → 탭 셸, 그 위에 첫 실행 로그인 안내(전체 화면, 탭 바 없음).
/// 안내는 복원이 끝난 뒤 로그인 전이고 아직 보지 않았을 때만 뜨고(`showFirstRunLogin`), 로그인하거나 "나중에 하기"를
/// 누르면 사라진다(나타남 모션 없음, 사라짐 opacity 260 `accelerate`). 뜨는 동안 아래 탭 셸은 입력·접근성에서 가려진다.
///
/// C4: 스택 칸별 Presenter owner(`WLEntryOwners`)를 여기서 만든다(runtime은 `WishlistApp`이 `\.wlRuntime`으로 넣는다).
/// 계정을 떠나면(`shouldDropAccountScoped`) 모든 탭의 계정 범위 화면을 전환 없이 닫고 그 owner를 바로 닫는다. 그 밖에
/// 스택에서 빠진 칸의 owner는 전환이 끝난 뒤(`drainRemoved()`) 닫는다. 비교는 메모리 값이다(처음 값은 기준값일 뿐 떠남이 아니다).
struct ContentView: View {
    @Environment(\.wlRuntime) private var runtime

    var body: some View {
        guard let runtime else { preconditionFailure("Inject wlRuntime at the app root (WishlistApp).") }
        return AppShell(runtime: runtime)
    }

    /// 계정을 떠났는지: 로그인한 계정이 있었고 그 값이 바뀌었을 때(로그아웃 또는 다른 계정). 로그인(nil → 계정)은 떠남이 아니다.
    static func shouldDropAccountScoped(previous: String?, next: String?) -> Bool {
        previous != nil && previous != next
    }
}

private struct AppShell: View {
    @State private var navigator: WLNavigator
    @State private var motion: WLNavMotion
    @State private var entryOwners: WLEntryOwners
    @State private var overlay = OverlayHostState()

    @Environment(AccountPresenterOwner.self) private var account
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(runtime: SharedRuntime) {
        let navigator = WLNavigator()
        _navigator = State(initialValue: navigator)
        _motion = State(initialValue: WLNavMotion(navigator: navigator))
        // Cheap when thrown away on a re-init: owners create Presenters only when a screen asks.
        _entryOwners = State(initialValue: WLEntryOwners(runtime: runtime))
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
                .environment(\.wlEntryOwnersStorage, entryOwners)
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
        .onChange(of: account.state.account?.accountId) { previous, next in
            if ContentView.shouldDropAccountScoped(previous: previous, next: next) {
                entryOwners.close(motion.dropAccountScoped())
            }
        }
        // Removed entries are still drawn until their motion ends: close their owners once it did.
        .onChange(of: !navigator.isTransitioning && navigator.hasRemoved, initial: true) { _, ready in
            if ready { entryOwners.close(navigator.drainRemoved()) }
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
            case .item(let itemId): ItemDetailScreen(itemId: itemId)
            case .local(let submissionId): LocalSubmissionScreen(submissionId: submissionId)
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
