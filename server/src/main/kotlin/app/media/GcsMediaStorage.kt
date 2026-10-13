package app.media

import com.google.api.gax.retrying.RetrySettings
import com.google.auth.Credentials
import com.google.api.core.ApiClock
import com.google.cloud.http.HttpTransportOptions
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.HttpMethod
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageException
import com.google.cloud.storage.StorageOptions
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Private Cloud Storage bucket. Uploads are V4 signed PUTs whose create-only, content type and size range headers are
 * signed, so READY bytes cannot be replaced later. RPCs get one attempt within 5 seconds; signing on Cloud Run is an IAM
 * signBlob call, bounded by the caller through [MediaSigningExecutor].
 */
class GcsMediaStorage internal constructor(
    private val bucket: String,
    private val storage: Storage,
    private val now: () -> Instant,
) : MediaStorage, AutoCloseable {
    override fun uploadTarget(objectKey: String, contentType: String, expiresAt: Instant): UploadTarget {
        val headers = MediaKeys.uploadHeaders(contentType)
        // expiresAt is the DB deadline; the app clock only sizes the signature, so a small skew shifts URL expiry slightly.
        val seconds = maxOf(1, Duration.between(now(), expiresAt).seconds)
        val url = storage.signUrl(BlobInfo.newBuilder(bucket, objectKey).build(), seconds, TimeUnit.SECONDS,
            Storage.SignUrlOption.httpMethod(HttpMethod.PUT), Storage.SignUrlOption.withV4Signature(),
            Storage.SignUrlOption.withExtHeaders(headers))
        return UploadTarget("PUT", url.toString(), headers, expiresAt)
    }

    override fun readUrl(objectKey: String, validFor: Duration): SignedRead {
        val issued = now()
        val url = storage.signUrl(BlobInfo.newBuilder(bucket, objectKey).build(), validFor.seconds, TimeUnit.SECONDS,
            Storage.SignUrlOption.httpMethod(HttpMethod.GET), Storage.SignUrlOption.withV4Signature())
        return SignedRead(url.toString(), issued.plus(validFor))
    }

    override fun stat(objectKey: String): StoredObject? = remote {
        storage.get(BlobId.of(bucket, objectKey), Storage.BlobGetOption.fields(Storage.BlobField.SIZE, Storage.BlobField.CONTENT_TYPE))
            ?.let { blob -> StoredObject(blob.size ?: throw MediaStorageException(), blob.contentType) }
    }

    override fun read(objectKey: String, maxBytes: Int): ByteArray = remote {
        storage.reader(BlobId.of(bucket, objectKey)).use { channel -> readBounded(channel, maxBytes) }
    }

    override fun delete(objectKey: String) { remote { storage.delete(BlobId.of(bucket, objectKey)) } }

    override fun close() = storage.close()

    private fun <T> remote(block: () -> T): T = try {
        block()
    } catch (error: StorageException) {
        throw MediaStorageException(error)
    } catch (error: java.io.IOException) {
        throw MediaStorageException(error)
    }

    companion object {
        /** Fills a [maxBytes] buffer and probes one more byte; the usual exact-size read returns the buffer without a copy. */
        internal fun readBounded(channel: ReadableByteChannel, maxBytes: Int): ByteArray {
            val buffer = ByteBuffer.allocate(maxBytes)
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
            if (!buffer.hasRemaining()) {
                val probe = ByteBuffer.allocate(1)
                while (probe.hasRemaining()) if (channel.read(probe) < 0) return buffer.array()
                throw MediaObjectTooLarge()
            }
            return buffer.array().copyOf(buffer.position())
        }
        private val retry = RetrySettings.newBuilder().setMaxAttempts(1)
            .setTotalTimeoutDuration(Duration.ofSeconds(5)).build()
        private val transport = HttpTransportOptions.newBuilder().setConnectTimeout(5_000).setReadTimeout(5_000).build()

        /** Application Default Credentials; on Cloud Run signing uses the service account through IAM signBlob. */
        fun create(bucket: String): GcsMediaStorage =
            GcsMediaStorage(bucket, StorageOptions.http().setRetrySettings(retry).setTransportOptions(transport).build().service, Instant::now)

        /** Signs locally with the given key and never calls the network for signing; tests only. */
        internal fun offline(bucket: String, credentials: Credentials, now: () -> Instant): GcsMediaStorage {
            val options = StorageOptions.http().setProjectId("offline").setCredentials(credentials)
                .setClock(object : ApiClock {
                    override fun nanoTime() = now().toEpochMilli() * 1_000_000
                    override fun millisTime() = now().toEpochMilli()
                }).setRetrySettings(retry).setTransportOptions(transport).build()
            return GcsMediaStorage(bucket, options.service, now)
        }
    }
}
