package app.wishlist.android.feature.purpose

import app.wishlist.android.navigation.WLPushStyle
import app.wishlist.android.navigation.WLRoute
import java.util.Base64

/** PUR-03 identity only: restored names and styling always come from the account repository. */
internal data class PurposeDetailRoute(val purposeId: String) : WLRoute {
    override val showsTabBar = true
    override val pushStyle = WLPushStyle.Slide
    override val accountScoped = true
    companion object {
        fun encode(route: PurposeDetailRoute) = listOf("purpose", Base64.getUrlEncoder().withoutPadding().encodeToString(route.purposeId.toByteArray(Charsets.UTF_8)))
        fun decode(tokens: List<String>): PurposeDetailRoute? {
            if (tokens.size != 2 || tokens[0] != "purpose") return null
            return try {
                val id = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Base64.getUrlDecoder().decode(tokens[1]))).toString()
                id.takeIf { it.isNotBlank() }?.let(::PurposeDetailRoute)
            } catch (_: IllegalArgumentException) { null }
              catch (_: java.nio.charset.CharacterCodingException) { null }
        }
    }
}

internal fun expandedPurposeId(current: String?, ids: List<String>): String? = current?.takeIf { it in ids } ?: ids.firstOrNull()

internal object PurposeChanges {
    private val mutable = kotlinx.coroutines.flow.MutableStateFlow(0)
    val revision: kotlinx.coroutines.flow.StateFlow<Int> = mutable
    fun changed() { mutable.value++ }
}
