import WebKit

/// 설정 "웹뷰 데이터 삭제"(C3-D5 h): the default `WKWebsiteDataStore` as a whole — cookies (store
/// sign-ins), caches, local storage and the rest of `allWebsiteDataTypes()`. `completion` runs on
/// the main thread once WebKit finished.
@MainActor
enum WebViewDataCleaner {
    static func clear(completion: @escaping @MainActor () -> Void) {
        WKWebsiteDataStore.default().removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast) {
            Task { @MainActor in completion() }
        }
    }
}
