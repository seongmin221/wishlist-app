package app.browser

import app.extraction.UrlSafetyPolicy
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EgressProxyTest {
    private val public = InetAddress.getByName("93.184.216.34")
    private val echo = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val connected = CopyOnWriteArrayList<Pair<InetAddress, Int>>()
    private val opened = CopyOnWriteArrayList<Socket>()

    init {
        thread(isDaemon = true) {
            while (!echo.isClosed) {
                val socket = runCatching { echo.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { socket.use { it.getInputStream().copyTo(it.getOutputStream()) } } }
            }
        }
    }

    @AfterTest fun closeEcho() { echo.close(); opened.forEach { runCatching { it.close() } } }

    /** Every validated public address is redirected to the local echo server; the address itself is recorded. */
    private fun proxy(resolve: (String) -> List<InetAddress>) = EgressProxy(UrlSafetyPolicy(resolve), { address, port, _ ->
        connected += address to port
        Socket().apply { connect(InetSocketAddress(InetAddress.getLoopbackAddress(), echo.localPort), 2_000) }
    })

    private fun open(proxy: EgressProxy, head: String): Pair<Socket, String> {
        val socket = Socket(InetAddress.getLoopbackAddress(), proxy.port).apply { soTimeout = 5_000 }
        opened += socket
        socket.getOutputStream().apply { write(head.toByteArray()); flush() }
        return socket to readLine(socket.getInputStream())
    }

    private fun readLine(input: InputStream): String = buildString {
        while (true) {
            val next = input.read()
            if (next < 0 || next == '\n'.code) break
            if (next != '\r'.code) append(next.toChar())
        }
    }

    private fun connect(proxy: EgressProxy, target: String) = open(proxy, "CONNECT $target HTTP/1.1\r\nHost: $target\r\n\r\n")

    @Test fun `connect tunnels to exactly the validated address without resolving again`() {
        val lookups = AtomicInteger()
        proxy { lookups.incrementAndGet(); listOf(public) }.use { proxy ->
            val (socket, status) = connect(proxy, "shop.test:443")
            assertEquals("HTTP/1.1 200 Connection Established", status)
            readLine(socket.getInputStream())   // blank line ending the proxy response
            socket.getOutputStream().apply { write("ping\n".toByteArray()); flush() }
            assertEquals("ping", readLine(socket.getInputStream()))
            assertEquals(listOf(public to 443), connected.toList())
            assertEquals(1, lookups.get())
        }
    }

    @Test fun `a host validated once in a render stays pinned so a later rebinding answer is never used`() {
        val answers = ArrayDeque(listOf(listOf(public), listOf(InetAddress.getByName("127.0.0.1"))))
        proxy { answers.removeFirst() }.use { proxy ->
            assertEquals("HTTP/1.1 200 Connection Established", connect(proxy, "shop.test:443").second)
            assertEquals("HTTP/1.1 200 Connection Established", connect(proxy, "shop.test:443").second)
            assertEquals(listOf(public to 443, public to 443), connected.toList())
            assertEquals(1, answers.size)
        }
    }

    @Test fun `an unreachable first record falls back to the next validated address`() {
        val second = InetAddress.getByName("93.184.216.35")
        EgressProxy(UrlSafetyPolicy { listOf(public, second) }, { address, port, _ ->
            connected += address to port
            if (address == public) throw java.net.ConnectException("unreachable")
            Socket().apply { connect(InetSocketAddress(InetAddress.getLoopbackAddress(), echo.localPort), 2_000) }
        }).use { proxy ->
            assertEquals("HTTP/1.1 200 Connection Established", connect(proxy, "shop.test:443").second)
            assertEquals(listOf(public to 443, second to 443), connected.toList())
        }
    }

    @Test fun `a client waiting on a slow response is not cut off by its own read timeout`() {
        val upstream = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            runCatching {
                val socket = upstream.accept()
                opened += socket
                val input = socket.getInputStream()
                while (input.read() != '\n'.code) { }
                // The response trickles in while the client sends nothing more. Like servers that abort on read-EOF,
                // this one drops the response if the proxy half-closes the request side early.
                repeat(4) {
                    Thread.sleep(150)
                    socket.soTimeout = 1
                    val halfClosed = try { input.read() < 0 } catch (_: java.net.SocketTimeoutException) { false }
                    if (halfClosed) { socket.close(); return@runCatching }
                    socket.getOutputStream().apply { write("part\n".toByteArray()); flush() }
                }
            }
        }
        EgressProxy(UrlSafetyPolicy { listOf(public) }, { _, _, _ ->
            Socket().apply { connect(InetSocketAddress(InetAddress.getLoopbackAddress(), upstream.localPort), 2_000) }
        }, timeout = java.time.Duration.ofMillis(300)).use { proxy ->
            val (socket, _) = connect(proxy, "shop.test:443")
            readLine(socket.getInputStream())
            socket.getOutputStream().apply { write("request\n".toByteArray()); flush() }
            assertEquals(List(4) { "part" }, List(4) { readLine(socket.getInputStream()) })
        }
        upstream.close()
    }

    @Test fun `route checks share the proxy validation so each origin is resolved once`() {
        val lookups = AtomicInteger()
        proxy { lookups.incrementAndGet(); listOf(public) }.use { proxy ->
            assertTrue(proxy.allows("https://shop.test/a.png"))
            assertTrue(proxy.allows("https://shop.test:443/b.js?x=1"))
            assertEquals("HTTP/1.1 200 Connection Established", connect(proxy, "shop.test:443").second)
            for (url in listOf("file:///etc/passwd", "https://user@shop.test/", "https://shop.test:8443/", "https://[::1]/", "data:text/plain,x")) {
                kotlin.test.assertFalse(proxy.allows(url), url)
            }
            assertEquals(1, lookups.get())
        }
    }

    @Test fun `absolute form http responses forbid reusing the proxy connection for another origin`() {
        val upstream = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            runCatching {
                val socket = upstream.accept()
                opened += socket
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nConnection: keep-alive\r\nKeep-Alive: timeout=60\r\nContent-Length: 2\r\n\r\nok".toByteArray()); flush()
                }
            }
        }
        EgressProxy(UrlSafetyPolicy { listOf(public) }, { _, _, _ ->
            Socket().apply { connect(InetSocketAddress(InetAddress.getLoopbackAddress(), upstream.localPort), 2_000) }
        }).use { proxy ->
            val (socket, status) = open(proxy, "GET http://shop.test/ HTTP/1.1\r\nHost: shop.test\r\n\r\n")
            assertEquals("HTTP/1.1 200 OK", status)
            val headers = generateSequence { readLine(socket.getInputStream()).takeIf { it.isNotEmpty() } }.toList()
            assertEquals(listOf("Content-Length: 2", "Connection: close"), headers)
        }
        upstream.close()
    }

    @Test fun `private answers and unsupported targets are refused before any connection`() {
        for (address in listOf("127.0.0.1", "10.0.0.1", "169.254.169.254")) proxy { listOf(InetAddress.getByName(address)) }.use { proxy ->
            assertEquals("HTTP/1.1 403 Forbidden", connect(proxy, "shop.test:443").second, address)
        }
        proxy { listOf(public) }.use { proxy ->
            for (target in listOf("shop.test:8443", "[::1]:443", "[fd00::1]:443", "[2606:4700::1111]:443", "localhost.:443", "127.0.0.1:443", "shop.test")) {
                assertEquals("HTTP/1.1 403 Forbidden", connect(proxy, target).second, target)
            }
            assertEquals("HTTP/1.1 403 Forbidden", open(proxy, "GET /path HTTP/1.1\r\nHost: shop.test\r\n\r\n").second)
            assertEquals("HTTP/1.1 403 Forbidden", open(proxy, "DELETE https://shop.test/ HTTP/1.1\r\n\r\n").second)
            assertEquals("HTTP/1.1 403 Forbidden", open(proxy, "GET http://[2606:4700::1111]/ HTTP/1.1\r\n\r\n").second)
            assertEquals("HTTP/1.1 200 Connection Established", connect(proxy, "SHOP.TEST:443").second)
        }
        assertEquals(listOf(public to 443), connected.toList())
    }

    @Test fun `absolute form http requests are forwarded in origin form`() {
        proxy { listOf(public) }.use { proxy ->
            val (socket, firstLine) = open(proxy, "GET http://shop.test/p?q=1 HTTP/1.1\r\nHost: shop.test\r\nProxy-Connection: keep-alive\r\n\r\n")
            assertEquals("GET /p?q=1 HTTP/1.1", firstLine)
            val headers = generateSequence { readLine(socket.getInputStream()).takeIf { it.isNotEmpty() } }.toList()
            assertTrue("Connection: close" in headers, headers.toString())
            assertTrue(headers.none { it.startsWith("Proxy-Connection") }, headers.toString())
            assertEquals(listOf(public to 80), connected.toList())
        }
    }

    @Test fun `closing active connections ends tunnels and closing the proxy refuses new clients`() {
        val proxy = proxy { listOf(public) }
        val (socket, status) = connect(proxy, "shop.test:443")
        assertEquals("HTTP/1.1 200 Connection Established", status)
        readLine(socket.getInputStream())
        proxy.closeActiveConnections()
        assertEquals(-1, runCatching { socket.getInputStream().read() }.getOrDefault(-1))
        proxy.close()
        val refused = runCatching {
            Socket(InetAddress.getLoopbackAddress(), proxy.port).use { s -> s.soTimeout = 1_000; s.getInputStream().read() }
        }
        assertTrue(refused.isFailure || refused.getOrNull() == -1)
    }
}
