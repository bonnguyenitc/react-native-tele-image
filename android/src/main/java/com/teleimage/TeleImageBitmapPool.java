package com.teleimage;

import android.graphics.Bitmap;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TeleImageBitmapPool — size-bucketed Bitmap memory reuse pool.
 *
 * Ported from Telegram's use of BitmapFactory.Options.inBitmap pattern
 * (BitmapsCache.java line 500: options.inBitmap = bitmap) combined with
 * Glide's BitmapPool bucketing strategy.
 *
 * Core pattern from Telegram (BitmapsCache.java ~lines 497-502):
 *   BitmapFactory.Options options = new BitmapFactory.Options();
 *   options.inBitmap = bitmap;           // ← reuse existing allocation
 *   BitmapFactory.decodeByteArray(..., options);
 *   options.inBitmap = null;             // ← release the reuse reference
 *
 * This eliminates GC pressure by reusing existing Bitmap byte buffers
 * instead of allocating new ones for every decode. Telegram calls this
 * pattern extensively in CacheOutTask and BitmapsCache.getFrame().
 *
 * Bucketing strategy:
 *   Key = (width, height, Config) → ArrayList<Bitmap> pool
 *   If exact match found → reuse (no allocation)
 *   If no match but pool has larger compatible bitmap → reuse with inBitmap
 *   Else → return null (let caller allocate)
 *
 * Pool size limit: maxPoolBytes (default 20MB, configurable).
 * Eviction: oldest acquired first (FIFO per bucket).
 */
public final class TeleImageBitmapPool {

    private static final String TAG = "TeleImage.BitmapPool";

    // ── Singleton ──────────────────────────────────────────────────────────────
    private static volatile TeleImageBitmapPool sInstance;

    public static TeleImageBitmapPool getInstance() {
        TeleImageBitmapPool local = sInstance;
        if (local == null) {
            synchronized (TeleImageBitmapPool.class) {
                local = sInstance;
                if (local == null) {
                    sInstance = local = new TeleImageBitmapPool(20 * 1024 * 1024);
                }
            }
        }
        return local;
    }

    // ── State ──────────────────────────────────────────────────────────────────
    private final long maxPoolBytes;
    private final AtomicLong currentBytes = new AtomicLong(0);
    // Buckets: key = width<<20 | height<<4 | configOrdinal
    private final HashMap<Long, ArrayList<Bitmap>> buckets = new HashMap<>();
    private final Object lock = new Object();

    private TeleImageBitmapPool(long maxPoolBytes) {
        this.maxPoolBytes = maxPoolBytes;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Get a reusable Bitmap for BitmapFactory.Options.inBitmap.
     *
     * Telegram pattern (BitmapsCache.java line 500):
     *   options.inBitmap = pool.get(w, h, config);
     *
     * @return a Bitmap with byteCount >= required, or null if pool is empty.
     */
    public Bitmap get(int width, int height, Bitmap.Config config) {
        long key = bucketKey(width, height, config);
        synchronized (lock) {
            ArrayList<Bitmap> pool = buckets.get(key);
            if (pool != null && !pool.isEmpty()) {
                Bitmap bmp = pool.remove(pool.size() - 1);
                if (bmp != null && !bmp.isRecycled()) {
                    currentBytes.addAndGet(-bmp.getByteCount());
                    return bmp;
                }
            }
            // Fallback: look for any larger bitmap in same config that can be reused
            return findCompatible(width, height, config);
        }
    }

    /**
     * Return a Bitmap to the pool after use.
     * Never call this on a Bitmap that is still displayed.
     *
     * Mirrors Telegram's AndroidUtilities.recycleBitmaps() pattern which
     * defers recycle to avoid racing with draw operations.
     */
    public void put(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return;
        long bytes = bitmap.getByteCount();
        if (bytes > maxPoolBytes / 4) {
            // Don't pool very large bitmaps — not worth the memory
            bitmap.recycle();
            return;
        }
        long key = bucketKey(bitmap.getWidth(), bitmap.getHeight(), bitmap.getConfig());
        synchronized (lock) {
            // Trim if over budget
            while (currentBytes.get() + bytes > maxPoolBytes) {
                if (!trimOne()) break;
            }
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(bitmap);
            currentBytes.addAndGet(bytes);
        }
    }

    /** Total bytes held in pool. */
    public long getCurrentBytes() { return currentBytes.get(); }

    /** Clear entire pool and recycle all Bitmaps. */
    public void clear() {
        synchronized (lock) {
            for (ArrayList<Bitmap> pool : buckets.values()) {
                for (Bitmap bmp : pool) {
                    if (bmp != null && !bmp.isRecycled()) bmp.recycle();
                }
                pool.clear();
            }
            buckets.clear();
            currentBytes.set(0);
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private Bitmap findCompatible(int w, int h, Bitmap.Config config) {
        int required = w * h * bytesPerPixel(config);
        for (Map.Entry<Long, ArrayList<Bitmap>> entry : buckets.entrySet()) {
            ArrayList<Bitmap> pool = entry.getValue();
            for (int i = pool.size() - 1; i >= 0; i--) {
                Bitmap bmp = pool.get(i);
                if (bmp != null && !bmp.isRecycled()
                        && bmp.getConfig() == config
                        && bmp.getByteCount() >= required) {
                    pool.remove(i);
                    currentBytes.addAndGet(-bmp.getByteCount());
                    return bmp;
                }
            }
        }
        return null;
    }

    /** Remove and recycle one Bitmap from the largest bucket. */
    private boolean trimOne() {
        long maxKey = -1; int maxSize = 0;
        for (Map.Entry<Long, ArrayList<Bitmap>> e : buckets.entrySet()) {
            if (e.getValue().size() > maxSize) {
                maxSize = e.getValue().size(); maxKey = e.getKey();
            }
        }
        if (maxKey < 0) return false;
        ArrayList<Bitmap> pool = buckets.get(maxKey);
        if (pool == null || pool.isEmpty()) return false;
        Bitmap evicted = pool.remove(0);
        if (evicted != null && !evicted.isRecycled()) {
            currentBytes.addAndGet(-evicted.getByteCount());
            evicted.recycle();
        }
        if (pool.isEmpty()) buckets.remove(maxKey);
        return true;
    }

    private static long bucketKey(int w, int h, Bitmap.Config config) {
        return ((long) w << 20) | ((long) h << 4) | configOrdinal(config);
    }

    private static int configOrdinal(Bitmap.Config c) {
        if (c == Bitmap.Config.ARGB_8888) return 0;
        if (c == Bitmap.Config.RGB_565)   return 1;
        if (c == Bitmap.Config.ARGB_4444) return 2;
        return 3;
    }

    private static int bytesPerPixel(Bitmap.Config c) {
        if (c == Bitmap.Config.ARGB_8888) return 4;
        if (c == Bitmap.Config.RGB_565)   return 2;
        if (c == Bitmap.Config.ARGB_4444) return 2;
        return 4;
    }
}
