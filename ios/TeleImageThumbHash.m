#import "TeleImageThumbHash.h"
#import <math.h>

/**
 * TeleImageThumbHash.m — Full DCT ThumbHash decode for iOS.
 *
 * Ported from ThumbHashDecoder.kt (Android, Phase 1) and the reference
 * JS implementation at https://evanw.github.io/thumbhash/
 *
 * Copyright (c) Evan Wallace. MIT License.
 * Port for iOS by react-native-tele-image (Phase 3).
 *
 * Algorithm:
 *   1. Unpack header bits (lDC, pDC, qDC, lScale, pScale, qScale, lx, ly, hasAlpha)
 *   2. Read AC nibbles from hash bytes
 *   3. Evaluate DCT basis functions at each of 32×32 pixels
 *   4. Convert YCbCr (l, p, q) → RGB
 *   5. Render to CGContext and create UIImage
 */

@implementation TeleImageThumbHash

static inline float _clamp01(float v) { return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v); }

+ (nullable UIImage *)decodeFromBase64:(NSString *)base64String {
    if (!base64String || base64String.length == 0) return nil;
    NSData *data = [[NSData alloc] initWithBase64EncodedString:base64String options:0];
    return [self decodeFromData:data];
}

+ (nullable UIImage *)decodeFromData:(NSData *)hashData {
    if (!hashData || hashData.length < 5) return nil;

    const uint8_t *hash = (const uint8_t *)hashData.bytes;
    NSUInteger len = hashData.length;

    // ── Unpack header (same bit layout as ThumbHashDecoder.kt) ───────────────
    uint32_t header24 = (uint32_t)hash[0] | ((uint32_t)hash[1] << 8) | ((uint32_t)hash[2] << 16);
    uint32_t header16 = len > 3 ? ((uint32_t)hash[3] | ((uint32_t)hash[4] << 8)) : 0;

    float lDC    = (float)(header24 & 0x3F) / 63.0f;
    float pDC    = (float)((header24 >> 6) & 0x3F) / 31.5f - 1.0f;
    float qDC    = (float)((header24 >> 12) & 0x3F) / 31.5f - 1.0f;
    float lScale = (float)((header24 >> 19) & 0x1F) / 31.0f;
    BOOL hasAlpha= (header24 >> 23) != 0;

    float pScale = (float)((header16 >> 3) & 0x1F) / 63.0f;
    float qScale = (float)((header16 >> 8) & 0x1F) / 63.0f;
    BOOL isLandscape = (header16 >> 13) != 0;

    int lx = MAX(3, isLandscape ? (hasAlpha ? 5 : 7) : (int)(header16 >> 14) + 1);
    int ly = MAX(3, isLandscape ? (int)(header16 >> 14) + 1 : (hasAlpha ? 5 : 7));

    // ── Read AC coefficients ──────────────────────────────────────────────────
    int acStart = hasAlpha ? 6 : 5;
    __block int acIndex = 0;
    int maxAC = (int)(len - acStart) * 2;

    // readAC block — mirrors ThumbHashDecoder.kt readAC()
    float (^readAC)(void) = ^float {
        if (acIndex >= maxAC) return 0.0f;
        int i = acStart + acIndex / 2;
        if (i >= (int)len) return 0.0f;
        int raw = (acIndex % 2 == 0) ? (hash[i] & 0x0F) : ((hash[i] >> 4) & 0x0F);
        acIndex++;
        return (float)raw / 7.5f - 1.0f;
    };

    int lCount = lx * ly - 1;
    int pCount = 3 * 3 - 1;
    int qCount = 3 * 3 - 1;

    float *lAC = (float *)alloca(lCount * sizeof(float));
    float *pAC = (float *)alloca(pCount * sizeof(float));
    float *qAC = (float *)alloca(qCount * sizeof(float));

    for (int i = 0; i < lCount; i++) lAC[i] = readAC();
    for (int i = 0; i < pCount; i++) pAC[i] = readAC();
    for (int i = 0; i < qCount; i++) qAC[i] = readAC();

    float aDC    = 1.0f;
    float aScale = 1.0f;
    int aCount   = 0;
    float *aAC   = NULL;
    if (hasAlpha && len > 5) {
        uint8_t alphaHeader = hash[5];
        aDC    = (float)(alphaHeader & 0x0F) / 15.0f;
        aScale = (float)((alphaHeader >> 4) & 0x0F) / 15.0f;
        aCount = 5 * 5 - 1;
        aAC = (float *)alloca(aCount * sizeof(float));
        for (int i = 0; i < aCount; i++) aAC[i] = readAC();
    }

    // ── Rasterize 32×32 pixels ────────────────────────────────────────────────
    const int W = 32, H = 32;
    uint8_t *pixels = (uint8_t *)malloc(W * H * 4);  // RGBA
    if (!pixels) return nil;

    for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
            float fx = (float)x / (float)W;
            float fy = (float)y / (float)H;

            // L channel DCT
            float l = lDC;
            int idx = 0;
            for (int cy = 0; cy < ly; cy++) {
                float yBasis = cosf((float)M_PI * fy * (float)cy);
                for (int cx = (cy == 0 ? 1 : 0); cx < lx; cx++) {
                    l += lScale * lAC[idx++] * cosf((float)M_PI * fx * (float)cx) * yBasis;
                }
            }

            // P and Q channels (3×3 DCT)
            float p = pDC, q = qDC;
            int pidx = 0, qidx = 0;
            for (int cy = 0; cy < 3; cy++) {
                float yBasis = cosf((float)M_PI * fy * (float)cy);
                for (int cx = (cy == 0 ? 1 : 0); cx < 3; cx++) {
                    float basis = cosf((float)M_PI * fx * (float)cx) * yBasis;
                    p += pScale * pAC[pidx++] * basis;
                    q += qScale * qAC[qidx++] * basis;
                }
            }

            // Alpha channel (5×5 DCT)
            float a = aDC;
            if (hasAlpha && aAC) {
                int aidx = 0;
                for (int cy = 0; cy < 5; cy++) {
                    float yBasis = cosf((float)M_PI * fy * (float)cy);
                    for (int cx = (cy == 0 ? 1 : 0); cx < 5; cx++) {
                        a += aScale * aAC[aidx++] * cosf((float)M_PI * fx * (float)cx) * yBasis;
                    }
                }
            }

            // YCbCr → RGB (matches ThumbHashDecoder.kt conversion)
            float b2 = l - 2.0f / 3.0f * p;
            float r  = l + p + q;
            float g  = l + p - q;

            int base = (y * W + x) * 4;
            pixels[base + 0] = (uint8_t)(_clamp01(r)  * 255.0f + 0.5f);
            pixels[base + 1] = (uint8_t)(_clamp01(g)  * 255.0f + 0.5f);
            pixels[base + 2] = (uint8_t)(_clamp01(b2) * 255.0f + 0.5f);
            pixels[base + 3] = (uint8_t)(_clamp01(a)  * 255.0f + 0.5f);
        }
    }

    // ── Create UIImage from pixel buffer ──────────────────────────────────────
    CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
    CGContextRef ctx = CGBitmapContextCreate(
        pixels, W, H, 8, W * 4, cs,
        kCGBitmapByteOrderDefault | kCGImageAlphaPremultipliedLast
    );
    CGColorSpaceRelease(cs);

    UIImage *result = nil;
    if (ctx) {
        CGImageRef cgImg = CGBitmapContextCreateImage(ctx);
        if (cgImg) {
            result = [UIImage imageWithCGImage:cgImg];
            CGImageRelease(cgImg);
        }
        CGContextRelease(ctx);
    }
    free(pixels);
    return result;
}

@end
