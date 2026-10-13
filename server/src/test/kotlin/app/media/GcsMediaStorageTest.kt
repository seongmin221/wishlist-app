package app.media

import com.google.auth.oauth2.ServiceAccountCredentials
import java.net.URI
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.time.Instant
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GcsMediaStorageTest {
    private val credentials = ServiceAccountCredentials.newBuilder()
        .setClientEmail("media-signer@test-project.iam.gserviceaccount.com")
        .setPrivateKey(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private)
        .setPrivateKeyId("test-key").setProjectId("test-project").build()
    private val now = Instant.parse("2026-10-13T00:00:00Z")
    private val storage = GcsMediaStorage.offline("wishlist-media-test", credentials) { now }

    private fun query(url: String): Map<String, String> = URI(url).rawQuery.split("&").associate {
        val (k, v) = it.split("=", limit = 2); k to URLDecoder.decode(v, Charsets.UTF_8)
    }

    @Test fun `upload target signs create-only content type and size headers for the remaining validity`() {
        val owner = UUID.randomUUID(); val id = UUID.randomUUID()
        val key = MediaKeys.objectKey(owner, id)
        assertEquals("media/$owner/$id.jpg", key)
        val target = storage.uploadTarget(key, MediaLimits.CONTENT_TYPE, now.plusSeconds(600))
        assertEquals("PUT", target.method)
        assertEquals(mapOf("Content-Type" to "image/jpeg", "x-goog-if-generation-match" to "0",
            "x-goog-content-length-range" to "1,5242880"), target.headers)
        assertEquals(now.plusSeconds(600), target.expiresAt)
        val uri = URI(target.url)
        assertEquals("/wishlist-media-test/$key", uri.path)
        val q = query(target.url)
        assertEquals("GOOG4-RSA-SHA256", q["X-Goog-Algorithm"])
        assertEquals("600", q["X-Goog-Expires"])
        assertEquals("content-type;host;x-goog-content-length-range;x-goog-if-generation-match", q["X-Goog-SignedHeaders"])
        assertTrue(q.getValue("X-Goog-Signature").matches(Regex("[0-9a-f]{512}")))
    }

    @Test fun `upload target never signs less than one second and read url lasts an hour`() {
        val key = MediaKeys.objectKey(UUID.randomUUID(), UUID.randomUUID())
        assertEquals("1", query(storage.uploadTarget(key, MediaLimits.CONTENT_TYPE, now.minusSeconds(5)).url)["X-Goog-Expires"])
        val read = storage.readUrl(key, Duration.ofHours(1))
        assertEquals(now.plus(Duration.ofHours(1)), read.expiresAt)
        val q = query(read.url)
        assertEquals("3600", q["X-Goog-Expires"])
        assertEquals("host", q["X-Goog-SignedHeaders"])
    }

    @Test fun `bounded read allocates the expected size and rejects anything longer`() {
        fun channel(bytes: ByteArray) = java.nio.channels.Channels.newChannel(java.io.ByteArrayInputStream(bytes))
        val bytes = ByteArray(40_000) { it.toByte() }
        kotlin.test.assertContentEquals(bytes, GcsMediaStorage.readBounded(channel(bytes), bytes.size))
        kotlin.test.assertContentEquals(bytes.copyOf(10), GcsMediaStorage.readBounded(channel(bytes.copyOf(10)), bytes.size))
        kotlin.test.assertFailsWith<MediaObjectTooLarge> { GcsMediaStorage.readBounded(channel(bytes), bytes.size - 1) }
    }
}
