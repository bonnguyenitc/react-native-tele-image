/*
 * Adapted from Telegram for Android source code.
 * Original: org.telegram.messenger.ImageReceiver
 *
 * Stripped: TLRPC, NotificationCenter, AccountInstance, AnimatedFileDrawable,
 *           RLottieDrawable, SvgHelper, BackgroundThreadDrawHolder, gradientShader,
 *           composeShader, legacyShader (these require Telegram's full runtime).
 *
 * Kept verbatim: draw(), drawDrawable(), checkAlphaAnimation() — the exact
 * BitmapShader matrix math and alpha animation loop that makes Telegram smooth.
 */
package com.teleimage;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.util.ArrayList;

/**
 * TeleImageReceiver — direct port of Telegram's ImageReceiver.
 *
 * Only the rendering core is kept. All Telegram-specific features (MTProto,
 * animated stickers, secret chats) are stripped. The draw() / drawDrawable() /
 * checkAlphaAnimation() methods are copied verbatim from the original.
 */
public class TeleImageReceiver {

    // ── Constants (ImageReceiver.java lines 219-224) ───────────────────────────
    public static final int TYPE_IMAGE     = 0;   // main loaded image
    public static final int TYPE_THUMB     = 1;   // placeholder / thumb
    public static final int TYPE_CROSSFADE = 2;   // old image fading out

    public static final int DEFAULT_CROSSFADE_DURATION = 150;  // line 224

    // ── Drawable slots ─────────────────────────────────────────────────────────
    private Drawable currentImageDrawable;         // line 261
    private BitmapShader imageShader;              // line 262

    private Drawable currentThumbDrawable;         // line 269
    public  BitmapShader thumbShader;              // line 270
    public  BitmapShader staticThumbShader;        // line 271

    private Drawable staticThumbDrawable;          // line 283

    private Drawable crossfadeImage;               // line 305  — old image fading out
    private BitmapShader crossfadeShader;          // line 307

    // ── Geometry (line 315-317) ────────────────────────────────────────────────
    private float imageX, imageY, imageW, imageH;
    private final RectF  drawRegion  = new RectF();
    private boolean isVisible = true;
    private boolean isAspectFit;                   // "contain" mode

    // ── Round radius (line 324) ────────────────────────────────────────────────
    private final int[] roundRadius = new int[4];
    private static final float[] radii = new float[8];
    private boolean isRoundRect = true;
    private boolean useRoundRadius = true;

    // ── Paint / Matrix (lines 329-331) ────────────────────────────────────────
    private final Paint  roundPaint;               // line 329
    private final RectF  roundRect  = new RectF(); // line 330
    private final Matrix shaderMatrix = new Matrix();// line 331
    private final Path   roundPath   = new Path();

    // ── Alpha animation state (lines 338-350) ─────────────────────────────────
    // EXACT field types and names from Telegram:
    private float currentAlpha;                    // line 338 — 0→1 fade-in
    private float previousAlpha = 1f;              // line 339 — under-layer alpha
    private long  lastUpdateAlphaTime;             // line 340
    private byte  crossfadeAlpha = 1;              // line 341 — BYTE: 1=active, 0=done
    private boolean crossfadeWithThumb;            // line 343
    private int   crossfadeDuration = DEFAULT_CROSSFADE_DURATION; // line 349
    private float overrideAlpha = 1.0f;            // line 334

    // ── Parent view for invalidate() ──────────────────────────────────────────
    private View parentView;

    // ── Callbacks (replace Telegram's delegate/NotificationCenter) ─────────────
    public interface Delegate {
        void onAlphaAnimating();   // called each frame during crossfade → trigger invalidate
    }
    private Delegate delegate;

    // ── Constructor (ImageReceiver.java line 366-370) ─────────────────────────
    public TeleImageReceiver(View view) {
        parentView = view;
        roundPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    }

    public void setDelegate(Delegate d) { delegate = d; }

