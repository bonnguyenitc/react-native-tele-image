package com.teleimage

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * TeleImageProgressiveLoader — Progressive JPEG download with per-chunk callbacks.
 *
 * Progressive JPEG architecture (ported from Telegram's approach):
 *
 * Telegram's FileLoadOperation.didChangedLoadProgress() (FileLoader.java line 173)
 * fires as bytes arrive. The UI shows the current available bytes via
 * ImageLoader.CacheOutTask which decodes what's available on each callback.
 *
 * Our implementation:
 *   1. OkHttp ResponseBody.source() → Okio BufferedSource for streaming read
 *   2. Read chunks of CHUNK_SIZE bytes
 *   3. After each chunk, attempt BitmapFactory.decodeByteArray() on accumulated bytes
 *      - JPEG is a forward-compatible format: partial bytes produce a partial image
 *   4. Dispatch onProgress + onPartialBitmap callbacks to UI thread
 *   5. On completion, dispatch final full-resolution bitmap
 *
 * This eliminates the "blank → image" jump — users see a blurry-then-sharp reveal.
 *
 * Note: Progressive decode works best with progressive JPEG encoding (libjpeg `-progressive`).
 * For baseline JPEG, partial decode shows the top portion of the image (top-to-bottom scan).
 */
object TeleImageProgressiveLoader {

    private const val TAG = "TeleImage.Progressive"
    private const val CHUNK_SIZE = 8 * 1024  // 8KB per read — same as Telegram's read buffer

    interface ProgressiveCallback {
        /** Called with each decoded partial bitmap. May be called 0+ times before onComplete. */
        fun onPartialBitmap(bitmap: Bitmap, progress: Float, url: String)
        /** Called with the final decoded bitmap. */
        fun onComplete(bitmap: Bitmap, url: String)
        /** Called on network or decode error. */
        fun onError(url: String, message: String)

    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)   // Telegram HttpImageTask line 577
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Telegram User-Agent (HttpImageTask line 576)
    private const val USER_AGENT =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 10_0 like Mac OS X) " +
        "AppleWebKit/602.1.38 (KHTML, like Gecko) Version/10.0 Mobile/14A5297c Safari/602.1"

    private val inFlight = ConcurrentHashMap<String, Call>()

    /**
     * Start a progressive download.
     *
     * @param url         image URL
     * @param cacheDir    disk cache directory
     * @param viewWidth   target view width in pixels — used to calculate inSampleSize
     * @param viewHeight  target view height in pixels — used to calculate inSampleSize
     * @param callback    result callbacks (called from background thread — post to main as needed)
     */
    fun load(url: String, cacheDir: File?, viewWidth: Int = 0, viewHeight: Int = 0, callback: ProgressiveCallback) {
        val req = Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)  // Telegram HttpImageTask line 576
            .build()
        val call = client.newCall(req)
        inFlight[url] = call



        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                inFlight.remove(url)
                if (!call.isCanceled()) callback.onError(url, e.message ?: "Network error")
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                if (!response.isSuccessful) {
                    inFlight.remove(url)
                    callback.onError(url, "HTTP ${response.code}")
                    return
                }

                val body = response.body ?: run {
                    inFlight.remove(url)
                    callback.onError(url, "Empty body")
                    return
                }

                val contentLength = body.contentLength()  // -1 if unknown
                val source = body.source()
                val accumulator = Buffer()
                var loaded = 0L
                var lastProgressBitmap = 0f

                try {
                    while (true) {
                        if (call.isCanceled()) break

                        // Read one chunk — mirrors Telegram's 8KB buffer read
                        val read = source.read(accumulator, CHUNK_SIZE.toLong())
                        if (read == -1L) break

                        loaded += read



                        // Progressive bitmap decode — attempt every 20% progress or every 32KB
                        val progress = if (contentLength > 0) loaded.toFloat() / contentLength else -1f
                        val progressDelta = if (progress > 0) progress - lastProgressBitmap else 0f
                        if (loaded % (CHUNK_SIZE * 4) == 0L || progressDelta >= 0.2f) {
                            // snapshot() creates an independent copy — avoids "re-fill a
                            // memory-mapped stream" error that occurs with peek() because
                            // libjpeg/SkCodec tries to re-seek data in the consumed buffer.
                            val bytes = accumulator.snapshot().toByteArray()
                            val partialBitmap = tryDecodeBitmap(bytes, viewWidth, viewHeight)
                            if (partialBitmap != null) {
                                // Note: we do NOT recycle previous partials here.
                                // After inSampleSize downsampling, each partial is ~260KB (RGB_565).
                                // Recycling on the background thread would race with the UI thread
                                // which may still be drawing the previous partial via a pending post().
                                // GC handles these small allocations without pressure.
                                lastProgressBitmap = progress.coerceAtLeast(0f)
                                callback.onPartialBitmap(partialBitmap, progress.coerceIn(0f, 1f), url)
                            }
                        }
                    }

                    if (call.isCanceled()) return

                    // Final decode from complete bytes — use view-sized downsampling
                    val allBytes = accumulator.readByteArray()
                    val finalBitmap = tryDecodeBitmap(allBytes, viewWidth, viewHeight)

                    if (finalBitmap != null) {
                        // Write disk cache (temp→final, Telegram pattern)
                        if (cacheDir != null) {
                            writeDiskCache(url, allBytes, cacheDir)
                        }
                        // Write memory cache
                        TeleImageMemCache.getInstance().put(
                            TeleImageHttpTask.cacheKey(url),
                            android.graphics.drawable.BitmapDrawable(Resources.getSystem(), finalBitmap)
                        )
                        callback.onComplete(finalBitmap, url)
                    } else {
                        callback.onError(url, "Decode failed")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Progressive load error: $url", e)
                    callback.onError(url, e.message ?: "Unknown error")
                } finally {
                    inFlight.remove(url)
                    body.close()
                }
            }
        })
    }

    fun cancel(url: String) {
        inFlight.remove(url)?.cancel()
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Calculate the optimal inSampleSize power-of-2 for downsampling.
     *
     * Mirrors the standard Android pattern from:
     * https://developer.android.com/topic/performance/graphics/load-bitmap
     *
     * For a 3840×2160 image displayed in a 400×240 view:
     *   inSampleSize = 8 → decodes to 480×270 (~500KB vs ~33MB)
     */
    private fun calculateInSampleSize(outWidth: Int, outHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        if (reqWidth <= 0 && reqHeight <= 0) return 1
        var inSampleSize = 1
        val w = if (reqWidth > 0) reqWidth else reqHeight
        val h = if (reqHeight > 0) reqHeight else reqWidth
        if (outHeight > h || outWidth > w) {
            val halfHeight = outHeight / 2
            val halfWidth = outWidth / 2
            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while (halfHeight / inSampleSize >= h && halfWidth / inSampleSize >= w) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * Attempt to decode a possibly-partial JPEG byte array.
     * Returns null if bytes are insufficient for any valid frame.
     *
     * BitmapFactory.decodeByteArray() is lenient — for a progressive JPEG
     * it returns whatever scan lines it can decode from partial data.
     * For baseline JPEG it returns the top N rows.
     *
     * When viewWidth/viewHeight are provided, calculates inSampleSize to
     * downsample the image to roughly the view size, reducing memory from
     * ~33MB (4K ARGB_8888) to ~500KB.
     */
    private fun tryDecodeBitmap(bytes: ByteArray, viewWidth: Int = 0, viewHeight: Int = 0): Bitmap? {
        if (bytes.size < 512) return null  // Too small to be decodable
        return try {
            val opts = BitmapFactory.Options()

            // First pass: decode bounds only (no pixel allocation)
            if (viewWidth > 0 || viewHeight > 0) {
                opts.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, viewWidth, viewHeight)
                opts.inJustDecodeBounds = false
            }

            opts.inPreferredConfig = Bitmap.Config.RGB_565  // 2 bytes/pixel vs 4 for ARGB_8888
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "OOM during partial decode, skipping", e)
            null
        }
    }

    private fun writeDiskCache(url: String, bytes: ByteArray, cacheDir: File) {
        try {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val key = TeleImageHttpTask.cacheKey(url)
            val tmp = File(cacheDir, "$key.tmp")
            val dest = File(cacheDir, "$key.jpg")
            tmp.writeBytes(bytes)
            tmp.renameTo(dest)
        } catch (e: Exception) {
            Log.w(TAG, "Disk cache write failed: $url", e)
        }
    }
}
