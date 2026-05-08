/*
 * teleimage_jni.cpp
 *
 * Adapted from Telegram for Android — TMessagesProj/jni/image.cpp
 *
 * Functions copied verbatim from Telegram's image.cpp:
 *   - fastBlur()      (lines 156-258) — fast box blur for small images (radius 1,3,7,15)
 *   - fastBlurMore()  (lines 68-154)  — higher quality blur
 *
 * These are exposed via JNI as:
 *   com.teleimage.TeleImageJni.blurBitmap(Bitmap, radius, width, height, stride)
 *
 * Telegram uses this for:
 *   1. Blurring stripped thumbnails (the 5-pixel JPEG header trick)
 *   2. Chat background blur
 *   3. Profile photo blur when loading
 *
 * We use it for: blurring the decoded stripped thumbnail placeholder.
 */

#include <jni.h>
#include <android/bitmap.h>
#include <cstdint>
#include <cstring>
#include <algorithm>

using std::min;
using std::max;

extern "C" {

// ── Copied verbatim from Telegram image.cpp line 59-60 ─────────────────────
static inline uint64_t getColors(const uint8_t *p) {
    return p[0] + (p[1] << 16) + ((uint64_t)p[2] << 32) + ((uint64_t)p[3] << 48);
}

// ── fastBlur() — Telegram image.cpp lines 156-258, verbatim ─────────────────
// Box blur for radius in {1, 3, 7, 15}. Operates on ARGB_8888 pixels.
// Constraint: w*h <= 150*150 (thumbnail size).
static void fastBlur(int32_t w, int32_t h, int32_t stride, uint8_t *pix, int32_t radius) {
    if (pix == nullptr) return;
    const int32_t r1  = radius + 1;
    const int32_t div = radius * 2 + 1;
    int32_t shift;
    if      (radius == 1)  shift = 2;
    else if (radius == 3)  shift = 4;
    else if (radius == 7)  shift = 6;
    else if (radius == 15) shift = 8;
    else return;

    if (radius > 15 || div >= w || div >= h || w * h > 150 * 150 || stride > w * 4) return;

    uint64_t *rgb = new uint64_t[w * h];
    if (rgb == nullptr) return;

    int32_t x, y, i;
    int32_t yw = 0;
    const int32_t we = w - r1;
    for (y = 0; y < h; y++) {
        uint64_t cur = getColors(&pix[yw]);
        uint64_t rgballsum = -radius * cur;
        uint64_t rgbsum    = cur * ((r1 * (r1 + 1)) >> 1);
        for (i = 1; i <= radius; i++) {
            cur = getColors(&pix[yw + i * 4]);
            rgbsum    += cur * (r1 - i);
            rgballsum += cur;
        }
        x = 0;

#define update(start, middle, end) \
        rgb[y * w + x] = (rgbsum >> shift) & 0x00FF00FF00FF00FFLL; \
        rgballsum += getColors(&pix[yw + (start) * 4]) - 2 * getColors(&pix[yw + (middle) * 4]) + getColors(&pix[yw + (end) * 4]); \
        rgbsum += rgballsum; \
        x++;

        while (x < r1) { update(0, x, x + r1) }
        while (x < we) { update(x - r1, x, x + r1) }
        while (x < w)  { update(x - r1, x, w - 1) }
#undef update
        yw += stride;
    }

    const int32_t he = h - r1;
    for (x = 0; x < w; x++) {
        uint64_t rgballsum = -radius * rgb[x];
        uint64_t rgbsum    = rgb[x] * ((r1 * (r1 + 1)) >> 1);
        for (i = 1; i <= radius; i++) {
            rgbsum    += rgb[i * w + x] * (r1 - i);
            rgballsum += rgb[i * w + x];
        }
        y = 0;
        int32_t yi = x * 4;

#define update(start, middle, end) \
        int64_t res = rgbsum >> shift; \
        pix[yi]     = res;          \
        pix[yi + 1] = res >> 16;    \
        pix[yi + 2] = res >> 32;    \
        pix[yi + 3] = res >> 48;    \
        rgballsum += rgb[x + (start) * w] - 2 * rgb[x + (middle) * w] + rgb[x + (end) * w]; \
        rgbsum += rgballsum; \
        y++; \
        yi += stride;

        while (y < r1) { update(0, y, y + r1) }
        while (y < he) { update(y - r1, y, y + r1) }
        while (y < h)  { update(y - r1, y, h - 1) }
#undef update
    }
    delete[] rgb;
}

// ── fastBlurMore() — Telegram image.cpp lines 68-154, verbatim ──────────────
// Higher quality blur for larger radius (> 3).
static void fastBlurMore(int32_t w, int32_t h, int32_t stride, uint8_t *pix, int32_t radius) {
    const int32_t r1  = radius + 1;
    const int32_t div = radius * 2 + 1;
    if (radius > 15 || div >= w || div >= h || w * h > 150 * 150 || stride > w * 4) return;

    uint64_t *rgb = new uint64_t[w * h];
    if (rgb == nullptr) return;

    int32_t x, y, i;
    int32_t yw = 0;
    const int32_t we = w - r1;
    for (y = 0; y < h; y++) {
        uint64_t cur      = getColors(&pix[yw]);
        uint64_t rgballsum = -radius * cur;
        uint64_t rgbsum    = cur * ((r1 * (r1 + 1)) >> 1);
        for (i = 1; i <= radius; i++) {
            cur = getColors(&pix[yw + i * 4]);
            rgbsum    += cur * (r1 - i);
            rgballsum += cur;
        }
        x = 0;

#define update(start, middle, end) \
        rgb[y * w + x] = (rgbsum >> 6) & 0x00FF00FF00FF00FF; \
        rgballsum += getColors(&pix[yw + (start) * 4]) - 2 * getColors(&pix[yw + (middle) * 4]) + getColors(&pix[yw + (end) * 4]); \
        rgbsum += rgballsum; \
        x++;

        while (x < r1) { update(0, x, x + r1) }
        while (x < we) { update(x - r1, x, x + r1) }
        while (x < w)  { update(x - r1, x, w - 1) }
#undef update
        yw += stride;
    }

    const int32_t he = h - r1;
    for (x = 0; x < w; x++) {
        uint64_t rgballsum = -radius * rgb[x];
        uint64_t rgbsum    = rgb[x] * ((r1 * (r1 + 1)) >> 1);
        for (i = 1; i <= radius; i++) {
            rgbsum    += rgb[i * w + x] * (r1 - i);
            rgballsum += rgb[i * w + x];
        }
        y = 0;
        int32_t yi = x * 4;

#define update(start, middle, end) \
        int64_t res = rgbsum >> 6;  \
        pix[yi]     = res;          \
        pix[yi + 1] = res >> 16;    \
        pix[yi + 2] = res >> 32;    \
        pix[yi + 3] = res >> 48;    \
        rgballsum += rgb[x + (start) * w] - 2 * rgb[x + (middle) * w] + rgb[x + (end) * w]; \
        rgbsum += rgballsum; \
        y++; \
        yi += stride;

        while (y < r1) { update(0, y, y + r1) }
        while (y < he) { update(y - r1, y, y + r1) }
        while (y < h)  { update(y - r1, y, h - 1) }
#undef update
    }
    delete[] rgb;
}

// ── JNI export — mirrors Telegram's Utilities.blurBitmap() (image.cpp line 514) ──
// Java signature: com.teleimage.TeleImageJni.blurBitmap(Bitmap, int, int, int, int)
JNIEXPORT void JNICALL
Java_com_teleimage_TeleImageJni_blurBitmap(
        JNIEnv *env, jclass clazz,
        jobject bitmap,
        jint radius, jint unpin,
        jint width, jint height, jint stride)
{
    if (!bitmap || !width || !height || !stride) return;

    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) return;
    if (!pixels) return;

    // Telegram image.cpp line 527-538: dispatch based on radius
    if (radius <= 3) {
        fastBlur(width, height, stride, (uint8_t *)pixels, radius);
    } else {
        fastBlurMore(width, height, stride, (uint8_t *)pixels, radius);
    }

    if (unpin) {
        AndroidBitmap_unlockPixels(env, bitmap);
    }
}

// ── JNI_OnLoad ───────────────────────────────────────────────────────────────
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) return -1;
    return JNI_VERSION_1_6;
}

} // extern "C"
