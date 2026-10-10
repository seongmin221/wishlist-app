package app.browser

import app.extraction.UrlSafetyPolicy
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaywrightGatewayTest {
    private val policy = UrlSafetyPolicy { listOf(InetAddress.getByName("93.184.215.14")) }

    @Test
    fun `browser request guard blocks private subresources`() {
        val gateway = PlaywrightGateway(policy) { EgressProxy(policy) }
        assertTrue(gateway.canRequest("https://shop.example/image.png"))
        assertFalse(gateway.canRequest("http://127.0.0.1/metadata"))
        assertFalse(gateway.canRequest("file:///etc/passwd"))
    }

    @Test
    fun `chromium routes every connection through the pinning proxy without bypasses`() {
        assertEquals(listOf(
            "--proxy-server=http://127.0.0.1:4567",
            "--proxy-bypass-list=<-loopback>",
            "--disable-quic",
            "--force-webrtc-ip-handling-policy=disable_non_proxied_udp",
        ), PlaywrightGateway.launchArguments(4567))
    }
}
