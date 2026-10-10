import WebKit

/// The one website data store of the app (spec §4 저장소): the original-link web view (`WebViewModel`) keeps
/// cookies (store sign-ins) and caches here, and settings "웹뷰 데이터 삭제" clears exactly this store.
@MainActor
enum WishlistWebStore {
    static var dataStore: WKWebsiteDataStore { .default() }
}

/// 설정 "웹뷰 데이터 삭제"(C3-D5 h): `WishlistWebStore.dataStore` as a whole — cookies (store sign-ins), caches,
/// local storage and the rest of `allWebsiteDataTypes()`. `completion` runs on the main thread once WebKit finished.
@MainActor
enum WebViewDataCleaner {
    static func clear(completion: @escaping @MainActor () -> Void) {
        WishlistWebStore.dataStore.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast) {
            Task { @MainActor in completion() }
        }
    }
}
