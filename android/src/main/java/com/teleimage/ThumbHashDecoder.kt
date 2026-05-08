package com.teleimage

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.*

/**
 * ThumbHashDecoder — Pure Kotlin implementation of the ThumbHash algorithm.
 *
 * ThumbHash spec: https://evanw.github.io/thumbhash/
 *
 * This decoder converts a ThumbHash byte payload into a small preview Bitmap.
 * ThumbHash is used as an alternative to Telegram's "stripped" format when
 * server-generated thumbnails are not available — it encodes color + shape
 * information in ~30 bytes and decodes in < 1ms on device.
 *
 * Used by TeleImageView as the `thumbhash` prop value (base64-encoded).
 */
object ThumbHashDecoder {

    /**
     * Decode a ThumbHash byte array to a Bitmap.
     * Returns null if the hash is invalid.
     */
    fun decode(hash: ByteArray): Bitmap? {
        if (hash.size < 5) return null
        return try {
            thumbHashToRgba(hash)
        } catch (e: Exception) {
            null
        }
    }

    // ── ThumbHash decode algorithm ────────────────────────────────────────────
    // Ported from the reference JS implementation at https://evanw.github.io/thumbhash/

    private fun thumbHashToRgba(hash: ByteArray): Bitmap? {
        // Read header
        val header24 = (hash[0].toInt() and 0xFF) or
                ((hash[1].toInt() and 0xFF) shl 8) or
                ((hash[2].toInt() and 0xFF) shl 16)
        val header16 = (hash[3].toInt() and 0xFF) or
                ((hash[4].toInt() and 0xFF) shl 8)

        val lDC   = (header24 and 0x3F).toFloat() / 63f
        val pDC   = ((header24 shr 6) and 0x3F).toFloat() / 31.5f - 1f
        val qDC   = ((header24 shr 12) and 0x3F).toFloat() / 31.5f - 1f
        val lScale= ((header24 shr 19) and 0x1F).toFloat() / 31f
        val hasAlpha = (header24 shr 23) != 0

        val pScale = ((header16 shr 3) and 0x1F).toFloat() / 63f
        val qScale = ((header16 shr 8) and 0x1F).toFloat() / 63f
        val isLandscape = (header16 shr 13) != 0
        val lx    = max(3, if (isLandscape) (if (hasAlpha) 5 else 7) else (header16 shr 14) + 1)
        val ly    = max(3, if (isLandscape) (header16 shr 14) + 1 else if (hasAlpha) 5 else 7)

        val acStart = if (hasAlpha) 6 else 5
        var acIndex = 0
        val hashBytes = hash.map { it.toInt() and 0xFF }.toIntArray()

        fun readAC(): Float {
            val i = acStart + (acIndex / 2)
            if (i >= hashBytes.size) return 0f
            val raw = if (acIndex % 2 == 0) hashBytes[i] and 0x0F else (hashBytes[i] shr 4) and 0x0F
            acIndex++
            return raw.toFloat() / 7.5f - 1f
        }

        // Decode L channel DCT coefficients
        val lAC = Array(lx * ly - 1) { readAC() }
        // Decode p and q (chroma) channels
        val pAC = Array(3 * 3 - 1) { readAC() }
        val qAC = Array(3 * 3 - 1) { readAC() }

        // Optional alpha
        var aDC = 1f; var aScale = 1f
        val aAC: Array<Float>
        if (hasAlpha) {
            val alphaHeader = hashBytes[5]
            aDC    = (alphaHeader and 0x0F).toFloat() / 15f
            aScale = ((alphaHeader shr 4) and 0x0F).toFloat() / 15f
            aAC    = Array(5 * 5 - 1) { readAC() }
        } else {
            aAC = emptyArray()
        }

        val w = 32; val h = 32
        val pixels = IntArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val fx = x.toFloat() / w
                val fy = y.toFloat() / h

                // Evaluate DCT basis functions for L
                var l = lDC
                var idx = 0
                for (cy in 0 until ly) {
                    val yBasis = cos(PIf * fy * cy)
                    for (cx in (if (cy == 0) 1 else 0) until lx) {
                        l += lScale * lAC[idx++] * cos(PIf * fx * cx) * yBasis
                    }
                }

                // P and Q (3×3 DCT)
                var p = pDC; var q = qDC
                var pidx = 0; var qidx = 0
                for (cy in 0 until 3) {
                    val yBasis = cos(PIf * fy * cy)
                    for (cx in (if (cy == 0) 1 else 0) until 3) {
                        val basis = cos(PIf * fx * cx) * yBasis
                        p += pScale * pAC[pidx++] * basis
                        q += qScale * qAC[qidx++] * basis
                    }
                }

                // Alpha channel
                var a = aDC
                if (hasAlpha) {
                    var aidx = 0
                    for (cy in 0 until 5) {
                        val yBasis = cos(PIf * fy * cy)
                        for (cx in (if (cy == 0) 1 else 0) until 5) {
                            a += aScale * aAC[aidx++] * cos(PIf * fx * cx) * yBasis
                        }
                    }
                }

                // YCbCr → RGB
                val b2 = l - 2f / 3f * p
                val r = l + p + q
                val g = l + p - q

                val ri = clamp((r * 255f + 0.5f).toInt(), 0, 255)
                val gi = clamp((g * 255f + 0.5f).toInt(), 0, 255)
                val bi = clamp((b2 * 255f + 0.5f).toInt(), 0, 255)
                val ai = clamp((a * 255f + 0.5f).toInt(), 0, 255)

                pixels[y * w + x] = Color.argb(ai, ri, gi, bi)
            }
        }

        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun clamp(v: Int, lo: Int, hi: Int): Int = maxOf(lo, minOf(hi, v))
    private val PIf = Math.PI.toFloat()
}
