package app.media

import app.media.MediaFailureReason.*
import app.testutil.MediaFixtures
import kotlin.test.Test
import kotlin.test.assertEquals

class JpegInspectorTest {
    private fun parsed(width: Int, height: Int) = JpegInspection.Parsed(width, height)
    private fun rejected(reason: MediaFailureReason) = JpegInspection.Rejected(reason)

    @Test fun `accepts baseline progressive grayscale and allowed segments`() {
        assertEquals(parsed(2048, 1536), JpegInspector.inspect(MediaFixtures.jpeg(2048, 1536)))
        assertEquals(parsed(1, 1), JpegInspector.inspect(MediaFixtures.jpeg(1, 1)))
        assertEquals(parsed(40, 30), JpegInspector.inspect(MediaFixtures.grayscale(40, 30)))
        assertEquals(parsed(64, 48), JpegInspector.inspect(MediaFixtures.progressive(64, 48)))
        val base = MediaFixtures.jpeg(32, 24)
        for (variant in listOf(MediaFixtures.iccProfile(base), MediaFixtures.adobe(base), MediaFixtures.withFillBytes(base), MediaFixtures.withTem(base)))
            assertEquals(parsed(32, 24), JpegInspector.inspect(variant))
        assertEquals(parsed(16, 16), JpegInspector.inspect(MediaFixtures.synthetic(sofMarker = 0xC1)))
        // Entropy data carries FF00 stuffing and an RST marker without ending the scan.
        assertEquals(parsed(16, 16), JpegInspector.inspect(MediaFixtures.synthetic()))
    }

    @Test fun `rejects broken structure as not jpeg`() {
        val base = MediaFixtures.jpeg(32, 24)
        val broken = listOf(
            ByteArray(0), byteArrayOf(0xFF.toByte()), MediaFixtures.png(8, 8), base.copyOfRange(2, base.size),
            MediaFixtures.withoutEoi(base), base.copyOfRange(0, 40),
            MediaFixtures.withSegment(base, 0xE0, ByteArray(0)).also { it[4] = 0x7F },  // APP0 length points outside the file
            MediaFixtures.synthetic(sofCount = 0),
        )
        for (bytes in broken) assertEquals(rejected(NOT_JPEG), JpegInspector.inspect(bytes))
    }

    @Test fun `rejects unsupported coding precision components and scan bombs`() {
        for (bytes in listOf(
            MediaFixtures.synthetic(sofMarker = 0xC3), MediaFixtures.synthetic(sofMarker = 0xC9), MediaFixtures.synthetic(sofCount = 2),
            MediaFixtures.synthetic(precision = 12), MediaFixtures.synthetic(components = 4), MediaFixtures.synthetic(components = 2),
            MediaFixtures.synthetic(sofMarker = 0xC2, scans = 65),
        )) assertEquals(rejected(UNSUPPORTED_JPEG), JpegInspector.inspect(bytes))
        assertEquals(parsed(16, 16), JpegInspector.inspect(MediaFixtures.synthetic(sofMarker = 0xC2, scans = 64)))
    }

    @Test fun `rejects metadata segments anywhere and bytes after the first EOI`() {
        val base = MediaFixtures.jpeg(32, 24)
        for (bytes in listOf(
            MediaFixtures.exif(base), MediaFixtures.xmp(base), MediaFixtures.mpf(base), MediaFixtures.iptc(base), MediaFixtures.comment(base),
            MediaFixtures.withTrailingImage(base), MediaFixtures.withTrailingByte(base),
            MediaFixtures.synthetic(sofMarker = 0xC2, scans = 2, betweenScans = MediaFixtures.segment(0xE1, "Exif\u0000\u0000".toByteArray())),
        )) assertEquals(rejected(METADATA_PRESENT), JpegInspector.inspect(bytes))
    }

    @Test fun `structural failure outranks unsupported coding which outranks metadata`() {
        val unsupportedWithMetadata = MediaFixtures.exif(MediaFixtures.synthetic(components = 4))
        assertEquals(rejected(UNSUPPORTED_JPEG), JpegInspector.inspect(unsupportedWithMetadata))
        assertEquals(rejected(NOT_JPEG), JpegInspector.inspect(MediaFixtures.withoutEoi(unsupportedWithMetadata)))
    }
}
