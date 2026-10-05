package app.extraction

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SafeHttpTransportTest {
    @Test fun `closing shared transport cancels a blocked HTTP fetch`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/item") { exchange ->
            entered.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val transport = SafeHttpTransport()
        try {
            val pending = executor.submit<HttpFetchResponse> {
                transport.fetch("http://pinned.invalid:${server.address.port}/item", listOf(InetAddress.getByName("127.0.0.1")))
            }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            transport.close()
            val failure = kotlin.test.assertFailsWith<java.util.concurrent.ExecutionException> { pending.get(3, java.util.concurrent.TimeUnit.SECONDS) }
            assertTrue(failure.cause is java.io.IOException)
        } finally { release.countDown(); transport.close(); executor.shutdownNow(); server.stop(0) }
    }

    @Test fun `closed transport rejects new fetch and close is idempotent`() {
        val transport = SafeHttpTransport()
        transport.close()
        transport.close()
        kotlin.test.assertFailsWith<IllegalStateException> {
            transport.fetch("http://pinned.invalid/item", listOf(InetAddress.getByName("127.0.0.1")))
        }
    }

    @Test fun `shared transport honors new pinned address for the same host`() {
        val firstAddress = InetAddress.getByName("127.0.0.1")
        val secondAddress = InetAddress.getByName("::1")
        val first = HttpServer.create(InetSocketAddress(firstAddress, 0), 0)
        val second = HttpServer.create(InetSocketAddress(secondAddress, first.address.port), 0)
        fun serve(server: HttpServer, body: String) {
            server.createContext("/item") { exchange ->
                exchange.responseHeaders.add("Content-Type", "text/html")
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }
        serve(first, "first")
        serve(second, "second")
        try {
            SafeHttpTransport().use { transport ->
                val url = "http://pinned.invalid:${first.address.port}/item"
                assertEquals("first", transport.fetch(url, listOf(firstAddress)).body)
                assertEquals("second", transport.fetch(url, listOf(secondAddress)).body)
            }
        } finally { first.stop(0); second.stop(0) }
    }

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
            val result = SafeHttpTransport().use { it.fetch("http://127.0.0.1:${server.address.port}/item", listOf(InetAddress.getByName("127.0.0.1"))) }
            assertEquals(200, result.status)
            assertTrue(result.body.contains("MIZUNO NEO DAICHI 10"))
            assertTrue(result.body.toByteArray().size <= 512 * 1024)
        } finally { server.stop(0) }
    }
}
