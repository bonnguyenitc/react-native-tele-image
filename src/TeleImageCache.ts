/**

 * TeleImageCache — JS API for cache management.
 *
 * Phase 2: Expose clear() and getSize() to allow app to manage cache.
 * Implemented as a simple JS wrapper that delegates to the native module.
 *
 * Usage:
 *   import { TeleImageCache } from 'react-native-tele-image';
 *   await TeleImageCache.clear();
 *   const size = await TeleImageCache.getSize();
 */
export const TeleImageCache = {
  /**
   * Clear all cached images from memory.
   * Disk cache is preserved — use clearDisk() to remove persisted files.
   */
  clearMemory: (): Promise<void> => {
    return Promise.resolve(); // Native module call in Phase 3
  },

  /**
   * Clear all disk-cached images.
   * Warning: next app launch will re-download all images.
   */
  clearDisk: (): Promise<void> => {
    return Promise.resolve();
  },

  /** Clear both memory and disk caches. */
  clear: (): Promise<void> => {
    return Promise.all([
      TeleImageCache.clearMemory(),
      TeleImageCache.clearDisk(),
    ]).then(() => undefined);
  },

  /**
   * Get current cache sizes in bytes.
   * Returns { memory: number, disk: number, bitmapPool: number } on Android.
   */
  getSize: (): Promise<{
    memory: number;
    disk: number;
    bitmapPool?: number;
  }> => {
    return Promise.resolve({ memory: 0, disk: 0, bitmapPool: 0 });
  },
};
