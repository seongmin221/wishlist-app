import Foundation

/// C3 production destinations (debug and release). The debug demo has its own `DemoDestination`.
enum AppDestination: Hashable {
    /// FSettings*: the home's top-right button, horizontal slide, no tab bar.
    case settings
    /// FLogin opened from the home login card or settings "로그인". The first-run offer is not a
    /// route but a layer over the app root (`ContentView`).
    case login

    var route: WLRoute { WLRoute(destination: AnyHashable(self), showsTabBar: false, pushStyle: .slide) }
}
