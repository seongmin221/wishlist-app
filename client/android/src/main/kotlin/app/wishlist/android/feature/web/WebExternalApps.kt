package app.wishlist.android.feature.web

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.FileUriExposedException

/** Where an external-scheme navigation goes (spec §4 탐색 규칙), worked out before any confirmation. */
internal sealed interface ExternalTarget {
    /** Start [intent] (already hardened); if no app takes it, load [fallbackUrl] (http/https only) inside. */
    data class Launch(val intent: Intent, val fallbackUrl: String?) : ExternalTarget

    /** `intent:` whose data is web, or a malformed one with a web `browser_fallback_url`: stays inside. */
    data class LoadInside(val url: String) : ExternalTarget

    data object None : ExternalTarget
}

internal object WebExternalApps {
    private const val FALLBACK_EXTRA = "browser_fallback_url"

    /**
     * `intent:` goes through [IntentSanitizer] (component and selector cleared, BROWSABLE added). Every other
     * external scheme (`tel:`, `mailto:`, `market:`, `android-app:`, payment apps) is a plain BROWSABLE `ACTION_VIEW`
     * of the URI and is never parsed with `Intent.parseUri` (an `android-app:` URI can name a component).
     *
     * Resolvability is not asked up front: from API 30 package visibility hides most apps from
     * `resolveActivity` without a `<queries>` entry per scheme, so "no app" is learned from `startActivity`
     * ([launch]), which then falls back to `browser_fallback_url`.
     */
    @Suppress("UseKtx") // Uri.parse: core-ktx is only a transitive dependency of this module.
    fun targetOf(url: String): ExternalTarget {
        if (schemeOf(url).equals("intent", ignoreCase = true)) {
            return when (val r = IntentSanitizer.sanitize(url) { true }) {
                is SanitizedIntent.External -> ExternalTarget.Launch(
                    r.intent,
                    r.intent.getStringExtra(FALLBACK_EXTRA)?.takeIf(WebUrl::isWeb),
                )
                is SanitizedIntent.Fallback -> ExternalTarget.LoadInside(r.url)
                SanitizedIntent.Drop -> ExternalTarget.None
            }
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).apply {
            component = null
            selector = null
        }
        return ExternalTarget.Launch(intent, null)
    }

    /** Starts [target]; on no app, loads its fallback through [loadInside]. Other failures do nothing (D16). */
    fun launch(context: Context, target: ExternalTarget.Launch, loadInside: (String) -> Unit) {
        when (startSafely(context, target.intent)) {
            StartResult.Started, StartResult.Refused -> Unit
            StartResult.NoApp -> target.fallbackUrl?.let(loadInside)
        }
    }

    enum class StartResult { Started, NoApp, Refused }

    /** Every `startActivity` of the web view goes through here: a failure never crashes the app. */
    fun startSafely(context: Context, intent: Intent): StartResult = try {
        context.startActivity(intent)
        StartResult.Started
    } catch (_: ActivityNotFoundException) {
        StartResult.NoApp
    } catch (_: SecurityException) {
        StartResult.Refused
    } catch (_: FileUriExposedException) {
        StartResult.Refused
    }
}
