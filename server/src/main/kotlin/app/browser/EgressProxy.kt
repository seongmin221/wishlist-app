package app.browser

import app.extraction.UrlSafetyPolicy
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import kotlinx.coroutines.CancellationException

/**
 * Loopback forward proxy for the browser worker. Chromium resolves DNS itself, so URL checks alone cannot stop a
 * rebinding answer; every connection instead goes through this proxy, which validates the host and connects only to
 * an address it validated. Only the same container is trusted: the proxy binds to loopback and has no authentication.
 */
class EgressProxy(
    private val safety: UrlSafetyPolicy,
    private val connect: (InetAddress, Int, Duration) -> Socket = { address, port, timeout ->
        Socket().apply { connect(InetSocketAddress(address, port), timeout.toMillis().toInt()) }
    },
    maxConnections: Int = 32,
    private val timeout: Duration = Duration.ofSeconds(30),
) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val slots = Semaphore(maxConnections)
    private val active = ConcurrentHashMap.newKeySet<Socket>()
    /** One proxy serves one render, so a host validated once is reused instead of resolving it per connection. */
    private val validated = ConcurrentHashMap<String, List<InetAddress>>()
    private val threads = Executors.newCachedThreadPool(ThreadFactory { task -> Thread(task, "egress-proxy").apply { isDaemon = true } })

    val port: Int = server.localPort

    init {
        threads.execute {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                if (!slots.tryAcquire()) { client.closeQuietly(); continue }
                track(client)
                threads.execute {
                    try { handle(client) } catch (cause: Exception) {
                        if (cause is CancellationException) throw cause
                    } finally { client.closeQuietly(); active.remove(client); slots.release() }
                }
            }
        }
    }

    /** Ends every open tunnel; called when a render finishes so no connection outlives its page. */
    fun closeActiveConnections() { active.toList().forEach { it.closeQuietly(); active.remove(it) } }

    override fun close() {
        server.closeQuietly()
        closeActiveConnections()
        threads.shutdownNow()
    }

    private fun track(socket: Socket) { active.add(socket); if (server.isClosed) socket.closeQuietly() }

    private fun handle(client: Socket) {
        client.soTimeout = timeout.toMillis().toInt()
        val input = client.getInputStream()
        val head = readHead(input) ?: return deny(client)
        val lines = head.split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size != 3) return deny(client)
        val (method, target, version) = parts
        val tunnel = method == "CONNECT"
        val destination = (if (tunnel) authority(target) else absoluteHttp(target)) ?: return deny(client)
        val (host, port, path) = destination
        // Pages have no reason to address an IPv6 literal; refusing them outright avoids range-list gaps.
        if (port != 80 && port != 443 || ':' in host) return deny(client)
        val literal = if (':' in host) "[$host]" else host
        val origin = "${if (port == 443) "https" else "http"}://$literal/"
        val addresses = validated[origin] ?: try { safety.validate(origin).also { validated[origin] = it } } catch (cause: Exception) {
            if (cause is CancellationException) throw cause
            return deny(client)
        }
        val upstream = try { connect(addresses.first(), port, timeout) } catch (cause: Exception) {
            if (cause is CancellationException) throw cause
            return deny(client)
        }
        upstream.use {
            track(upstream)
            upstream.soTimeout = timeout.toMillis().toInt()
            try {
                if (tunnel) {
                    client.getOutputStream().apply { write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); flush() }
                } else {
                    val headers = lines.drop(1).filter { it.isNotEmpty() }
                        .filterNot { it.startsWith("Proxy-Connection:", true) || it.startsWith("Connection:", true) }
                    val request = (listOf("$method $path $version") + headers + "Connection: close").joinToString("\r\n") + "\r\n\r\n"
                    upstream.getOutputStream().apply { write(request.toByteArray()); flush() }
                }
                val back = threads.submit {
                    // Absolute-form HTTP is pinned to this one host, so the response forbids reusing the proxy
                    // connection; otherwise a later request for another origin would reach this host's address.
                    if (tunnel || forwardResponseHead(upstream.getInputStream(), client.getOutputStream())) {
                        pump(upstream.getInputStream(), client.getOutputStream())
                    }
                    client.closeQuietly()
                }
                pump(input, upstream.getOutputStream())
                upstream.shutdownOutputQuietly()
                back.get()
            } finally { active.remove(upstream) }
        }
    }

    /** CONNECT host:port, with IPv6 in brackets. A host is lowercased and loses a trailing dot before validation. */
    private fun authority(target: String): Triple<String, Int, String>? {
        val separator = target.lastIndexOf(':')
        if (separator <= 0) return null
        val port = target.substring(separator + 1).toIntOrNull() ?: return null
        val host = target.substring(0, separator).removePrefix("[").removeSuffix("]").lowercase().trimEnd('.')
        return if (host.isEmpty()) null else Triple(host, port, "")
    }

    private fun absoluteHttp(target: String): Triple<String, Int, String>? {
        val uri = runCatching { URI(target) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "http" || uri.userInfo != null) return null
        val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return null
        val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
        return Triple(host, if (uri.port == -1) 80 else uri.port, path)
    }

    private fun readHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        val end = "\r\n\r\n"
        while (buffer.size() < MAX_HEAD_BYTES) {
            val next = input.read()
            if (next < 0) return null
            buffer.write(next)
            matched = if (next.toChar() == end[matched]) matched + 1 else if (next == '\r'.code) 1 else 0
            if (matched == end.length) return buffer.toString(Charsets.ISO_8859_1).removeSuffix(end)
        }
        return null
    }

    private fun forwardResponseHead(from: InputStream, to: OutputStream): Boolean {
        val head = readHead(from) ?: return false
        val lines = head.split("\r\n")
        val headers = lines.drop(1).filter { it.isNotEmpty() }.filterNot { line ->
            HOP_BY_HOP.any { line.startsWith("$it:", true) }
        }
        val rewritten = (listOf(lines.first()) + headers + "Connection: close").joinToString("\r\n") + "\r\n\r\n"
        return runCatching { to.write(rewritten.toByteArray(Charsets.ISO_8859_1)) }.isSuccess
    }

    private fun pump(from: InputStream, to: OutputStream) {
        try { from.copyTo(to) } catch (_: Exception) { }
        runCatching { to.flush() }
    }

    private fun deny(client: Socket) {
        runCatching { client.getOutputStream().apply { write(FORBIDDEN); flush() } }
    }

    private fun Socket.shutdownOutputQuietly() { runCatching { shutdownOutput() } }
    private fun Socket.closeQuietly() { runCatching { close() } }
    private fun ServerSocket.closeQuietly() { runCatching { close() } }

    private companion object {
        const val MAX_HEAD_BYTES = 8 * 1024
        val HOP_BY_HOP = listOf("Connection", "Proxy-Connection", "Keep-Alive")
        val FORBIDDEN = "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
    }
}
