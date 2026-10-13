package app.testutil

import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/** JPEG byte fixtures. Real images come from ImageIO; structural variants are synthetic marker streams. */
object MediaFixtures {
    fun jpeg(width: Int, height: Int, type: Int = BufferedImage.TYPE_INT_RGB): ByteArray = encode(image(width, height, type), progressive = false)
    fun grayscale(width: Int, height: Int): ByteArray = jpeg(width, height, BufferedImage.TYPE_BYTE_GRAY)
    fun progressive(width: Int, height: Int): ByteArray = encode(image(width, height, BufferedImage.TYPE_INT_RGB), progressive = true)

    fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(image(width, height, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()

    /** Inserts a segment right after SOI. */
    fun withSegment(jpeg: ByteArray, marker: Int, payload: ByteArray): ByteArray =
        jpeg.copyOfRange(0, 2) + segment(marker, payload) + jpeg.copyOfRange(2, jpeg.size)

    fun exif(jpeg: ByteArray) = withSegment(jpeg, 0xE1, "Exif\u0000\u0000".toByteArray() + ByteArray(16))
    fun xmp(jpeg: ByteArray) = withSegment(jpeg, 0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta/>".toByteArray())
    fun iccProfile(jpeg: ByteArray) = withSegment(jpeg, 0xE2,
        "ICC_PROFILE\u0000".toByteArray() + byteArrayOf(1, 1) + ICC_Profile.getInstance(ColorSpace.CS_sRGB).data)
    fun mpf(jpeg: ByteArray) = withSegment(jpeg, 0xE2, "MPF\u0000".toByteArray() + ByteArray(8))
    fun iptc(jpeg: ByteArray) = withSegment(jpeg, 0xED, "Photoshop 3.0\u0000".toByteArray() + ByteArray(8))
    fun adobe(jpeg: ByteArray) = withSegment(jpeg, 0xEE, "Adobe".toByteArray() + byteArrayOf(0, 100, 0, 0, 0, 0, 1))
    fun comment(jpeg: ByteArray) = withSegment(jpeg, 0xFE, "hello".toByteArray())

    /** A secondary image appended after EOI, as MPF writers do; the file still ends in FFD9. */
    fun withTrailingImage(jpeg: ByteArray) = jpeg + jpeg(8, 8)
    fun withTrailingByte(jpeg: ByteArray) = jpeg + byteArrayOf(0)
    fun withoutEoi(jpeg: ByteArray) = jpeg.copyOfRange(0, jpeg.size - 2)

    /** Cuts the entropy data in half and closes the stream with EOI so only the decoder notices. */
    fun truncatedAfterSos(jpeg: ByteArray): ByteArray {
        val scan = indexOfMarker(jpeg, 0xDA)
        val cut = scan + (jpeg.size - scan) / 2
        return jpeg.copyOfRange(0, cut) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    /** Doubles the FF prefix of the first marker after SOI and APP0 (legal fill bytes). */
    fun withFillBytes(jpeg: ByteArray): ByteArray {
        val at = indexOfMarker(jpeg, 0xDB)
        return jpeg.copyOfRange(0, at) + byteArrayOf(0xFF.toByte(), 0xFF.toByte()) + jpeg.copyOfRange(at, jpeg.size)
    }

    fun withTem(jpeg: ByteArray) = jpeg.copyOfRange(0, 2) + byteArrayOf(0xFF.toByte(), 0x01) + jpeg.copyOfRange(2, jpeg.size)

    /** Synthetic marker stream for inspector-only cases; it is not decodable. */
    fun synthetic(sofMarker: Int = 0xC0, precision: Int = 8, components: Int = 3, width: Int = 16, height: Int = 16,
                  sofCount: Int = 1, scans: Int = 1, betweenScans: ByteArray = ByteArray(0),
                  entropy: ByteArray = byteArrayOf(0x12, 0xFF.toByte(), 0x00, 0x34, 0xFF.toByte(), 0xD3.toByte(), 0x56)): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        out.write(segment(0xE0, "JFIF\u0000".toByteArray() + byteArrayOf(1, 1, 0, 0, 1, 0, 1, 0, 0)))
        val sof = byteArrayOf(precision.toByte(), (height shr 8).toByte(), height.toByte(), (width shr 8).toByte(), width.toByte(),
            components.toByte()) + (1..components).flatMap { listOf(it.toByte(), 0x11, 0) }.toByteArray()
        repeat(sofCount) { out.write(segment(sofMarker, sof)) }
        repeat(scans) { index ->
            if (index > 0) out.write(betweenScans)
            out.write(segment(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0)))
            out.write(entropy)
        }
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    private fun indexOfMarker(bytes: ByteArray, marker: Int): Int =
        (2 until bytes.size - 1).first { bytes[it] == 0xFF.toByte() && bytes[it + 1] == marker.toByte() }

    private fun image(width: Int, height: Int, type: Int) = BufferedImage(width, height, type).also { image ->
        for (x in 0 until width) for (y in 0 until height) image.setRGB(x, y, ((x * 7) shl 16) or ((y * 5) shl 8) or ((x + y) and 0xFF))
    }

    private fun encode(image: BufferedImage, progressive: Boolean): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam
            if (progressive) param.progressiveMode = ImageWriteParam.MODE_DEFAULT
            writer.write(null, IIOImage(image, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }
}
