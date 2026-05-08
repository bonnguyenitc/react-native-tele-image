import { NativeModules } from 'react-native';
import type { ImagePriority } from './types';

/**
 * TeleImagePrefetch — preload images before they appear on screen.
 *
 * Equivalent to Telegram's "prefetch" pass where the ImageLoader pre-downloads
 * thumbnails for the next page of a media grid before the user scrolls to it.
 *
 * Android: delegates to TeleImageHttpTask.load() with PRIORITY_LOW.
 * iOS: uses a background NSURLSession data task with QOS_CLASS_BACKGROUND.
 *
 * Usage:
 *   import { TeleImagePrefetch } from 'react-native-tele-image';
 *
 *   // Prefetch an array of URLs (e.g., next page of a FlatList)
 *   await TeleImagePrefetch.prefetch([
 *     { uri: 'https://example.com/img1.jpg' },
 *     { uri: 'https://example.com/img2.jpg', priority: 'low' },
 *   ]);
 *
 *   // Cancel inflight prefetch
 *   TeleImagePrefetch.cancel('https://example.com/img1.jpg');
 */

const RNTeleImage = NativeModules.TeleImageModule;

export interface PrefetchSource {
  uri: string;
  priority?: ImagePriority;
}

export const TeleImagePrefetch = {
  /**
   * Prefetch one or more images into disk + memory cache.
   * Returns a promise that resolves when all prefetches complete (or fail silently).
   *
   * Phase 3: delegates to native module when wired; falls back to no-op.
   */
  prefetch: (sources: PrefetchSource | PrefetchSource[]): Promise<void> => {
    const list = Array.isArray(sources) ? sources : [sources];
    if (RNTeleImage?.prefetch) {
      return RNTeleImage.prefetch(
        list.map((s) => ({ uri: s.uri, priority: s.priority ?? 'low' }))
      );
    }
    // Fallback: no-op in Phase 3 (wired in Phase 4 with full native module)
    return Promise.resolve();
  },

  /**
   * Cancel a pending prefetch by URI.
   */
  cancel: (uri: string): void => {
    if (RNTeleImage?.cancelPrefetch) {
      RNTeleImage.cancelPrefetch(uri);
    }
  },

  /**
   * Cancel all pending prefetch operations.
   */
  cancelAll: (): void => {
    if (RNTeleImage?.cancelAllPrefetch) {
      RNTeleImage.cancelAllPrefetch();
    }
  },
};
