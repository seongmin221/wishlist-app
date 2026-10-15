package app.wishlist.shared.di

import app.wishlist.shared.core.ApiId
import app.wishlist.shared.core.PlatformTokenSource

enum class ClientBuildMode { DEBUG, RELEASE }

enum class Backend { FAKE, REMOTE, UNAVAILABLE }

/**
 * Explicit backend per server API. The build mode never implies a backend: every [ApiId] must be
 * listed. RELEASE rejects any FAKE, and FAKE/REMOTE are accepted only for APIs that have that
 * implementation; anything else must be chosen explicitly as UNAVAILABLE. Violations throw
 * [IllegalArgumentException] at construction (a configuration error, never a runtime fallback).
 */
data class RepositoryBindings(
    val buildMode: ClientBuildMode,
    val backends: Map<ApiId, Backend>,
) {
    init {
        val missing = ApiId.entries.filter { it !in backends }
        require(missing.isEmpty()) { "Every API needs an explicit backend; missing ${missing.map { it.wireId }}" }
        if (buildMode == ClientBuildMode.RELEASE) {
            val fakes = backends.filterValues { it == Backend.FAKE }.keys
            require(fakes.isEmpty()) { "RELEASE cannot bind FAKE: ${fakes.map { it.wireId }}" }
        }
        val unimplemented = backends.filter { (api, backend) ->
            when (backend) {
                Backend.FAKE -> api !in FAKE_IMPLEMENTED_APIS
                Backend.REMOTE -> api !in REMOTE_IMPLEMENTED_APIS
                Backend.UNAVAILABLE -> false
            }
        }
        require(unimplemented.isEmpty()) {
            "No implementation for ${unimplemented.map { "${it.key.wireId}=${it.value}" }}; bind UNAVAILABLE"
        }
    }

    fun backendOf(apiId: ApiId): Backend = backends.getValue(apiId)
}

/** Remote server connection. Required if and only if at least one binding is REMOTE. */
class RemoteConfig(val baseUrl: String, val tokenSource: PlatformTokenSource) {
    init {
        require(baseUrl.isNotBlank()) { "Remote base URL must not be blank" }
    }
}

/** Fake wire APIs: C2 ITEM-01/03, C5 CAT-01~04 and ITEM-02 (category scope). The seed catalog is a separate debug facade. */
internal val FAKE_IMPLEMENTED_APIS: Set<ApiId> = setOf(
    ApiId.ITEM_01, ApiId.ITEM_03, ApiId.ITEM_02, ApiId.CAT_01, ApiId.CAT_02, ApiId.CAT_03, ApiId.CAT_04,
)

/** Remote APIs: B1 ITEM-01/03 (C2), B2 CAT-01~04 and B4 ITEM-02 category scope (C5). */
internal val REMOTE_IMPLEMENTED_APIS: Set<ApiId> = FAKE_IMPLEMENTED_APIS

internal val RepositoryBindings.usesRemote: Boolean get() = backends.containsValue(Backend.REMOTE)
