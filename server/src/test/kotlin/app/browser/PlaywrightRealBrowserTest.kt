package app.browser

import app.extraction.UrlSafetyPolicy
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Opt-in: needs an installed Chromium. Run with RUN_BROWSER_TESTS=1. */
class PlaywrightRealBrowserTest {
    @Test fun `real chromium renders through the proxy and private subresources never connect`() {
        assumeTrue(System.getenv("RUN_BROWSER_TESTS") == "1", "RUN_BROWSER_TESTS=1 is required")
        val requests = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                requests += exchange.requestURI.toString()
                val body = """<html><head><meta property="og:title" content="Rendered cap">
                    <script type="application/ld+json">{"@type":"Product","name":"Rendered cap","brand":"CAYL"}</script></head>
                    <body><img src="http://127.0.0.1:${address.port}/private.png"></body></html>""".toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html")
                exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val public = InetAddress.getByName("93.184.216.34")
            val policy = UrlSafetyPolicy { host -> if (host == "shop.test") listOf(public) else listOf(InetAddress.getByName(host)) }
            EgressProxy(policy, { address, _, _ ->
                check(address == public)
                Socket().apply { connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.address.port), 2_000) }
            }).use { proxy ->
                val metadata = PlaywrightGateway(policy, proxy).render("http://shop.test/product")
                assertEquals("Rendered cap", metadata?.title)
                assertEquals("CAYL", metadata?.brand)
                assertTrue(requests.none { it.contains("private.png") }, requests.toString())
            }
        } finally { server.stop(0) }
    }
}
