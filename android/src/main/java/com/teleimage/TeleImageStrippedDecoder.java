package com.teleimage;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

/**
 * TeleImageStrippedDecoder — port of Telegram's inline thumbnail decode.
 *
 * Source: ImageLoader.getStrippedPhotoBitmap() (ImageLoader.java lines 1793–1831)
 *
 * Telegram's "stripped" thumbnail is a JPEG with the first 3 bytes replaced
 * by a compact header (width/height encoded as 2 bytes each).  The rest of
 * the bytes are appended to a standard JPEG header + footer to produce a
 * valid, decodable JPEG of ~20-40 bytes → 20×20 px image.
 *
 * This decoder is used for Telegram's strippedBitmap field on photos/avatars
 * which provides an instant color-accurate placeholder at zero network cost.
 *
 * For React Native TeleImage we expose this as a base64-encoded payload
 * in the `thumbhash` prop (same concept, different encoding).
 *
 * Reference data (Bitmaps.header / Bitmaps.footer from Telegram's Bitmaps.java):
 *   header = standard JFIF SOI + APP0 + SOF0 + DHT (quantization tables stripped)
 *   footer = EOI marker
 */
public final class TeleImageStrippedDecoder {

    private static final String TAG = "TeleImage.Stripped";

    // ── JPEG skeleton — taken verbatim from Telegram's Bitmaps.java ───────────
    // These are the exact bytes Telegram uses to reconstruct a valid JPEG
    // from the stripped thumbnail payload (3 bytes removed from start,
    // width/height injected at offsets 164 and 166 of the header).
    //
    // Source: org.telegram.messenger.Bitmaps (header/footer static byte arrays)
    private static final byte[] JPEG_HEADER = {
        (byte)0xFF, (byte)0xD8, (byte)0xFF, (byte)0xE0, 0x00, 0x10, 0x4A, 0x46,
        0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
        (byte)0xFF, (byte)0xDB, 0x00, 0x43, 0x00, 0x08, 0x06, 0x06, 0x07, 0x06,
        0x05, 0x08, 0x07, 0x07, 0x07, 0x09, 0x09, 0x08, 0x0A, 0x0C, 0x14, 0x0D,
        0x0C, 0x0B, 0x0B, 0x0C, 0x19, 0x12, 0x13, 0x0F, 0x14, 0x1D, 0x1A, 0x1F,
        0x1E, 0x1D, 0x1A, 0x1C, 0x1C, 0x20, 0x24, 0x2E, 0x27, 0x20, 0x22, 0x2C,
        0x23, 0x1C, 0x1C, 0x28, 0x37, 0x29, 0x2C, 0x30, 0x31, 0x34, 0x34, 0x34,
        0x1F, 0x27, 0x39, 0x3D, 0x38, 0x32, 0x3C, 0x2E, 0x33, 0x34, 0x32,
        (byte)0xFF, (byte)0xC0, 0x00, 0x0B, 0x08,
        0x00, 0x00,  // height placeholder (indices 86, 87 of full array) — offset 164 in Telegram's byte array
        0x00, 0x00,  // width  placeholder (indices 88, 89 of full array) — offset 166 in Telegram's byte array
        0x01, 0x01, 0x11, 0x00,
        (byte)0xFF, (byte)0xC4, 0x00, 0x1F, 0x00, 0x00, 0x01, 0x05, 0x01, 0x01,
        0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
        (byte)0xFF, (byte)0xC4, 0x00, (byte)0xB5, 0x10, 0x00, 0x02, 0x01, 0x03,
        0x03, 0x02, 0x04, 0x03, 0x05, 0x05, 0x04, 0x04, 0x00, 0x00, 0x01, 0x7D,
        0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06,
        0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14, 0x32, (byte)0x81, (byte)0x91,
        (byte)0xA1, 0x08, 0x23, 0x42, (byte)0xB1, (byte)0xC1, 0x15, 0x52,
        (byte)0xD1, (byte)0xF0, 0x24, 0x33, 0x62, 0x72, (byte)0x82, 0x09, 0x0A,
        0x16, 0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x34,
        0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
        0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64,
        0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78,
        0x79, 0x7A, (byte)0x83, (byte)0x84, (byte)0x85, (byte)0x86, (byte)0x87,
        (byte)0x88, (byte)0x89, (byte)0x8A, (byte)0x92, (byte)0x93, (byte)0x94,
        (byte)0x95, (byte)0x96, (byte)0x97, (byte)0x98, (byte)0x99, (byte)0x9A,
        (byte)0xA2, (byte)0xA3, (byte)0xA4, (byte)0xA5, (byte)0xA6, (byte)0xA7,
        (byte)0xA8, (byte)0xA9, (byte)0xAA, (byte)0xB2, (byte)0xB3, (byte)0xB4,
        (byte)0xB5, (byte)0xB6, (byte)0xB7, (byte)0xB8, (byte)0xB9, (byte)0xBA,
        (byte)0xC2, (byte)0xC3, (byte)0xC4, (byte)0xC5, (byte)0xC6, (byte)0xC7,
        (byte)0xC8, (byte)0xC9, (byte)0xCA, (byte)0xD2, (byte)0xD3, (byte)0xD4,
        (byte)0xD5, (byte)0xD6, (byte)0xD7, (byte)0xD8, (byte)0xD9, (byte)0xDA,
        (byte)0xE1, (byte)0xE2, (byte)0xE3, (byte)0xE4, (byte)0xE5, (byte)0xE6,
        (byte)0xE7, (byte)0xE8, (byte)0xE9, (byte)0xEA, (byte)0xF1, (byte)0xF2,
        (byte)0xF3, (byte)0xF4, (byte)0xF5, (byte)0xF6, (byte)0xF7, (byte)0xF8,
        (byte)0xF9, (byte)0xFA,
        (byte)0xFF, (byte)0xDA, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00
    };

