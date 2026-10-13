package app.media

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Display URLs valid for an hour, reused while at least 45 minutes remain, so a list does not issue one signBlob per
 * item. Misses are signed on the shared [MediaSigningExecutor]; one response waits at most [budget] in total, and a
 * signature that finishes late still lands in the cache for the next request.
 */
class CachingMediaUrlSigner(
    private val storage: MediaStorage,
    private val signing: MediaSigningExecutor,
    private val clock: () -> Instant = Instant::now,
    private val validFor: Duration = MediaLimits.READ_VALIDITY,
    private val reuseWhile: Duration = MediaLimits.READ_REUSE_MINIMUM,
    private val maxEntries: Int = 10_000,
    private val budget: Duration = signing.wait,
) : MediaUrlSigner {
    private val cache = ConcurrentHashMap<String, SignedRead>()

    override fun sign(objectKey: String): SignedRead? =
        cache[objectKey]?.takeIf(::fresh) ?: signing.await(signed(objectKey), System.nanoTime() + budget.toNanos())

    override fun signAll(objectKeys: Set<String>): MediaUrls {
        val deadline = System.nanoTime() + budget.toNanos()
        val hits = objectKeys.mapNotNull { key -> cache[key]?.takeIf(::fresh)?.let { key to it } }.toMap()
        val pending = (objectKeys - hits.keys).associateWith(::signed)
        val signed = pending.mapNotNull { (key, future) -> signing.await(future, deadline)?.let { key to it } }.toMap()
        return MediaUrls(hits + signed)
    }

    private fun signed(key: String) = signing.shared(key) { storage.readUrl(key, validFor).also { remember(key, it) } }

    private fun fresh(read: SignedRead) = Duration.between(clock(), read.expiresAt) >= reuseWhile

    private fun remember(key: String, read: SignedRead) {
        cache[key] = read
        while (cache.size > maxEntries) {
            val oldest = cache.entries.minByOrNull { it.value.expiresAt } ?: break
            cache.remove(oldest.key, oldest.value)
        }
    }
}
