package app

import app.media.IntentRequest
import app.media.MediaLimits
import app.testutil.InMemoryMediaStorage
import app.testutil.withAnalysisDatabase
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaWiringTest {
    @Test fun `display signing load cannot starve upload intents`() = withAnalysisDatabase { source ->
        val storage = InMemoryMediaStorage().apply { readSignDelayMillis = 3_000 }
        val closeables = mutableListOf<AutoCloseable>()
        val wiring = buildMediaWiring(source, storage) { closeables += it }
        val pool = Executors.newFixedThreadPool(4)
        try {
            // Several cold list responses fill the display signing pool.
            repeat(4) { batch -> pool.submit { wiring.signer.signAll((1..100).map { "media/$batch/$it.jpg" }.toSet()) } }
            Thread.sleep(200)
            val started = System.nanoTime()
            val result = wiring.service.createIntent(UUID.randomUUID(), UUID.randomUUID(), IntentRequest(MediaLimits.CONTENT_TYPE, 100, 10, 10))
            assertEquals(false, result.replayed)
            assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(1))
        } finally {
            pool.shutdownNow(); closeables.forEach(AutoCloseable::close)
        }
    }
}
