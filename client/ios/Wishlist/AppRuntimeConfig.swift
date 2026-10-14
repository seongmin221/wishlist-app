import Shared

/// Build-configuration choice of every API backend for the shared runtime. The build mode and the
/// complete 37-API map are passed explicitly; the shared runtime never infers a backend from the
/// mode. C2 has no remote connection, so `remote` is nil and no API is REMOTE.
enum AppRuntimeConfig {
    static func bindings() -> RepositoryBindings {
        #if DEBUG
        // C2 Fake wire APIs only; every other API is explicitly unavailable.
        var backends = allBackends(.unavailable)
        backends[.item01] = .fake
        backends[.item03] = .fake
        // C5: categories (CAT-01~04) and the category item list (ITEM-02).
        for api in [ApiId.item02, .cat01, .cat02, .cat03, .cat04] { backends[api] = .fake }
        return RepositoryBindings(buildMode: .debug, backends: backends)
        #else
        // C2 release: no auth provider or server connection yet, so all 37 APIs are unavailable.
        return RepositoryBindings(buildMode: .theRelease, backends: allBackends(.unavailable))
        #endif
    }

    static func allBackends(_ backend: Backend) -> [ApiId: Backend] {
        Dictionary(uniqueKeysWithValues: ApiId.allCases.map { ($0, backend) })
    }
}
