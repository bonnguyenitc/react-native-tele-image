package com.teleimage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.View
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.Event

/**
 * TeleImageView — thin Android View shell wrapping TeleImageReceiver.
 *
 * All draw logic is delegated to TeleImageReceiver, which is a direct port
 * of Telegram's ImageReceiver.java (draw/drawDrawable/checkAlphaAnimation).
 *
 * This view handles only:
 *   - View lifecycle (attach/detach/sizeChanged)
 *   - Prop updates from React Native (uri, thumbhash, priority, resizeMode)
 *   - Image loading via TeleImageProgressiveLoader + OkHttp
 *   - Animated WebP/GIF support (AnimatedImageDrawable, API 28+)
 */
class TeleImageView(context: Context) : View(context) {

    companion object {
        private const val TAG = "TeleImage.View"
        const val DEFAULT_CROSSFADE_DURATION = TeleImageReceiver.DEFAULT_CROSSFADE_DURATION
    }

    // ── Telegram ImageReceiver — all drawing delegated here ───────────────────
    private val receiver = TeleImageReceiver(this).also {
        it.setDelegate { invalidate() }  // checkAlphaAnimation() → invalidate
    }

    // ── Phase 3: Animated WebP/GIF (API 28+) ──────────────────────────────────
    private var animatedDrawable: Drawable? = null

    // ── Props ─────────────────────────────────────────────────────────────────
    var uri: String? = null
        set(value) { if (field != value) { field = value; loadImage() } }

    var thumbhash: String? = null
        set(value) { if (field != value) { field = value; decodeThumb() } }

    var priority: Int = TeleImageHttpTask.PRIORITY_NORMAL

    var resizeMode: String = "cover"
        set(value) {
            field = value
            receiver.setAspectFit(value == "contain")
            invalidate()
        }

    var fadeDuration: Int = DEFAULT_CROSSFADE_DURATION
        set(value) {
            field = value
            receiver.setCrossfadeDuration(value)
        }



    private var currentRequestUrl: String? = null