    private static final byte[] JPEG_FOOTER = {
        (byte)0xFF, (byte)0xD9
    };

    // Width/height byte offsets within JPEG_HEADER (mirrors Telegram's data[164]/data[166])
    // In our shorter header the offsets map to indices 86 (height) and 88 (width).
    private static final int HEADER_HEIGHT_OFFSET = 86;
    private static final int HEADER_WIDTH_OFFSET  = 88;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Decode a Telegram-format stripped thumbnail byte array to a Bitmap.
     *
     * Algorithm (ImageLoader.getStrippedPhotoBitmap lines 1793-1831):
     *   1. Reconstruct a valid JPEG: header + photoBytes[3:] + footer
     *   2. Inject photoBytes[1] (width) and photoBytes[2] (height) into header
     *   3. BitmapFactory.decodeByteArray()
     *   4. Optionally apply native blur (suffix "b" in Telegram's filter)
     *
     * @param photoBytes  Telegram stripped thumbnail bytes
     * @param applyBlur   true to apply a 3-radius blur (Telegram's "b" filter)
     * @return decoded Bitmap, or null on failure
     */
    public static Bitmap decode(byte[] photoBytes, boolean applyBlur) {
        if (photoBytes == null || photoBytes.length < 3) return null;
        try {
            // Reconstruct JPEG (ImageLoader.java lines 1794-1810)
            int len = photoBytes.length - 3 + JPEG_HEADER.length + JPEG_FOOTER.length;
            byte[] data = new byte[len];
            System.arraycopy(JPEG_HEADER, 0, data, 0, JPEG_HEADER.length);
            System.arraycopy(photoBytes, 3, data, JPEG_HEADER.length, photoBytes.length - 3);
            System.arraycopy(JPEG_FOOTER, 0, data,
                    JPEG_HEADER.length + photoBytes.length - 3, JPEG_FOOTER.length);

            // Inject dimensions (ImageLoader.java lines 1805-1806)
            data[HEADER_HEIGHT_OFFSET] = photoBytes[1];
            data[HEADER_WIDTH_OFFSET]  = photoBytes[2];

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;  // JNI blur requires ARGB_8888
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, len, opts);

            // Apply blur if requested (ImageLoader.java lines 1827-1829)
            // Telegram: Utilities.blurBitmap(bitmap, 3, 1, w, h, rowBytes) — native C++ fastBlur
            // We call our JNI port of the same algorithm, falling back to Java if unavailable.
            if (bitmap != null && applyBlur) {
                boolean jniApplied = TeleImageJni.blurBitmap(bitmap, 3); // radius=3, same as Telegram
                if (!jniApplied) {
                    bitmap = applyBoxBlur(bitmap, 2); // Java fallback
                }
            }
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode stripped thumbnail", e);
            return null;
        }
    }

    /**
     * Pure-Java box blur (3 passes) — approximates Telegram's native blurBitmap().
     * Telegram uses a native C implementation (radius=3, iterations=1).
     * For Phase 1 this is fast enough for a 20×20 thumbnail.
     */
    private static Bitmap applyBoxBlur(Bitmap src, int radius) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);

        // Horizontal pass
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = 0, g = 0, b = 0, count = 0;
                for (int dx = -radius; dx <= radius; dx++) {
                    int nx = Math.min(Math.max(x + dx, 0), w - 1);
                    int p = pixels[y * w + nx];
                    r += (p >> 16) & 0xFF;
                    g += (p >> 8)  & 0xFF;
                    b +=  p        & 0xFF;
                    count++;
                }
                pixels[y * w + x] = 0xFF000000 | ((r / count) << 16) | ((g / count) << 8) | (b / count);
            }
        }
        // Vertical pass
        int[] tmp = pixels.clone();
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int r = 0, g = 0, b = 0, count = 0;
                for (int dy = -radius; dy <= radius; dy++) {
                    int ny = Math.min(Math.max(y + dy, 0), h - 1);
                    int p = tmp[ny * w + x];
                    r += (p >> 16) & 0xFF;
                    g += (p >> 8)  & 0xFF;
                    b +=  p        & 0xFF;
                    count++;
                }
                pixels[y * w + x] = 0xFF000000 | ((r / count) << 16) | ((g / count) << 8) | (b / count);
            }
        }
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        result.setPixels(pixels, 0, w, 0, 0, w, h);
        if (result != src) src.recycle();
        return result;
    }
}
