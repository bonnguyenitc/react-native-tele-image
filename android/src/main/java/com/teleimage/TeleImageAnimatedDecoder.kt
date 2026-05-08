package com.teleimage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.widget.ImageView
import java.io.File

/**
 * TeleImageAnimatedDecoder — Animated WebP and GIF support.
 *
 * Android API 28+ provides ImageDecoder which natively decodes:
 *   - Animated WebP
 *   - Animated GIF
 *   - HEIF sequences
 *
 * This mirrors how Telegram handles animated stickers and WebM via
 * AnimatedFileDrawable (their custom native decoder). We use the system
 * ImageDecoder for standard formats, reserving native NDK for exotic cases.
 *
 * Usage:
 *   TeleImageAnimatedDecoder.decode(file) → AnimatedImageDrawable (API 28+)
 *   or → BitmapDrawable fallback (API < 28)
 *
 * The returned Drawable is then passed to TeleImageView.setAnimatedDrawable()
 * which calls drawable.start() after attaching to window.
 *
 * Telegram reference:
 *   AnimatedFileDrawable.java — custom frame-by-frame WebM/GIF decoder using NDK
 *   For standard animated WebP, Telegram calls BitmapFactory at the system level.
 */
object TeleImageAnimatedDecoder {

    private const val TAG = "TeleImage.Animated"

    /**
     * Detect if a file is an animated format by checking magic bytes.
     * Returns true for: animated WebP, GIF87a, GIF89a.
     */
    fun isAnimated(file: File): Boolean {
        if (!file.exists() || file.length() < 12) return false
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(12)
                stream.read(header)
                isAnimatedBytes(header)
            }
        } catch (e: Exception) { false }
    }

    fun isAnimatedBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 6) return false
        // GIF87a / GIF89a
        if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()) {
            return true
        }
        // WebP: RIFF....WEBP + ANIM chunk
        if (bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&   // RI
            bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&   // FF
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&   // WE
            bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()) { // BP
            return true  // Could be animated; system decoder will confirm
        }
        return false
    }

    /**
     * Decode an animated file to a Drawable.
     *
     * API 28+: returns AnimatedImageDrawable (hardware-accelerated, runs on RenderThread)
     * API < 28: returns BitmapDrawable of first frame (graceful degradation)
     *
     * Telegram: AnimatedFileDrawable wraps libyuv/FFmpeg — we use system decoder.
     *
     * @param file          decoded file from disk cache
     * @param autoStart     automatically start the animation (mirrors AnimatedFileDrawable.checkRepeat())
     */
    fun decode(file: File, autoStart: Boolean = true): Drawable? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // API 28+: ImageDecoder for animated WebP + GIF
                val source = ImageDecoder.createSource(file)
                val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE  // GPU-accelerated
                    // Telegram: AnimatedFileDrawable.setAllowDecodeSingleFrame()
                    // We allow full animation by default
                }
                if (drawable is AnimatedImageDrawable && autoStart) {
                    drawable.start()  // mirrors Telegram's fileDrawable.checkRepeat()
                }
                drawable
            } else {
                // Graceful degradation: decode first frame
                BitmapFactory.decodeFile(file.absolutePath)?.let {
                    android.graphics.drawable.BitmapDrawable(it)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Animated decode failed: ${file.name}", e)
            null
        }
    }
}
