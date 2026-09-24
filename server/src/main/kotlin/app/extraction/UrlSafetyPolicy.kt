package app.extraction

import java.net.InetAddress
import java.net.URI

class UnsafeUrlException(message: String) : IllegalArgumentException(message)

class UrlSafetyPolicy(
    private val resolve: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
) {
    fun validate(url: String): List<InetAddress> {
        val uri = runCatching { URI(url) }.getOrElse { throw UnsafeUrlException("invalid URL") }
        val host = uri.host?.lowercase() ?: throw UnsafeUrlException("missing host")
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.userInfo != null) throw UnsafeUrlException("unsupported URL")
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) throw UnsafeUrlException("local host")
        if (uri.port !in setOf(-1, 80, 443)) throw UnsafeUrlException("unsupported port")
        if (host.matches(Regex("[0-9.]+")) || ':' in host) {
            val literal = runCatching { InetAddress.getByName(host) }.getOrElse { throw UnsafeUrlException("invalid IP address") }
            if (isBlocked(literal)) throw UnsafeUrlException("blocked address")
        }
        val addresses = runCatching { resolve(host) }.getOrElse { throw UnsafeUrlException("DNS lookup failed") }
        if (addresses.isEmpty() || addresses.any(::isBlocked)) throw UnsafeUrlException("blocked address")
        return addresses
    }

    private fun isBlocked(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return true
        val bytes = address.address
        if (bytes.size == 4) {
            val a = bytes[0].toInt() and 255
            val b = bytes[1].toInt() and 255
            val c = bytes[2].toInt() and 255
            if (a == 0 || a >= 224 || a == 100 && b in 64..127 || a == 169 && b == 254 ||
                a == 192 && b == 0 && c == 0 || a == 198 && b in 18..19) return true
        }
        return false
    }
}
