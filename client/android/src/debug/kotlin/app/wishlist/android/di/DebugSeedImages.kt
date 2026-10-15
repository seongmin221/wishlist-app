package app.wishlist.android.di

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import coil3.EventListener
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.Uri
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * DEBUG only: answers the C5 fixture's placeholder photo URLs (`https://seed.wishlist.invalid/…`,
 * `DebugControls.seedLongCategory`) without a network, so the 120-item list shows the D10 cases:
 * `/tall/` is a 1:4 photo, `/slow/` a square that comes after 5 s (past the 3 s skeleton timeout),
 * anything else on that host fails. Other hosts go to the normal network fetcher.
 */
internal object DebugSeedImages {
    const val HOST = "seed.wishlist.invalid"
    private const val SLOW_MS = 5_000L

    fun install(context: Context) {
        SingletonImageLoader.setSafe {
            ImageLoader.Builder(context).components { add(Factory()) }.eventListener(ImageEvents).build()
        }
    }

    /**
     * C5 PR B device check: a loader request (`start`) and a fetch (`fetch`: the photo was not in the memory
     * cache and is downloaded or, for the seed host, made) are logged apart, with the request's size.
     */
    private object ImageEvents : EventListener() {
        override fun onStart(request: ImageRequest) {
            Log.i(IMAGE_TAG, "start ${request.data} ${request.sizeResolver}")
        }

        override fun fetchStart(request: ImageRequest, fetcher: Fetcher, options: Options) {
            Log.i(IMAGE_TAG, "fetch ${request.data} ${options.size}")
        }
    }

    private const val IMAGE_TAG = "WishlistImages"

    private class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.authority == HOST) SeedFetcher(data.path.orEmpty()) else null
    }

    private class SeedFetcher(private val path: String) : Fetcher {
        override suspend fun fetch(): FetchResult {
            val (w, h) = when {
                path.startsWith("/tall/") -> 300 to 1200
                path.startsWith("/slow/") -> { delay(SLOW_MS); 600 to 600 }
                else -> throw IOException("no debug seed image for $path")
            }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.rgb(0x7A, 0x6B, 0x5B))
                val paint = Paint().apply { color = Color.rgb(0xE9, 0xDF, 0xD2); strokeWidth = 12f }
                drawLine(0f, 0f, w.toFloat(), h.toFloat(), paint)
                drawLine(w.toFloat(), 0f, 0f, h.toFloat(), paint)
            }
            return ImageFetchResult(bitmap.asImage(), isSampled = false, dataSource = DataSource.MEMORY)
        }
    }
}
