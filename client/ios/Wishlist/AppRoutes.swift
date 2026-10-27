import Foundation

/// C3 production destinations (debug and release). The debug demo has its own `DemoDestination`.
enum AppDestination: Hashable {
    /// FSettings*: the home's top-right button, horizontal slide, no tab bar.
    case settings
    /// FLogin opened from the home login card or settings "로그인". The first-run offer is not a
    /// route but a layer over the app root (`ContentView`).
    case login
    /// C4 item detail (server item id): horizontal slide, no tab bar, closed when the account is left.
    /// C5 PR B: opened from a category list card (`viaPhoto`), the card photo grows into the detail photo.
    case item(String, viaPhoto: Bool = false)
    /// C4 local (not yet sent) link detail (client submission id): same policy as `item`.
    case local(String)
    /// C4 PR B original-link web view (FWebView): same policy as `item`. Only a validated `WebPageURL`.
    case web(WebPageURL)
    /// C5 one category's item list (FCategoryList*): tab bar stays, horizontal slide (both screens show the
    /// tab bar, so it does not move), closed when the account is left.
    case categoryList(CategoryListTarget)
    case purpose(String)
    /// C7 FHomeReviewFlow / FHomeFillFlow from a home to-do card: horizontal slide, no tab bar, closed when
    /// the account is left.
    case homeFlow(HomeFlowTarget)

    var accountScoped: Bool {
        switch self {
        case .settings, .login: false
        case .item, .local, .web, .categoryList, .purpose, .homeFlow: true
        }
    }

    var route: WLRoute {
        WLRoute(destination: AnyHashable(self), showsTabBar: showsTabBar, pushStyle: pushStyle, accountScoped: accountScoped)
    }

    var pushStyle: WLPushStyle {
        if case .item(_, true) = self { return .photo }
        return .slide
    }

    var showsTabBar: Bool {
        if case .categoryList = self { return true }
        if case .purpose = self { return true }
        return false
    }
}
