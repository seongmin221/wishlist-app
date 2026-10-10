package app.browser

import app.analysis.WorkerExecution
import java.time.Duration
import app.extraction.ExtractionResult
import app.extraction.HttpFetchResponse
import app.extraction.HttpMetadataExtractor
import app.extraction.Metadata
import app.extraction.UrlSafetyPolicy
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.PlaywrightException
import com.microsoft.playwright.options.ServiceWorkerPolicy

/** Each render owns a fresh pinning proxy, so one render ending never cuts another render's connections. */
class PlaywrightGateway(
    private val safety: UrlSafetyPolicy,
    private val createPlaywright: () -> Playwright = Playwright::create,
    private val newProxy: () -> EgressProxy,
) {
    fun canRequest(url: String): Boolean = runCatching { safety.validate(url) }.isSuccess


    fun render(url: String): Metadata? {
        safety.validate(url)
        newProxy().use { proxy -> return render(url, proxy) }
    }

    private fun render(url: String, proxy: EgressProxy): Metadata? {
        // Playwright, browser and context start-up failures are Worker infrastructure faults and stay Retryable;
        // only failures while loading the target page are target failures (PARTIAL).
        createPlaywright().use { playwright ->
            val launch = BrowserType.LaunchOptions().setHeadless(true).setArgs(launchArguments(proxy.port))
                .setTimeout(WorkerExecution.remaining(Duration.ofSeconds(30)).toMillis().toDouble())
            playwright.chromium().launch(launch).use { browser ->
                browser.newContext(Browser.NewContextOptions().setServiceWorkers(ServiceWorkerPolicy.BLOCK).setAcceptDownloads(false)).use { context ->
                    // Fast rejection only, sharing the proxy's per-render answers; the proxy is what pins every
                    // connection to a validated address.
                    context.route("**/*") { route ->
                        if (proxy.allows(route.request().url())) route.resume() else route.abort()
                    }
                    val page = context.newPage()
                    val (finalUrl, html) = try {
                        page.navigate(url, com.microsoft.playwright.Page.NavigateOptions().setTimeout(WorkerExecution.remaining(Duration.ofSeconds(30)).toMillis().toDouble()))
                        page.url() to page.content()
                    } catch (_: PlaywrightException) {
                        throw BrowserNavigationTimeout()
                    }
                    safety.validate(finalUrl)
                    val extractor = HttpMetadataExtractor(safety) { _, _ -> HttpFetchResponse(200, mapOf("content-type" to "text/html"), html) }
                    return (extractor.extract(finalUrl) as? ExtractionResult.Complete)?.metadata
                }
            }
        }
    }

    companion object {
        /** Every Chromium connection goes through the loopback proxy; QUIC and non-proxied WebRTC UDP would bypass it. */
        fun launchArguments(proxyPort: Int): List<String> = listOf(
            "--proxy-server=http://127.0.0.1:$proxyPort",
            "--proxy-bypass-list=<-loopback>",
            "--disable-quic",
            "--force-webrtc-ip-handling-policy=disable_non_proxied_udp",
        )
    }
}
