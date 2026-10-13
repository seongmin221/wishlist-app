package app.media

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Display URLs valid for an hour, reused while at least 45 minutes remain, so a list does not issue one signBlob per
 * item. Misses are signed on the display [MediaSigningExecutor]; one response starts at most [maxNewPerResponse] new
 * signatures so a single cold list cannot fill the pool for everyone, waits at most [budget] in total, and a signature
 * that finishes late still lands in the cache for the next request.
 */
class CachingMediaUrlSigner(
    private val storage: MediaStorage,
    private val signing: MediaSigningExecutor,
    private val clock: () -> Instant = Instant::now,
    private val validFor: Duration = MediaLimits.READ_VALIDITY,
    private val reuseWhile: Duration = MediaLimits.READ_REUSE_MINIMUM,
    private val maxEntries: Int = 10_000,
    private val budget: Duration = signing.wait,
    private val maxNewPerResponse: Int = 32,
) : MediaUrlSigner {
    private val cache = ConcurrentHashMap<String, SignedRead>()
    private val log = LoggerFactory.getLogger(CachingMediaUrlSigner::class.java)

    override fun sign(objectKey: String): SignedRead? =
        cache[objectKey]?.takeIf(::fresh) ?: signing.await(signed(objectKey), System.nanoTime() + budget.toNanos())

    override fun signAll(objectKeys: Set<String>): MediaUrls {
        val deadline = System.nanoTime() + budget.toNanos()
        val hits = objectKeys.mapNotNull { key -> cache[key]?.takeIf(::fresh)?.let { key to it } }.toMap()
        val pending = (objectKeys - hits.keys).take(maxNewPerResponse).associateWith(::signed)
        val signed = pending.mapNotNull { (key, future) -> signing.await(future, deadline)?.let { key to it } }.toMap()
        return MediaUrls(hits + signed)
    }

    private fun signed(key: String) = signing.shared(key) {
        try {
            storage.readUrl(key, validFor).also { remember(key, it) }
        } catch (error: Exception) {
            log.warn("Media display signing failed mediaId={} exceptionType={}", MediaKeys.mediaId(key), error.javaClass.name)
            throw error
        }
    }

    private fun fresh(read: SignedRead) = Duration.between(clock(), read.expiresAt) >= reuseWhile

    /** Past the limit, drops entries no longer reusable, then the earliest-expiring tenth in one pass. */
    private fun remember(key: String, read: SignedRead) {
        cache[key] = read
        if (cache.size <= maxEntries) return
        cache.entries.removeIf { !fresh(it.value) && it.key != key }
        if (cache.size <= maxEntries) return
        cache.entries.filter { it.key != key }.sortedBy { it.value.expiresAt }.take(maxOf(1, maxEntries / 10))
            .forEach { cache.remove(it.key, it.value) }
    }
}
