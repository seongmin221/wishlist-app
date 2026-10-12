import Foundation

/// 하단 탭 3개(디자인 결정 2026-10-01). 순서가 탭 바 칸 순서다(Android `WLTab`과 같다).
enum WLTab: String, CaseIterable, Identifiable {
    case home, category, purpose

    var id: String { rawValue }

    var label: String {
        switch self {
        case .home: String(localized: "wl.tab.home")
        case .category: String(localized: "wl.tab.category")
        case .purpose: String(localized: "wl.tab.purpose")
        }
    }
}

/// 기능이 목적지와 전환 정책을 넘긴다. 라우터는 목적지의 구체적인 타입을 알지 않는다.
struct WLRoute: Hashable {
    let destination: AnyHashable
    let showsTabBar: Bool
    let pushStyle: WLPushStyle
    let rootTab: WLTab?
    /// 로그인한 계정의 데이터를 보이는 화면. 계정을 떠나면(로그아웃·다른 계정) `WLNavigator.dropAccountScoped()`가 닫는다.
    let accountScoped: Bool

    init(destination: AnyHashable, showsTabBar: Bool = false, pushStyle: WLPushStyle, accountScoped: Bool = false) {
        self.destination = destination
        self.showsTabBar = showsTabBar
        self.pushStyle = pushStyle
        self.rootTab = nil
        self.accountScoped = accountScoped
    }

    private init(tab: WLTab) {
        destination = AnyHashable(tab)
        showsTabBar = true
        pushStyle = .slide
        rootTab = tab
        accountScoped = false
    }

    static func tabRoot(_ tab: WLTab) -> WLRoute { WLRoute(tab: tab) }
    var isTabRoot: Bool { rootTab != nil }
}

/// 화면 이동 방식: 사진이 커지는 이동, 사진이 없는 가로 밀기.
enum WLPushStyle: Hashable { case photo, slide }
