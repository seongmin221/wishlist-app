package app.media

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.Semaphore
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.event.IIOReadWarningListener

data class DeclaredImage(val byteSize: Int, val width: Int, val height: Int)

sealed interface ImageVerdict {
    data class Valid(val byteSize: Int, val width: Int, val height: Int) : ImageVerdict
    data class Invalid(val reason: MediaFailureReason) : ImageVerdict
}

/**
 * Checks an uploaded object against its declaration. Dimensions are confirmed from the single SOF before the full decode,
 * so a small file cannot claim a huge raster. Decoder warnings count as failures because the JDK reader fills truncated
 * entropy data instead of throwing.
 */
class MediaImageValidator(maxConcurrent: Int = 4) {
    private val permits = Semaphore(maxConcurrent)

    init { ImageIO.setUseCache(false) }

    fun precheck(stored: StoredObject, declared: DeclaredImage): MediaFailureReason? = when {
        stored.byteSize > MediaLimits.MAX_BYTES || stored.byteSize != declared.byteSize.toLong() -> MediaFailureReason.SIZE_MISMATCH
        stored.contentType != MediaLimits.CONTENT_TYPE -> MediaFailureReason.CONTENT_TYPE_MISMATCH
        else -> null
    }

    fun verify(bytes: ByteArray, declared: DeclaredImage): ImageVerdict {
        if (bytes.size != declared.byteSize || bytes.size > MediaLimits.MAX_BYTES) return ImageVerdict.Invalid(MediaFailureReason.SIZE_MISMATCH)
        val header = when (val inspection = JpegInspector.inspect(bytes)) {
            is JpegInspection.Rejected -> return ImageVerdict.Invalid(inspection.reason)
            is JpegInspection.Parsed -> inspection
        }
        if (header.width !in 1..MediaLimits.MAX_EDGE || header.height !in 1..MediaLimits.MAX_EDGE)
            return ImageVerdict.Invalid(MediaFailureReason.DIMENSIONS_EXCEEDED)
        if (header.width != declared.width || header.height != declared.height) return ImageVerdict.Invalid(MediaFailureReason.DIMENSIONS_MISMATCH)
        if (!decodes(bytes, header.width, header.height)) return ImageVerdict.Invalid(MediaFailureReason.DECODE_FAILED)
        return ImageVerdict.Valid(bytes.size, header.width, header.height)
    }

    /** Bounds concurrent decodes; storage calls stay outside. */
    fun <T> gated(block: () -> T): T {
        if (!permits.tryAcquire()) throw MediaException("MEDIA_BUSY", retryAfterSeconds = 1)
        try { return block() } finally { permits.release() }
    }

    private fun decodes(bytes: ByteArray, width: Int, height: Int): Boolean {
        val reader: ImageReader = ImageIO.getImageReadersByFormatName("jpeg").next()
        return try {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                reader.setInput(input, true, true)
                var warned = false
                reader.addIIOReadWarningListener(IIOReadWarningListener { _, _ -> warned = true })
                if (reader.getWidth(0) != width || reader.getHeight(0) != height) return false
                reader.read(0)
                !warned
            }
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        } finally {
            reader.dispose()
        }
    }
}
