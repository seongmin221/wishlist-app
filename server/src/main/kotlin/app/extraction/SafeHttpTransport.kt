package app.extraction

import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

class SafeHttpTransport {
    fun fetch(url: String, pinnedAddresses: List<InetAddress>): HttpFetchResponse {
        val host = URI(url).host ?: throw UnsafeUrlException("missing host")
        val client = OkHttpClient.Builder()
            .dns(Dns { requested ->
                if (!requested.equals(host, ignoreCase = true)) throw UnsafeUrlException("unexpected DNS lookup")
                pinnedAddresses
            })
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            val type = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
            if (response.code == 200 && type !in setOf("text/html", "application/xhtml+xml")) return HttpFetchResponse(415, emptyMap(), "")
            val bytes = response.body.byteStream().use { it.readNBytes(512 * 1024 + 1) }
            if (bytes.size > 512 * 1024) return HttpFetchResponse(413, emptyMap(), "")
            return HttpFetchResponse(response.code, response.headers.names().associate { it.lowercase() to response.header(it).orEmpty() }, bytes.toString(Charsets.UTF_8))
        }
    }
}
