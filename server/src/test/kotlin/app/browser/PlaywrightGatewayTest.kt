package app.browser

import app.extraction.UrlSafetyPolicy
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaywrightGatewayTest {
    @Test
    fun `browser request guard blocks private subresources`() {
        val policy = UrlSafetyPolicy { listOf(InetAddress.getByName("93.184.215.14")) }
        val gateway = PlaywrightGateway(policy)

        assertTrue(gateway.canRequest("https://shop.example/image.png"))
        assertFalse(gateway.canRequest("http://127.0.0.1/metadata"))
        assertFalse(gateway.canRequest("file:///etc/passwd"))
    }
}