    // Defer network load until view dimensions are known (onSizeChanged).
    // React Native sets props (uri) BEFORE layout, so width/height = 0 at first call.
    private var pendingLoad = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!receiver.hasImageLoaded() && uri != null) loadImage()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            (animatedDrawable as? AnimatedImageDrawable)?.start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        currentRequestUrl?.let {
            TeleImageHttpTask.getInstance().cancel(it)
            TeleImageProgressiveLoader.cancel(it)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            (animatedDrawable as? AnimatedImageDrawable)?.stop()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // ImageReceiver.setImageCoords() (line 2372)
        receiver.setImageCoords(0f, 0f, w.toFloat(), h.toFloat())
        animatedDrawable?.setBounds(0, 0, w, h)
        invalidate()
        // If uri was set before layout, trigger the deferred load now that we have dimensions.
        if (pendingLoad && w > 0 && h > 0) {
            pendingLoad = false
            loadImage()
        }
    }

    // ── onDraw — delegates entirely to TeleImageReceiver ─────────────────────
    override fun onDraw(canvas: Canvas) {
        // Animated drawable overrides receiver draw
        animatedDrawable?.let { anim ->
            anim.draw(canvas)
            return
        }
        // Telegram ImageReceiver.draw(canvas) — exact port
        receiver.draw(canvas)
    }

    // ── Thumb decode ──────────────────────────────────────────────────────────

    private fun decodeThumb() {
        val th = thumbhash ?: return
        try {
            val bytes = Base64.decode(th, Base64.DEFAULT)
            val bmp = when {
                bytes.isNotEmpty() && bytes[0] == 0x01.toByte() ->
                    TeleImageStrippedDecoder.decode(bytes, true)
                else -> ThumbHashDecoder.decode(bytes)
            }
            if (bmp != null) {
                receiver.setStaticThumb(bmp)  // ImageReceiver.staticThumbDrawable
            }
        } catch (e: Exception) {
            Log.w(TAG, "Thumb decode failed", e)
        }
    }

    // ── Image loading ──────────────────────────────────────────────────────────

    private fun loadImage() {
        val url = uri ?: return

        // Defer until view has been laid out — avoids decoding at inSampleSize=1 (full 4K).
        // onSizeChanged will call loadImage() once dimensions are available.
        if (width == 0 && height == 0) {
            pendingLoad = true
            return
        }
        pendingLoad = false

        // ── Memory cache hit: crossfade in immediately, no blank flash, no network ──
        // TeleImageProgressiveLoader does not read the memory cache, so we check here
        // before blanking the view. Mirrors Telegram's ImageLoader memCache fast-path.
        val memKey = TeleImageHttpTask.cacheKey(url)
        val cached = TeleImageMemCache.getInstance().get(memKey)
        if (cached != null && cached.bitmap != null && !cached.bitmap.isRecycled) {
            currentRequestUrl?.let {
                TeleImageHttpTask.getInstance().cancel(it)
                TeleImageProgressiveLoader.cancel(it)
            }
            currentRequestUrl = null
            // setImageBitmap() crossfades from old → cached (no reset/blank)
            receiver.setImageBitmap(cached.bitmap)
            emitLoad(cached.bitmap.width, cached.bitmap.height, url)
            return
        }

        // ── Disk cache hit: decode from file, no network ─────────────────────────
        // Telegram's ImageLoader checks: memCache → disk → network.
        // TeleImageProgressiveLoader only does network, so we check disk here.
        val diskFile = java.io.File(context.cacheDir, "${TeleImageHttpTask.cacheKey(url)}.jpg")
        if (diskFile.exists()) {
            currentRequestUrl?.let {
                TeleImageHttpTask.getInstance().cancel(it)
                TeleImageProgressiveLoader.cancel(it)
            }
            currentRequestUrl = null
            // Decode on background, post result to UI.
            // Use screen width as fallback if view hasn't been laid out yet
            // (pendingLoad guard should prevent this, but defense-in-depth).
            val screenW = resources.displayMetrics.widthPixels
            val viewW = if (width > 0) width else screenW
            val viewH = if (height > 0) height else (screenW * 9 / 16)  // assume 16:9 fallback
            Thread {
                try {
                    // Calculate inSampleSize to avoid decoding full 4K from disk
                    val opts = android.graphics.BitmapFactory.Options()
                    opts.inJustDecodeBounds = true
                    android.graphics.BitmapFactory.decodeFile(diskFile.absolutePath, opts)
                    opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, viewW, viewH)
                    opts.inJustDecodeBounds = false
                    opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    val bmp = android.graphics.BitmapFactory.decodeFile(diskFile.absolutePath, opts)
                    if (bmp != null && !bmp.isRecycled) {
                        // Refill memory cache so next hit is instant
                        TeleImageMemCache.getInstance().put(
                            memKey,
                            android.graphics.drawable.BitmapDrawable(resources, bmp)
                        )
                        post {
                            if (url == uri && !bmp.isRecycled) {
                                receiver.setImageBitmap(bmp)
                                emitLoad(bmp.width, bmp.height, url)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Disk file corrupted — fall through to network on next loadImage call
                }
            }.start()
            return
        }

        // ── Full cache miss: cancel old request, blank view, start network load ─────
        currentRequestUrl?.let {
            TeleImageHttpTask.getInstance().cancel(it)
            TeleImageProgressiveLoader.cancel(it)
        }
        currentRequestUrl = url
        receiver.reset()

        if (TeleImageHttpTask.getInstance().cacheDir == null) {
            TeleImageHttpTask.getInstance().cacheDir = context.cacheDir
        }

        // Progressive loader — OkHttp streaming with partial decode callbacks
        TeleImageProgressiveLoader.load(url, context.cacheDir, width, height,
            object : TeleImageProgressiveLoader.ProgressiveCallback {



                override fun onPartialBitmap(bitmap: Bitmap, progress: Float, url: String) {
                    if (url != uri || bitmap.isRecycled) return
                    post {
                        // Show partial frame instantly (no fade) via setPartialBitmap.
                        // fadeDuration only applies to the final full-res image in onComplete.
                        if (!bitmap.isRecycled) {
                            receiver.setPartialBitmap(bitmap)
                        }
                    }
                }

                override fun onComplete(bitmap: Bitmap, url: String) {
                    if (url != uri || bitmap.isRecycled) return
                    post {
                        if (bitmap.isRecycled) return@post
                        // Check for animated format first
                        val cachedFile = java.io.File(
                            context.cacheDir,
                            "${TeleImageHttpTask.cacheKey(url)}.jpg"
                        )
                        if (TeleImageAnimatedDecoder.isAnimated(cachedFile)) {
                            val anim = TeleImageAnimatedDecoder.decode(cachedFile)
                            if (anim != null) {
                                applyAnimated(anim, url)
                                return@post
                            }
                        }
                        // Standard bitmap — hand to TeleImageReceiver for crossfade
                        receiver.setImageBitmap(bitmap)
                        emitLoad(bitmap.width, bitmap.height, url)
                    }
                }

                override fun onError(url: String, message: String) {
                    if (url != uri) return
                    post { emitError(message, url) }
                }
            })
    }

    private fun applyAnimated(drawable: Drawable, url: String) {
        animatedDrawable = drawable
        drawable.setBounds(0, 0, width, height)
        drawable.callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) = invalidate()
            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) { post(what) }
            override fun unscheduleDrawable(who: Drawable, what: Runnable) { removeCallbacks(what) }
        }
        invalidate()
        emitLoad(drawable.intrinsicWidth, drawable.intrinsicHeight, url)
    }

    /**
     * Calculate the optimal inSampleSize power-of-2 for downsampling.
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
            while (halfHeight / inSampleSize >= h && halfWidth / inSampleSize >= w) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    // ── Fabric event emission ─────────────────────────────────────────────────

    private fun emitLoad(width: Int, height: Int, source: String) {
        val reactContext = context as? ReactContext ?: return
        val dispatcher = UIManagerHelper.getEventDispatcherForReactTag(reactContext, id) ?: return
        dispatcher.dispatchEvent(
            TeleImageLoadEvent(UIManagerHelper.getSurfaceId(reactContext), id, width, height, source)
        )
    }

    private fun emitError(error: String, source: String) {
        val reactContext = context as? ReactContext ?: return
        val dispatcher = UIManagerHelper.getEventDispatcherForReactTag(reactContext, id) ?: return
        dispatcher.dispatchEvent(
            TeleImageErrorEvent(UIManagerHelper.getSurfaceId(reactContext), id, error, source)
        )
    }

    // ── Event classes ─────────────────────────────────────────────────────────

    private class TeleImageLoadEvent(
        surfaceId: Int, viewId: Int,
        private val width: Int, private val height: Int, private val source: String
    ) : Event<TeleImageLoadEvent>(surfaceId, viewId) {
        override fun getEventName() = "topLoad"
        override fun getEventData() = Arguments.createMap().apply {
            putInt("width", width)
            putInt("height", height)
            putString("source", source)
        }
    }

    private class TeleImageErrorEvent(
        surfaceId: Int, viewId: Int,
        private val error: String, private val source: String
    ) : Event<TeleImageErrorEvent>(surfaceId, viewId) {
        override fun getEventName() = "topError"
        override fun getEventData() = Arguments.createMap().apply {
            putString("error", error)
            putString("source", source)
        }
    }
}
