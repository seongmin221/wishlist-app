package app.extraction

import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

class SafeHttpTransport : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private val activeCalls = mutableSetOf<Call>()
    private val sharedClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            // Include admitted calls which have not entered OkHttp's dispatcher yet.
            activeCalls.forEach { it.cancel() }
            sharedClient.dispatcher.cancelAll()
            sharedClient.dispatcher.executorService.shutdown()
            sharedClient.connectionPool.evictAll()
        }
    }

    fun fetch(url: String, pinnedAddresses: List<InetAddress>): HttpFetchResponse {
        val host = URI(url).host ?: throw UnsafeUrlException("missing host")
        val addresses = pinnedAddresses.toList()
        val call = synchronized(lock) {
            check(!closed) { "HTTP transport is closed" }
            // A distinct DNS identity per request prevents reuse of a connection with old pins.
            sharedClient.newBuilder().dns(Dns { requested ->
                if (!requested.equals(host, ignoreCase = true)) throw UnsafeUrlException("unexpected DNS lookup")
                addresses
            }).build().newCall(Request.Builder().url(url).get().build()).also { activeCalls.add(it) }
        }
        try {
            call.execute().use { response ->
                val type = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                if (response.code == 200 && type !in setOf("text/html", "application/xhtml+xml")) return HttpFetchResponse(415, emptyMap(), "")
                val bytes = response.body.byteStream().use { it.readNBytes(512 * 1024 + 1) }
                val bounded = if (bytes.size > 512 * 1024) bytes.copyOf(512 * 1024) else bytes
                return HttpFetchResponse(response.code, response.headers.names().associate { it.lowercase() to response.header(it).orEmpty() }, bounded.toString(Charsets.UTF_8), bytes.size > 512 * 1024)
            }
        } finally { synchronized(lock) { activeCalls.remove(call) } }
    }
}
