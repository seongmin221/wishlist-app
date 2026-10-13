package app.media

sealed interface JpegInspection {
    data class Parsed(val width: Int, val height: Int) : JpegInspection
    data class Rejected(val reason: MediaFailureReason) : JpegInspection
}

/**
 * Walks every marker from SOI to the first EOI without decoding. Progressive files may place segments between scans, so
 * the walk does not stop at SOS, and MPF secondary images also end in FFD9, so EOI is the first one met by the walk rather
 * than the last two bytes. Structural failures win over unsupported coding, which wins over metadata.
 */
object JpegInspector {
    private val ICC_PROFILE = "ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII)

    fun inspect(bytes: ByteArray): JpegInspection {
        if (bytes.size < 4 || u8(bytes, 0) != 0xFF || u8(bytes, 1) != 0xD8) return notJpeg
        var unsupported = false
        var metadata = false
        var sofCount = 0
        var scans = 0
        var width = 0
        var height = 0
        var at = 2
        var inScan = false
        while (true) {
            if (inScan) {
                // Entropy data: FF00 is a stuffed byte and RSTn stays inside the scan; any other FFxx starts a marker.
                at = nextMarkerInScan(bytes, at) ?: return notJpeg
                inScan = false
            }
            if (at >= bytes.size || u8(bytes, at) != 0xFF) return notJpeg
            while (at < bytes.size && u8(bytes, at) == 0xFF) at++  // fill bytes before the marker code
            if (at >= bytes.size) return notJpeg
            val marker = u8(bytes, at++)
            when {
                marker == 0xD9 -> {
                    if (sofCount == 0 || scans == 0) return notJpeg
                    if (at < bytes.size) metadata = true
                    return when {
                        unsupported -> JpegInspection.Rejected(MediaFailureReason.UNSUPPORTED_JPEG)
                        metadata -> JpegInspection.Rejected(MediaFailureReason.METADATA_PRESENT)
                        else -> JpegInspection.Parsed(width, height)
                    }
                }
                marker == 0x01 || marker in 0xD0..0xD7 -> Unit  // TEM and RSTn carry no length
                marker == 0xD8 || marker == 0x00 -> return notJpeg
                else -> {
                    if (at + 2 > bytes.size) return notJpeg
                    val length = (u8(bytes, at) shl 8) or u8(bytes, at + 1)
                    if (length < 2 || at + length > bytes.size) return notJpeg
                    val payload = at + 2
                    val payloadSize = length - 2
                    when (marker) {
                        0xC0, 0xC1, 0xC2 -> {
                            sofCount++
                            if (payloadSize < 6) return notJpeg
                            val components = u8(bytes, payload + 5)
                            if (payloadSize < 6 + 3 * components) return notJpeg
                            if (sofCount == 1) {
                                height = (u8(bytes, payload + 1) shl 8) or u8(bytes, payload + 2)
                                width = (u8(bytes, payload + 3) shl 8) or u8(bytes, payload + 4)
                            }
                            if (sofCount > 1 || u8(bytes, payload) != 8 || components !in setOf(1, 3)) unsupported = true
                        }
                        0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF -> { sofCount++; unsupported = true }
                        0xDC, 0xDE, 0xDF -> unsupported = true  // DNL and hierarchical markers
                        0xDA -> {
                            if (sofCount == 0) return notJpeg
                            scans++
                            if (scans > MediaLimits.MAX_SCANS) unsupported = true
                            inScan = true
                        }
                        0xE0, 0xEE -> Unit
                        0xE2 -> if (!startsWith(bytes, payload, payloadSize, ICC_PROFILE)) metadata = true
                        in 0xE1..0xEF, 0xFE -> metadata = true
                        0xC4, 0xC8, 0xCC, 0xDB, 0xDD -> Unit
                        else -> return notJpeg
                    }
                    at += length
                }
            }
        }
    }

    private val notJpeg = JpegInspection.Rejected(MediaFailureReason.NOT_JPEG)

    private fun nextMarkerInScan(bytes: ByteArray, from: Int): Int? {
        var at = from
        while (at < bytes.size) {
            if (u8(bytes, at) != 0xFF) { at++; continue }
            var code = at + 1
            while (code < bytes.size && u8(bytes, code) == 0xFF) code++
            if (code >= bytes.size) return null
            val value = u8(bytes, code)
            if (value == 0x00 || value in 0xD0..0xD7) { at = code + 1; continue }
            return code - 1
        }
        return null
    }

    private fun startsWith(bytes: ByteArray, offset: Int, size: Int, prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { bytes[offset + it] == prefix[it] }

    private fun u8(bytes: ByteArray, index: Int) = bytes[index].toInt() and 0xFF
}
