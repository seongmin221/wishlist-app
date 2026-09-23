package app.browser

import app.extraction.ExtractionResult
import app.extraction.HttpFetchResponse
import app.extraction.HttpMetadataExtractor
import app.extraction.Metadata
import app.extraction.UrlSafetyPolicy
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException

class PlaywrightGateway(private val safety: UrlSafetyPolicy) {
    fun canRequest(url: String): Boolean = runCatching { safety.validate(url) }.isSuccess

    fun render(url: String): Metadata? {
        safety.validate(url)
        try {
            Playwright.create().use { playwright ->
                playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                    browser.newContext().use { context ->
                        context.route("**/*") { route ->
                            if (canRequest(route.request().url())) route.resume() else route.abort()
                        }
                        val page = context.newPage()
                        page.navigate(url, com.microsoft.playwright.Page.NavigateOptions().setTimeout(30_000.0))
                        val finalUrl = page.url()
                        safety.validate(finalUrl)
                        val html = page.content()
                        val extractor = HttpMetadataExtractor(safety) { _, _ -> HttpFetchResponse(200, mapOf("content-type" to "text/html"), html) }
                        return (extractor.extract(finalUrl) as? ExtractionResult.Complete)?.metadata
                    }
                }
            }
        } catch (_: PlaywrightException) {
            throw BrowserNavigationTimeout()
        }
    }
}
