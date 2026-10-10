package app.wishlist.android.feature.web

import android.content.Intent
import java.net.URISyntaxException
import java.net.URLDecoder

/** Result of handling an `intent:` navigation (spec §4 Android `intent://`). */
sealed interface SanitizedIntent<out T> {
    /** Launch [intent]: component and selector cleared, `CATEGORY_BROWSABLE` added. */
    data class External<T>(val intent: T) : SanitizedIntent<T>

    /** Load this `http`/`https` `browser_fallback_url` inside the web view. */
    data class Fallback(val url: String) : SanitizedIntent<Nothing>

    data object Drop : SanitizedIntent<Nothing>
}

/** The parts of a parsed intent the sanitizer reads and hardens. Android: a real [Intent]; tests: [ParsedIntentUri]. */
internal interface IntentTarget {
    val dataScheme: String?
    val fallbackUrl: String?
    fun clearComponent()
    fun clearSelector()
    fun addBrowsableCategory()
}

/**
 * Stops intent-scheme attacks: a page must not start an arbitrary in-device component (explicit `component=`
 * or one hidden in a `SEL;` selector). Only a hardened, BROWSABLE intent leaves the web view; one pointing at
 * `http`/`https` data or that nothing resolves falls back to `browser_fallback_url` (web URLs only) or is dropped.
 */
internal object IntentSanitizer {
    private const val FALLBACK_EXTRA = "browser_fallback_url"

    /** Android adapter: `Intent.parseUri(URI_INTENT_SCHEME)` then the pure [sanitize] core. Not JVM-testable. */
    fun sanitize(uri: String, resolvable: (Intent) -> Boolean): SanitizedIntent<Intent> {
        // Only the well-formed `#Intent;…;end` form; the legacy format and plain URIs never reach parseUri.
        val parsed = ParsedIntentUri.parse(uri) ?: return SanitizedIntent.Drop
        val intent = try {
            Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
        } catch (_: URISyntaxException) {
            return fallbackOrDrop(parsed.fallbackUrl)
        }
        return when (val result = sanitize(AndroidIntentTarget(intent), { resolvable(it.intent) })) {
            is SanitizedIntent.External -> SanitizedIntent.External(result.intent.intent)
            is SanitizedIntent.Fallback -> result
            SanitizedIntent.Drop -> SanitizedIntent.Drop
        }
    }

    /** Pure core: hardens [target] in place before asking [resolvable]. */
    fun <T : IntentTarget> sanitize(target: T?, resolvable: (T) -> Boolean): SanitizedIntent<T> {
        if (target == null) return SanitizedIntent.Drop
        val scheme = target.dataScheme?.lowercase()
        if (scheme == "http" || scheme == "https") return fallbackOrDrop(target.fallbackUrl)
        target.clearComponent()
        target.clearSelector()
        target.addBrowsableCategory()
        return if (resolvable(target)) SanitizedIntent.External(target) else fallbackOrDrop(target.fallbackUrl)
    }

    private fun fallbackOrDrop(url: String?): SanitizedIntent<Nothing> =
        if (url != null && WebUrl.isWeb(url)) SanitizedIntent.Fallback(url) else SanitizedIntent.Drop

    private class AndroidIntentTarget(val intent: Intent) : IntentTarget {
        override val dataScheme: String? get() = intent.data?.scheme
        override val fallbackUrl: String? get() = intent.getStringExtra(FALLBACK_EXTRA)
        override fun clearComponent() { intent.component = null }
        override fun clearSelector() { intent.selector = null }
        override fun addBrowsableCategory() { intent.addCategory(Intent.CATEGORY_BROWSABLE) }
    }
}

/**
 * Pure reading of `intent:<data>#Intent;key=value;…;end` (the `Intent.toUri(URI_INTENT_SCHEME)` form). Fields
 * after `SEL;` belong to the selector. Values are percent-decoded like `Uri.decode` (`+` stays `+`).
 * [parse] returns null for anything that is not that form.
 */
internal class ParsedIntentUri private constructor(
    override val dataScheme: String?,
    val packageName: String?,
    component: String?,
    hasSelector: Boolean,
    override val fallbackUrl: String?,
) : IntentTarget {
    var component: String? = component
        private set
    var hasSelector: Boolean = hasSelector
        private set
    private val mutableCategories = mutableSetOf<String>()
    val categories: Set<String> get() = mutableCategories

    override fun clearComponent() { component = null }
    override fun clearSelector() { hasSelector = false }
    override fun addBrowsableCategory() { mutableCategories += CATEGORY_BROWSABLE }

    companion object {
        /** Same value as `Intent.CATEGORY_BROWSABLE`. */
        const val CATEGORY_BROWSABLE = "android.intent.category.BROWSABLE"
        private const val PREFIX = "intent:"
        private const val MARKER = "#Intent;"

        fun parse(uri: String): ParsedIntentUri? {
            if (!uri.startsWith(PREFIX, ignoreCase = true)) return null
            val hash = uri.lastIndexOf('#')
            if (hash < 0 || !uri.startsWith(MARKER, hash)) return null
            val fields = uri.substring(hash + MARKER.length).split(';')
            val end = fields.indexOf("end")
            if (end < 0) return null

            var scheme: String? = null
            var pkg: String? = null
            var component: String? = null
            var fallback: String? = null
            var inSelector = false
            for (field in fields.subList(0, end)) {
                if (field == "SEL") { inSelector = true; continue }
                if (inSelector) continue // selector fields are discarded with the selector
                val eq = field.indexOf('=')
                if (eq < 0) continue
                val key = field.substring(0, eq)
                val value = decode(field.substring(eq + 1))
                when (key) {
                    "scheme" -> scheme = value
                    "package" -> pkg = value
                    "component" -> component = value
                    "S.$FALLBACK_KEY" -> fallback = value
                }
            }
            val data = uri.substring(PREFIX.length, hash)
            val dataScheme = scheme ?: data.substringBefore(':', "").takeIf { ':' in data && it.isNotEmpty() && '/' !in it }
            return ParsedIntentUri(dataScheme, pkg, component, inSelector, fallback)
        }

        private const val FALLBACK_KEY = "browser_fallback_url"

        private fun decode(value: String): String? = try {
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") // Charset overload is API 33+
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
