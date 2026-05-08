import TeleImageViewNativeComponent from './TeleImageViewNativeComponent';
import type { TeleImageProps } from './types';

/**
 * TeleImage — Telegram-inspired high-performance image component for React Native.
 *
 * Features:
 *  ✅ Instant ThumbHash placeholder — rendered in <1ms with zero network calls
 *  ✅ Priority-aware download queue — visible images load first
 *  ✅ Disk + memory cache — survives app restarts, serves from RAM on repeat
 *  ✅ BitmapPool — reuses Bitmap allocations, eliminates GC pressure (Android)
 *  ✅ Smooth crossfade — placeholder → real image (configurable duration, default 150ms)
 *  ✅ Custom resizeMode — cover | contain | stretch
 *  ✅ Canvas/CALayer rendering — no UIImageView/Android ImageView overhead
 *  ✅ OkHttp 4 + HTTP/2 — connection pooling, keepalive (Android)
 *  ✅ Shared URLSession — HTTP/2, 6 connections/host (iOS)

 *  ✅ Prefetch — preload images before they appear on screen (Phase 3)
 *  ✅ Animated WebP / GIF — native decode without JS bridge (Phase 3, Android API 28+)
 *
 * @example
 * ```tsx
 * <TeleImage
 *   source={{
 *     uri: 'https://images.unsplash.com/photo-1506905925346-21bda4d32df4?w=800',
 *     thumbhash: 'HBkSHYSIeHiPiHh4eJd4eFeEd3',
 *   }}
 *   style={{ width: 200, height: 200, borderRadius: 12 }}
 *   priority="high"
 *   resizeMode="cover"
 *   fadeDuration={150}
 *   onLoad={({ nativeEvent }) => console.log(`Loaded ${nativeEvent.width}×${nativeEvent.height}`)}
 *   onError={({ nativeEvent }) => console.warn('Load failed:', nativeEvent.error)}

 * />
 * ```
 */
export function TeleImage({
  source,
  style,
  resizeMode = 'cover',
  priority = 'normal',
  fadeDuration = 150,
  onLoad,
  onError,
}: TeleImageProps) {
  return (
    <TeleImageViewNativeComponent
      uri={source.uri}
      thumbhash={source.thumbhash}
      resizeMode={resizeMode}
      priority={priority}
      fadeDuration={fadeDuration}
      style={style}
      onLoad={onLoad}
      onError={onError}
    />
  );
}
