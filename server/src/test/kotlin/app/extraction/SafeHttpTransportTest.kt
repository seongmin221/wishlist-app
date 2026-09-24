package app.extraction

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SafeHttpTransportTest {
    @Test fun `large HTML keeps bounded prefix containing product metadata`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/item") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            val bytes = ("<html><head><meta property=\"og:title\" content=\"MIZUNO NEO DAICHI 10\"></head><body>" +
                "x".repeat(700_000) + "</body></html>").toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            runCatching { exchange.responseBody.use { it.write(bytes) } }
        }
        server.start()
        try {
            val result = SafeHttpTransport().fetch("http://127.0.0.1:${server.address.port}/item", listOf(InetAddress.getByName("127.0.0.1")))
            assertEquals(200, result.status)
            assertTrue(result.body.contains("MIZUNO NEO DAICHI 10"))
            assertTrue(result.body.toByteArray().size <= 512 * 1024)
        } finally { server.stop(0) }
    }
}
