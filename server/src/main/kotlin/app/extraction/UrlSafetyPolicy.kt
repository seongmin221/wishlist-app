package app.extraction

import java.net.InetAddress
import java.net.URI

class UnsafeUrlException(message: String) : IllegalArgumentException(message)

class UrlSafetyPolicy(
    private val resolve: (String) -> List<InetAddress> = BoundedResolver.default,
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
        val addresses = try { resolve(host) } catch (cause: Exception) {
            // Deadline and cancellation keep their meaning; any other resolver failure is a DNS failure, not a block.
            if (cause is DnsLookupFailed || cause is app.analysis.ProcessingDeadlineExceeded || cause is kotlinx.coroutines.CancellationException) throw cause
            throw DnsLookupFailed()
        }
        if (addresses.isEmpty()) throw DnsLookupFailed()
        if (addresses.any(::isBlocked)) throw UnsafeUrlException("blocked address")
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
        if (bytes.size == 16) {
            val first = bytes[0].toInt() and 255
            val second = bytes[1].toInt() and 255
            // Native DNS keeps an AAAA ::ffff:a.b.c.d answer as Inet6Address, whose JDK checks then miss the IPv4
            // target; it is re-checked as IPv4 so a public mapped address still passes.
            if (bytes.copyOfRange(0, 12).contentEquals(MAPPED_PREFIX)) return isBlocked(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
            // fc00::/7 unique local (cloud VPC internal IPv6), 2002::/16 6to4, 2001::/32 Teredo, 64:ff9b::/96 and
            // 64:ff9b:1::/48 NAT64 and ::ffff:0:0:0/96 SIIT can embed or reach internal addresses; ::/96
            // IPv4-compatible addresses embed an IPv4 target directly.
            if (first and 0xfe == 0xfc) return true
            if (first == 0x20 && second == 0x02) return true
            if (bytes.copyOfRange(0, 4).contentEquals(TEREDO_PREFIX)) return true
            if (bytes.copyOfRange(0, 12).contentEquals(NAT64_PREFIX)) return true
            if (bytes.copyOfRange(0, 6).contentEquals(NAT64_LOCAL_PREFIX)) return true
            if (bytes.copyOfRange(0, 12).contentEquals(SIIT_PREFIX)) return true
            if (bytes.copyOfRange(0, 12).all { it.toInt() == 0 }) return true
        }
        return false
    }

    private companion object {
        val NAT64_PREFIX = byteArrayOf(0, 0x64, 0xff.toByte(), 0x9b.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        val NAT64_LOCAL_PREFIX = byteArrayOf(0, 0x64, 0xff.toByte(), 0x9b.toByte(), 0, 1)
        val MAPPED_PREFIX = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte())
        val SIIT_PREFIX = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(), 0, 0)
        val TEREDO_PREFIX = byteArrayOf(0x20, 0x01, 0, 0)
    }
}
