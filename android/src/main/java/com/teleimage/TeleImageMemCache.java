package com.teleimage;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.util.LruCache;
import android.util.Log;

/**
 * TeleImageMemCache — ported directly from Telegram's ImageLoader.java
 *
 * Telegram source reference: ImageLoader.java (lines 113–117, 2057–2153)
 *
 * Telegram uses THREE separate LruCache tiers:
 *   memCache          → regular images (80% of budget)
 *   smallImagesMemCache → thumbnails/small images (20% of budget)
 *   (lottieMemCache  → animations, Phase 3)
 *
 * Budget calculation (from Telegram ImageLoader constructor ~line 2060):
 *   memoryClass = ActivityManager.getMemoryClass()  // e.g. 256MB
 *   maxSize = min(30, memoryClass / 7) MB           // ~36MB cap
 *   cacheSize = maxSize * 1024 * 1024
 *   commonCacheSize    = cacheSize * 0.8
 *   smallImagesCacheSize = cacheSize * 0.2
 *
 * Telegram also tracks bitmapUseCounts per key (HashMap<String, Integer>)
 * so that evicted entries are only recycled if no ImageReceiver holds a ref.
 */
public final class TeleImageMemCache {

    private static final String TAG = "TeleImage.MemCache";

    // ── Singleton (double-checked locking — mirrors Telegram's ImageLoader.getInstance()) ──
    private static volatile TeleImageMemCache sInstance = null;

    public static TeleImageMemCache getInstance() {
        TeleImageMemCache local = sInstance;
        if (local == null) {
            synchronized (TeleImageMemCache.class) {
                local = sInstance;
                if (local == null) {
                    sInstance = local = new TeleImageMemCache();
                }
            }
        }
        return local;
    }

    // ── Telegram-style 3-tier LruCache ────────────────────────────────────────
    // Ported from ImageLoader.java lines 114–117, 2072–2115
    private final LruCache<String, BitmapDrawable> memCache;
    private final LruCache<String, BitmapDrawable> smallImagesMemCache;

    // bitmapUseCounts: tracks active references per key.
    // Telegram: HashMap<String, Integer> bitmapUseCounts  (ImageLoader.java line 113)
    // Used to defer Bitmap.recycle() until all ImageReceivers release the key.
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> bitmapUseCounts =
            new java.util.concurrent.ConcurrentHashMap<>();

    private TeleImageMemCache() {
        // Mirror Telegram's memory budget logic (ImageLoader constructor ~line 2060)
        int memoryClassMb = getMemoryClassMb();
        boolean canForce8888 = memoryClassMb >= 192;

        int maxSizeMb = Math.min(30, memoryClassMb / 7);
        int cacheSizeBytes = maxSizeMb * 1024 * 1024;

        // Telegram split: 80% regular, 20% small (ImageLoader.java lines 2069-2070)
        int commonCacheSize      = (int) (cacheSizeBytes * 0.8f);
        int smallImagesCacheSize = (int) (cacheSizeBytes * 0.2f);

        // Telegram's LruCache with entryRemoved recycling
        // (ImageLoader.java lines 2072-2115)
        memCache = new LruCache<String, BitmapDrawable>(commonCacheSize) {
            @Override
            protected int sizeOf(String key, BitmapDrawable value) {
                return bitmapByteCount(value);
            }

            @Override
            protected void entryRemoved(boolean evicted, String key,
                                        BitmapDrawable oldValue, BitmapDrawable newValue) {
                // Note: Telegram calls Bitmap.recycle() here guarded by bitmapUseCounts.
                // We intentionally do NOT recycle — our TeleImageReceiver/View lifecycle
                // doesn't track use-counts, so evicted bitmaps may still be held by a
                // receiver. On API 28+ bitmaps are heap-allocated and GC'd naturally.
            }
        };

        smallImagesMemCache = new LruCache<String, BitmapDrawable>(smallImagesCacheSize) {
            @Override
            protected int sizeOf(String key, BitmapDrawable value) {
                return bitmapByteCount(value);
            }

            @Override
            protected void entryRemoved(boolean evicted, String key,
                                        BitmapDrawable oldValue, BitmapDrawable newValue) {
                // Same as memCache — no manual recycle; GC handles it.
            }
        };
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Get from cache. Checks memCache first, then smallImagesMemCache.
     * Mirrors Telegram's memCache.get() / smallImagesMemCache.get() pattern.
     */
    public BitmapDrawable get(String key) {
        if (key == null) return null;
        BitmapDrawable val = memCache.get(key);
        if (val == null) {
            val = smallImagesMemCache.get(key);
        }
        return val;
    }

    /**
     * Put into the appropriate tier.
     * Telegram uses smallImagesMemCache for images <= 100x100px.
     * Reference: ImageLoader.java putImageToCache() method.
     */
    public void put(String key, BitmapDrawable drawable) {
        if (key == null || drawable == null) return;
        Bitmap bmp = drawable.getBitmap();
        if (bmp == null || bmp.isRecycled()) return;

        boolean isSmall = bmp.getWidth() <= 100 && bmp.getHeight() <= 100;
        if (isSmall) {
            smallImagesMemCache.put(key, drawable);
        } else {
            memCache.put(key, drawable);
        }
    }

    /**
     * Increment the active reference count for a key.
     * Called when an ImageReceiver starts using this bitmap.
     * Mirrors: ImageLoader.incrementUseCount() (ImageLoader.java)
     */
    public void incrementUseCount(String key) {
        if (key == null) return;
        bitmapUseCounts.merge(key, 1, Integer::sum);
    }

    /**
     * Decrement reference count. Returns true if count reached 0
     * (safe to recycle if also evicted from cache).
     * Mirrors: ImageLoader.decrementUseCount() (ImageLoader.java)
     */
    public boolean decrementUseCount(String key) {
        if (key == null) return true;
        Integer count = bitmapUseCounts.get(key);
        if (count == null || count <= 1) {
            bitmapUseCounts.remove(key);
            return true;
        }
        bitmapUseCounts.put(key, count - 1);
        return false;
    }

    public boolean isInMemCache(String key) {
        return memCache.get(key) != null || smallImagesMemCache.get(key) != null;
    }

    public void remove(String key) {
        if (key == null) return;
        memCache.remove(key);
        smallImagesMemCache.remove(key);
    }

    /** Total bytes currently held across both cache tiers. */
    public long getCurrentMemoryBytes() {
        return memCache.size() + smallImagesMemCache.size();
    }

    public void evictAll() {
        memCache.evictAll();
        smallImagesMemCache.evictAll();
        bitmapUseCounts.clear();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Byte count of a BitmapDrawable.
     * Mirrors: ImageLoader.sizeOfBitmapDrawable()
     */
    private int bitmapByteCount(BitmapDrawable d) {
        Bitmap b = d.getBitmap();
        if (b == null || b.isRecycled()) return 0;
        return b.getByteCount();
    }

    /**
     * Approximate device memory class in MB without a Context reference.
     * Falls back to 128MB if detection fails.
     */
    private int getMemoryClassMb() {
        try {
            // Use Runtime as proxy: Telegram checks ActivityManager.getMemoryClass()
            long maxMem = Runtime.getRuntime().maxMemory();
            return (int) (maxMem / (1024 * 1024));
        } catch (Exception e) {
            return 128;
        }
    }
}
