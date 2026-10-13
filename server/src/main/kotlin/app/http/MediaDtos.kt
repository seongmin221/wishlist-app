package app.http

import app.media.MediaLimits
import app.media.MediaStatus
import app.media.MediaView
import app.purpose.PurposeListEntry
import app.wishlist.WishlistItem
import kotlinx.serialization.Serializable

@Serializable data class MediaDto(
    val id: String, val status: MediaStatus, val contentType: String,
    val byteSize: Int, val width: Int, val height: Int,
    /** Upload deadline while PENDING; null once settled. */
    val expiresAt: String?,
)
@Serializable data class MediaUploadDto(val method: String, val url: String, val headers: Map<String, String>, val expiresAt: String)
@Serializable data class MediaDisplayDto(val url: String, val expiresAt: String)
@Serializable data class MediaLimitsDto(val contentTypes: List<String>, val maxBytes: Int, val maxLongEdge: Int)
@Serializable data class MediaResponseDto(val media: MediaDto, val upload: MediaUploadDto?, val display: MediaDisplayDto?, val limits: MediaLimitsDto)

private val LIMITS = MediaLimitsDto(listOf(MediaLimits.CONTENT_TYPE), MediaLimits.MAX_BYTES, MediaLimits.MAX_EDGE)

/** PENDING reports declared values; READY reports verified ones. */
fun MediaView.toDto(): MediaResponseDto {
    val pending = asset.status == MediaStatus.PENDING
    return MediaResponseDto(
        MediaDto(asset.id.toString(), asset.status, asset.contentType,
            asset.byteSize ?: asset.declared.byteSize, asset.width ?: asset.declared.width, asset.height ?: asset.declared.height,
            if (pending) asset.uploadExpiresAt.toString() else null),
        upload?.let { MediaUploadDto(it.method, it.url, it.headers, it.expiresAt.toString()) },
        display?.let { MediaDisplayDto(it.url, it.expiresAt.toString()) },
        LIMITS,
    )
}

/** Object keys of the user images a response will show. */
internal fun Iterable<WishlistItem>.mediaKeys(): Set<String> = mapNotNullTo(mutableSetOf()) { it.mediaObjectKey }
internal fun Iterable<PurposeListEntry>.previewMediaKeys(): Set<String> =
    flatMap { it.previews }.mapNotNullTo(mutableSetOf()) { it.mediaObjectKey }
