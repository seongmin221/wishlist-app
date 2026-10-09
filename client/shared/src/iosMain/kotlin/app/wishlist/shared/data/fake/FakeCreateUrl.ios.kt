@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.wishlist.shared.data.fake

import kotlinx.cinterop.*
import platform.Foundation.NSURLComponents
import platform.posix.*

internal actual fun fakeCreateUrlHost(sourceUrl: String): String? {
    // Foundation repairs invalid URI input. Reject those raw spellings before using its parser.
    if (sourceUrl.any { it.isWhitespace() || it.code < 32 || it.code in 127..159 || it in "<>\"{}|\\^`" }) return null
    val schemeEnd = sourceUrl.indexOf("://")
    if (schemeEnd < 0 || sourceUrl.substring(0, schemeEnd).lowercase() !in setOf("http", "https")) return null
    val authority = sourceUrl.substring(schemeEnd + 3).takeWhile { it != '/' && it != '?' && it != '#' }
    if (authority.count { it == '@' } > 1) return null
    if ('@' in authority && authority.substringBefore('@').any { it == '[' || it == ']' }) return null
    val hostPort = authority.substringAfterLast('@')
    val host: String
    val port: String?
    if (hostPort.startsWith('[')) {
        val end = hostPort.indexOf(']')
        if (end < 0) return null
        host = hostPort.substring(1, end)
        val tail = hostPort.substring(end + 1)
        if (tail.isNotEmpty() && !tail.startsWith(':')) return null
        port = tail.takeIf { it.isNotEmpty() }?.substring(1)
        if (fakeIpv6Bytes(host) == null) return null
    } else {
        host = hostPort.substringBefore(':')
        port = hostPort.substringAfter(':', "").takeIf { ':' in hostPort }
        val labels = host.removeSuffix(".").split('.')
        if (labels.any { !it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?")) }) return null
        if (labels.size > 1 && labels.last().first().isDigit()) {
            if (host.endsWith('.')) return null
            if (labels.size != 4 || labels.any { it.length !in 1..3 || !it.all(Char::isDigit) || it.toInt() > 255 }) return null
        }
    }
    // Java URI accepts high ports, but only within a signed Int. No extraction port policy here.
    if (port != null && port.isNotEmpty() && (!port.all { it in '0'..'9' } || port.toIntOrNull() == null)) return null
    val rest = sourceUrl.substring(schemeEnd + 3 + authority.length)
    if (rest.count { it == '#' } > 1 || rest.substringBefore('?').substringBefore('#').any { it == '[' || it == ']' }) return null
    // A raw numeric IPv6 zone (e.g. %1) is legal Java URI syntax, unlike percent escapes elsewhere.
    val escapedParts = if (hostPort.startsWith('[')) authority.substringBefore('[') + hostPort.substringAfter(']') + rest else authority + rest
    for (i in escapedParts.indices) if (escapedParts[i] == '%') {
        if (i + 2 >= escapedParts.length || escapedParts[i + 1].digitToIntOrNull(16) == null || escapedParts[i + 2].digitToIntOrNull(16) == null) return null
    }
    return NSURLComponents.componentsWithString(sourceUrl)?.host?.takeIf { it.isNotEmpty() }?.let { host }
}

private fun fakeIpv6Bytes(host: String): ByteArray? = memScoped {
    val hints = alloc<addrinfo>().apply {
        ai_flags = AI_NUMERICHOST
        ai_family = AF_INET6
        ai_socktype = 0
        ai_protocol = 0
        ai_addrlen = 0u
        ai_addr = null
        ai_canonname = null
        ai_next = null
    }
    val result = alloc<CPointerVar<addrinfo>>()
    if (getaddrinfo(host, null, hints.ptr, result.ptr) != 0) return@memScoped null
    val info = result.value ?: return@memScoped null
    try {
        info.pointed.ai_addr?.reinterpret<sockaddr_in6>()?.pointed?.sin6_addr?.ptr?.reinterpret<ByteVar>()?.readBytes(16)
    } finally {
        freeaddrinfo(info)
    }
}

internal actual fun isLocalFakeIpv6(host: String): Boolean {
    val bytes = fakeIpv6Bytes(host) ?: return true
    val allZero = bytes.all { it == 0.toByte() }
    val loopback = (0..14).all { bytes[it] == 0.toByte() } && bytes[15] == 1.toByte()
    val mapped = (0..9).all { bytes[it] == 0.toByte() } && bytes[10] == (-1).toByte() && bytes[11] == (-1).toByte()
    return allZero || loopback || (mapped && (bytes[12] == 127.toByte() || (12..15).all { bytes[it] == 0.toByte() }))
}