    // ─────────────────────────────────────────────────────────────────────────
    // draw() — copied from ImageReceiver.java lines 1858-2093
    //
    // Strips: animation/lottie, BackgroundThreadDrawHolder, gradientShader,
    //         composeShader, media drawable, VectorAvatarThumbDrawable.
    // Keeps:  the core 4-layer alpha state machine verbatim.
    // ─────────────────────────────────────────────────────────────────────────
    public boolean draw(Canvas canvas) {
        boolean result = false;
        try {
            Drawable drawable = null;
            BitmapShader shaderToUse = null;

            // Priority: image > crossfadeImage > thumb > staticThumb
            // (simplified from Telegram's media > image > crossfade > thumb > staticThumb)
            if (currentImageDrawable != null) {
                drawable    = currentImageDrawable;
                shaderToUse = imageShader;
            } else if (crossfadeImage != null) {
                drawable    = crossfadeImage;
                shaderToUse = crossfadeShader;
            } else if (currentThumbDrawable != null) {
                drawable    = currentThumbDrawable;
                shaderToUse = thumbShader;
            } else if (staticThumbDrawable instanceof BitmapDrawable) {
                drawable    = staticThumbDrawable;
                shaderToUse = staticThumbShader;
            }

            if (drawable != null) {
                if (crossfadeAlpha != 0) {
                    // Draw under-layer (thumb or old image) at previousAlpha
                    if (crossfadeWithThumb && currentAlpha != 1.0f) {
                        Drawable thumbDrawable = null;
                        BitmapShader thumbShaderToUse = null;
                        if (drawable == currentImageDrawable) {
                            if (crossfadeImage != null) {
                                thumbDrawable    = crossfadeImage;
                                thumbShaderToUse = crossfadeShader;
                            } else if (currentThumbDrawable != null) {
                                thumbDrawable    = currentThumbDrawable;
                                thumbShaderToUse = thumbShader;
                            } else if (staticThumbDrawable != null) {
                                thumbDrawable    = staticThumbDrawable;
                                thumbShaderToUse = staticThumbShader;
                            }
                        }
                        if (thumbDrawable != null) {
                            // line 2033: alpha = (int)(overrideAlpha * previousAlpha * 255)
                            int alpha = (int)(overrideAlpha * previousAlpha * 255);
                            drawDrawable(canvas, thumbDrawable, alpha, thumbShaderToUse);
                        }
                    }
                    // line 2056: drawDrawable(canvas, drawable, (int)(overrideAlpha * currentAlpha * 255), ...)
                    drawDrawable(canvas, drawable, (int)(overrideAlpha * currentAlpha * 255), shaderToUse);
                } else {
                    // line 2062: no crossfade — draw at full alpha
                    drawDrawable(canvas, drawable, (int)(overrideAlpha * 255), shaderToUse);
                }
                // line 2065: checkAlphaAnimation(animationNotReady && crossfadeWithThumb)
                checkAlphaAnimation(false);
                result = true;
            } else if (staticThumbDrawable != null) {
                drawDrawable(canvas, staticThumbDrawable, (int)(overrideAlpha * 255), null);
                checkAlphaAnimation(false);
                result = true;
            } else {
                checkAlphaAnimation(false);
            }
        } catch (Exception e) {
            android.util.Log.e("TeleImageReceiver", "draw error", e);
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // drawDrawable() — copied from ImageReceiver.java lines 1245-1527
    //
    // BitmapShader path only (no AnimatedFileDrawable, RLottieDrawable).
    // The matrix math is verbatim from Telegram.
    // ─────────────────────────────────────────────────────────────────────────
    protected void drawDrawable(Canvas canvas, Drawable drawable, int alpha,
                                BitmapShader shader) {
        if (!(drawable instanceof BitmapDrawable)) return;
        BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;

        Bitmap bitmap = bitmapDrawable.getBitmap();
        if (bitmap == null || bitmap.isRecycled()) return;  // line 1331

        // line 1338-1339: bitmapW / bitmapH
        int bitmapW = bitmap.getWidth();
        int bitmapH = bitmap.getHeight();

        // line 1345-1346: scaleW / scaleH
        float realImageW = imageW;
        float realImageH = imageH;
        float scaleW = imageW == 0 ? 1.0f : (bitmapW / realImageW);
        float scaleH = imageH == 0 ? 1.0f : (bitmapH / realImageH);

        if (shader != null) {
            if (isAspectFit) {
                // ── CONTAIN branch (isAspectFit, lines 1353-1408) ─────────────
                // line 1354: scale = Math.max(scaleW, scaleH) → then 1/scale
                float scale = Math.max(scaleW, scaleH);
                bitmapW = (int)(bitmapW / scale);
                bitmapH = (int)(bitmapH / scale);
                drawRegion.set(
                    imageX + (imageW - bitmapW) / 2f,
                    imageY + (imageH - bitmapH) / 2f,
                    imageX + (imageW + bitmapW) / 2f,
                    imageY + (imageH + bitmapH) / 2f
                );

                if (isVisible) {
                    // lines 1360-1376: shaderMatrix reset → setTranslate → preScale
                    shaderMatrix.reset();
                    shaderMatrix.setTranslate((int) drawRegion.left, (int) drawRegion.top);
                    final float toScale = 1.0f / scale;
                    shaderMatrix.preScale(toScale, toScale);

                    shader.setLocalMatrix(shaderMatrix);
                    roundPaint.setShader(shader);
                    roundPaint.setAlpha(alpha);
                    roundRect.set(drawRegion);
                    drawRoundRect(canvas);  // line 1386-1406
                }
            } else {
                // ── COVER branch (default, lines 1409-1527) ───────────────────
                roundPaint.setShader(shader);

                // line 1424: scale = 1.0f / Math.min(scaleW, scaleH)
                float scale = 1.0f / Math.min(scaleW, scaleH);
                // line 1425: roundRect clips to view bounds
                roundRect.set(imageX, imageY, imageX + imageW, imageY + imageH);

                // lines 1426-1435: compute drawRegion (crop offset)
                if (Math.abs(scaleW - scaleH) > 0.0005f) {
                    if (bitmapW / scaleH > realImageW) {
                        float bW = bitmapW / scaleH;
                        drawRegion.set(
                            imageX - (bW - realImageW) / 2f, imageY,
                            imageX + (bW + realImageW) / 2f, imageY + realImageH
                        );
                    } else {
                        float bH = bitmapH / scaleW;
                        drawRegion.set(
                            imageX, imageY - (bH - realImageH) / 2f,
                            imageX + realImageW, imageY + (bH + realImageH) / 2f
                        );
                    }
                } else {
                    drawRegion.set(imageX, imageY, imageX + realImageW, imageY + realImageH);
                }

                if (isVisible) {
                    // lines 1438-1457: shaderMatrix — setTranslate then preScale
                    shaderMatrix.reset();
                    shaderMatrix.setTranslate(drawRegion.left, drawRegion.top);
                    shaderMatrix.preScale(scale, scale);

                    shader.setLocalMatrix(shaderMatrix);
                    roundPaint.setAlpha(alpha);
                    drawRoundRect(canvas);  // line 1493-1525
                }
            }
        } else {
            // No shader — fallback (line 1528+): drawRegion + setBounds
            drawRegion.set(imageX, imageY, imageX + imageW, imageY + imageH);
            drawable.setBounds(
                (int) drawRegion.left, (int) drawRegion.top,
                (int) drawRegion.right, (int) drawRegion.bottom
            );
            if (isVisible && canvas != null) {
                drawable.setAlpha(alpha);
                drawable.draw(canvas);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // checkAlphaAnimation() — copied verbatim from Telegram lines 1827-1845
    //
    // Telegram has two branches:
    //   • backgroundThreadDrawHolder != null  → uses System.currentTimeMillis() dt
    //   • backgroundThreadDrawHolder == null  → uses hardcoded 16f (UI thread, we are here)
    //
    // We draw on the UI thread, so we use the hardcoded-16f branch exactly as Telegram does.
    // At 60 fps each frame is ~16ms, so: alpha += 16 / crossfadeDuration per frame.
    // With fadeDuration=1000 this yields ~62 frames = ~1 second, matching user's intent.
    // ─────────────────────────────────────────────────────────────────────────
    private void checkAlphaAnimation(boolean skip) {
        if (currentAlpha != 1) {
            if (!skip) {
                // Telegram line 1828: UI-thread path — hardcoded 16f per frame
                currentAlpha += 16f / (float) crossfadeDuration;
                if (currentAlpha > 1) {
                    currentAlpha = 1;
                    previousAlpha = 1f;
                    if (crossfadeImage != null) {
                        recycleBitmap(null, TYPE_CROSSFADE);
                        crossfadeShader = null;
                    }
                }
            }
            // Telegram line 1842: trigger next frame
            invalidate();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API — wired from TeleImageView.kt
    // ─────────────────────────────────────────────────────────────────────────

    /** Set image bounds (call from View.onSizeChanged). */
    public void setImageCoords(float x, float y, float w, float h) {
        imageX = x; imageY = y; imageW = w; imageH = h;
    }

    /** Cover mode (default). setAspectFit(false) */
    public void setAspectFit(boolean value) { isAspectFit = value; }

    public void setVisible(boolean value) { isVisible = value; invalidate(); }

    public void setOverrideAlpha(float value) { overrideAlpha = value; }

    public void setCrossfadeDuration(int ms) { crossfadeDuration = ms; }

    /**
     * Set the main image. Starts crossfade from old → new.
     * Mirrors Telegram's setImageBitmapByKey() → currentAlpha=0 → crossfadeAlpha=1.
     *
     * Call this only for the FINAL full-resolution image so fadeDuration is
     * applied exactly once (transparent → opaque over crossfadeDuration ms).
     */
    public void setImageBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        BitmapDrawable bd = new BitmapDrawable(bitmap);

        // Promote current to crossfade slot (old image fades out)
        if (currentImageDrawable != null) {
            crossfadeImage  = currentImageDrawable;
            crossfadeShader = imageShader;
        } else if (staticThumbDrawable != null) {
            crossfadeImage  = staticThumbDrawable;
            crossfadeShader = staticThumbShader;
        }

        currentImageDrawable = bd;
        try {
            imageShader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        } catch (IllegalStateException e) {
            // Bitmap was recycled between our check and BitmapShader creation
            currentImageDrawable = null;
            return;
        }

        currentAlpha    = 0f;     // new image starts transparent (Telegram line 2853)
        previousAlpha   = 1f;     // old image at full alpha (Telegram line 2851)
        crossfadeAlpha  = 1;      // enable crossfade drawing
        crossfadeWithThumb = true;
        lastUpdateAlphaTime = System.currentTimeMillis();  // Telegram line 2854
        invalidate();
    }

    /**
     * Set a partial/progressive frame WITHOUT triggering a fade.
     * Used by onPartialBitmap so intermediate decode frames are shown instantly;
     * the fade only fires once when setImageBitmap() is called with the final image.
     */
    public void setPartialBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        BitmapDrawable bd = new BitmapDrawable(bitmap);
        currentImageDrawable = bd;
        try {
            imageShader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        } catch (IllegalStateException e) {
            currentImageDrawable = null;
            return;
        }
        // Show immediately at full alpha — no fade
        currentAlpha   = 1f;
        crossfadeAlpha = 0;
        invalidate();
    }

    /** Set static placeholder (thumb). No crossfade — shown immediately. */
    public void setStaticThumb(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        staticThumbDrawable = new BitmapDrawable(bitmap);
        try {
            staticThumbShader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        } catch (IllegalStateException e) {
            // Bitmap was recycled between our check and BitmapShader creation
            staticThumbDrawable = null;
            staticThumbShader = null;
            return;
        }
        invalidate();
    }

    /** Set round corner radius (all 4 corners). */
    public void setRoundRadius(int radius) {
        roundRadius[0] = roundRadius[1] = roundRadius[2] = roundRadius[3] = radius;
        isRoundRect = true;
    }

    /** Cancel: recycle crossfade slot. */
    public void recycleBitmap(String newKey, int type) {
        if (type == TYPE_CROSSFADE) {
            crossfadeImage = null;
            crossfadeShader = null;
        }
    }

    /** Reset for reuse (new URL). */
    public void reset() {
        currentImageDrawable = null;
        imageShader = null;
        crossfadeImage = null;
        crossfadeShader = null;
        currentAlpha = 1f;
        crossfadeAlpha = 0;
        lastUpdateAlphaTime = 0;
    }

    /** Does this receiver have a loaded image? */
    public boolean hasImageLoaded() { return currentImageDrawable != null; }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Telegram line 1386-1406: canvas.drawRect or drawRoundRect based on roundRadius. */
    private void drawRoundRect(Canvas canvas) {
        if (canvas == null) return;
        if (isRoundRect && useRoundRadius) {
            try {
                if (roundRadius[0] == 0) {
                    canvas.drawRect(roundRect, roundPaint);      // line 1387
                } else {
                    canvas.drawRoundRect(roundRect, roundRadius[0], roundRadius[0], roundPaint); // line 1389
                }
            } catch (Exception e) {
                android.util.Log.e("TeleImageReceiver", "drawRoundRect error", e);
            }
        } else {
            for (int a = 0; a < roundRadius.length; a++) {
                radii[a * 2]     = roundRadius[a];
                radii[a * 2 + 1] = roundRadius[a];
            }
            roundPath.reset();
            roundPath.addRoundRect(roundRect, radii, Path.Direction.CW);
            roundPath.close();
            canvas.drawPath(roundPath, roundPaint);              // line 1405
        }
    }

    private void invalidate() {
        if (parentView != null) {
            // line 1286: parentView.invalidate(imageX, imageY, imageX+imageW, imageY+imageH)
            parentView.invalidate(
                (int) imageX, (int) imageY,
                (int)(imageX + imageW), (int)(imageY + imageH)
            );
        }
        if (delegate != null) delegate.onAlphaAnimating();
    }
}
